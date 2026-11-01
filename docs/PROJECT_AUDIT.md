# PROJECT AUDIT — SETT Browser

Audit date: 2026-10-31 · Codebase state: v0.4.0 (versionCode 9) · Method:
every `.kt` file, the manifest, all Gradle files, layouts and values
resources were read in full; claims below cite the file that proves them.
This is an audit only — no application code was modified.

## 1. Repository structure

```
private-x/
├── README.md, LICENSE (GPL-3.0)
├── android/                  Gradle project (rootProject.name still "PrivateX")
│   ├── build.gradle.kts      AGP 9.1.0, Kotlin 2.4.20 (plugin declarations)
│   ├── settings.gradle.kts   local-maven first, then google/mavenCentral/mozilla
│   ├── gradle.properties
│   ├── key.properties        signing secrets — gitignored, never read by this audit
│   ├── vendor_mozilla.py     curl-vendors the org.mozilla.* closure → local-maven/
│   ├── upload_asset.py       GitHub Release asset uploader (connector credential)
│   ├── local-maven/          274 MB, 43 AARs + 45 POMs (gitignored, regenerable)
│   └── app/
│       ├── build.gradle.kts  the only module
│       └── src/
│           ├── main/java/app/privatex/browser/   6 Kotlin files, 1,191 lines
│           ├── main/res/     1 layout, 5 drawables, colors/themes, launcher icons
│           └── test/         1 JVM test class, 7 tests
├── design/                   icon/mascot generation scripts (PIL) + concept art
├── docs/                     ADR-001, architecture.md, roadmap.md, research-summary.md,
│                             UX_STUDY.md, SETT_040_PLAN.md, TEST_RESULTS_040.md (+ this audit set)
├── hidden_files/             build logs
└── release/                  every shipped APK v0.1.0–v0.4.0 (both ABIs) + the
                              release keystore .jks (gitignored; see SECURITY_AUDIT_INITIAL)
```

The entire application logic is **6 Kotlin files**:

| File | Lines | Role |
|---|---|---|
| `BrowserActivity.kt` | 734 | The whole UI: chrome, tabs tray, shields, menu, settings, downloads list, fire dialog, about |
| `Components.kt` | 235 | DI wiring: GeckoRuntime, GeckoEngine + privacy defaults, store, use cases; `HomeInterceptor` (start page + error pages) |
| `PrivateXApp.kt` | 83 | Application: main-process guard, session autosave, local `CrashLog` |
| `SettingsStore.kt` | 83 | 3 SharedPreferences settings + `AddressResolver` (omnibox classification) |
| `DownloadService.kt` | 30 | `AbstractFetchDownloadService` wiring |
| `HomeUrls.kt` | 26 | Pure-Kotlin start-page URL matching (current + legacy) |

## 2. Entry points

- **Application**: `PrivateXApp` (`AndroidManifest.xml`: `android:name=".PrivateXApp"`).
  Initialises components **only in the main process** — GeckoView child
  processes (`:gpu`, `:content`) re-run `Application.onCreate`, and building
  a runtime there was the v0.1.1 launch crash (fixed v0.1.2).
- **Activity**: single activity, `BrowserActivity` — launcher entry plus an
  `ACTION_VIEW` intent filter for `http`/`https` (external links open a new
  tab, `handleViewIntent`). No other activities, no deep links beyond that.
- **Service**: `DownloadService` (foreground service, `dataSync` type,
  `exported=false`).
- Manifest permissions are minimal: `INTERNET`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`. `allowBackup=false`,
  `usesCleartextTraffic=false`.

## 3. Engine and rendering architecture

- **Engine**: GeckoView `159.0.20261022150525` (`org.mozilla.geckoview:geckoview-omni`
  AAR in local-maven), consumed prebuilt via Mozilla Android Components
  **159.0** — decision record `docs/ADR-001-engine-decision.md`. No engine
  source is compiled; no Chromium/WebView exists anywhere in the app.
- **Runtime**: one `GeckoRuntime` per process (`Components.runtime`,
  `GeckoRuntime.create`) — a hard GeckoView limit, documented in ADR-001.
- **Rendering**: `GeckoEngine.createView()` produces the `EngineView`, added
  to `R.id.engine_container` in the single layout
  (`res/layout/activity_browser.xml`: progress bar / engine container /
  floating bottom chrome bar). All other UI (tabs tray, menu, settings,
  dialogs) is built **programmatically in Kotlin** — there is exactly one
  layout XML and **no `strings.xml`** (all UI text is hardcoded English in
  Kotlin and in the start-page HTML).
- **State**: AC `BrowserStore` (Redux-style) with middleware
  `listOf(UndoMiddleware()) + EngineMiddleware.create(engine)`
  (`Components.store`). The activity collects `store.flow()` and re-renders
  chrome manually (`BrowserActivity.render`).

## 4. Navigation

- Omnibox = a plain `EditText`. On Go: `AddressResolver.classify()` →
  Home / Direct URL / Search (`SettingsStore.kt`) — pure Kotlin, unit-tested.
  Bare hosts get `https://`; `about:`/`view-source:` pass through.
