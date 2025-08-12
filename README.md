# Anchor Watch (Android)

Android app that monitors your position at anchor, alerts if you drift outside a defined guard zone, and shows your track in real time on a MapLibre map.

> **New (Aug 2025):**
>
> - **Lift Anchor button**: quickly remove the current anchor, clear the guard circle and trace history.
> - **Reticle coordinates**: shows the center map point’s position in DMS and decimal format; long‑press to copy.
> - **Smart arming**:
>   - Prevents arming if no anchor is set (toast warning).
>   - Prompts for confirmation if you are already outside the guard circle when arming.
> - **Shadow‑free UI**: cleaner floating buttons with no drop shadow.
>
> Files updated: `MapFragment.kt`, `fragment_map.xml`, `strings.xml` and related resources.

---

## 📑 Table of Contents

- [Features](#-features)
- [Quick Start](#-quick-start)
- [How it works](#-how-it-works)
- [Configuration (optional)](#-configuration-optional)
- [Project Structure](#-project-structure)
- [Build & Dependencies](#-build--dependencies)
- [Android Manifest & Permissions](#-android-manifest--permissions)
- [Resources](#-resources)
- [Troubleshooting](#-troubleshooting)
- [FAQ](#-faq)
- [License](#-license)

---

## 🚀 Features

- Set an **anchor position** and guard radius directly from the map view.
- **Arm/Disarm** monitoring; warns if already outside the guard zone.
- **Lift Anchor** button to remove the anchor and clear all traces instantly.
- **Live GPS marker** updated every second (armed or disarmed).
- **Real-time track plotting** with instant updates and history replay.
- **Reticle coordinate display** in both DMS and decimal formats, with one‑tap copy to clipboard.
- **Configurable update interval** when running in the background.
- **Sector mode** to limit guard zone to a specific arc and heading.

---

## ⚡ Quick Start

1. Download the latest APK from the **GitHub Releases** page.  
2. Install it on your Android device (enable installation from unknown sources if needed).
3. Grant **location permissions** when prompted.
4. Center the map where you want to set the anchor, then tap **Set Anchor**.
5. Tap **Arm** to start monitoring.  
   - If you are outside the guard circle, confirm via the dialog to proceed.
6. Move around — your track will be drawn in real time.
7. Tap **Lift Anchor** to stop monitoring and clear the guard circle and trace.

---

## 🧠 How it works

- **Map Fragment** (`MapFragment.kt`)
  - Hosts the MapLibre map and UI controls.
  - Updates the “me” marker every second via fused location provider.
  - Handles arming logic, lift anchor action, and trace display.
- **AnchorWatchService**
  - Runs in the foreground when armed, collects GPS positions, and appends to the local Room database.
  - Broadcasts trace points for real‑time updates.
- **TraceBus** & **Room DB**
  - `TraceBus` publishes instant GPS updates to the UI.
  - The Room database stores recent points for history replay.

---

## ⚙ Configuration (optional)

- **Update Interval**: adjust with the seekbar; applies when in background mode.
- **Guard Radius**: set via the radius slider before arming.
- **Sector Mode**: enable and set arc, heading, and inner radius to limit the monitored area.

---

## 🗂 Project Structure

- `MapFragment.kt` – Map UI, FAB controls, guard circle drawing, trace updates.
- `AnchorWatchService.kt` – Foreground service, GPS collection, broadcasts.
- `TraceBus.kt` – Simple coroutine‑based bus for real‑time point updates.
- `AppDatabase.kt` / `TraceDao.kt` – Room database for storing/replaying track points.
- `fragment_map.xml` – Layout for the map fragment and controls.
- `res/drawable/*` – Icons and shapes.
- `res/values/strings.xml` – All user‑visible text.

---

## 🏗 Build & Dependencies

```gradle
dependencies {
    implementation "androidx.core:core-ktx:1.13.1"
    implementation "androidx.appcompat:appcompat:1.7.0"
    implementation "com.google.android.material:material:1.12.0"

    // MapLibre Maps
    implementation "org.maplibre.gl:android-sdk:10.3.1"

    // Google Play location services
    implementation "com.google.android.gms:play-services-location:21.3.0"

    // Room database
    implementation "androidx.room:room-runtime:2.6.1"
    kapt "androidx.room:room-compiler:2.6.1"
}
```

**Compile/target**: tested with `compileSdk 36`, `targetSdk 36`, `minSdk 29`.

---

## 📝 Android Manifest & Permissions

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## 🖼 Resources

**Icons:**
- `ic_anchor` – for status chip and lift anchor button.
- `ic_place_anchor` – for set anchor FAB.
- `ic_my_location` – for my location FAB.
- `ic_shield` – for arm/disarm extended FAB.

**Strings:**  
See `strings.xml` for all labels, toasts, and dialog texts.

---

## 🧰 Troubleshooting

| Symptom                                  | Likely Cause                      | Fix                                               |
| ---------------------------------------- | ---------------------------------- | ------------------------------------------------- |
| Arm button shows toast “Set anchor”      | No anchor has been set             | Tap **Set Anchor** before arming.                 |
| Track not drawing                        | Service not armed or no GPS fix    | Ensure you are armed and have a clear GPS signal. |
| Coordinates label not updating           | Camera idle listener not firing    | Move the map slightly to trigger updates.         |

---

## ❓ FAQ

**Q: Does it work without arming?**  
A: The “me” marker will still update every second, but no drift alert or track plotting will occur.

**Q: Can I copy the coordinates of the reticle?**  
A: Yes — long‑press the coordinate label to copy both DMS and decimal formats.

---

## 📄 License

Distributed under **CC BY‑NC‑SA 4.0** (Non‑commercial, credit required, share alike).
