# NetForgeInspector — MEMORY.md

> Generated 2026-10-15 after successful `assembleDebug`.
> Build status: **SUCCESS** (requires Java 21; Java 25 fails).
> APKs verified:
> - `app/build/outputs/apk/debug/app-debug.apk` (302M)
> - `app/build/outputs/apk/release/app-release-unsigned.apk` (264M)
> Build command: `JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.10-ms ./gradlew assembleDebug`
> Failure mode on Java 25.0.2: `IllegalArgumentException: 25.0.2` in `JavaVersion.parse` (Kotlin KTS compiler can't parse JDK 25 version string). Fix = use JDK 21 (matches `sourceCompatibility/targetCompatibility = 21`, `jvmTarget = JVM_21`).

## 1. What this project is

Android app `com.netforge.inspector` ("NetForge Inspector", v1.0.0): a **mobile Burp-Suite-like HTTP inspector**:

1. Full GeckoView (Firefox ESR 128) browser with Google-OAuth-compatible UA + multi-window popup sessions.
2. Built-in WebExtension (`assets/inspector_ext`) captures all HTTP via `webRequest` + `filterResponseData`, forwards JSON packets over native-messaging port `netforge_bridge`.
3. Kotlin pipeline parses/classifies (REST/GraphQL/XHR/PAGE/OTHER), extracts JWT/auth tokens + cookies, buffers into Room.
4. Compose UI: smart URL bar, GeckoView surface, network console bottom sheet, request repeater sheet.
5. Storage: one-click MediaStore export to `Download/DevInspector_Logs/<domain>_<timestamp>/{network_traffic.jsonl,auth_summary.json}` + JSONL re-import.
6. Repeater: native OkHttp re-execution of edited requests (CORS-free).

## 2. Architecture (layered, api/internal split)

```
WebExtension background.js
  --(runtime.connectNative("netforge_bridge") JSON)-->
GeckoBrowserManager (browser/internal) --hook(ext)--> GeckoExtensionBridge (interceptor/internal)
  --RawCapturedPacket--> JwtAndCookieParserImpl (parser/internal) --AnalyzedPacket-->
  Room (InspectorDatabase/NetworkLogDao/NetworkLogEntity)
  --Flow<List<Entity>>--> InspectorViewModel --> Compose UI
Repeater: UI --RepeaterSheetState--> InspectorViewModel --RequestRepeaterApi--> OkHttpRepeaterEngine (OkHttp)
Storage: InspectorViewModel --StorageExporterApi--> MediaStoreJsonlManager <--> Room + MediaStore.Downloads
Wiring: MainActivity.ServiceLocator (process singleton) creates parser, DB, bridge, repeater, storage, VM factory.
```

Core shared types in `core/model/NetworkPacketModels.kt` mirror the JSONL schema so export/import is lossless.
Each feature module exposes only `api/` outward; `internal/` holds the implementation. UI/ViewModel depend only on `api/` (+ `RepeaterBridge` adapter in `ui/state`).

Data flow detail:
- `background.js:onBeforeRequest` stores `{requestId,url,method,body}` in `pending` map.
- `onSendHeaders` adds request headers + `isApiCall` hint.
- `onHeadersReceived` stores response headers/status; attaches `filterResponseData` stream **only for text-like MIME** (JSON/XML/HTML/CSS/JS) with 2MB cap.
- `onCompleted/onErrorOccurred → finishRequest()` builds `netforge_packet` JSON → `connectNative` port → `GeckoExtensionBridge.PortDelegate.onPortMessage → handleRawJson → PacketWireFormat.parseRawPacket → parser.analyze → toEntity() → Room.upsert → listener.onCaptureCountChanged`.
- Room `observeAll()` Flow drives `InspectorUiState.packets`; `GeckoExtensionBridge.setListener` also pushes `captureCount` for the FAB badge.

## 3. File-by-file inventory

### Root / build config

| File | Purpose | Connections |
|---|---|---|
| `settings.gradle.kts` | Project setup: `pluginManagement` (google/mavenCentral/gradlePluginPortal), `dependencyResolutionManagement.FAIL_ON_PROJECT_REPOS` with `google(), mavenCentral(), https://maven.mozilla.org/maven2` (required for GeckoView), `rootProject.name="NetForgeInspector"`, `include(":app")`. | Consumed by Gradle before any module build; Mozilla repo enables `libs.geckoview`. |
| `build.gradle.kts` (root) | Applies version-catalog plugin aliases (`android.application, kotlin.android, kotlin.compose, ksp`) with `apply false`. | Delegates versions to `gradle/libs.versions.toml`. |
| `gradle/libs.versions.toml` | Single version source: AGP 8.13.2, Kotlin 2.3.21, KSP 2.3.12, Room 2.8.5, Compose BOM 2025.09.00, activity-compose 1.11.0, lifecycle 2.9.4, core-ktx 1.17.0, coroutines 1.10.2, okhttp 4.12.0, geckoview 128.0.20240704121409. Defines `androidx-*, compose-*, room-*, okhttp, geckoview` libraries + 4 plugins. | Read by both build scripts via `libs.*` aliases. |
| `gradle.properties` | `jvmargs -Xmx4096m`, `parallel/caching=true`, `android.useAndroidX=true`, `nonTransitiveRClass=true`, `kotlin.code.style=official`. | Global Gradle/Kotlin behavior. |
| `gradle/wrapper/gradle-wrapper.properties` + `gradle/wrapper/gradle-wrapper.jar` | Pins Gradle 8.14.3 (`services.gradle.org/distributions/gradle-8.14.3-bin.zip`). | Used by `gradlew`. |
| `gradlew`, `gradlew.bat` | Wrapper launchers. | Entry to build. |
| `local.properties` | `sdk.dir=/home/codespace/Android/Sdk` (local only, gitignored). | Tells AGP where Android SDK lives. |
| `.gitignore` | Ignores `.gradle/, build/, local.properties, .idea/, *.iml, captures/, .cxx/`. | Keeps build artifacts/SDK path out of VCS. |
| `app/build.gradle.kts` | Module config: `namespace/applicationId com.netforge.inspector`, `compileSdk 36, minSdk 24, targetSdk 35, v1.0.0`; release has `minify+shrinkResources+proguard`, debug not; `Java 21 / jvmTarget JVM_21`; `buildFeatures compose+buildConfig`; `jniLibs.useLegacyPackaging=true` (GeckoView `.so` requirement). Deps: core-ktx, activity-compose, compose BOM (ui/tooling-preview/foundation/material3/icons), lifecycle-runtime/viewmodel-compose, room-runtime/ktx + ksp compiler, coroutines-android, okhttp, geckoview. | Implements versions from TOML; produces debug/release APKs. |
| `app/proguard-rules.pro` | Release keep rules: `-keep org.mozilla.geckoview.**` (JNI), `-dontwarn okhttp3/okio/snakeyaml/java.beans/coroutines`, `-keep * extends RoomDatabase`, `-dontwarn room.paging`. | Applied only in release (`getDefaultProguardFile + this`). |
| `app/src/main/AndroidManifest.xml` | Permissions `INTERNET, ACCESS_NETWORK_STATE`; `application allowBackup=false, label @string/app_name, icon @mipmap/ic_launcher, theme @style/Theme.NetForge, usesCleartextTraffic=true`; single exported `MainActivity` with MAIN/LAUNCHER filter, `configChanges=orientation\|screenSize\|...`, `adjustResize`. | Entry point declaration; links label/icon/theme resources. |

### App entry + DI — `MainActivity.kt` (File 28)

Single file holds 4 types:
- `MainActivity : ComponentActivity`: `enableEdgeToEdge()`, `ServiceLocator.ensureInitialized()`, `setContent` wires `InspectorViewModel` (via `ServiceLocator.requireVmFactory()`), `GeckoViewHolder.create()`, `MainBrowserInspectorScreen` with all callbacks (`onUrlSubmit → browserManager.loadUrl(normalized)`, back/reload, console open/close/select/repeat/export/import/clear, repeater field updates, `onBrowserAttach → prepareEngine()+attachView`, `onRepeatSend/Close`). `jsonlPicker = OpenDocument(*/*) → vm.importFromUri`. `onResume → prepareEngine()` (creates `GeckoBrowserManager` with `ServiceLocator.extensionBridge` + Callbacks proxying to `vmProxy.onBrowserUrlChanged/onPageLoading/onHistoryChanged`). `onNewIntent → vmProxy.onImportIntent`. `onDestroy → browserManager.shutdown()`.
- `GeckoViewHolder.create(context): GeckoView` — avoids leaking Activity into engine module.
- `ServiceLocator` (process singleton): `ensureInitialized(appContext)` creates `JwtAndCookieParserImpl`, `InspectorDatabase.get()`, `GeckoExtensionBridge(parser,db)`, `OkHttpRepeaterEngine`, `MediaStoreJsonlManager`, VM Factory instantiating `InspectorViewModel(app, interceptor=bridge, repeaterApi, storage)` + `vmProxy`; `attachNativeRoutingModule` routes external JSON via `NativePacketReceiver → bridge.forwardExternalJson`.
- `InspectorViewModelProxy` interface (`onBrowserUrlChanged/onPageLoading/onHistoryChanged/onImportIntent`) — leak-safe bridge from Gecko callbacks to VM.
- Connects to: all 5 modules, `InspectorDatabase`, `InspectorViewModel`, `MainBrowserInspectorScreen`, GeckoView.

### Core — shared model + Room buffer

| File | Purpose | Connections |
|---|---|---|
| `core/model/NetworkPacketModels.kt` (File 08) | Shared DTOs: `TrafficCategory{REST,GRAPHQL,XHR_FETCH,PAGE,OTHER+fromJson}`, `ExtractedToken(headerName,tokenType,rawToken,decodedPayload + TYPE_* consts)`, `CookieInfo(...)`, `RawCapturedPacket(requestId,timestamp,domain,method,url,isApiCallHint,reqHeaders/Cookies/Body,statusCode,durationMs,respHeaders/Cookies/Body,error)`, `AnalyzedPacket(raw,category,tokens)`, `JwtPayload(headerJson,payloadJson,signature,exp)`, `RepeaterRequest(sourcePacketId,method,url,headers,cookies,body)`, `RepeaterResponse(statusCode,statusMessage,headers,body,durationMs,isError,errorMessage)`, `ExportResult(success,displayName,rowsExported,folderPath,jsonlUri,summaryUri)`. | Imported by parser, interceptor, repeater, storage, UI state, DB mapper. JSONL schema mirrors `RawCapturedPacket`+tokens. |
| `core/database/NetworkLogEntity.kt` (File 09) | Room `@Entity(network_logs, index timestamp)`: `requestId PK (ext "req_*" or localId()), timestamp, domain, method, url, requestHeadersJson, requestCookiesJson, extractedTokensJson, requestBody, statusCode, durationMs, responseHeadersJson, responseCookiesJson, responseBody, category (default OTHER), isApiCall, error`. `localId() = "local_"+now`. JSON columns preserve lossless export. | Written by `GeckoExtensionBridge.toEntity()`, read by VM/UI/storage; `category/isApiCall` derived from `AnalyzedPacket`. |
| `core/database/NetworkLogDao.kt` (File 10) | DAO: `insertAll(REPLACE)`, `upsert` (two-phase request→response coalescing, one row per requestId), `observeAll() Flow` (timestamp DESC), `getById`, `getAllOnce`, `observeCount() Flow<Int>`, `clearAll`, `delete(id)`. | Used by bridge (upsert/clear/count), VM (observeAll), storage (getAllOnce/insertAll/clear via bridge). |
| `core/database/InspectorDatabase.kt` (File 11) | `@Database([NetworkLogEntity], v1, exportSchema=false)` singleton `get(context)` via `Room.databaseBuilder("netforge_inspector.db").fallbackToDestructiveMigration()`. | Instantiated once in `ServiceLocator`; accessed in bridge, VM, storage via `get(context)`. |

### Module: browser engine

| File | Purpose | Connections |
|---|---|---|
| `modules/browser/api/BrowserEngineApi.kt` (File 16) | Interface: `attachView(Any?), loadUrl, loadUrlPasted, stop, reload, canGoBack/goBack, isSessionOpen, shutdown`. Contract promises standard mobile UA (Google OAuth works), multi-window popups in same cookie session, Paste&Go. | Implemented by `GeckoBrowserManager`; called by `MainActivity` only. |
| `modules/browser/internal/GeckoBrowserManager.kt` (File 17) | Owns `GeckoRuntime` (default `GeckoRuntimeSettings`: no forced UA spoof, keeps Google OAuth happy), primary `GeckoSession` + popup sessions, `Main` scope. `start()` opens session, installs `ProgressDelegate (onProgressChange/onPageStart→25/onPageStop→100)` + `NavigationDelegate (onLocationChange→onUrlChanged, onCanGoBack→tracked, onNewSession→fresh GeckoSession opened on same runtime, swapped into view, returned)` and `ensureBuiltIn("resource://android/assets/inspector_ext/","inspector_ext@netforge.inspector") → extensionBridge.hook(ext)`. `loadUrl = normalize+start+loadUri`; `normalize`: trim, keep if `://`, `https://` if host-like else DuckDuckGo search; `attachView(GeckoView)`, `goBack/reload/stop`, `shutdown` (close session, `runtime.shutdown`, `scope.cancel`). `GeckoViewHost` stub keeps Compose factory engine-agnostic. | Depends on `GeckoExtensionBridge` (nullable) + `Callbacks`; drives `ServiceLocator.vmProxy` via MainActivity callbacks; view attached from `MainBrowserInspectorScreen AndroidView`. |

### Module: interceptor

| File | Purpose | Connections |
|---|---|---|
| `modules/interceptor/api/NetworkInterceptorApi.kt` (File 14) | Interface: `setListener(Listener?), attachNativeBridge(NativePacketReceiver), clearLogs()` + `Listener{onPacketLogged(entity), onCaptureCountChanged(count)}` + `NativePacketReceiver{onNativePacket(json)}`. Companion `PacketWireFormat`: consts `TYPE_PACKET=netforge_packet, PING/PONG, NATIVE_PORT_NAME=netforge_bridge`; `parseRawPacket(json): RawCapturedPacket?` via `org.json` (rejects non-`netforge*` types, defaults for missing fields); `mapOfStrings`. | Implemented by `GeckoExtensionBridge`; consumed by VM (`setListener/clearLogs`) and `ServiceLocator.attachNativeRoutingModule`. |
| `modules/interceptor/internal/GeckoExtensionBridge.kt` (File 15) | Native-messaging bridge: `Default` scope, `listener, openPort, externalReceiver`. `hook(ext)`: `ext.setMessageDelegate(onConnect(port) → port.setDelegate(onPortMessage→handleRawJson, onDisconnect→clear), NATIVE_APP_NAME="runtime")`. `forwardExternalJson` (onMessage path), `attachNativeBridge` stores receiver. `handleRawJson`: skip blank/ping/pong, `parseRawPacket ?: return`, `parser.analyze → toEntity() → dao.upsert → listener callbacks`. `clearLogs→dao.clearAll+count(0)`, `postControlMessage`, `shutdown`. Bottom `AnalyzedPacket.toEntity()`: blank id→`localId()`, uppercase method, `JSONObject(map).toString()` for headers/cookies, `JwtAndCookieParserImpl.tokensToJson(tokens)`, `category.name`, `isApiCall = category!=PAGE`. | Depends on `TrafficParserApi` + `InspectorDatabase`; `hook` called by `GeckoBrowserManager.start()`; listener is `InspectorViewModel`. |

### Module: parser

| File | Purpose | Connections |
|---|---|---|
| `modules/parser/api/TrafficParserApi.kt` (File 12) | Interface: `suspend analyze(raw): AnalyzedPacket` (stateless, Default dispatcher), `hasAuthHeaders(headers): Boolean`; `AUTH_HEADER_NAMES = {authorization,x-access-token,x-api-key,x-auth-token,x-csrf-token,x-xsrf-token,api-key,apikey,token}`. | Implemented by `JwtAndCookieParserImpl`; used by bridge + `hasAuthHeaders` available to console badging. |
| `modules/parser/internal/JwtAndCookieParserImpl.kt` (File 13) | 3 jobs on `Dispatchers.Default`: (1) `classify`: GraphQL if `/graphql` path / `application/graphql` / body starts `"query"`; PAGE if GET + html accept/content-type and no API hint; REST if json content/accept or API path hint (`/api/,/rest/,/rpc/,/service/,/ajax/,/_api/,/v1-4/,/graphql,/gateway/,/json/`) or `isApiCallHint`; else XHR_FETCH. (2) `extractTokens`: Bearer→split JWT, `decodeJwt`→`Bearer_JWT` else `Bearer`; Basic→base64-decode→`Basic`; other auth headers→`ApiKey/Bearer`; auth-like cookies (`auth,session,sess,sid,jwt,token,access,refresh,id` substring)→`Custom_Auth`. `decodeJwt`: split 2-3 parts, base64url-decode header/payload, require `{...}`, extract `exp` via JSONObject. (3) Cookie parsing: `parseSetCookie` splits `;`, extracts domain/path/httpOnly/secure/sameSite/expires → `CookieInfo`; static `tokensToJson` serializes tokens to `[{headerName,tokenType,rawToken,decodedPayload}]`. | Called by `GeckoExtensionBridge.handleRawJson`; `tokensToJson/parseSetCookie` also used by bridge/UI export. |

### Module: repeater

| File | Purpose | Connections |
|---|---|---|
| `modules/repeater/api/RequestRepeaterApi.kt` (File 18) | Interface: `suspend execute(RepeaterRequest): RepeaterResponse` (IO, never throws — errors in `isError`), `cloneForEditing(sourcePacketId,method,url,headersJson,cookiesJson,body): RepeaterRequest`. | Implemented by `OkHttpRepeaterEngine`; called via `RepeaterBridge` from VM. |
| `modules/repeater/internal/OkHttpRepeaterEngine.kt` (File 19) | Native CORS-free engine: lazy `OkHttpClient(connect 20s/read 30s/write 30s, followRedirects)`. `execute` on IO: `buildCall→client.newCall→execute`, time with `nanoTime`, headers→map, body capped `512KB` UTF-8, map to `RepeaterResponse`; catch→`isError` response. `buildCall`: `Request.Builder.url`, copy headers skipping hop-by-hop `{host,content-length,connection,accept-encoding,transfer-encoding,upgrade,keep-alive}`, merge cookies to `Cookie: k=v;…`, GET/HEAD/OPTIONS→no body, POST/PUT/PATCH/DELETE→`body.toRequestBody(contentType before ; or application/json)`, else→body with null type. `cloneForEditing`: `jsonToObject` (JSONObject→map, blank/corrupt→empty) + uppercase method. | Used by `InspectorViewModel.sendRepeaterRequest`; cookies from editor preserve session. |

### Module: storage

| File | Purpose | Connections |
|---|---|---|
| `modules/storage/api/StorageExporterApi.kt` (File 20) | Interface: `suspend exportAll(): ExportResult`, `suspend importJsonl(uri): Int`, `toJsonlLine(entity): String`. Export layout `Downloads/DevInspector_Logs/<domain>_<yyyy-MM-dd_HH-mm-ss>/{network_traffic.jsonl,auth_summary.json}`. Scoped-storage, zero legacy permissions. | Implemented by `MediaStoreJsonlManager`; called by VM export/import. |
| `modules/storage/internal/MediaStoreJsonlManager.kt` (File 21) | MediaStore impl on IO: `exportAll`: `getAllOnce`, empty→failure; `buildFolderName(first non-blank domain sanitized [^A-Za-z0-9._-]→_ max48 or mixed_domains + local-tz stamp)`; `createFile(relativeDir,name,mime)` via `MediaStore.Downloads` (+`IS_PENDING=1` on Q+); `writeJsonl` (one `toJsonlLine` per line, clear `IS_PENDING`); `writeSummary` (dedup endpoints by `METHOD path-before-?`, dedup tokens by `rawToken`, merge session cookies from resp+req) → `{exportedAt,totalPackets,endpoints[],extractedTokens[],sessionCookies{}}` pretty 2-space; returns `ExportResult`. `importJsonl(uri)`: SAF input stream, per-line `entityFromJsonlLine` (packet rows require `requestId`, token-only rows tolerated), dedup id `orig#imp_<now>`, `insertAll`, return count. `toJsonlLine`: `{requestId,timestamp,domain,method,url,isApiCall,requestHeaders,requestCookies,extractedTokens[],requestBody,statusCode,durationMs,responseHeaders,responseCookies,responseBody}` preserving stored JSON. `EXPORT_ROOT="Download/DevInspector_Logs"`, `JSONL_FILE, SUMMARY_FILE` consts. | Depends on `InspectorDatabase.get(context)` + `ContentResolver`; URIs surfaced in `ExportStatus` message via VM. |

### UI — state + ViewModel

| File | Purpose | Connections |
|---|---|---|
| `ui/state/InspectorUiState.kt` (File 22) | `InspectorUiState(urlBarText,pageProgress,canGoBack,consoleOpen,captureCount,packets,selectedPacketId,expandedPacket,repeaterSheet,exportStatus,captureEnabled=true,urlValidationError)`. `RepeaterSheetState(open,sourcePacketId,method,url,headersText,cookiesText,bodyText,sending,response + formatMapText(mapJson→"K: V\n…"))`. `ExportStatus(busy,message,isError)`. `RepeaterMethods.ALL=[GET,POST,PUT,PATCH,DELETE,HEAD,OPTIONS]`. `RepeaterRequestModel` typealias. `RepeaterBridge(api)` adapter: `clone(sheet)` (`headersText/cookiesText.toMapJson()`), `send(model)=api.execute`. `String.toMapJson()`: `"K: V"` lines→JSONObject string (skip lines without colon). | Consumed by all Compose screens; `RepeaterBridge` keeps VM dependent only on `RequestRepeaterApi`. |
| `ui/viewmodel/InspectorViewModel.kt` (File 23) | Central orchestrator (`AndroidViewModel`, only `api/` deps + `InspectorDatabase` for the Flow). `ui: StateFlow<InspectorUiState>`, `pendingJsonlPickRequest: MutableStateFlow(false)` (observed by MainActivity picker). `init`: collect `dao.observeAll()`→`packets/expandedPacket`; `interceptor.setListener(onPacketLogged noop — Room flow pushes rows; onCaptureCountChanged→captureCount)`. URL: `onUrlBarChanged/onUrlSubmitted (empty→validation error)/onPageLoading/onBrowserUrlChanged/onHistoryChanged`. Console: `open/close/select/clearLogs→interceptor.clearLogs`. Import: `request/consumeJsonlPickRequest/onImportIntent(intent.data)/importFromUri→storage.importJsonl + ExportStatus`. Export: guard `captureCount==0`, busy→`storage.exportAll()`→message `Saved to <folder> (N packets).` or failure. Repeater: `openRepeater(id)` clones packet (`formatMapText` headers/cookies) + closes console; `updateRepeater/closeRepeater/sendRepeaterRequest` (guard blank/sending, `cloneForEditing(toMapJson)+execute→response`). | Bridges Gecko callbacks (via `vmProxy`), Room Flow, interceptor/repeater/storage APIs, and Compose state. |

### UI — Compose components + screen

| File | Purpose | Connections |
|---|---|---|
| `ui/components/TopSmartUrlBar.kt` (File 24) | Smart bar: back (enabled by `canGoBack`), reload, Paste&Go (reads clipboard→`onUrlChanged+onSubmit+clearFocus`, green), `TextField` (dark `161A22`, Go IME), submit arrow, validation error text, circular progress when `1..99`. Uses `GeckoBrowserManager.normalize` doc-wise (actual normalize happens in manager). | Top slot of `MainBrowserInspectorScreen`; props from `InspectorUiState` + VM callbacks. |
| `ui/components/NetworkConsoleBottomSheet.kt` (File 25) | Console (92% height, `12141A`): `ConsoleHeader` (back-when-expanded else close, title, import/upload blue, clear red, export green, status message), `PacketList` (empty hint; rows: `MethodBadge` GET blue/POST green/DELETE red/other amber + `·API` suffix, url ellipsis, `domain · ms · 🔑 if tokens!=[]`, `StatusBadge` 2xx green/3xx amber/4-5xx red/0 ERR) or `PacketDetail` (METHOD url, domain·status·ms, `Open in Repeater` 200dp, sections: Req/Resp headers+cookies (`KeyValues` pretty JSON), `TokensSection ([type] header\nraw\npayload)`, bodies capped 12k, error). | Shown in `ModalBottomSheet` when `consoleOpen`; `onRepeat(requestId)`→VM `openRepeater`. |
| `ui/components/RequestRepeaterSheet.kt` (File 26) | Editor (96% height): header + Close, method dropdown (`RepeaterMethods.ALL`) + URL field, headers/cookies multi-line (`Key: Value`), body (hidden for GET/HEAD), Execute/Sending button, `ResponseBlock` (status color + ms, error red block else headers blue + body mono blocks, scrollable). | Shown when `repeaterSheet.open`; all edits→`vm.updateRepeater`, send→`vm.sendRepeaterRequest`. |
| `ui/screens/MainBrowserInspectorScreen.kt` (File 27) | Root layout: `Column(0B0D12)`: `TopSmartUrlBar` + `Box(weight 1f)`: `AndroidView(factory→onBrowserAttach(geckoView))` (engine attaches session) + FAB (`NetworkCheck`, badge `captureCount`) bottom-end. Two `ModalBottomSheet(skipPartiallyExpanded)`: console (`consoleOpen`) and repeater (`repeaterSheet.open`). | Composed by `MainActivity.setContent`; forwards all VM callbacks; `onUrlSubmit(ui.urlBarText)`→activity normalizes via manager. |

### WebExtension assets

| File | Purpose | Connections |
|---|---|---|
| `app/src/main/assets/inspector_ext/manifest.json` | MV2 extension: `name NetForge Inspector Traffic Capture v1.0.0`, `gecko.id inspector_ext@netforge.inspector, strict_min_version 113.0`, perms `geckoViewAddons,nativeMessaging,webRequest,webRequestBlocking,cookies,<all_urls>`, `background.scripts=[background.js]`. ID+path must match `GeckoBrowserManager.ensureBuiltIn("resource://android/assets/inspector_ext/", id)`. | Loaded at `GeckoBrowserManager.start()`; `hook()` wires native port. |
| `app/src/main/assets/inspector_ext/background.js` | Capture logic: `NATIVE_PORT_NAME, MAX_BODY_CHARS 2M, TEXT_LIKE_MIME_FRAGMENTS (json/form/graphql/xml/js/api+json/plain/html/css), API_HINT_PATHS`. `ensureNativePort (connectNative + ping→pong + disconnect tolerate)`, `sendToNative (+500 backlog queue)`, helpers (`headerArrayToObject last-wins, parseCookiePairs skip Path/Domain, setCookieJarFromResponseArray array-aware for multi Set-Cookie, contentTypeOf, isTextLikeMime, isApiCall (path hint / non-GET non-frame / accept json), decodeRawBody (formData/raw ArrayBuffer/bytes/string, UTF-8)`), `pending Map + 5min sweep + 60s interval`, listeners `onBeforeRequest(requestBody) / onSendHeaders(requestHeaders) / onHeadersReceived(responseHeaders + conditional filterResponseData stream with resume/disconnect, cached/SW→filterFailed) / onCompleted+onErrorOccurred→finishRequest` building `netforge_packet{type,requestId "req_"+id,requestTimestamp,domain,method,url,isApiCall,requestHeaders/Cookies,extractedTokens:[],requestBody,statusCode,durationMs,responseHeaders/Cookies,responseBody,outcome,error}`. Schema matches `PacketWireFormat.parseRawPacket` (unknown fields like `outcome` ignored). | Sole traffic source; JSON consumed by `GeckoExtensionBridge`→parser→Room. |

### Resources

| File | Purpose | Connections |
|---|---|---|
| `res/values/strings.xml` | `app_name = NetForge Inspector`. | Referenced by manifest `android:label`. |
| `res/values/themes.xml` | `Theme.NetForge : Material.NoActionBar`, transparent status/nav, `windowBackground #FF12141A` (matches Compose `0B0D12/12141A` dark chrome, avoids white flash). | Referenced by manifest `android:theme`. |
| `res/values/colors.xml` | `ic_launcher_background #12141A`. | Used by adaptive icon. |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon: background `@color/ic_launcher_background` + foreground drawable. | Referenced by manifest `android:icon`. |
| `res/drawable/ic_launcher_foreground.xml` | Vector 108dp: teal `#2ECE9E` shield + dark network nodes/lines (brand = security + traffic graph). | Foreground of adaptive icon. |

## 4. Build / environment notes

- JDK **21 required**. JDK 25 breaks Kotlin-DSL script compilation (`JavaVersion.parse: 25.0.2`). Use `JAVA_HOME=/usr/local/sdkman/candidates/java/21.0.10-ms` (also available: `/usr/lib/jvm/java-17-openjdk-amd64`, sdkman `21.0.10-ms/25.0.2-ms`).
- Gradle 8.14.3, AGP 8.13.2, Kotlin 2.3.21, KSP 2.3.12, compileSdk 36 / target 35 / min 24.
- GeckoView 128 ESR from `maven.mozilla.org`; `jniLibs.useLegacyPackaging=true` mandatory.
- No unit tests in repo; verification was `assembleDebug UP-TO-DATE → BUILD SUCCESSFUL` + APK existence check.
- `android:usesCleartextTraffic=true` allows http repeater/capture; no `WRITE_EXTERNAL_STORAGE` (MediaStore+SAF only).

## 5. Known quirks (for next tasks)

- `GeckoExtensionBridge.NATIVE_APP_NAME="runtime"` vs JS `connectNative("netforge_bridge")`: GV-128 `setMessageDelegate(delegate, NATIVE_APP_NAME)` scopes messages; port name match is by extension wiring in `GeckoBrowserManager`, not string equality — don't "fix" by renaming without testing on device.
- `background.js:collectSetCookies` is a no-op stub; real work is `setCookieJarFromResponseArray` — intentional.
- `MediaStoreJsonlManager.EXPORT_ROOT="Download/DevInspector_Logs"` combined as `RELATIVE_PATH` — on-device path resolves under `Download/`; spec folder name is `DevInspector_Logs` (not NetForge).
- Import dedups via `requestId#imp_<now>` so re-imports never collide; token-only lines create blank rows (filtered by `isNotBlank` check).
- `InspectorViewModel.onPacketLogged` is intentionally empty (Room Flow is source of truth); count flows separately.
- Release APK is `unsigned` (`app-release-unsigned.apk`) — needs signing before install; use debug APK for device testing.
