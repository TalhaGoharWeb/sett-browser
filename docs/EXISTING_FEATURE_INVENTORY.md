# EXISTING FEATURE INVENTORY — SETT Browser v0.4.0

Audit date: 2026-10-31. Classifications use the master plan's vocabulary:
**Fully implemented** / **Partially implemented** / **Implemented but
defective** / **UI-only** / **Placeholder** / **Missing** / **Deprecated** /
**Security-sensitive** / **Requires architectural changes**. Every row names
the file that proves it. "Engine" means the behaviour is provided by
GeckoView/Android Components and configured — not re-implemented — by Sett.

## A. What the app actually implements

| # | Feature | Classification | Evidence |
|---|---|---|---|
| A1 | Web navigation (load URL, back/forward/reload) | Fully implemented | `BrowserActivity` + `SessionFeature`/`SessionUseCases` (`feature-session`) |
| A2 | Address bar (host-only display, 🔒 for HTTPS, full URL on focus) | Fully implemented | `BrowserActivity.render/displayUrl` |
| A3 | Omnibox classification (URL vs search vs home) | Fully implemented, unit-tested | `SettingsStore.AddressResolver`, `SettLogicTest` (7 tests) |
| A4 | Local start page (`sett://home` + legacy `privatex://home`), search form, 4 search tiles | Fully implemented | `Components.HomeInterceptor.onLoadRequest`; page served from memory, no network |
| A5 | Start-page status chips ("Tracking protection on", "HTTPS-Only on", "GPC signal sent", "Cookies partitioned") | UI-only (static text) | Hardcoded HTML in `HomeInterceptor`. The claims match the engine defaults set in `Components`, but the chips are not live-measured state |
| A6 | Multi-tab browsing (open/select/close, tab-count badge) | Fully implemented | `feature-tabs` `TabsUseCases`; `BrowserActivity.showTabsTray/TabsAdapter` |
| A7 | Private tabs (isolated cookie/storage context, PRIVATE chip in tray) | Fully implemented (engine contextual isolation) | `addTab(..., private = true)` in menu + tray; isolation is Gecko's. Not separate runtimes — stated honestly in About |
| A8 | Tab-close Undo (seconds-long window) | Fully implemented | `UndoMiddleware` in `Components.store`; Snackbar flow in `BrowserActivity.updateUndoSnackbar` |
| A9 | Session restore across restarts | Fully implemented | `SessionStorage.autoSave` in `PrivateXApp`; store seeded from `restore()` in `Components.store` |
| A10 | Crash recovery (session) + local crash report | Fully implemented, Security-sensitive (report contents are user-shared only) | `PrivateXApp.CrashLog`; dialog in `BrowserActivity.onCreate` |
| A11 | Failed-load error pages | Fully implemented | `HomeInterceptor.onErrorRequest` + `browser-errorpages`; archive.org fallback explicitly disabled |
| A12 | Find in page | Fully implemented | `feature-findinpage`, `FindInPageBar` inserted in `BrowserActivity` |
| A13 | Downloads (start, foreground service, progress in store, list + remove entry) | Partially implemented | `DownloadsFeature` + `DownloadService` (AC `AbstractFetchDownloadService`) wired; UI is a simple list dialog (`showDownloads`) with remove-entry only — no pause/resume controls, no open-file action, no checksum verify, no .apk warning in app UI |
| A14 | External links (ACTION_VIEW http/https → new tab) | Fully implemented | Manifest intent filter; `BrowserActivity.handleViewIntent` |
| A15 | Enhanced Tracking Protection (Standard/Strict) | Fully implemented (engine), Security-sensitive | `DefaultSettings.trackingProtectionPolicy` in `Components`; live switch in Settings dialog |
| A16 | Per-site ETP exception (shields on/off per site) | Fully implemented | `BrowserActivity.showShields` via `engine.trackingProtectionExceptionStore` + reload |
| A17 | Shields panel (connection security label, ETP state, HTTPS-Only/GPC/fingerprinting/query-stripping state, site host) | Partially implemented — honest state display, **no counts** | `BrowserActivity.showShields`. AC exposes no per-request tracker counts, so none are shown (by design, never fabricated) |
| A18 | HTTPS-Only Mode | Fully implemented (engine) | `DefaultSettings.httpsOnlyMode = ENABLED`; manifest `usesCleartextTraffic=false` |
| A19 | Global Privacy Control | Fully implemented (engine) | `globalPrivacyControlEnabled = true` |
| A20 | Tracking-parameter stripping | Fully implemented (engine) | `queryParameterStripping = true` |
| A21 | Fingerprinting protection (engine FP) | Fully implemented (engine) | `fingerprintingProtection = true` |
| A22 | Strict fingerprinting profile (resistFingerprinting + letterboxing) | Partially implemented — two modes only (Balanced/Strict), no Custom mode, no per-API controls; applies after restart | `SettingsStore.strictFingerprinting`; `Components.applyGeckoPrefs` |
| A23 | Mozilla telemetry/Normandy/ping-centre off | Fully implemented (prefs) | `Components.applyGeckoPrefs` (6 prefs). The app never initialises Glean/Nimbus in its own code (no references in app sources; those AARs exist only as transitive deps in the vendored closure) |
| A24 | Clear all browsing data ("Fire") | Fully implemented | Menu → `engine.clearData(Engine.BrowsingData.all())`, then closes all tabs (`BrowserActivity.confirmFire`) |
| A25 | Search engines (4, user-selectable) | Fully implemented | `SettingsStore.SEARCH_ENGINES/searchUrl` |
| A26 | Settings screen | Partially implemented — one dialog, exactly 3 settings (ETP, search engine, fingerprinting) | `BrowserActivity.showSettings` |
| A27 | About & limits screen (honest capability limits, engine version) | Fully implemented | `BrowserActivity.showAbout` |
| A28 | Adaptive app icon (Brock the badger; foreground/background/monochrome layers) | Fully implemented | `res/mipmap-anydpi-v26/ic_launcher.xml`; `design/make_icon_badger.py` |
| A29 | Remote debugging off | Fully implemented | `DefaultSettings.remoteDebuggingEnabled = false` |
| A30 | DoH | Partially implemented — engine default only (`DohSettingsMode.DEFAULT` = Gecko's default TRR behaviour); no provider choice, no UI | `Components.DefaultSettings.dohSettingsMode` |

## B. Present in the platform but NOT wired in the app

These exist in GeckoView/AC 159 (several AARs are already in the vendored
closure as transitive dependencies) but Sett declares no dependency and has
no code for them. Classification: **Missing (app level)**.

| Feature | Upstream component | Notes |
|---|---|---|
| JS dialogs & site permission prompts UI | `feature-prompts` | **Most serious gap.** No prompt UI is wired; camera/mic/location requests and `alert()/confirm()` have no user-facing flow (see PROJECT_AUDIT known bug #5) |
| Reader mode | `feature-readerview` | Described as present in `docs/architecture.md`; it is not in the build |
| Context menus (long-press link/image) | `feature-contextmenu` | No app code |
| Bookmarks storage | `concept-storage-bookmarks` / `browser-storage-sync` family | AAR sits in local-maven only as a transitive artefact; unused |
| History storage/surfacing | AC history storage components | Engine keeps its own internal history (wiped by Fire) but the app has no history list, search, or API |
| Toolbar/awesomebar components | `browser-toolbar`, `concept-awesomebar`, `ui-autocomplete` | Vendored transitively; Sett hand-rolls an `EditText` instead |
| WebExtensions management | GeckoView `WebExtensionController`, `feature-webextensions` | Engine capability exists (ADR-001 cites it); no install/manage UI or controller wiring in app code |
| Tab thumbnails / tabs tray component | `browser-thumbnails`, `browser-tabstray` | Vendored transitively; tray is hand-built |
| Fullscreen / Picture-in-picture handling | `feature-session` fullscreen support + app wiring | No fullscreen or PiP code in `BrowserActivity`; video fullscreen behaviour unverified |

## C. Master-plan area mapping (Parts 4–17 of the product master plan)

| Master-plan area | Status in v0.4.0 | Evidence / note |
|---|---|---|
| Tab groups | Missing | No code, no state model |
| Vertical tabs | Missing | Tray is a 2-column card grid (bottom sheet) |
| Split-screen browsing | Missing | Single `EngineView` container |
| Multiple windows | Missing | Single activity, single store |
| Workspaces / Private Spaces | Missing | `docs/architecture.md` designs them on contextual identities; no code. GeckoRuntime is once-per-process (ADR-001), so spaces can only ever be cookie-store partitions |
| Pinned tabs | Missing | No pin state or UI |
| Tab duplication | Missing | Tray offers select/close only |
| Tab search | Missing | Tray has no filter field |
| Recently closed tabs | Partially implemented | Only the seconds-long UndoMiddleware window (A8); no persistent recently-closed list |
| Session restoration | Fully implemented | A9 |
| Crash recovery | Fully implemented | A10 + A9 |
| Bookmarks + folders | Missing | No storage, no UI (see B) |
| History + history search | Missing (app level) | Engine-internal only; Fire deletes it; nothing is surfaced |
| Reading list | Missing | — |
| Reader mode | Missing | See B |
| Website translation | Missing | No component wired; no service (any real option needs a network service — see roadmap) |
| Picture-in-picture | Missing | No wiring (see B) |
| Full-screen mode | Partially implemented (unverified) | No app wiring; engine behaviour on device not verified in this audit |
| Custom homepage | Missing | Start page is fixed `sett://home`; content is not user-editable |
| Search suggestions | Missing — **deliberate** | Documented privacy decision (SETT_040_PLAN 3c): nothing is sent while typing |
| Address-bar autocomplete | Missing | Plain `EditText`; no completion of any kind (would need history/bookmarks stores first) |
| Context menus | Missing | See B |
| Keyboard shortcuts | Missing | Android/touch target; no shortcut handling |
| Download interception | Fully implemented | Engine → `DownloadsFeature` → `DownloadService` |
| Downloads subsystem (queue/pause/resume UI, categories, hashes, scheduling) | Partially implemented | Store/service support the states; app UI is list + remove only (A13) |
| Browser profiles | Missing | One implicit profile (the Gecko profile); no profile manager |
| Content filtering — ad/tracker blocking | Partially implemented | ETP content-blocking categories via Gecko (A15) — real blocking, engine lists. **No** ABP-style filter engine, no custom lists, no subscriptions |
| Cosmetic filtering / element hiding / element picker | Missing | No engine hook exposed to the embedder for cosmetic rules; uBlock-as-extension is the ADR-001 answer, not yet wired |
| Custom filter lists / allowlists | Missing | Per-site ETP exception (A16) is the only allowlist-like control |
| CNAME tracking protection | Engine-inherited, not surfaced | Part of Gecko ETP behaviour; no app control or display. Not separately verified |
| Anti-tracking (3p cookies, storage partitioning, bounce tracking, referrer, param stripping) | Fully implemented (engine) | Total Cookie Protection/dFPI + ETP in Gecko; param stripping A20; GPC A19. Referrer behaviour is Gecko default, not app-configured |
| Automatic site-data clearing | Partially implemented | Manual Fire (A24) only; no on-exit/per-timer clearing. Private tabs' storage is engine-isolated per session |
| Anti-fingerprinting modes (Balanced/Strict/Custom) | Partially implemented | Balanced + Strict exist (A22); Custom missing; canvas/WebGL/audio/font/UA/timezone are engine/RFP behaviours, not per-API app controls |
| Privacy dashboard with real stats | Missing | Shields panel (A17) shows policy/exception state only. Per-request blocked counts are **not exposed by AC** — any counts would be fabricated, which the project forbids |
| Security dashboard | Missing | Shields shows a Secure/Insecure label only; no certificate viewer, no TLS details |
| Network center (DoH config, proxy, Tor, leak tests, IP info, connection inspector) | Missing | DoH is engine-default only (A30). No proxy UI/prefs, no Orbot hookup, no leak tests, no cert viewer. `docs/architecture.md` describes intent only |
| Password vault / autofill / passkeys | Missing in Sett | **Exists in the sibling suite** (PRIVACY X VaultX, libsodium — see §D). About screen says "arrive in later versions". No credential storage of any kind in Sett today |
| Extensions (install/manage/permissions) | Missing | Engine supports WebExtensions; nothing wired (see B) |
| Integrated search platform + categories (web/images/news/academic/…) | Missing | Sett only forwards queries to one of 4 third-party web-search engines (A25). No categories, no metasearch, no own index |
| Developer tools (inspector/console/network/…) | Missing | Remote debugging is deliberately OFF (A29). No in-app devtools of any kind; Chromium-style devtools do not exist on GeckoView |
| Scholar Research Desk (OpenAlex/Crossref/DOI, citations, PDF tools) | Missing | Nothing in code. Adjacent assets exist outside the browser: the developer's Maliki-fiqh bilingual databases and the suite (see §D) |
| Arabic/Urdu research (normalisation, root search, OCR, RTL) | Missing in the browser | `android:supportsRtl="true"` is declared; no Arabic/Urdu features exist in Sett |
| Study tools (flashcards/SRS/notes/quizzes) | Missing | Nothing in code |
| Built-in utilities (hash, EXIF, QR, converters, PDF tools) | Missing in Sett | Several exist in the sibling suite (§D) |
| AI workspace | Missing | Nothing in code; no provider config, no local model |
| Sync / backup | Missing | No account, no export/import, no backup format. Session/settings survive only on-device |

## D. Sibling-project assets (PRIVACY X suite, `~/workspace/privacy-x/`)

Not part of Sett, but real, tested code owned by the same developer and
licence-compatible (GPL-3.0) — the roadmap treats these as reuse assets:

| Asset | Where | State per its own records |
|---|---|---|
| VaultX — libsodium encrypted vault (Argon2id/XChaCha20 via `sodium_bindings.dart`, `vault.dart`) | `privacy-x/core/lib/privacy_x/vault.dart`, `app/lib/vault/` | Implemented; core test suites recorded green in `privacy-x/docs/TEST_RESULTS.md` |
| AuthenticatorX — TOTP | `core/lib/privacy_x/totp.dart` | Implemented + tested |
| CryptX — file encryption | `core/lib/privacy_x/cryptx.dart` | Implemented + tested |
| MetaClean — EXIF/metadata stripping (JPEG/PNG/WebP) | `core/lib/privacy_x/metaclean.dart` | Implemented |
| LinkGuard — URL analysis | `core/lib/privacy_x/url_analyser.dart` | Implemented |
| NetShield/TrackerX — DNS/VPN filtering, tracker blocking | `core/lib/privacy_x/netshield.dart`, `trackerx.dart` | Implemented (suite-side) |
| ABP-subset filter engine (pure Dart, ~178k rules parsed) | `core/lib/privacy_x/filter_engine.dart` | Implemented + benchmarked in the suite browser |
| Password tools, utilities, AppAudit, SecurityCenter (k-anonymity password check) | `core/lib/privacy_x/password_tools.dart`, `utilities.dart`, `appaudit.dart`, `security_center.dart` | Implemented |
| PrivateX v2 — hardened-WebView browser with the above | `app/lib/browser/` | Shipped as suite APK v1.1.0; superseded as flagship by Sett (ADR-001) |

Reuse caveat: the suite is **Dart/Flutter + Kotlin bridges**; Sett is
Kotlin/Android-Components. Vault/crypto *designs and test vectors* port;
Dart code does not drop in. `docs/architecture.md` already designates the
VaultX core as the password-manager source (Kotlin port, Phase 7 in the
old roadmap numbering).

## E. Deprecated / legacy items

| Item | Status |
|---|---|
| `privatex://home` URL | Deprecated as an address, **supported forever** as a served alias (`HomeUrls.LEGACY`) — removing it breaks restored sessions |
| "Private X" naming | Deprecated brand (renamed Sett in v0.3.0); survives deliberately in `applicationId` (`app.privatex.browser`), the prefs file name (`privatex-settings`), and accidentally in class names (`PrivateXApp`, root project name `PrivateX`, CrashLog header text) |
| Suite WebView browser (PrivateX v2) | Deprecated as the flagship browser per ADR-001; still shipped inside the suite |
| compileSdk 37.0 build setup | Deprecated (caused the v0.1.0 install failure); README build notes still describe it — stale |
