package com.insail.anchorwatch.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.insail.anchorwatch.R
import com.insail.anchorwatch.databinding.FragmentMapBinding
import com.insail.anchorwatch.location.AnchorWatchService
import com.insail.anchorwatch.model.AnchorConfig
import com.insail.anchorwatch.util.PermissionsHelper
import com.insail.anchorwatch.util.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.maps.Style
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MapFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null

    private var anchor: AnchorConfig? = null
    private var pendingRadiusMeters: Int = 60
    private var traceCollectorStarted = false
    private var lastLat: Double? = null
    private var lastLon: Double? = null


    private var receiverRegistered = false

    // --- Fused location (pour mettre à jour "moi" même désarmé) ---
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(requireContext()) }
    private var fusedActive = false
    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc: Location = result.lastLocation ?: return
            setMe(loc.latitude, loc.longitude)
        }
    }

    private val traceReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != AnchorWatchService.ACTION_TRACE_POINT) return
            val lat = intent.getDoubleExtra(AnchorWatchService.EXTRA_LAT, Double.NaN)
            val lon = intent.getDoubleExtra(AnchorWatchService.EXTRA_LON, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return

            setMe(lat, lon)

            if (!styleReady()) {
                pendingTrace.add(lat to lon)
                return
            }
            appendToLiveTrace(lat, lon)
        }
    }

    /** Mémoire du tracé courant (lat, lon) */
    private val liveTrace = ArrayList<Pair<Double, Double>>()
    /** Points reçus avant que le style soit prêt */
    private val pendingTrace = ArrayList<Pair<Double, Double>>()

    companion object {
        fun newInstance() = MapFragment()

        private const val OSM_SOURCE = "osm-base"
        private const val OSM_LAYER = "osm-base-layer"
        private const val OSM_URL_ONLINE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        private const val OSM_URL_OFFLINE = "asset://tiles/osm/{z}/{x}/{y}.png"

        private const val OSM_SEA_SOURCE = "openseamap"
        private const val OSM_SEA_LAYER = "openseamap-layer"
        private const val OSM_SEA_URL_ONLINE = "https://tiles.openseamap.org/seamark/{z}/{x}/{y}.png"
        private const val OSM_SEA_URL_OFFLINE = "asset://tiles/openseamap/{z}/{x}/{y}.png"

        private const val SRC_CIRCLE = "guard-circle-src"
        private const val LYR_CIRCLE_FILL = "guard-circle-fill"
        private const val LYR_CIRCLE_LINE = "guard-circle-line"

        private const val SRC_SECTOR = "sector-src"

        private const val SRC_TRACE = "trace-src"
        private const val LYR_TRACE = "trace-line"

        private const val SRC_ME = "me-src"
        private const val LYR_ME = "me-circle"

        private const val DEFAULT_STYLE_URI = "https://demotiles.maplibre.org/style.json"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        // NOUVEAU : Relever l'ancre
        binding.fabLiftAnchor.setOnClickListener {
            liftAnchor()
        }

        // Poser l'ancre au centre de la caméra
        binding.fabSetAnchor.setOnClickListener {
            // Demande : poser une nouvelle ancre doit supprimer le tracé
            clearTraceUiAndDb()

            val center = mapLibreMap?.cameraPosition?.target ?: return@setOnClickListener
            val radius = pendingRadiusMeters.toFloat()

            anchor = AnchorConfig(center.latitude, center.longitude, radius)
            Prefs.setAnchor(requireContext(), anchor!!)
            drawGuard()
            binding.status.text = getString(R.string.anchor_set_fmt, radius.toInt())

            pushAnchorToServiceIfArmed()
        }

        binding.fabArmDisarm.setOnClickListener {
            if (Prefs.isArmed(requireContext())) {
                // -> Désarmer
                stopWatch(requireContext())
                Prefs.setArmed(requireContext(), false)
                binding.fabArmDisarm.setText(R.string.arm)
                return@setOnClickListener
            }

            // -> Armer
            val a = anchor
            if (a == null) {
                // Pas d’ancre posée : Toast
                Toast.makeText(
                    requireContext(),
                    getString(R.string.toast_anchor_required), // voir strings plus bas
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Si on a une position, vérifier si on est hors du cercle
            val curLat = lastLat
            val curLon = lastLon
            if (curLat != null && curLon != null) {
                val dist = distanceMeters(curLat, curLon, a.lat, a.lon)
                if (dist > a.radiusMeters) {
                    // Demander confirmation (Material)
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.dialog_outside_title)
                        .setMessage(getString(R.string.dialog_outside_message, dist.toInt(), a.radiusMeters.toInt()))
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.arm_anyway) { _, _ ->
                            startWatch(requireContext(), a)
                            Prefs.setArmed(requireContext(), true)
                            binding.fabArmDisarm.setText(R.string.disarm)
                            refreshTraceOnce()
                            startTraceCollectorIfReady()
                        }
                        .show()
                    return@setOnClickListener
                }
            }

            // OK, on peut armer directement
            startWatch(requireContext(), a)
            Prefs.setArmed(requireContext(), true)
            binding.fabArmDisarm.setText(R.string.disarm)
            refreshTraceOnce()
            startTraceCollectorIfReady()
        }


        // Radius slider
        binding.radiusSeek.apply {
            max = 200
            progress = 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    pendingRadiusMeters = p.coerceAtLeast(10)
                    binding.labelRadius.text = getString(R.string.circle_radius_fmt, pendingRadiusMeters)
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }
        binding.labelRadius.text = getString(R.string.circle_radius_fmt, pendingRadiusMeters)

        // Intervalle (BG)
        binding.intervalSeek.max = 100
        fun mapToSeconds(p: Int) = 15 + (p * (120 - 15) / 100)
        val saved = Prefs.getIntervalSec(requireContext()) ?: 60
        binding.intervalSeek.progress = ((saved - 15) * 100 / (120 - 15)).coerceIn(0, 100)
        binding.intervalLabel.text = getString(R.string.interval_fmt, saved)
        binding.intervalSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val seconds = mapToSeconds(p)
                binding.intervalLabel.text = getString(R.string.interval_fmt, seconds)
                Prefs.setIntervalSec(requireContext(), seconds)
                if (Prefs.isArmed(requireContext())) {
                    ContextCompat.startForegroundService(
                        requireContext(),
                        Intent(requireContext(), AnchorWatchService::class.java)
                            .setAction(AnchorWatchService.ACTION_SET_INTERVAL)
                            .putExtra(AnchorWatchService.EXTRA_INTERVAL_MS, seconds * 1000L)
                    )
                }
            }
            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {}
        })

        binding.fabArmDisarm.setText(if (Prefs.isArmed(requireContext())) R.string.disarm else R.string.arm)
        binding.fabMyLocation.setOnClickListener { centerOnMyLocation() }

        setupSectorControls()

        return binding.root
    }

    private fun setupSectorControls() {
        val enabled = Prefs.isSectorEnabled(requireContext())
        binding.sectorSwitch.isChecked = enabled
        binding.sectorControls.visibility = if (enabled) View.VISIBLE else View.GONE

        binding.sectorSwitch.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setSectorEnabled(requireContext(), isChecked)
            if (!isChecked) {
                Prefs.setSector(requireContext(), 0)
            } else if (Prefs.getSector(requireContext()) == 0) {
                Prefs.setSector(requireContext(), 90)
            }
            binding.sectorControls.visibility = if (isChecked) View.VISIBLE else View.GONE
            drawGuard()
        }

        val bearingAdapter = ArrayAdapter.createFromResource(
            requireContext(), R.array.bearing_values, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.bearingSpinner.adapter = bearingAdapter

        val angleAdapter = ArrayAdapter.createFromResource(
            requireContext(), R.array.angle_values, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.angleSpinner.adapter = angleAdapter

        val innerAdapter = ArrayAdapter.createFromResource(
            requireContext(), R.array.inner_radius_values, android.R.layout.simple_spinner_item
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.innerRadiusSpinner.adapter = innerAdapter

        fun selectValue(spinner: android.widget.Spinner, value: Int) {
            val a = spinner.adapter
            for (i in 0 until a.count) if (a.getItem(i).toString().toInt() == value) {
                spinner.setSelection(i); break
            }
        }

        selectValue(binding.bearingSpinner, Prefs.getHeading(requireContext()))
        selectValue(binding.angleSpinner, Prefs.getSector(requireContext()).let { if (it == 0) 90 else it })
        selectValue(binding.innerRadiusSpinner, Prefs.getInnerRadius(requireContext()))

        binding.bearingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val deg = parent.getItemAtPosition(position).toString().toInt()
                Prefs.setHeading(requireContext(), deg)
                drawGuard()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        binding.angleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val deg = parent.getItemAtPosition(position).toString().toInt()
                if (!binding.sectorSwitch.isChecked) {
                    Prefs.setSector(requireContext(), 0)
                } else {
                    Prefs.setSector(requireContext(), deg)
                }
                drawGuard()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        binding.innerRadiusSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val meters = parent.getItemAtPosition(position).toString().toInt()
                Prefs.setInnerRadius(requireContext(), meters)
                drawGuard()
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    @SuppressLint("DefaultLocale")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.updateLayoutParams<ConstraintLayout.LayoutParams> { height = top }
            insets
        }
        binding.labelReticleCoords.setOnLongClickListener {
            val target = mapLibreMap?.cameraPosition?.target ?: return@setOnLongClickListener false

            // Texte à copier : DMS + décimal
            val dms = toDms(target.latitude, target.longitude)
            val dec = String.format("%.5f, %.5f", target.latitude, target.longitude)
            val text = "$dms ($dec)"

            val cm = ContextCompat.getSystemService(
                requireContext(), ClipboardManager::class.java
            )
            cm?.setPrimaryClip(ClipData.newPlainText(getString(R.string.clip_label_coords), text))

            Toast.makeText(requireContext(), getString(R.string.copied_coords_to_clipboard), Toast.LENGTH_SHORT).show()
            true
        }

        collectTraceLive()
        collectTraceLiveBus()
    }

    override fun onMapReady(map: MapLibreMap) {
        this.mapLibreMap = map
        startTraceCollectorIfReady()
        map.uiSettings.apply {
            isCompassEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }

        map.setStyle(DEFAULT_STYLE_URI) { style ->
            // Fonds
            val osmUrl = if (hasOffline("osm")) OSM_URL_OFFLINE else OSM_URL_ONLINE
            style.addSource(RasterSource(OSM_SOURCE, TileSet("tiles", osmUrl), 256))
            style.addLayer(RasterLayer(OSM_LAYER, OSM_SOURCE))
            val seaUrl = if (hasOffline("openseamap")) OSM_SEA_URL_OFFLINE else OSM_SEA_URL_ONLINE
            style.addSource(RasterSource(OSM_SEA_SOURCE, TileSet("tiles", seaUrl), 256))
            style.addLayerAbove(RasterLayer(OSM_SEA_LAYER, OSM_SEA_SOURCE).withProperties(
                PropertyFactory.rasterOpacity(0.95f)
            ), OSM_LAYER)

            // Sources/layers UI
            ensureSource(style, SRC_CIRCLE); ensureSource(style, SRC_SECTOR)
            ensureSource(style, SRC_TRACE); ensureSource(style, SRC_ME)

            style.addLayerAbove(
                FillLayer(LYR_CIRCLE_FILL, SRC_CIRCLE).withProperties(
                    PropertyFactory.fillColor("#3344AA"),
                    PropertyFactory.fillOpacity(0.15f)
                ),
                OSM_SEA_LAYER
            )
            style.addLayerAbove(
                LineLayer(LYR_CIRCLE_LINE, SRC_CIRCLE).withProperties(
                    PropertyFactory.lineColor("#4466FF"),
                    PropertyFactory.lineWidth(2.0f)
                ),
                LYR_CIRCLE_FILL
            )
            style.addLayerAbove(
                LineLayer(LYR_TRACE, SRC_TRACE).withProperties(
                    PropertyFactory.lineColor("#00D1FF"),
                    PropertyFactory.lineWidth(3.0f),
                    PropertyFactory.lineJoin("round"),
                    PropertyFactory.lineCap("round")
                ),
                LYR_CIRCLE_LINE
            )
            style.addLayerAbove(
                CircleLayer(LYR_ME, SRC_ME).withProperties(
                    PropertyFactory.circleColor("#FFFFFF"),
                    PropertyFactory.circleStrokeColor("#1E90FF"),
                    PropertyFactory.circleStrokeWidth(2.0f),
                    PropertyFactory.circleRadius(6.0f)
                ),
                LYR_TRACE
            )

            // Affiche ce qu’on a déjà
            if (pendingTrace.isNotEmpty()) {
                liveTrace.addAll(pendingTrace)
                pendingTrace.clear()
                updateTraceSourceFromLive()
            }

            // Restauration d’état
            if (Prefs.isArmed(requireContext())) {
                Prefs.getAnchor(requireContext())?.let { a ->
                    anchor = a
                    drawGuard()
                    startWatch(requireContext(), a)
                    binding.status.text = getString(R.string.armed)
                }
            }
            anchor?.let { existing ->
                pendingRadiusMeters = existing.radiusMeters.toInt().coerceAtLeast(10)
                binding.radiusSeek.progress = pendingRadiusMeters
                binding.labelRadius.text = getString(R.string.circle_radius_fmt, pendingRadiusMeters)
            }
        }

        // Caméra par défaut
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(48.0, -4.5), 18.5))

        // Mettre à jour au relâchement (évite de spammer à chaque pixel)
        map.addOnCameraIdleListener { updateReticleCoordsLabel() }

        // Init une première fois
        updateReticleCoordsLabel()

        refreshTraceOnce()
    }

    private fun ensureSource(style: Style, id: String) {
        if (style.getSource(id) == null) {
            style.addSource(GeoJsonSource(id, emptyFC()))
        }
    }

    private fun drawGuard() {
        val a = anchor ?: return
        val style = mapLibreMap?.style ?: return

        // Cercle plein
        style.getSourceAs<GeoJsonSource>(SRC_CIRCLE)
            ?.setGeoJson(circlePolygonJson(a.lat, a.lon, a.radiusMeters.toDouble()))

        // Secteur optionnel
        val enabled = Prefs.isSectorEnabled(requireContext())
        val sectorDeg = if (enabled) Prefs.getSector(requireContext()) else 0
        val heading = Prefs.getHeading(requireContext())
        val inner = Prefs.getInnerRadius(requireContext()).toDouble()
        val json = if (!enabled || sectorDeg == 0) emptyFC()
        else sectorDonutJson(a.lat, a.lon, inner, a.radiusMeters.toDouble(), heading, sectorDeg)
        style.getSourceAs<GeoJsonSource>(SRC_SECTOR)?.setGeoJson(json)
    }

    private fun clearGuardUi() {
        val style = mapLibreMap?.style ?: return
        style.getSourceAs<GeoJsonSource>(SRC_CIRCLE)?.setGeoJson(emptyFC())
        style.getSourceAs<GeoJsonSource>(SRC_SECTOR)?.setGeoJson(emptyFC())
    }

    private fun startTraceCollectorIfReady() {
        val styleReady = mapLibreMap?.style != null
        if (styleReady && !traceCollectorStarted) {
            traceCollectorStarted = true
            collectTraceLive()
        }
    }

    private fun refreshTraceOnce() {
        mapLibreMap?.style ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val pts = com.insail.anchorwatch.data.AppDatabase
                .get(requireContext())
                .traceDao()
                .latest(2000)

            liveTrace.clear()
            pts.reversed().forEach { p -> liveTrace.add(p.lat to p.lon) }

            withContext(Dispatchers.Main) {
                updateTraceSourceFromLive()
                pts.firstOrNull()?.let { last ->
                    setMe(last.lat, last.lon)
                }
            }
        }
    }

    private fun collectTraceLive() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.insail.anchorwatch.data.AppDatabase
                    .get(requireContext())
                    .traceDao()
                    .latestFlow(2000)
                    .collect { pts ->
                        val style = mapLibreMap?.style ?: return@collect

                        if (pts.size >= 2) {
                            style.getSourceAs<GeoJsonSource>(SRC_TRACE)
                                ?.setGeoJson(buildTraceLineStringJson(pts))
                        } else {
                            style.getSourceAs<GeoJsonSource>(SRC_TRACE)
                                ?.setGeoJson(emptyFC())
                        }

                        liveTrace.clear()
                        pts.reversed().forEach { p -> liveTrace.add(p.lat to p.lon) }

                        pts.firstOrNull()?.let { last ->
                            setMe(last.lat, last.lon)
                        }
                    }
            }
        }
    }

    /** Écoute temps réel côté app (sans relire la DB à chaque point) */
    private fun collectTraceLiveBus() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                com.insail.anchorwatch.data.TraceBus.points.collect { (lat, lon) ->
                    setMe(lat, lon)
                    appendToLiveTrace(lat, lon)
                }
            }
        }
    }

    private fun appendToLiveTrace(lat: Double, lon: Double) {
        val sameAsLast = liveTrace.lastOrNull()?.let { it.first == lat && it.second == lon } == true
        if (!sameAsLast) {
            liveTrace.add(lat to lon)
            updateTraceSourceFromLive()
        }
    }

    private fun updateTraceSourceFromLive() {
        val style = mapLibreMap?.style ?: return
        val src = style.getSourceAs<GeoJsonSource>(SRC_TRACE) ?: return

        if (liveTrace.size < 2) {
            src.setGeoJson(emptyFC())
            return
        }
        val sb = StringBuilder("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[""")
        liveTrace.forEachIndexed { i, (lat, lon) ->
            if (i > 0) sb.append(',')
            sb.append('[').append(lon).append(',').append(lat).append(']')
        }
        sb.append("]}}]}")
        src.setGeoJson(sb.toString())
    }

    /** Point "moi" */
    private fun setMe(lat: Double, lon: Double) {
        lastLat = lat
        lastLon = lon
        val style = mapLibreMap?.style ?: return
        style.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(
            """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[$lon,$lat]}}]}"""
        )
    }


    /** Actions ancre **/
    private fun liftAnchor() {
        // Désarme le service si nécessaire
        if (Prefs.isArmed(requireContext())) {
            stopWatch(requireContext())
            Prefs.setArmed(requireContext(), false)
            binding.fabArmDisarm.setText(R.string.arm)
        }
        // Oublie l'ancre courante (mémoire locale) et nettoie l'UI
        anchor = null
        clearGuardUi()
        clearTraceUiAndDb()
        binding.status.text = getString(R.string.anchor_lifted)
    }

    /** Nettoyage tracé (UI + DB + mémoire) */
    private fun clearTraceUiAndDb() {
        mapLibreMap?.style?.getSourceAs<GeoJsonSource>(SRC_TRACE)
            ?.setGeoJson("""{"type":"FeatureCollection","features":[]}""")
        liveTrace.clear()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                com.insail.anchorwatch.data.AppDatabase
                    .get(requireContext())
                    .traceDao()
                    .clearAll()
            } catch (_: Exception) { /* no-op */ }
        }
    }

    /** GeoJSON helpers (string) */
    private fun emptyFC() = """{"type":"FeatureCollection","features":[]}"""

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6378137.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }


    private fun circlePolygonJson(lat: Double, lon: Double, radiusMeters: Double, steps: Int = 90): String {
        val sb = StringBuilder()
        sb.append("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[""")
        val r = radiusMeters / 6_378_137.0
        val lat0 = Math.toRadians(lat); val lon0 = Math.toRadians(lon)
        for (i in 0..steps) {
            val theta = 2.0 * PI * (i.toDouble() / steps)
            val sinLat = sin(lat0) * cos(r) + cos(lat0) * sin(r) * cos(theta)
            val latI = asin(sinLat)
            val y = sin(theta) * sin(r) * cos(lat0)
            val x = cos(r) - sin(lat0) * sinLat
            val lonI = lon0 + atan2(y, x)
            val lonDeg = Math.toDegrees(lonI); val latDeg = Math.toDegrees(latI)
            if (i > 0) sb.append(',')
            sb.append('[').append(lonDeg).append(',').append(latDeg).append(']')
        }
        sb.append("""]]},""").append(""""properties":{}}]}""")
        return sb.toString()
    }

    private fun sectorDonutJson(
        lat: Double, lon: Double,
        innerM: Double, outerM: Double,
        headingDeg: Int, sectorDeg: Int,
        steps: Int = 90
    ): String {
        val startDeg = headingDeg - sectorDeg / 2
        val endDeg = headingDeg + sectorDeg / 2
        val outer = arcPoints(lat, lon, outerM, startDeg, endDeg, steps)
        val inner = arcPoints(lat, lon, innerM, endDeg, startDeg, steps)
        val ring = (outer + inner)
        val coords = ring.joinToString(",") { "[${it.second},${it.first}]" }
        return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coords]]}}]}"""
    }

    private fun arcPoints(
        lat: Double, lon: Double, rM: Double, startDeg: Int, endDeg: Int, steps: Int
    ): List<Pair<Double, Double>> {
        val pts = ArrayList<Pair<Double, Double>>()
        val start = startDeg.toDouble()
        val end = endDeg.toDouble()
        val sweep = if (end >= start) end - start else (360 - start + end)
        val segments = (steps * (sweep / 360.0)).coerceAtLeast(2.0).toInt()
        val r = 6_378_137.0
        val d = rM / r
        val lat0 = Math.toRadians(lat); val lon0 = Math.toRadians(lon)
        for (i in 0..segments) {
            val bearing = Math.toRadians((start + sweep * i / segments))
            val sinLat = sin(lat0) * cos(d) + cos(lat0) * sin(d) * cos(bearing)
            val latI = asin(sinLat)
            val y = sin(bearing) * sin(d) * cos(lat0)
            val x = cos(d) - sin(lat0) * sinLat
            val lonI = lon0 + atan2(y, x)
            pts.add(Math.toDegrees(latI) to Math.toDegrees(lonI))
        }
        return pts
    }

    private fun buildTraceLineStringJson(pts: List<com.insail.anchorwatch.data.TracePoint>): String {
        val usable = pts.reversed()
        if (usable.size < 2) return emptyFC()
        val sb = StringBuilder(
            """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":["""
        )
        usable.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append('[').append(p.lon).append(',').append(p.lat).append(']')
        }
        sb.append("]}}]}")
        return sb.toString()
    }

    private fun startWatch(ctx: Context, cfg: AnchorConfig) {
        val i = Intent(ctx, AnchorWatchService::class.java).apply {
            putExtra(AnchorWatchService.EXTRA_ANCHOR_LAT, cfg.lat)
            putExtra(AnchorWatchService.EXTRA_ANCHOR_LON, cfg.lon)
            putExtra(AnchorWatchService.EXTRA_ANCHOR_RADIUS, cfg.radiusMeters)
        }
        ContextCompat.startForegroundService(ctx, i)
        binding.status.text = getString(R.string.armed)
    }

    private fun stopWatch(ctx: Context) {
        ctx.stopService(Intent(ctx, AnchorWatchService::class.java))
        binding.status.text = getString(R.string.disarmed)
    }

    private fun pushAnchorToServiceIfArmed() {
        if (!Prefs.isArmed(requireContext())) return
        val a = anchor ?: return
        ContextCompat.startForegroundService(
            requireContext(),
            Intent(requireContext(), AnchorWatchService::class.java).apply {
                putExtra(AnchorWatchService.EXTRA_ANCHOR_LAT, a.lat)
                putExtra(AnchorWatchService.EXTRA_ANCHOR_LON, a.lon)
                putExtra(AnchorWatchService.EXTRA_ANCHOR_RADIUS, a.radiusMeters)
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun centerOnMyLocation() {
        if (!PermissionsHelper.hasAll(requireContext(), listOf(Manifest.permission.ACCESS_FINE_LOCATION))) return
        fused.lastLocation.addOnSuccessListener { loc: Location? ->
            loc?.let {
                setMe(it.latitude, it.longitude)
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 18.5)
                )
            }
        }
    }

    private fun setServiceForeground(ctx: Context, fg: Boolean) {
        ContextCompat.startForegroundService(
            ctx,
            Intent(ctx, AnchorWatchService::class.java)
                .setAction(AnchorWatchService.ACTION_APP_FOREGROUND)
                .putExtra(AnchorWatchService.EXTRA_FOREGROUND, fg)
        )
    }

    private fun hasOffline(folder: String): Boolean = try {
        val list = requireContext().assets.list("tiles/$folder")
        list != null && list.isNotEmpty()
    } catch (_: Exception) { false }

    private fun updateReticleCoordsLabel() {
        val target = mapLibreMap?.cameraPosition?.target ?: return
        binding.labelReticleCoords.text = toDms(target.latitude, target.longitude)
    }

    private fun toDms(lat: Double, lon: Double): String {
        @SuppressLint("DefaultLocale")
        fun convert(value: Double, isLat: Boolean): String {
            val hemisphere = when {
                isLat && value >= 0 -> "N"
                isLat && value < 0  -> "S"
                !isLat && value >= 0 -> "E"
                else -> "W"
            }
            val absValue = kotlin.math.abs(value)
            val degrees = absValue.toInt()
            val minutesFull = (absValue - degrees) * 60
            val minutes = minutesFull.toInt()
            val seconds = (minutesFull - minutes) * 60
            return String.format("%d°%02d'%05.2f\" %s", degrees, minutes, seconds, hemisphere)
        }

        return "${convert(lat, true)}  ${convert(lon, false)}"
    }


    // ------ Cycle de vie ------
    override fun onStart() {
        super.onStart()
        mapView.onStart()

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                requireContext(),
                traceReceiver,
                IntentFilter(AnchorWatchService.ACTION_TRACE_POINT),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }

        startFusedUpdates()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
        setServiceForeground(requireContext(), true)
        refreshTraceOnce()
    }

    override fun onPause() {
        setServiceForeground(requireContext(), false)
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        stopFusedUpdates()
        if (receiverRegistered) {
            requireContext().unregisterReceiver(traceReceiver)
            receiverRegistered = false
        }
        mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroyView() { mapView.onDestroy(); _binding = null; super.onDestroyView() }

    private fun styleReady(): Boolean =
        mapLibreMap?.style?.getSourceAs<GeoJsonSource>(SRC_TRACE) != null

    // ------ Fused helpers ------
    @SuppressLint("MissingPermission")
    private fun startFusedUpdates() {
        if (fusedActive) return
        if (!PermissionsHelper.hasAll(requireContext(), listOf(Manifest.permission.ACCESS_FINE_LOCATION))) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setWaitForAccurateLocation(false)
            .build()
        fused.requestLocationUpdates(req, fusedCallback, requireActivity().mainLooper)
        fusedActive = true
    }
    private fun stopFusedUpdates() {
        if (!fusedActive) return
        fused.removeLocationUpdates(fusedCallback)
        fusedActive = false
    }
}
