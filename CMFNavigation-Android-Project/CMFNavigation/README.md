# CMF Navigation Alerts

An Android app that calculates a driving route and posts text-only Android notifications. It does **not** pair with, connect to, or send BLE commands to a CMF Watch Pro 2. If Nothing X mirrors the app's notifications, the alerts may appear on the watch.

## Important limitations

- Nothing X notification mirroring for this app has not been verified. Enable this app in Nothing X's app-notification settings and send a test alert first.
- Alerts are text-only; there is no watch map, live arrow, or guaranteed instant delivery. Android notification mirroring and GPS/network timing can introduce delays.
- This is a prototype, not a safety-certified navigation product. Do not interact with the phone or watch while driving; use vehicle navigation as your primary guidance.
- Public Nominatim and OSRM demo services are community services, not an SLA-backed production routing backend. They can throttle, be unavailable, or change their usage policies. Do not send frequent geocoding requests; this app geocodes once per destination and on reroute.
- GPS accuracy, urban canyons, tunnels, and route geometry can affect maneuver timing. The current prototype uses a simple nearest-polyline projection and fixed thresholds.
- Notification permission (Android 13+) and location permission are required. Location must remain enabled.
- Android may restrict foreground location use depending on device settings and battery policies.

## Build in GitHub Actions

1. Create a GitHub repository and push this project to its `main` branch.
2. Open the repository's **Actions** tab.
3. Select **Build debug APK** and open the latest successful run.
4. Under **Artifacts**, download `cmf-navigation-debug-apk`.
5. Extract the ZIP; it contains `app-debug.apk`. Transfer it to your Android phone and install it. You may need to allow installation from that file manager/browser.

You can also trigger the workflow manually from **Actions → Build debug APK → Run workflow**.

## Open in Android Studio

1. Install Android Studio and a JDK 17 runtime (Android Studio's bundled JBR 17 is suitable).
2. Choose **Open** and select the `CMFNavigation` project folder (the folder containing `settings.gradle.kts`).
3. Let Gradle sync. Install Android SDK Platform 35 if Android Studio requests it.
4. Select the `app` run configuration and run on an Android phone, or use **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
5. The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## App use

1. Install and open the app.
2. Allow notifications and location access.
3. Tap **Send test alert to watch**. Check whether Nothing X forwards it.
4. In Nothing X, enable notification access/mirroring for **CMF Navigation Alerts** if the app appears in its app-notification list.
5. Enter a destination and tap **Find place & start**. Keep location enabled.
6. Tap **Stop** to end navigation.

## Data and services

- Geocoding: OpenStreetMap Nominatim (`https://nominatim.openstreetmap.org`).
- Routing: OSRM public demo server (`https://router.project-osrm.org`), driving profile, turn steps and GeoJSON step geometry.
- Network requests use `HttpURLConnection`; responses are parsed with `org.json`.
