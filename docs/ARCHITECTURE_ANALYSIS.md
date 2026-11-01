# ARCHITECTURE ANALYSIS — SETT Browser

Audit date: 2026-10-31 · Scope: the shipped v0.4.0 codebase (not the
aspirational design in `docs/architecture.md`, whose divergences from the
code are catalogued in EXISTING_FEATURE_INVENTORY §B/§C).

## 1. Component map

```
┌──────────────────────────────────────────────────────────────┐
│ BrowserActivity (single Activity, 734 lines)                 │
│  chrome bar (layout XML) · tabs tray · shields · menu ·      │
│  settings dialog · downloads dialog · fire/about dialogs     │
│  observes BrowserStore.flow() → render(state)                │
├──────────────────────────────────────────────────────────────┤
│ Components (manual DI, lazy singletons)                      │
│  SettingsStore · GeckoRuntime · GeckoEngine · BrowserStore   │
│  SessionStorage · SessionUseCases · TabsUseCases ·           │
│  DownloadsUseCases · fetch client · notifications delegate   │
├──────────────────────────────────────────────────────────────┤
│ Android Components 159 (Mozilla, MPL-2.0, prebuilt AARs)    │
│  browser-state · browser-engine-gecko · browser-session-     │
│  storage · browser-errorpages · feature-session/-tabs/       │
│  -findinpage/-downloads · lib-fetch · support-*              │
├──────────────────────────────────────────────────────────────┤
│ GeckoView 159 (Mozilla prebuilt omni AAR)                    │
│  GeckoRuntime (once per process) → GeckoSession per tab      │
│  ETP/ContentBlocking · HTTPS-Only · GPC · dFPI · RFP         │
└──────────────────────────────────────────────────────────────┘
Processes: main (UI + runtime) · GeckoView :gpu / :content children
Services: DownloadService (AC AbstractFetchDownloadService, dataSync FGS)
```

There is no ViewModel layer, no repository layer, no DI framework, no
Compose, no navigation component. `Components` is a hand-rolled service
locator owned by `PrivateXApp` and exposed via the `Context.components`
extension (`PrivateXApp.kt`).

## 2. State management

- **Single source of truth**: AC `BrowserStore` (Redux-style,
  `lib-state`). All tab/session/download state lives in `BrowserState`;
  the app adds **no custom state classes, actions, or reducers**.
- **Middleware**: `listOf(UndoMiddleware()) + EngineMiddleware.create(engine)`
  (`Components.store`). `UndoMiddleware` snapshots removed tabs for the
  undo window; `EngineMiddleware` binds store tabs to engine sessions.
  That is the entire middleware chain — notably **no** middleware for
  history, bookmarks, thumbnails, or downloads-state enrichment, because
  those subsystems are not wired.
- **UI binding**: `BrowserActivity` collects `store.flow()` with
  `collectLatest` and imperatively re-renders (address text, progress,
  tab badge, find-bar binding, undo snackbar) with change-guards
  (`lastShownUrl`, `lastTabCount`). The per-phase guards added in v0.4.0
  (SETT_040_PLAN 3c) keep re-renders cheap; there is no diffing framework.
- **Engine → store direction**: AC features (`SessionFeature`,
  `FindInPageFeature`, `DownloadsFeature`) are started/stopped with the
  activity lifecycle and translate engine callbacks into store actions.
- **Use cases as the write API**: the activity never dispatches raw tab
  actions except `UndoAction.ClearRecoverableTabs`; everything else goes
  through `TabsUseCases`/`SessionUseCases`/`DownloadsUseCases`.

## 3. Data storage — what persists, where