- Back/forward/reload via `SessionUseCases` + `SessionFeature`
  (`BrowserActivity`); system back goes through find-in-page, then web
  history, then finish.
- Address display is host-only with a 🔒 prefix for HTTPS while browsing;
  the full URL appears on focus for editing. **No autocomplete, no
  suggestions** — a documented privacy decision (SETT_040_PLAN Phase 3c:
  nothing is sent while typing).
- Error pages: `HomeInterceptor.onErrorRequest` → AC `browser-errorpages`
  `ErrorPages.createUrlEncodedErrorPage` (archive.org fallback disabled).

## 5. Tabs and sessions

- Tabs via AC `feature-tabs` `TabsUseCases`: normal + **private tabs**
  (`private = true`), card-grid tray (2-column `RecyclerView` in a bottom
  sheet, built in `BrowserActivity.TabsAdapter`), tab-count badge,
  close-with-**Undo** (`UndoMiddleware` + Snackbar, v0.4.0).
- **Session persistence**: AC `SessionStorage` with `autoSave`
  (periodically in foreground, on backgrounding, on session change —
  `PrivateXApp.onCreate`); the store is seeded from `sessionStorage.restore()`.
- **Start page**: `sett://home` served from memory by `HomeInterceptor`
  (no network). Legacy `privatex://home` is served permanently
  (`HomeUrls.kt`) because session restore resurrects pre-rename tab URLs
  verbatim — dropping it blanked home tabs in v0.3.0.
- Crash handling: `CrashLog` writes the last uncaught-exception trace to
  the private files dir; next launch offers to share it. Never transmitted
  by the app itself.

## 6. Settings (complete list — there are only three)

`SettingsStore.kt` → SharedPreferences file `privatex-settings`:

| Setting | Values | Effect |
|---|---|---|
| `etp_strict` | Standard / Strict | `TrackingProtectionPolicy.recommended()` / `.strict()`; applied live to `engine.settings` from the Settings dialog |
| `search_engine` | DuckDuckGo HTML (default), Brave, Mojeek, Startpage | URL template in `searchUrl()` |
| `fp_strict` | Balanced / Strict | Strict sets Gecko prefs `privacy.resistFingerprinting` + `letterboxing` at engine creation (`Components.applyGeckoPrefs`) — applies after restart, and the UI says so |

Settings UI is a single `AlertDialog` with three radio groups
(`BrowserActivity.showSettings`). There is no settings screen, no homepage
choice, no theme choice, no per-site settings store beyond ETP exceptions
(engine-held, toggled from the Shields dialog).

Engine-level privacy defaults are set once in `Components.engine`
(`DefaultSettings`): HTTPS-Only `ENABLED`, GPC on, fingerprinting
protection on, query-parameter stripping on, DoH mode `DEFAULT`,
remote debugging off, JavaScript on; plus Gecko prefs disabling Mozilla
telemetry/Normandy/ping-centre (`applyGeckoPrefs`).

## 7. Build configuration

- AGP **9.1.0**, Kotlin **2.4.20** (root plugin declaration; AGP 9 built-in
  Kotlin), Java 17, Gradle 9.3.1 (invoked directly — no `gradlew` in repo).
- `compileSdk 36`, `targetSdk 36`, `minSdk 26` (Android 8.0+). AC 159 AARs
  declare minCompileSdk 37; the AAR-metadata check is **disabled**
  (`tasks.matching { it.name.contains("AarMetadata") }` in
  `app/build.gradle.kts`) — a known fragility, see TECHNICAL_DEBT.
- ABI splits: arm64-v8a, armeabi-v7a, x86_64; no universal APK.
- Signing: release keystore via `key.properties` (gitignored), v1+v2+v3
  schemes enabled (apksig skips v1 at minSdk 26; verified v2+v3 in
  TEST_RESULTS_040). Cert SHA-256 `0a60cd9f…24f2`, unchanged since v0.1.0.
- **R8/minification OFF** (`isMinifyEnabled = false`, deliberate, documented
  in the build file).
