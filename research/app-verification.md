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

## Route shortcuts and stop search

Native pinned shortcuts were exercised through the Android launcher: long-pressing the route title requested system confirmation, and the pinned 0南 shortcut reopened the direction selected at creation even after changing the app to the other direction. The launcher visibly displayed route text inside the icon; 綠7 used a green background. Existing pinned icons were updated when the new app started. Reopening 綠7 after app updates restored its separately saved direction.

Stop-name search was exercised on Android with Chinese text entered using the visible OhMyBias software keyboard. Searching 捷運辛亥 displayed routes 294, 295 and 295副, with matched stop names beneath their route descriptions and no city subtitle. Opening route 294 succeeded. Scrolling that route hid app/route titles, direction controls and bottom tabs; the update timestamp remained pinned at the top and the stop list had no visible scrollbar.

An empty-query startup crash introduced during stop-search development was traced to Android rawQuery rejecting null string bindings. The fix supplies only string arguments and explicitly skips stop matching for an empty query. Startup was verified on the fixed APK. The runnable checks now include the actual app search SQL against the bundled catalogue, covering empty queries, stop-only matches, distinct routes and stop names, route-name matches, and literal wildcard/SQL-like input. Both checks and the final assembleDebug/lintDebug passed.

## Recent searches and arrival estimates in search results

The actual OhMyBias software keyboard was exercised by tapping the Liu-code keys and committing 順安街 with the IME space key. Matching results displayed live estimates: 棕2 showed 5 minutes, 綠9 showed 4 minutes, and other routes showed service states. Clearing the field saved the query; tapping the empty field opened the recent-search popup with 順安街. Choosing it repopulated the matching routes and reloaded their estimates. After an intentional app restart, the saved query remained available and was selected again.

History stores up to 20 distinct nonempty terms in SharedPreferences, with the latest first. Visible matching routes fetch arrivals through three background workers; delayed requests are discarded when the search or screen changes. Stop IDs come from the catalogue rather than display-name guesses. The debug build, lint and both existing regression checks passed.

## History styling and both-direction arrivals

The native recent-search popup now uses the same rounded white surface, blue icon tile, typography and spacing as the route cards, spanning the search box width. Emulator screenshots verified its appearance; selecting 順安街 still restored the query.

Matched stop IDs are grouped by route path before ETA lookup. Both directions are displayed separately, and directions without matching stops are explicitly marked. A live 順安街 search showed 棕2 towards 景美女中 at 9 minutes and towards 萬芳社區 at 13 minutes. 綠7 displayed its other direction as skipping the matched stop. Search regression checks cover a two-direction matching stop and a direction that does not serve the matched stop. Build, lint and parser/search checks passed.

## Palma 2 Pro release installation

Signed release 0.2.0 (version code 2) passed assembleRelease, lintRelease and both parser/search checks. APK verification confirmed its release certificate and APK Signature Scheme v2 signature. The non-debuggable APK was installed on the connected Android 15 Palma 2 Pro. sim-use preflight passed; the app loaded its route catalogue, opened 0南 and fetched a current update timestamp and last-service-departed statuses from the live endpoint.

## Shortcut warm-launch fix (0.2.1)

The old shortcut intent used CLEAR_TASK, recreating the activity when the app was already open. An emulator launcher test reproduced a transient view containing only the bottom tabs. The fix uses NEW_TASK, CLEAR_TOP and SINGLE_TOP, handles route and direction in onNewIntent, and updates existing pinned shortcut intents. Catalogue loading now precedes pinned-shortcut maintenance.

Release 0.2.1 (version code 3) passed assembleRelease and lintRelease; parser and catalogue checks passed. The signed release was installed successfully on Palma 2 Pro, and the user subsequently reported that the shortcut appeared to work. The device disconnected before final automated verification there.

On emulator-5556, checks/ShortcutCheck.py passed for both directions of 綠7 and switching to 0南, asserting the activity token remains unchanged. Clicking the existing 綠7 home-screen shortcut also reused activity 193063089 and restored 往黎明清境 with live arrivals (17 and 18 minutes at the first two stops). After intentionally force-stopping the app, the same shortcut showed the catalogue loading message, then restored the same route, direction and live arrivals.
