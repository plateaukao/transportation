# Developing 台北交通

The app uses Java and native Android widgets. There are no third-party runtime dependencies. `Catalogue.java` reads the bundled SQLite snapshot; `Transit.java` parses arrival and MRT data; `MainActivity.java` owns screens, background requests, preferences and shortcuts.

## Build and verify

Use JDK 17 and Android SDK platform 36. Set `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir`.

```sh
./gradlew :app:assembleDebug :app:lintDebug
sh checks/run.sh
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

```sh
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL shell am start -n info.plateaukao.transportation/.MainActivity
```

For UI verification, run sim-use preflight first. Then `python3 checks/ShortcutCheck.py DEVICE_SERIAL` verifies warm route/stop shortcuts and a cold stop shortcut. This check intentionally restarts the app. Parser and catalogue checks cover actual fixtures, arrival statuses, rounding, exact stop-name matching, directions, literal search input and XML external-entity rejection.

## Signed release

Provide your own untracked `~/.android/transportation-release.properties` containing `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Keep signing credentials outside the repository. Without signing configuration, release builds are unsigned.

```sh
./gradlew :app:assembleRelease :app:lintRelease
```

Release APK: `app/build/outputs/apk/release/app-release.apk`.

## Data

The bundled snapshot is dated 2026-10-01. Live Yahoo arrival requests are separate from static catalogue data. Screen/request generations discard delayed responses after navigation. Saved preferences contain favourites, selected directions, search mode and recent terms; arrival estimates are not persisted across process restarts.

See [data-source research](../research/bustracker-taipei.md) for endpoint formats and limitations, and [verification notes](../research/app-verification.md) for tested behavior. The current sources have no established public API contract. A migration to documented official feeds requires independent route/stop identifiers and the appropriate provider credentials.

Extracted third-party APKs, decompiled code, local research downloads and signing credentials are excluded from Git and are not part of the app.