- Dependencies resolve **offline-first**: the whole `org.mozilla.*` closure
  is vendored into `android/local-maven/` by `vendor_mozilla.py` (curl)
  because Java/Gradle cannot reach maven.mozilla.org through this
  environment's proxy; other deps come from google()/mavenCentral().
- **No CI**: there is no `.github/` directory; builds and releases are
  manual, from this VM.

## 8. Platform support

Android only. minSdk 26; published ABIs arm64-v8a + armeabi-v7a (x86_64 is
built by the split config but not published). Desktop is a documented
future direction (ADR-001: Gecko-family, LibreWolf model), not started.
Verified install target so far: the developer's Android 16 phone only.

## 9. Tests

- **One JVM unit-test class**: `SettLogicTest.kt` — 7 tests covering
  `HomeUrls` matching and `AddressResolver.classify`. 7/7 passing
  (TEST_RESULTS_040). The suite caught one real bug in development
  (localhost:port misclassified as search).
- **No instrumentation tests, no emulator/device test automation** (the
  build VM has no KVM/emulator — stated in TEST_RESULTS_040). On-device
  acceptance is manual, by the developer, per release.
- Release verification is a manual from-the-file ritual (badging,
  apksigner, zipalign, zip integrity, DEX string checks) — recorded per
  release in `docs/TEST_RESULTS_040.md`; not scripted as a single gate.
- Lint runs as part of the release build and is currently green; an AGP
  lint/UAST crash on a lambda-typed helper occurred during v0.4.0
  development and was removed by refactoring (TEST_RESULTS_040).

## 10. Known bugs and defects (from code + release history)

| # | Issue | Evidence |
|---|---|---|
| 1 | v0.1.0 uninstallable on Android 16 (compileSdk 37 preview codename stamp) | README release history; fixed v0.1.1 |
| 2 | v0.1.1 crash at launch (components built in GeckoView child processes) | README; `PrivateXApp.isMainProcess` guard; fixed v0.1.2 |
| 3 | v0.3.0 blank home tab after rename (legacy URL unserved) | `HomeUrls.kt` doc comment; fixed v0.3.1/v0.4.0 |
| 4 | v0.3.0 content under status bar; keyboard covered chrome | fixed v0.4.0 (window insets in `BrowserActivity.onCreate`) |
| 5 | No prompt UI exists: `feature-prompts` is not a dependency and no prompt/permission delegate UI is wired — JS `alert/confirm/prompt`, site permission requests (camera/mic/location) and fullscreen requests have **no app-level UI**. Behaviour falls to AC/Gecko defaults; not device-verified in this audit | absence in `app/build.gradle.kts` + grep of app sources (no `Prompt`, `Fullscreen`, `onRequestPermissions` hits) |
| 6 | Device acceptance of v0.4.0 still pending — all v0.4.0 behaviour is builder-verified only | README status; TEST_RESULTS_040 "Not covered here" |
| 7 | Doc/code drift: `docs/architecture.md` describes components that do not exist in the build (feature-toolbar, feature-awesomebar, feature-readerview, feature-contextmenu, feature-prompts, feature-webextensions, bookmarks/history stores, dashboard with counts, WebRTC pref, cookie-banner pref). It is a Phase-2 design document, not a description of the shipped app | `docs/architecture.md` vs `app/build.gradle.kts` dependency list and app sources |
| 8 | README build notes are stale: they describe a compileSdk 37.0 setup while `app/build.gradle.kts` pins compileSdk 36 (the v0.1.1 fix) | README "Build notes" vs `app/build.gradle.kts` |

No `TODO`/`FIXME`/`HACK` markers exist anywhere in the app sources or docs.

## 11. Overall health verdict

**Healthy, small, and honest — a working thin embedder, not yet the
ecosystem the master plan describes.** The codebase is 1,191 lines of
application Kotlin on top of Mozilla's engine and components; what exists
(browsing, tabs + private tabs + undo, session restore, downloads plumbing,
find-in-page, ETP configuration, error pages, local start page, data
clearing) is real and wired end-to-end, and the project has an unusually
disciplined verification habit for a solo project (from-the-file release
checks, unit tests pinning the two rules that previously broke on device,
an ADR, and in-app honesty about limits). The gaps are absences, not
rot: no bookmarks, no history UI, no prompt/permission UI, no reader mode,
no extensions UI, no dashboards, and most of the master plan's Part 4–17
surface is unbuilt. The main risks are structural (single-activity
programmatic UI, disabled AAR-metadata check, manual release pipeline,
upstream cadence) and are catalogued in TECHNICAL_DEBT.md.
