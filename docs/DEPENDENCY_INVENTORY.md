# DEPENDENCY INVENTORY — SETT Browser

Audit date: 2026-10-31. Source of truth: `android/app/build.gradle.kts`
(direct dependencies), `android/build.gradle.kts` (build plugins),
`android/local-maven/` (the vendored closure actually resolved in this
environment), `android/vendor_mozilla.py` (how the closure is produced).

## 1. Direct runtime dependencies

All Mozilla components are pinned by a single `val ac = "159.0"` in
`app/build.gradle.kts`.

| Dependency | Version | Licence | Role in Sett |
|---|---|---|---|
| `org.mozilla.components:browser-engine-gecko` | 159.0 | MPL-2.0 | GeckoEngine — binds AC to GeckoView |
| `org.mozilla.geckoview:geckoview-omni` | 159.0.20261022150525 | MPL-2.0 (Gecko; some third-party code under other FOSS licences inside) | The browser engine itself (transitive via browser-engine-gecko; prebuilt AAR, ~the bulk of the APK) |
| `org.mozilla.components:browser-state` | 159.0 | MPL-2.0 | BrowserStore / BrowserState |
| `org.mozilla.components:browser-session-storage` | 159.0 | MPL-2.0 | Session persistence + restore |
| `org.mozilla.components:browser-errorpages` | 159.0 | MPL-2.0 | Error pages for failed loads |
| `org.mozilla.components:feature-session` | 159.0 | MPL-2.0 | SessionFeature, SessionUseCases, UndoMiddleware |
| `org.mozilla.components:feature-tabs` | 159.0 | MPL-2.0 | TabsUseCases |
| `org.mozilla.components:feature-findinpage` | 159.0 | MPL-2.0 | Find in page |
| `org.mozilla.components:feature-downloads` | 159.0 | MPL-2.0 | DownloadsFeature, AbstractFetchDownloadService |
| `org.mozilla.components:lib-fetch-httpurlconnection` | 159.0 | MPL-2.0 | Fetch client for downloads (HttpURLConnection) |
| `org.mozilla.components:support-ktx` | 159.0 | MPL-2.0 | Kotlin extensions (declared at Fenix's pattern) |
| `org.mozilla.components:support-utils` | 159.0 | MPL-2.0 | DownloadFileUtils etc. |
| `org.mozilla.components:concept-engine` | 159.0 | MPL-2.0 | Engine interfaces (DefaultSettings, RequestInterceptor, ErrorType) |
| `org.mozilla.components:concept-fetch` | 159.0 | MPL-2.0 | Fetch interfaces |
| `org.mozilla.components:support-base` | 159.0 | MPL-2.0 | NotificationsDelegate etc. |
| `org.mozilla.components:lib-state` | 159.0 | MPL-2.0 | Store/flow primitives |
| `androidx.appcompat:appcompat` | 1.7.1 | Apache-2.0 | AppCompatActivity |
| `androidx.core:core-ktx` | 1.19.1 | Apache-2.0 | WindowCompat/insets handling |
| `androidx.recyclerview:recyclerview` | 1.4.0 | Apache-2.0 | Tabs tray grid |
| `com.google.android.material:material` | 1.12.0 | Apache-2.0 | Theme (Material3), BottomSheetDialog, Snackbar |

Transitively resolved (present in the vendored closure, not declared):
Kotlin stdlib + kotlinx-coroutines (Apache-2.0, via AC), AC support
libraries (`support-base`, `support-images`, `support-locale`, `ui-icons`,
`ui-colors`, `browser-menu(2)`, `browser-toolbar`, `browser-tabstray`,
`concept-storage(-bookmarks)`, `lib-publicsuffixlist`, … — 43 AARs total),
`org.mozilla.appservices` megazord/nimbus/remotesettings (MPL-2.0) and
`org.mozilla.telemetry:glean` 70.0.0 (MPL-2.0). **Presence ≠ use**: the
app declares only the table above, never initialises Glean/Nimbus/Remote
Settings in its own code, and sets Gecko telemetry prefs off; the extra
AARs are AC-internal transitive requirements of the components Sett does
use. They still ship inside the APK and count toward its size.

## 2. Test-only dependencies

| Dependency | Version | Licence | Notes |
|---|---|---|---|
| `junit:junit` | 4.13.2 | EPL-1.0 | `testImplementation`; vendored into local-maven (jar + POM) |
| `org.hamcrest:hamcrest-core` | 1.3 | BSD-3-Clause-style (Hamcrest licence) | JUnit transitive; vendored likewise |

No instrumentation-test dependencies (no Espresso/AndroidX Test) exist —
there are no on-device tests.

## 3. Build tooling

| Tool | Version | Licence | Notes |
|---|---|---|---|
| Android Gradle Plugin | 9.1.0 | Apache-2.0 (Google) | Root plugin declaration |
| Kotlin (Android plugin) | 2.4.20 | Apache-2.0 | Version taken from the root declaration by AGP 9's built-in Kotlin |
| Gradle | 9.3.1 | Apache-2.0 | Invoked directly from the toolchain; **no `gradlew` wrapper in the repo** |
| JDK | 17 | — | Toolchain-local |
| Android SDK | platform 36 (+37 present), build-tools 37.0.0 | — | Toolchain-local, installed from dl.google.com zips |

## 4. The vendored local-maven approach

- **Why**: in this build environment, Java/Gradle HTTPS to
  maven.mozilla.org read-times out through the egress proxy while curl
  succeeds (documented in `vendor_mozilla.py` and the README).
- **How**: `vendor_mozilla.py` walks POM + Gradle module metadata from a
  seed list of the declared Mozilla artifacts and downloads every
  `org.mozilla.*` file (POM, `.module`, AAR) into `android/local-maven/`
  in Maven layout. `settings.gradle.kts` lists `local-maven` **first**,
  then google(), mavenCentral(), and maven.mozilla.org as a fallback.
  Non-Mozilla dependencies still resolve from google()/mavenCentral().
- **Properties**: fully offline-reproducible Mozilla resolution; the
  directory is gitignored and regenerable (274 MB on disk). Integrity of
  vendored artifacts rests on HTTPS to maven.mozilla.org at vendor time;
  no checksum/signature verification step is recorded in the script —
  noted in SECURITY_AUDIT_INITIAL as a low-severity supply-chain gap.

## 5. How updates flow

- **Engine/components**: Mozilla ships Firefox/GeckoView on a ~4-week
  major cadence (with chemspill point releases). For Sett, an update is a
  manual, multi-step operation: bump `val ac` (and confirm the GeckoView
  build stamp), re-run `vendor_mozilla.py`, fix any AC API breaks
  (v0.4.0's cycle hit two), re-run unit tests, rebuild, redo the
  from-the-file verification, publish a new GitHub Release. There is no
  dependabot, no CI, and no auto-update channel — and **no in-app update
  check**: users learn about releases out-of-band (ADR-001's planned
  signed-manifest update check was never implemented).
- **AndroidX/Material**: manual version bumps in `app/build.gradle.kts`.
- **Risk**: every week of lag behind a Gecko security release is a week
  the shipped browser carries known engine vulnerabilities; ADR-001
  records the family's worst observed lag (~2 months, Fennec F-Droid,
  autumn 2024) and the project's disclosure policy for that case.

## 6. Licence compatibility under GPL-3.0

Sett itself is GPL-3.0 (`LICENSE` at repo root).

| Combination | Verdict | Basis |
|---|---|---|
| MPL-2.0 (all Mozilla components + GeckoView) in a GPL-3.0 product | Compatible | MPL-2.0 §3.3 permits distribution of the combined work under GPL-family terms (MPL-2.0 is GPL-compatible by design unless the "Incompatible With Secondary Licenses" notice is invoked; Mozilla's components do not invoke it). Attribution/notices must be preserved; `packaging.resources.excludes` in the build drops only duplicate META-INF licence *files* from the APK resources, not the obligations |
| Apache-2.0 (AndroidX, Material, Kotlin, coroutines, Gradle/AGP) | Compatible | Apache-2.0 is one-way compatible with GPL-3.0 |
| EPL-1.0 (JUnit 4) / Hamcrest | Test-scope only | Never shipped in the APK; no distribution question arises |
| EasyList / EasyPrivacy | **Not currently bundled in Sett** | The README's licence paragraph mentions them (elected under their GPL-3.0 option) — that text describes the sibling suite's filter engine, not Sett, which uses Gecko's built-in ETP lists inside the engine AAR. Flagged as doc drift in PROJECT_AUDIT |
| DuckDuckGo Tracker Data Set (CC BY-NC-SA 4.0) | **BANNED — not used** | Non-commercial + share-alike terms conflict with GPL-3.0 distribution; recorded in ADR-001/research-summary and honoured (no TDS artefact anywhere in the repo or local-maven) |
| Firefox/Mozilla trademarks | Not used | Product ships under its own name (Sett), mascot, and signing key — the distribution model Mozilla intends for embedders (ADR-001) |

Open licence-adjacent item (not a licence conflict): whether a renamed
embedder may use Mozilla-key services (Safe Browsing, location) is a
*terms/API-key* question, still unverified from a Mozilla source; those
features remain off (ADR-001).
