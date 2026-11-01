# SECURITY AUDIT (INITIAL) — SETT Browser v0.4.0

Audit date: 2026-10-31 · Type: code-and-configuration review of the app
layer plus an inventory of inherited engine properties. **Not** a
penetration test: this environment has no emulator/device lab, and no
claim below rests on runtime exploitation. Severities are for the
project's own prioritisation (Critical/High/Medium/Low/Info).

## 1. What the engine provides (inherited, not app-implemented)

Sett's security floor is GeckoView 159's, consumed as Mozilla's prebuilt
AAR — the same engine family as Firefox for Android:

- **Renderer sandboxing & content processes**: GeckoView runs web content
  in isolated child processes (`:content`, `:gpu`); the app correctly
  refuses to initialise a second runtime there (`PrivateXApp` main-process
  guard). The app never weakens this — there is no code that disables the
  sandbox, relaxes process isolation, or bypasses TLS anywhere in the
  codebase (verified by full read of all 6 Kotlin files).
- **Site isolation / origin separation**: Gecko's process-per-site model
  and Total Cookie Protection (dynamic first-party isolation) are engine
  behaviours, on by default; private tabs get a separate cookie/storage
  context from normal tabs.
- **TLS validation & certificate errors**: engine-enforced; certificate
  errors are hard stops (the error-page path, `HomeInterceptor.onErrorRequest`),
  with no "proceed anyway" UI anywhere in the app.
- **Content blocking**: ETP categories (incl. cryptomining/fingerprinting
  lists) maintained by Mozilla inside the engine — the app's contribution
  is policy selection (Standard/Strict) and per-site exceptions.
- **Secure IPC**: GeckoView's Java↔native bridge is Mozilla's; the app
  adds no custom native code, no JNI, and **no WebView at all** — the
  entire class of WebView bridge vulnerabilities (addJavascriptInterface,
  file-access misconfiguration) is absent by construction.

## 2. What the app layer does (verified in code)

| Control | State | Evidence |
|---|---|---|
| HTTPS-Only Mode | On, no downgrade UI | `DefaultSettings.httpsOnlyMode = ENABLED`; manifest `usesCleartextTraffic=false` |
| ETP | Standard default, Strict available, per-site exception with explicit wording | `Components`, `BrowserActivity.showShields/showSettings` |
| Global Privacy Control | On | `DefaultSettings` |
| Query-parameter stripping | On | `DefaultSettings` |
| Remote debugging | Off | `remoteDebuggingEnabled = false` |
| Mozilla telemetry / Normandy / studies / ping-centre | Off via prefs; app never initialises Glean/Nimbus SDKs | `Components.applyGeckoPrefs`; grep of app sources |
| JavaScript | On (compatibility default; no per-site JS control) | `DefaultSettings.javascriptEnabled = true` |
| Data clearing | Full engine wipe available (Fire), incl. history/cookies/cache | `engine.clearData(BrowsingData.all())` |
| Crash data | Local file only; user-initiated share; read-and-delete on next launch | `PrivateXApp.CrashLog` |
| Downloads | Via AC fetch service; the app adds no "install APK" flow and no auto-open | `DownloadService`, `DownloadsFeature` wiring |
| App exposure | Single exported activity (launcher + http/https VIEW); download service `exported=false`; `allowBackup=false` | `AndroidManifest.xml` |
| Signing | Project keystore; v2+v3 verified per release; cert unchanged since v0.1.0 | TEST_RESULTS_040; README |

## 3. Gaps and risks

