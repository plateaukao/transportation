# How BusTracker Taipei retrieves transportation data

Research date: 2026-10-01. Installed version: 1.98.2 (198212316).

## Findings

The installed app talks to Yahoo's bus backend. It does not directly expose the government's ingestion pipeline. Static code inspection establishes client requests; ordinary unauthenticated HTTP downloads verified the bus catalogue, one route, live bus estimates and the Taipei MRT bundle. No runtime traffic capture was performed.

| Information | Observed source and format | Verification |
| --- | --- | --- |
| Bus route catalogue | `https://files.bus.yahoo.com/bustracker/data/dataurl_tpe.txt` points to `https://s.yimg.com/eg/bus_version/1146/`; `dat_tpe_zh.gz` there is zlib-compressed SQLite | Downloaded and decoded: 2,562 routes, 4,545 paths, 155,954 stops |
| Route stop sequence | `https://files.bus.yahoo.com/bustracker/routes/{routeKey}_{locale}.dat` | Route 100810, locale zh: HTTP 200, zlib-compressed XML |
| Bus 到站時間 | `https://busserver.bus.yahoo.com/api/route/{routeKey}` | Route 100810: HTTP 200, zlib-compressed XML |
| Route geometry | `https://files.bus.yahoo.com/bustracker/line/{routeKey}.line` | Located in Retrofit interface; not downloaded |
| Bus route details | `https://files.bus.yahoo.com/bustracker/info/{routeKey}.html` | Located in URL provider; not downloaded |
| MRT map, fares and journey times | `https://files.bus.yahoo.com/bustracker/metros/tpc_metros_v1.zip` | Downloaded: XML with 119 stations and SVG map; ZIP entries dated 2026-09-30 |
| MRT station details | `https://files.bus.yahoo.com/bustracker/metros/tpc_{sid}.html` | Located in URL provider; not downloaded |
| Nearby stops and journey planning | `https://api.bus.yahoo.com/api/v4/nearby/{key}/{locale}/{lon}/{lat}`, corresponding `passby` and `directions` paths | Located in Retrofit interface; not called |

These Yahoo services have no public API contract established by this investigation. Reachability alone does not establish permission or stability for use in a separate distributed app. Prefer official documented feeds for the new app.

## Bus routes and arrival times

The catalogue separates `routes`, `paths`, and ordered `stops`. Route rows include provider, MD5, route key, route ID, displayed and official names, description, category, sequence and round-trip flag. Stop rows include route key, path ID, stop ID, name, sequence and coordinates. Preserve directions and branches rather than grouping by displayed route name alone.

Example route: `100810`, provider `tpc`, displayed name `285 敦化幹線`, official name `敦化幹線`, description `麟光新村 - 榮總`. Its downloaded XML has `<r>` route metadata, `<p>` directions and ordered `<s>` stops.

Arrival response structure:

```xml
<r src="tpc" md5="" key="100810" id="810">
  <e id="18101" sec="-1" msg="未發車" t="5" buf="" lon="121.5609" lat="25.0159" />
  <e id="18105" sec="28" msg="" t="5" buf="" lon="121.5562" lat="25.0211" />
</r>
```

`e.id` joins the static stop ID. `sec` is arrival seconds; `msg` preserves nonnumeric states. The observed response also contains nested `b` records with vehicle ID, type, note and full flags. The parser reads `buf` into a stop-type model and `t` into transfer information. Treat negative seconds as unavailable/status information, never as a countdown. Saved estimates are a snapshot and become stale immediately.

The `.gz` and `.dat` names are misleading: the verified payloads use a zlib stream, not a gzip container. The downloaded catalogue decompresses directly to SQLite; route and arrival payloads decompress to XML.

## MRT time and fare information

Taipei's metro code is `tpc`. The downloaded ZIP contains `tpc_metros.xml` and `tpc_metros.svg`. The client caches this bundle and checks for refresh after 86,400,000 milliseconds (one day).

Each XML station has `id`, `zh`, `en`, `lon`, `lat`, and `datas`. `datas` is a semicolon-separated destination matrix; each destination entry is comma-separated. The station model selects columns according to display mode:

| Column | Meaning from client code |
| --- | --- |
| 0 | Destination station ID |
| 1 | Full fare (mode 0) |
| 2 | EasyCard field (mode 1); sampled Taipei entries use -1 |
| 3 | Welfare/discount fare (mode 2) |
| 4 | Journey time (mode 3) |
| 5 | Additional mode 4 field; meaning not established |

