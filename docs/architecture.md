# SETT — As-built architecture

Rewritten 2026-10-31 (Phase 1 doc reconciliation). This document
describes the system **as shipped in v0.4.0 + Phase 1**, not the
original Phase-2 design. For what is planned next see
`IMPLEMENTATION_ROADMAP.md`; for the evidence base see
`PROJECT_AUDIT.md` and `EXISTING_FEATURE_INVENTORY.md`.

## Shape

A thin embedder around Mozilla's prebuilt engine: **one activity,
~1,200 lines of Kotlin in 6 files** (`android/app/src/main/java/
app/privatex/browser/`), no fragments of our own, no WebView anywhere.

- **Engine:** GeckoView 159 via Android Components 159 AARs, vendored
  into `android/local-maven/` by `android/vendor_mozilla.py` (Java
  cannot reach maven.mozilla.org through the build sandbox; curl can).
- **State:** a single `BrowserStore` (browser-state). Middleware:
  `UndoMiddleware` (tab-close undo), `PromptMiddleware` (prompt state
  across tab changes), AC `EngineMiddleware`.
- **Persistence:** AC `SessionStorage` restores tabs across restarts
  (tab URLs restore verbatim — internal URLs, once shipped, are served
  forever; see `HomeUrls.kt`). App preferences live in one
  SharedPreferences file (`SettingsStore`): search engine, ETP level,
  per-site ETP exceptions, optional resistFingerprinting.

## Components wired in BrowserActivity

- `SessionFeature` + `EngineView` — rendering and back/forward.
- `PromptFeature` (feature-prompts) — JS dialogs, beforeunload, file
  uploads, camera/mic/location consent. Runtime permissions are
  requested only when a site asks. Autofill delegates disabled (no
  vault yet).
- `DownloadsFeature` + `FetchDownloadManager` + `DownloadService`.
- `FindInPageFeature` with the AC find bar.
- Custom chrome: floating bar (pill address display, shields, tabs,
  menu), card-grid tabs tray, bottom-sheet menu — all plain Views.

## Request interception (HomeInterceptor)

- Serves the local start page for `sett://home` **and** the legacy
  `privatex://home` (pre-rename restored tabs). Zero network.
- `onErrorRequest` returns AC `browser-errorpages` pages for failed
  loads; the archive.org fallback is disabled (a failed URL is never
  sent to a third party).

## Privacy configuration actually set (Components.kt)

ETP (user-selectable Standard/Strict) with fingerprinting protection,
cryptominer/fingerprinting/tracking blocklists, query-parameter
stripping; HTTPS-Only; Global Privacy Control; total cookie
protection (dFPI) by the engine default; DoH at GeckoView default;
optional `privacy.resistFingerprinting` + letterboxing (Settings);
telemetry/Normandy/ping-centre/data-reporting prefs off; remote
debugging off. No Safe Browsing (Mozilla key terms unverified —
deliberate; see SECURITY_AUDIT_INITIAL.md S-findings).

## Hard platform limits (accepted, not worked around)

One `GeckoRuntime` per process (no true multi-profile "spaces";
private tabs + contextual separation only). AC exposes **no**
per-request tracker/ad counts — SETT shows policy state, never
invented numbers. WebExtensions are GeckoView-controller-based only
(no add-on store for embedders). Updates are manual: signed APKs on
GitHub Releases, same key since v0.1.0 (cert SHA-256 0a60cd9f…24f2).

## Identity

App label **Sett**; mascot Brock the badger; `applicationId
app.privatex.browser` (unchanged across the rename so every release
installs as an update); GPL-3.0.