| # | Finding | Severity | Detail |
|---|---|---|---|
| S1 | **No update mechanism** | **High** | Updates are manual GitHub Release downloads; there is no in-app check, no auto-update, no F-Droid listing yet. Engine security fixes reach users only when (a) the developer ships a bump and (b) the user notices and installs it. For a browser, update latency is the dominant real-world risk (see ADR-001 lag precedent) |
| S2 | **No Safe Browsing / phishing-malware URL protection** | Medium | Deliberately off: Mozilla's service keys may not be reused by a renamed embedder (unverified terms — ADR-001 open item). Consequence is real: known-phishing/malware URLs get no warning beyond TLS/ETP behaviour. The About screen does not overclaim, but users may assume Firefox-equivalent protection |
| S3 | **No site-permission UI wired** (`feature-prompts` absent) | Medium | Camera/mic/location/notification requests and JS dialogs have no app-level consent UI. Whatever AC/Gecko does by default is what happens — **not verified on device**. A permission system the user cannot see or revoke is a security-UX gap even if the default is deny |
| S4 | **R8/minification off; no obfuscation/shrinking** | Low | Documented, deliberate (reflection-heavy engine). Security impact is minor for an open-source app (source is public anyway); the real cost is size/startup (TECHNICAL_DEBT) |
| S5 | **AAR-metadata gate disabled** | Low | `app/build.gradle.kts` disables AGP's check that library minCompileSdk ≤ compileSdk, because AC 159 declares 37 while Sett compiles at 36. Safe only as long as no API-37-only code paths execute on older platforms; it removes a guardrail and must be re-examined at every AC bump |
| S6 | **Vendored-artifact integrity rests on one HTTPS fetch** | Low | `vendor_mozilla.py` downloads the engine closure over HTTPS from maven.mozilla.org but records no checksum/signature verification. A compromised or corrupted vendored AAR would flow straight into releases; the from-the-file verification checks *our* APK's signature, not the inputs' provenance |
| S7 | **Release keystore lives in the repo folder** | Low (process) | `release/privatex-release.jks` sits inside the project tree. It **is** covered by `.gitignore` (`*.jks`, `key.properties`) and `git ls-files` confirms neither it nor `key.properties` is tracked locally. Residual risk: the GitHub push path uses an API script (`push_repo.py`) rather than this git checkout — whether it honoured the same exclusions was **not verified in this audit** and should be confirmed once, explicitly. Loss or leak of this key ends update continuity (ADR-001) |
| S8 | **No certificate viewer / TLS detail UI** | Low | Users get a 🔒/Secure–Insecure label only; they cannot inspect the certificate, issuer, or mixed-content state. Limits informed trust decisions on sensitive sites |
| S9 | **Private-tab persistence unverified** | Info (verify) | Whether AC `SessionStorage` writes private-tab state to disk in its snapshot was not established by this audit. Sett's privacy story assumes private tabs are session-only; this must be verified (library source or device test) before the claim is repeated |
| S10 | **Download safety is engine-default only** | Info | No dangerous-filetype warnings, no checksum verification UI, no post-download scanning in the app (the architecture doc's ".apk warning / SHA-256 verify" were never implemented in Sett). Android's own install prompts still apply to sideloaded APKs |
| S11 | **No vulnerability-disclosure / security contact published** | Info | `docs/roadmap.md` Phase 10 lists it as future work; the repo has no SECURITY.md (not verified on the GitHub side in this audit) |

## 4. Privacy posture (security-adjacent)

- **No telemetry by the app**: no analytics SDK is declared, no network
  call is made by app code except user-initiated browsing/downloads and
  the search engine the user picked. The start page is served from memory
  and states so.
- **No search suggestions**: typing in the omnibox sends nothing anywhere
  until Go is pressed (documented decision).
- **Search queries go to the chosen provider** (DDG HTML default, Brave,
  Mojeek, Startpage) over HTTPS — unavoidable for web search, disclosed
  by the provider choice UI.
- **Not anonymity**: the app says so in-product (About): the network
  provider still sees connection targets; there is no Tor/proxy support.
- **Data minimisation**: the app stores 3 settings keys, a session
  snapshot, and one crash file. There are no accounts, no identifiers,
  no advertising anything.

## 5. Threat-model summary (initial)

Primary assets: browsing history/session state on the device, downloaded
files, the release signing key. Primary threats, in order of realistic
impact: (1) stale engine between manual releases (S1); (2) malicious or
compromised websites — mitigated by the inherited Gecko sandbox/ETP/HTTPS-
Only stack, which the app never weakens; (3) supply-chain compromise of
vendored artifacts or the signing key (S6/S7); (4) on-device attackers
with unlocked-device access — largely out of scope: the app keeps no
credential store to steal, and Android's app sandbox + `allowBackup=false`
are the controls. The app adds **no** network attack surface of its own
(no listening services, no exported providers/receivers beyond the VIEW
intent filter, which only opens a tab with a URL string).