Example: 動物園 (019) to 木柵 (018) is `018,20,-1,8,2,0`: full fare 20, discount field 8, journey-time field 2. Journey time is consistent with minutes, but the matrix itself does not declare units; confirm units and ticket eligibility against official data before shipping. Do not interpret -1 as a valid fare. Yahoo's station identifiers are not necessarily official TDX identifiers.

This matrix contains station-to-station journey estimates, not a live next-train countdown. No live Taipei MRT countdown endpoint was identified in the inspected bus network interfaces. This is a scoped negative finding, not proof that every possible app screen lacks such a feature.

## Official sources for an independent Android app

Use [TDX's official API catalogue](https://tdx.transportdata.tw/api-service/swagger) for bus routes, stops, route stop sequences and estimated arrivals, and rail station, line, transfer, travel-time, timetable and OD fare data. The platform currently says programmatic members must supply their own API credentials; its guest browser mode is limited to basic services and 20 calls per source IP per day. Verify the chosen service tier and quota before implementation.

The standard bus resource families to inspect are `Bus/Route/City/{City}`, `Bus/StopOfRoute/City/{City}` and `Bus/EstimatedTimeOfArrival/City/{City}` under the basic v2 API, for both `Taipei` and `NewTaipei`. Exact selected endpoint schemas and coverage still need validation in Swagger. Yahoo route/stop IDs must not be substituted for TDX IDs.

[Taipei Metro's official API service list](https://www.metro.taipei/cp.aspx?n=BDEB860F2BE3E249) distinguishes public timetable/travel-time/OD-fare resources from membership services. Its next-train remaining-time service and recommended-path/journey-time service require Metro membership. Applications include an API membership form and a usage proposal. Consequently, a real-time MRT countdown needs its own approved source; a timetable estimate should be explicitly labelled as scheduled.

Additional public resources: [Taipei Metro line/station dataset](https://data.taipei/dataset/detail?id=8bf00fa8-86a5-437e-b5c7-9bc0fe0e2971), and [government fare dataset catalogue](https://data.gov.tw/en/datasets/128418). Dataset versions and coverage must be checked, especially where other MRT operators connect to Taipei Metro.

Recommended first implementation: offline route/stop search and favourites; a route screen with directional stop ordering and live bus arrivals; MRT station-pair fare and journey-time lookup; add live MRT countdown only after access is established. Cache static information separately from estimates and show refresh time/status. Keep API secrets in a small service if the selected provider uses confidential client credentials, rather than embedding them in an APK. Target Android 10 or earlier to support this A7 and favour high-contrast, low-animation screens for its e-ink display.

## Evidence and limits

Important decompiled files, relative to `decompiled/sources/`:

- `com/oath/mobile/client/android/abu/bus/core/io/a.java`: Yahoo production and staging hosts.
- `com/oath/mobile/client/android/abu/bus/network/k.java`: host selection.
- `com/oath/mobile/client/android/abu/bus/network/retrofit/l.java`: bus arrival requests.
- `com/oath/mobile/client/android/abu/bus/network/a.java`: static route and geometry requests.
- `com/oath/mobile/client/android/abu/bus/network/s.java`, `r.java`: catalogue and MRT URL construction.
- `com/oath/mobile/client/android/abu/bus/core/xml/i.java`: arrival XML parser.
- `com/mozyapp/bustracker/helpers/m.java`: SQLite catalogue queries.
- `com/oath/mobile/client/android/abu/bus/metro/svg/a.java`: MRT ZIP download and daily refresh.
- `com/oath/mobile/client/android/abu/bus/core/xml/f.java`: MRT station XML parser.
- `com/mozyapp/bustracker/models/i.java`: destination-matrix column selection (JADX instruction dump).
- `com/oath/mobile/client/android/abu/bus/metro/o.java`: fare/time display modes.

JADX completed with 15,719 reported errors, many related to Kotlin metadata; some methods are instruction dumps or incomplete decompilations. Findings above combine readable interfaces/parsers, instruction dumps and independently decoded downloads, rather than assuming a clean reconstruction.

Device UI verification could not proceed: sim-use 0.14.0's bridge requires SDK 30, while this A7 has SDK 29. Bridge installation failed with `INSTALL_FAILED_OLDER_SDK`; the existing app was not changed. No personal app storage was extracted, no root access used, and no credentials were reused. The phone app was not launched or driven. A compatible sim-use bridge or another explicitly approved UI inspection method would allow screen-level verification later.
