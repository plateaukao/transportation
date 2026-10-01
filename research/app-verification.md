# App verification

2026-10-01

Build: `:app:assembleDebug` and `:app:lintDebug` passed. Lint has no errors; remaining warnings concern translation-ready string resources, backup extraction rules and an available Gradle update.

A host-side live HTTPS smoke check also passed using the same `Transit.download`, route parser and arrival parser as the app: route 100810 matched 41 stops in its first direction to current arrival records. This confirms endpoint access and Java parsing, but does not replace Android UI verification.

Standalone checks passed for actual zlib route/arrival payloads, status and minute display boundaries, fare/discount/journey-time matrix columns, same-station lookup, malformed service responses, and XML external-entity rejection (including alternate-encoding attempts).

Android 14 Pixel 7 emulator: sim-use preflight passed. App installed and launched successfully, loaded 1,051 bus entries and its MRT bundle, and showed the route search screen. The search field focused and a caret and actual software keyboard were visible. A key press through OhMyBias IME produced text and the app displayed its no-results state; deleting that text returned the full route list. No `adb shell input text` was used.

UI verification paused when sim-use reported unrelated process disappearance for `com.mixplorer.beta` and `com.danielkao.autoscreenonoff`; 台北交通 remained foreground. Under the sim-use skill's crash protocol, further device automation requires the user's instruction. Live arrival refresh, favourites persistence and MRT screen interactions have not yet been verified on-device. Their underlying data parsers were checked independently.

The original Hisense A7 was not modified or used for app UI testing. sim-use's bridge requires Android 11, while the A7 is Android 10. Android 10 API compatibility is checked by lint; physical A7 behaviour remains unverified.

## Subsequent verification

The Android 16 Pixel 7 emulator was restarted with explicit DNS. The app now retrieves live arrivals on Android: route 0南 returned service statuses, and 綠7 displayed numeric arrival estimates. The compact route screen and one-tap direction switching were exercised through sim-use. Scrolling the header away expanded the stop list to the bottom system inset; returning to the top restored the header and tabs. The latest build adds a sticky update row and hides stop-list scrollbars. Each route stores its direction separately; 綠7 direction 1 was observed in SharedPreferences after switching. Restart restoration and the final sticky-row layout still need a dedicated interaction check. The MRT swap control was visually verified beside the station selectors.

The latest APK passed assembleDebug and lintDebug and was installed on emulator-5556. The earlier verification pause is historical; work resumed under subsequent user instructions.