| Data | Store | Location / mechanism | Survives restart? |
|---|---|---|---|
| Open tabs + selected tab | AC `SessionStorage` (autoSave: periodic-in-foreground, on-background, on-change) | App-private files (AC's session snapshot) | Yes — seeded into the store at startup (`Components.store`) |
| App settings (3 booleans/strings) | SharedPreferences `privatex-settings` | `SettingsStore.kt` | Yes |
| Cookies, site storage, cache, engine history | Gecko profile (engine-owned) | GeckoView profile dir in app-private storage | Yes, until Fire (`engine.clearData(BrowsingData.all())`) or app-data clear |
| ETP per-site exceptions | Engine's `trackingProtectionExceptionStore` | Engine-owned persistence | Yes (engine-managed) |
| Downloads (files) | Public Downloads via AC fetch service + `DefaultDownloadFileUtils` | Device storage | Yes |
| Downloads (list state) | `BrowserState.downloads` | In-store only; Sett adds no download-history persistence of its own | Not guaranteed by app code (AC behaviour, unverified) |
| Last crash trace | Plain text file `last_crash.txt` | App-private files dir (`CrashLog`) | Until next launch (read-and-delete) |
| Bookmarks / reading list / app-level history / notes | — | **Do not exist** | — |
| Passwords / credentials | — | **Do not exist** (nothing is stored) | — |

No SQLite/Room database exists anywhere in the app. The only structured
persistence is AC's session snapshot + SharedPreferences + the Gecko
profile. There are **no schema migrations to manage today** — and equally,
no data layer ready for the master plan's Part 19 entities
(BrowserProfile, Bookmark, HistoryEntry, …); those all start from zero.

## 4. Threading and processes

- UI/main thread: all chrome rendering; `render()` runs on the activity's
  `lifecycleScope` collection of the store flow.
- `Components.appScope` = `SupervisorJob() + Dispatchers.IO` — used as
  `SessionStorage`'s application scope (saves off the main thread).
- Engine work runs in GeckoView's own processes/threads; the app creates
  the runtime lazily on first `components` access in the main process.
- `PrivateXApp.onCreate` **must** no-op in `:gpu`/`:content` processes
  (`isMainProcess()` guard) — building `Components` there creates a second
  `GeckoRuntime`, which is fatal (the v0.1.1 crash; the guard is the fix).
- Downloads run in the foreground `DownloadService`, decoupled from the
  activity lifecycle.

## 5. The engine boundary

Sett touches Gecko through exactly four seams:

1. **`DefaultSettings`** at `GeckoEngine` construction — ETP policy,
   HTTPS-Only, GPC, fingerprinting protection, query stripping, DoH mode,
   remote debugging, the `RequestInterceptor`.
2. **`BrowserPreferencesRuntime.setBrowserPref`** (`applyGeckoPrefs`) —
   for prefs AC does not surface: Mozilla telemetry/Normandy off, and the
   optional RFP + letterboxing profile. Marked
   `@OptIn(ExperimentalAndroidComponentsApi)` — an explicitly experimental
   API (see §7).
3. **`RequestInterceptor`** (`HomeInterceptor`) — serves the local start
   page and error pages; it does **not** inspect or block other requests,
   so there is no app-level network filtering layer.
4. **Engine services** — `trackingProtectionExceptionStore`,
   `engine.clearData`, `engine.settings.trackingProtectionPolicy` (live
   ETP switch), `engine.version` (shown in About).

Everything else (rendering, networking, TLS, sandboxing, content
blocking, cookie partitioning) is inside the prebuilt GeckoView binary
and is inherited, not implemented.

## 6. Strengths

- **Tiny owned surface**: 1,191 lines of app Kotlin. Almost every hard
  problem (rendering, TLS, ETP, session serialisation) is Mozilla's
  maintained code, which is the only sustainable shape for a solo,
  zero-budget project (ADR-001's core argument).
- **Honesty is structural**: the shields UI reads real engine state
  (security info, exception store); no counters exist because AC exposes
  none to fabricate from; About states limits in-product.
- **Testable seams were created where bugs actually happened**: URL
  classification and home-URL matching are pure Kotlin and unit-tested —
  precisely the two rules that broke on device (v0.3.0/v0.4.0 history).
- **Process-safety and crash locality**: main-process guard; crash data
  never leaves the device unless the user shares it.
- **Reproducible offline build**: the vendored local-maven closure makes
  the build independent of network reachability quirks in this
  environment; versions are pinned everywhere (AC `159.0` in one `val`).

## 7. Structural limits of the prebuilt-AAR + AC 159 approach

1. **API ceiling**: the embedder can only do what AC/GeckoView expose.
   Concretely hit already: no per-request/per-tab tracker counts (privacy
   dashboard can show policy state, never real blocked counts); no
   cosmetic-filtering hook; no per-API fingerprinting controls (only
   whole-engine prefs); no certificate-chain viewer API surfaced to the
   app (only Secure/Insecure state).
2. **Once-per-process runtime**: no multi-runtime isolation. "Private
   Spaces"/workspaces can only be contextual identities (cookieStoreId)
   on one runtime — a weaker boundary than separate profiles/processes,
   and AC 159 exposes no ready-made spaces UI or management component.
3. **Experimental-API dependence**: the pref-setting seam
   (`BrowserPreferencesRuntime`) is `@ExperimentalAndroidComponentsApi`;
   Mozilla may change or remove it in any release, which would silently
   degrade the telemetry-off and RFP toggles unless re-verified per bump.
4. **Version lock-step and lag**: GeckoView/AC ship on Mozilla's ~4-week
   cadence; every bump is a re-vendor + rebuild + re-verify cycle done
   manually, and AC API breaks between majors are the embedder's problem
   (two were hit and fixed during v0.4.0 alone — TEST_RESULTS_040).
   Falling behind upstream is a *security* risk, not just staleness
   (ADR-001 records the Fennec ~2-month lag precedent).
5. **Key-gated Mozilla services stay off**: Safe Browsing and Mozilla
   location services use Mozilla's API keys; a renamed embedder's right
   to use them is unverified, so phishing/malware URL protection beyond
   engine defaults is absent by policy (ADR-001 open item).
6. **Size floor**: the GeckoView omni AAR sets an APK size floor near
   ~190 MB per ABI before app code; R8 cannot shrink native/engine
   payloads, so Sett will never be a small download on this stack.
7. **Single-activity programmatic UI**: every new surface (bookmarks,
   history, dashboards, vault, tools) would land in or beside a 734-line
   activity that builds views in code. It works at current scope; it is
   the first thing that must change before the master plan's multi-section
   UI (Part 18) is feasible — see TECHNICAL_DEBT.
8. **No data layer**: Part 19's entities require introducing real local
   storage (Room/SQLite or AC storage components) plus migrations from
   zero; nothing in the current architecture anticipates it beyond
   SharedPreferences.
9. **Doc/code drift risk**: `docs/architecture.md` describes a larger
   system than the code implements. Until the docs are reconciled, they
   are a liability for any future contributor (or auditor) who trusts
   them.
