package com.insail.anchorwatch.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.gms.location.LocationServices
import com.insail.anchorwatch.R
import com.insail.anchorwatch.databinding.FragmentMapBinding
import com.insail.anchorwatch.location.AnchorWatchService
import com.insail.anchorwatch.model.AnchorConfig
import com.insail.anchorwatch.util.PermissionsHelper
import com.insail.anchorwatch.util.Prefs
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
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

    companion object {
        fun newInstance() = MapFragment()

        // Sources & layers
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

        // Style public MapLibre (évite MAPBOX_STREETS)
        private const val DEFAULT_STYLE_URI = "https://demotiles.maplibre.org/style.json"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        mapView = binding.mapView
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        binding.btnSetAnchor.setOnClickListener {
            val center = mapLibreMap?.cameraPosition?.target ?: return@setOnClickListener
            val radius = (binding.radiusSeek.progress.coerceAtLeast(10)) * 1f
            anchor = AnchorConfig(center.latitude, center.longitude, radius)
            drawAnchorCircle()
            binding.status.text = getString(R.string.anchor_set_fmt, radius.toInt())
        }

        binding.btnArm.setOnClickListener {
            anchor?.let {
                startWatch(requireContext(), it)
                Prefs.setArmed(requireContext(), true)
                Prefs.setAnchor(requireContext(), it)
            }
        }
        binding.btnDisarm.setOnClickListener {
            stopWatch(requireContext())
            Prefs.setArmed(requireContext(), false)
        }

        binding.radiusSeek.apply {
            max = 200
            progress = 60
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    drawAnchorCircle()
                }
                override fun onStartTrackingTouch(p0: SeekBar?) {}
                override fun onStopTrackingTouch(p0: SeekBar?) {}
            })
        }

        binding.btnMyLocation.setOnClickListener { centerOnMyLocation() }
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            binding.statusBarSpacer.updateLayoutParams<ConstraintLayout.LayoutParams> {
                height = top
            }
            insets
        }
    }

    override fun onMapReady(map: MapLibreMap) {
        this.mapLibreMap = map
        map.uiSettings.apply {
            isCompassEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }

        // Charge un style public (évite la constante MAPBOX_STREETS)
        map.setStyle(Style.Builder().fromUri(DEFAULT_STYLE_URI), object : Style.OnStyleLoaded {
            override fun onStyleLoaded(style: Style) {
                // Base OSM (offline si présent)
                val osmUrl = if (hasOffline("osm")) OSM_URL_OFFLINE else OSM_URL_ONLINE
                style.addSource(RasterSource(OSM_SOURCE, TileSet("tiles", osmUrl), 256))
                style.addLayer(RasterLayer(OSM_LAYER, OSM_SOURCE))

                // OpenSeaMap (offline si présent)
                val seaUrl = if (hasOffline("openseamap")) OSM_SEA_URL_OFFLINE else OSM_SEA_URL_ONLINE
                style.addSource(RasterSource(OSM_SEA_SOURCE, TileSet("tiles", seaUrl), 256))
                val seaLayer = RasterLayer(OSM_SEA_LAYER, OSM_SEA_SOURCE)
                seaLayer.withProperties(PropertyFactory.rasterOpacity(0.95f))
                style.addLayerAbove(seaLayer, OSM_LAYER)

                // Source + calques pour le cercle de garde (GeoJSON fourni en chaîne)
                val circleSrc = GeoJsonSource(SRC_CIRCLE, emptyFeatureCollectionJson())
                style.addSource(circleSrc)

                val fill = FillLayer(LYR_CIRCLE_FILL, SRC_CIRCLE).withProperties(
                    PropertyFactory.fillColor("#3344AA"),
                    PropertyFactory.fillOpacity(0.15f)
                )
                val line = LineLayer(LYR_CIRCLE_LINE, SRC_CIRCLE).withProperties(
                    PropertyFactory.lineColor("#4466FF"),
                    PropertyFactory.lineWidth(2.0f)
                )
                style.addLayerAbove(fill, OSM_SEA_LAYER)
                style.addLayerAbove(line, LYR_CIRCLE_FILL)

                // Si déjà armé, restaure visuel + service
                if (Prefs.isArmed(requireContext())) {
                    Prefs.getAnchor(requireContext())?.let { a ->
                        anchor = a
                        drawAnchorCircle()
                        startWatch(requireContext(), a)
                        binding.status.text = getString(R.string.armed)
                    }
                }
            }
        })

        // Caméra par défaut
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(48.0, -4.5), 9.0))
    }

    private fun drawAnchorCircle() {
        val a = anchor ?: return
        val map = mapLibreMap ?: return
        val style = map.style ?: return
        val circleSrc = style.getSourceAs<GeoJsonSource>(SRC_CIRCLE) ?: return
        circleSrc.setGeoJson(circlePolygonJson(a.lat, a.lon, a.radiusMeters.toDouble()))
    }

    /** Retourne un FeatureCollection (string JSON) vide */
    private fun emptyFeatureCollectionJson(): String =
        """{"type":"FeatureCollection","features":[]}"""

    /**
     * Construit un FeatureCollection (string JSON) contenant un seul Feature Polygon représentant
     * un cercle approximé autour [lat, lon] de rayon [radiusMeters].
     */
    private fun circlePolygonJson(
        lat: Double,
        lon: Double,
        radiusMeters: Double,
        steps: Int = 90
    ): String {
        val sb = StringBuilder()
        sb.append("""{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[""")

        val r = radiusMeters / 6_378_137.0 // distance angulaire (rad) sur WGS84
        val lat0 = Math.toRadians(lat)
        val lon0 = Math.toRadians(lon)

        for (i in 0..steps) {
            val theta = 2.0 * PI * (i.toDouble() / steps.toDouble())
            val sinLat = sin(lat0) * cos(r) + cos(lat0) * sin(r) * cos(theta)
            val latI = asin(sinLat)
            val y = sin(theta) * sin(r) * cos(lat0)
            val x = cos(r) - sin(lat0) * sinLat
            val lonI = lon0 + atan2(y, x)
            val lonDeg = Math.toDegrees(lonI)
            val latDeg = Math.toDegrees(latI)
            if (i > 0) sb.append(',')
            // GeoJSON: [lon, lat]
            sb.append('[').append(lonDeg).append(',').append(latDeg).append(']')
        }

        sb.append("""]]},"properties":{}}]}""")
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

    @SuppressLint("MissingPermission") // on vérifie via PermissionsHelper
    private fun centerOnMyLocation() {
        if (!PermissionsHelper.hasAll(requireContext(), listOf(Manifest.permission.ACCESS_FINE_LOCATION)))
            return
        val fused = LocationServices.getFusedLocationProviderClient(requireContext())
        fused.lastLocation.addOnSuccessListener { loc: Location? ->
            loc?.let {
                mapLibreMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 14.0)
                )
            }
        }
    }

    private fun hasOffline(folder: String): Boolean {
        // Vérifie s'il y a des tuiles dans assets/tiles/<folder>/
        return try {
            val list = requireContext().assets.list("tiles/$folder")
            list != null && list.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { mapView.onPause(); super.onPause() }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroyView() { mapView.onDestroy(); _binding = null; super.onDestroyView() }
}
