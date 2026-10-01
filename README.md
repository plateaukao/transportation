# 台北交通 — Transportation

A small native Android app for Taipei and New Taipei, with a compact colour interface. Android 10 (API 29) or later. Java, Android platform widgets and networking; no third-party runtime dependencies, ads, analytics or location permission.

## What works

- Offline search across 1,051 Taipei/New Taipei bus route entries, including directions and ordered stops.
- Favourite routes and each route's selected direction saved in SharedPreferences.
- Manual live bus arrival updates with an update timestamp, status messages and explicit network-failure handling.
- Offline Taipei MRT station-pair full fare, discount reference and journey-time lookup across 119 station entries; swap origins and destinations.
- Compact route screens with one-tap switching for two directions, scrolling headers, a sticky update timestamp, and collapsing bottom tabs. No automatic refresh timer.

The current private prototype uses the Yahoo data sources verified during research. Its bundled route and MRT snapshot is dated 2026-10-01. MRT journey time is not the next-train countdown. The sources are not an established public API contract; transition to documented official feeds before public distribution. No code or UI assets from the extracted BusTracker APK are part of the new app.

## Build and check

Use JDK 17 and Android SDK platform 36. Configure `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir`.

```sh
./gradlew :app:assembleDebug :app:lintDebug
sh checks/run.sh
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Package: `info.plateaukao.transportation`. Launcher name: **台北交通**.

```sh
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL shell am start -n info.plateaukao.transportation/.MainActivity
```

`checks/run.sh` uses the JDK alone to check actual compressed route/arrival fixtures, negative ETA states, minute rounding, matrix columns, same-station handling, service error pages and external-entity rejection. It needs no device or Gradle dependencies.

## Data and follow-up

`Catalogue.java` reads the bundled city-filtered SQLite snapshot. `Transit.java` independently parses compressed bus XML and the MRT station matrix. `MainActivity.java` owns the native screens, background network requests and local preferences. Screen/request generations prevent delayed network responses from overwriting another screen.

The route catalogue is a bundled snapshot, not a synchronised database. Stop ordering comes from the bundled catalogue; live arrival requests are independent of optional catalogue updates. Bus estimates refresh only on opening a route or pressing the update button. No historical ETA is treated as current after process restart.

Next data integration requires the owner's official TDX account/API access. Live MRT countdowns require a separately approved source. [Research findings](research/bustracker-taipei.md) explain the available official services, observed Yahoo formats and their limitations.

## Research artifacts

- `apk/`: extracted BusTracker Taipei 1.98.2 base APK and installed configuration splits.
- `decompiled/`: JADX output for research; excluded from the app and Git staging.
- `research/samples/`: verified downloads, decoded XML and source SQLite database.
- `research/extraction.json`: version, source device and APK SHA-256 hashes.
- [Verification record](research/app-verification.md).
