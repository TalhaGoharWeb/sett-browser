# IMPLEMENTATION ROADMAP — SETT Browser (post-audit)

Written 2026-10-31 from the codebase audit (PROJECT_AUDIT,
EXISTING_FEATURE_INVENTORY, ARCHITECTURE_ANALYSIS, DEPENDENCY_INVENTORY,
SECURITY_AUDIT_INITIAL, TECHNICAL_DEBT). Phase order follows the product
master plan Part 22. This roadmap supersedes the phase numbering in
`docs/roadmap.md` (the Phase-2 design roadmap).

## Binding constraints (apply to every phase)

1. **Zero spend** — no paid hosting, APIs, stores, or certificates.
   Anything that fundamentally needs a server is flagged **(c) blocked**
   or re-scoped to client-side / user-supplied-provider form.
2. **One developer** — phases are sized as sequential solo work; anything
   requiring an engine fork team is out of scope by definition (ADR-001).
3. **GPL-3.0, clean-room** — reference projects may be *studied*;
   code reuse only where licence-compatible (MPL-2.0/Apache-2.0/BSD/GPL).
   DuckDuckGo TDS (CC BY-NC-SA) is banned, permanently.
4. **AC/GeckoView API limits are real** — no per-request tracker counts
   exist (never fabricate stats), no cosmetic-filtering hook, no
   Chromium-style devtools, WebExtension support is GeckoView's
   (controller-based, no AMO store integration for embedders, no
   chrome.* APIs), one GeckoRuntime per process.
5. **The phased delivery rule stands** (learn → plan → execute → test →
   one verified APK per release) — per-phase gates below assume it.

Feasibility flags: **(a)** buildable on this stack · **(b)** buildable
only in reduced form · **(c)** blocked by constraints.

---

## Phase 1 — Foundation & stabilization

**Scope.** Fix the audit's defects before adding features:
- Wire `feature-prompts` (S3): JS dialogs, site-permission consent UI,
  fullscreen handling — the largest functional hole in the current app.
- Verify the two open audit questions on device: private-tab disk
  persistence in SessionStorage (S9); actual prompt/permission default
  behaviour pre-fix.
- Reconcile documentation: rewrite `docs/architecture.md` to describe
  the shipped system; fix README licence/build-notes drift (D6).
- Script the release verification ritual (badging/apksigner/zipalign/
  DEX checks) into one command; add the missing handover note (D9).
- Confirm the GitHub push path honoured `.gitignore` signing exclusions
  (S7); add SECURITY.md (S11).
- R8 experiment on a branch: measure size/startup delta with
  device-verified keep rules; adopt only if the on-device checklist
  passes (D1).

**Dependencies:** none. **Feasibility:** (a) throughout — all are
existing-component wiring, docs, and process work.

## Phase 2 — Core browser completion — ✅ SHIPPED as v0.5.0 (2026-10-31)

**As built:** PlacesHistoryStorage/PlacesBookmarksStorage wired
(HistoryDelegate records visits; private tabs excluded by the engine);
LibraryActivity (Bookmarks flatten w/ folder headers, History w/
search + per-item delete + clear-all, Downloads w/ open-completed via
new FileProvider, Closed tabs restore via engineStateStorage);
bookmark toggle + tab duplication + reader mode (feature-readerview)
+ context menus (feature-contextmenu defaultCandidates + open-in-app)
+ tab-tray search. AC 159 truths recorded: DownloadsUseCases has no
pause/resume (the download notification owns them — downloads tab says
so), BookmarkRoot lives in org.mozilla.appservices.places,
RecentlyClosedMiddleware takes Lazy<Storage>, glean-native capability
conflict resolved for geckoview-omni in app/build.gradle.kts.
Downloads "completed list persistence" from the original scope is
session-store-backed only — the store's download state does not
survive process death in AC 159 without extra work; noted as debt.

**Scope.** Bookmarks + folders; history list + search + per-item delete;
downloads manager UI (pause/resume/open, completed list persistence);
reader mode; context menus; recently-closed list (persistent, beyond the
undo window); tab duplication; find-in-tray tab search.
**How:** declare the AC components that already exist for this —
bookmarks/history storage components, `feature-readerview`,
`feature-contextmenu`, `feature-prompts` (Phase 1) — plus the app's
first real data layer (Room/SQLite or AC storage) with migrations and a
data-integrity test (master plan Part 19 starts here).
**Dependencies:** Phase 1 (data-layer decision gates bookmarks/history).
**Feasibility:** (a) bookmarks/history/reader/context menus/recently
closed — standard AC embedder work, Fenix is the reference. (b) downloads
manager — AC store/service states exist; segmented download, scheduling
and checksum-verify UI are custom work on top (checksum verify is easy
and honest; segmented multi-connection download is a bigger build —
defer). Tab groups / pinned tabs: (b) — state is just tab metadata in
the store, UI is custom; do them here only if time remains after the
core list. Vertical tabs, split-screen, workspaces-as-spaces: (b) at
best (contextual identities give cookie isolation only — one runtime);
defer to Phase 4 evaluation. Multiple windows: (c) in any meaningful
sense on this single-activity, single-runtime design.

## Phase 3 — Privacy & security engines + dashboards — ✅ SHIPPED as v0.6.0 (2026-10-31)

**As built:** ShieldActivity (menu → Sett Shield, also linked from the
per-tab shields dialog): this-site card (host, connection security,
ETP exception toggle, and the **per-page blocked-tracker list exactly
as the engine reports it** — a correction to this roadmap's earlier
framing: AC's TrackingProtectionState DOES carry per-tab
blockedTrackers from Gecko; what does not exist is a trustworthy
cumulative counter, which remains (c)/never). Global protection state
rows; full exceptions list with per-item removal (fetchAll/remove);
fingerprinting Custom mode (resistFingerprinting, letterboxing,
WebGL, WebRTC as individual documented toggles; strict profile still
wins when on; applied at engine creation → next start, stated in UI);
clear-on-exit with a two-layer guarantee (onDestroy attempt +
session_dirty startup sweep in PrivateXApp before store restore).
Fire unified into Components.clearAllData — which also fixed a real
gap: the old Fire cleared engine data only and left Library history
behind; it now deletes places history, sessions and closed tabs too.

**Scope.** Privacy dashboard v1: per-site + global display of what is
*actually knowable* — ETP policy state, per-site exceptions list, counts
of sites with exceptions, data stored per site where the engine exposes
it, Fire controls. Security view: connection security state per tab with
an honest explanation surface. Fingerprinting "Custom" mode: expose the
individual Gecko prefs that exist (RFP, letterboxing, WebGL-related
prefs) as documented toggles. Scheduled/on-exit data clearing options.
**Dependencies:** Phase 2 data layer (for any persisted stats that are
real, e.g. exception lists).
**Feasibility:** (b) — dashboards are **reduced by platform truth**,
permanently: AC exposes no per-request blocked counts, so "trackers
blocked: N" can never be shown honestly; the dashboard must be a
*state and configuration* dashboard, not a counters dashboard. Any
design that needs counters is (c) — do not fabricate. Custom
fingerprinting mode: (b) pref-level toggles only; per-API standardisation
(canvas/WebGL/audio spoofing) is engine territory — (c) without forking
Gecko. Content filtering beyond ETP: custom filter lists/cosmetic
filtering are (c) at the embedder layer; the supported route to
uBlock-class filtering is Phase 4's extension support.

## Phase 4 — Advanced browser systems

**Status: COMPLETE — vault v0.7.0 (2026-10-31); extensions +
network center + spaces v0.8.0 (2026-11-01).** As built for the
remainder: **ExtensionsActivity** on WebExtensionSupport +
GeckoEngine's WebExtensionRuntime — install from direct .xpi URL
(InstallationMethod.MANAGER) or picked file (FROM_FILE), list with
metadata, enable/disable (EnableSource.USER), uninstall, and a
permissions dialog fed by the real Metadata (required permissions /
origins / granted optionals). Extension tab requests route through
Sett's tabsUseCases; permission-requesting updates are refused by
policy. No storefront, no popup-action support (feature-webextensions
not integrated) — stated on screen. **NetworkActivity** writes real
Gecko prefs via BrowserPreferencesRuntime: network.trr.mode/uri for
Cloudflare/Mullvad/Quad9/custom DoH (strict = mode 3), proxy type +
http/ssl/socks prefs for manual profiles, Orbot preset
(127.0.0.1:9050 SOCKS5 + remote DNS) labelled non-anonymity, and
external leak-test links. **Spaces** shipped as tab GROUPS (Spaces.kt
+ store observer claiming untagged tabs for the current space;
per-space tab tray; delete moves tabs) — explicitly NOT the
cookie/storage partitions originally imagined: one engine profile
makes true partitions (c) without a second process/runtime, and the
UI says so rather than claiming separation that does not exist.

**Vault as built (v0.7.0):** `vault/VaultCore.kt` — a faithful Kotlin
port of the suite's VaultX design: random VDK encrypts each record
(XChaCha20-Poly1305, per-record nonce, record-id AD); Argon2id
(ops 3, 256 MiB) KEK wraps the VDK; recovery-key slot; verifier record
enables the biometric path (Keystore AES key, auth-per-use, wraps the
VDK; invalidated by new biometric enrolment). Crypto runs on bundled
libsodium via lazysodium-android (+ JNA AAR for libjnidispatch).
**Verified by real JVM tests** (lazysodium-java + system libsodium):
create/unlock/wrong-password/CRUD/recovery/password-change/tamper
rejection + a no-plaintext-in-file assertion — 3/3, alongside the
existing 7/7 logic tests. UI: create with one-time recovery key,
unlock (password / recovery / fingerprint), search, entry detail with
reveal+copy, generator, change master password, 3-minute auto-lock.
Fill is manual copy/paste (stated in the UI); Android AutofillService
integration remains (b) future work, passkeys remain (b/c).

**Scope & feasibility, item by item:**
- **Password vault (VaultX port)** — (a). Port the sibling suite's
  libsodium vault design (Argon2id + XChaCha20-Poly1305) to Kotlin/
  libsodium on Android; encrypted local store, generator, manual
  copy/paste fill first. **Autofill integration is (b)**: Android
  AutofillService + Gecko form interaction is the hard part; passkeys
  are (b/c) — GeckoView passkey/WebAuthn support on Android depends on
  engine + Play-services-free credential plumbing that a sideloaded
  GPL app cannot assume. Biometric unlock: (a) via AndroidX Biometric.
- **Extensions** — (b). GeckoView `WebExtensionController` can install
  and manage persistent WebExtensions (uBlock Origin is the canonical
  case, ADR-001). Reduced form means: install from file/URL, list,
  enable/disable, remove, honest permission display. There is no AMO
  storefront integration, no extension auto-update channel guarantee,
  and no chrome.* compatibility — market it as exactly that.
- **Network Protection center** — mixed. DoH provider choice: (a)
  (Gecko TRR modes/providers are pref/config-level). Proxy profiles:
  (b) — Gecko prefs expose proxy config; per-site rules and a polished
  profile UI are custom and fragile across engine bumps. Tor: (b) —
  Orbot integration (route via Orbot's SOCKS, user installs Orbot) is
  the only honest zero-spend route; bundling a Tor daemon is (c) for a
  solo maintainer (update/safety burden) and proxying must never be
  labelled anonymity. Leak tests (DNS/WebRTC/IP): (a) as *in-app links
  to test pages plus a WebRTC-state display*; a self-hosted leak-test
  server is (c) zero-spend. Certificate viewer: (b) — AC exposes only
  coarse security state to the embedder; a full chain viewer may not be
  obtainable from the public API (verify against AC 159 sources during
  planning; if absent, ship the honest reduced form).
- **Private Spaces/workspaces** — (b), same ceiling as Phase 2:
  contextual-identity partitions with per-space data wipe, labelled as
  cookie/storage separation, not process isolation.

## Phase 5 — Productivity utilities — ✅ SHIPPED as v0.9.0 (2026-11-01)

**As built:** UtilitiesActivity hub with seven tools, the pure logic
ported from the suite's tested engines and covered by 10 new JVM
tests (20 total): **MetaClean** (full byte-level port — JPEG/PNG/WebP
inspect incl. TIFF/EXIF/GPS parsing, strip with self-verification;
fixtures built byte-by-byte in tests incl. a GPS-bearing EXIF block),
**password tools** (strength estimator port with the suite's exact
bands/penalties; generator; HIBP breach check using the suite's
k-anonymity design — only the 5-char SHA-1 prefix leaves the device,
only on an explicit tap), **hashes** (text + streamed file, all four
algorithms in one pass), **QR** (ZXing generate + decode-from-image,
round-trip tested), **text tools** (Base64/URL/JSON/stats),
**converter + expression calculator** (recursive-descent parser,
tested), **PDF tools** (PdfBox-Android 2.0.27.0, Apache-2.0: merge,
page-range extract, rotate, text extract; scanned-PDF/OCR limit
stated in UI). Camera QR scanning is not included (read-from-image
is); OCR remains (b/c) as flagged.

**Scope.** A Utilities section built from local, offline tools: password
generator + strength estimator, file hash calculator (SHA-256/512), EXIF/
metadata viewer+remover, QR generate/read, URL/Base64/JSON tools,
counters/converters/calculators, PDF merge/split/rotate/extract where a
licence-clean Android library exists.
**Dependencies:** none hard; benefits from Phase 2's sectioned UI.
**Feasibility:** (a) for the text/hash/QR/converter class — small,
pure, testable, and several already exist in the sibling suite as
*designs and test vectors* (MetaClean, password tools, utilities) to
port from Dart logic to Kotlin. (b) PDF manipulation — depends on
finding a maintained, GPL-compatible Android PDF library; OCR is (b/c)
(on-device OCR models are large and licence-sensitive — flag, don't
promise). All processing stays on-device (zero spend is naturally
satisfied).

## Phase 6 — Developer Studio (within engine limits) — ✅ SHIPPED as v0.10.0 (2026-11-01)

**As built:** DeveloperActivity hub (menu → Developer Studio):
view-source for the current page; HTTP API tester (method/headers/
body, redirects shown not followed, direct-fetch limits labelled);
security-header analyzer (presence + shape notes for HSTS, CSP,
nosniff, Referrer-Policy, Permissions-Policy, X-Frame-Options, COOP,
plus Server/X-Powered-By disclosure — facts, no invented score);
JWT decoder (decode-only, labelled never-verified, alg=none and
missing-exp warnings); regex tester; URL parser; robots.txt and
sitemap.xml viewers. **Remote debugging toggle** wired to
DefaultSettings.remoteDebuggingEnabled from a SettingsStore pref
(default OFF, applies next start) — GeckoView's real devtools path
via desktop Firefox about:debugging. 9 new JVM tests (29 total).

**Scope.** Honest subset only: JSON/JWT/regex/URL tooling (shared with
Phase 5), HTTP API tester (uses the fetch client), security-header
analyzer and robots.txt/sitemap viewers (fetch + parse, client-side),
page-source view (`view-source:` already passes through the resolver),
and **remote debugging via desktop Firefox devtools** — GeckoView's
supported debugging path — documented and toggled explicitly (it is
deliberately OFF today).
**Feasibility:** (b) overall. DOM/CSS/Network inspectors, console,
profilers, Lighthouse: (c) — no embeddable devtools frontend exists for
GeckoView; recreating one is not a solo project. The master plan's
"Developer Studio" ships as *web-developer utilities + remote devtools
hookup*, labelled as such.

## Phase 7 — SETT Search (integration, not infrastructure) — ✅ SHIPPED as v0.11.0 (2026-11-01)

**As built:** SearchActivity (menu → SETT Search) with six category
tabs and a persisted per-category provider choice; routing is a pure,
unit-tested table (`tools/SearchRouting.kt`, 5 tests; 34 total).
Web honours the Settings engine, which gained a **SearXNG option**:
the user supplies their own instance URL (stored locally; blank
falls back to DuckDuckGo — Sett hosts nothing, zero-spend intact).
Every provider carries a plain-language data-flow note, all of them
listed on the search screen itself; suggestions stay off (nothing is
sent while typing — the query leaves only on Search, only to the
chosen provider). Academic routes to OpenAlex / Semantic Scholar /
Scholar Archive ahead of the Phase 8 desk.

**Scope.** A search *experience* layer in the browser: category tabs
(web/images/news/videos/maps/academic) implemented as transparent
per-category provider routing; search preferences stored locally;
documented data-flow page stating exactly what each upstream receives.
Academic/books categories route to OpenAlex/Crossref/DOAJ-style sources
(shared groundwork with Phase 8).
**Feasibility:** (b). Client-side metasearch *aggregation* (fetching and
merging several providers' result pages serverlessly) is fragile and
ToS-sensitive; the honest zero-spend form is provider routing + a local
results UI where providers offer keyless JSON/HTML endpoints.
**SearXNG specifically: (c) as a hosted service** — it needs a server;
zero spend forbids running one for users. (a) only in the form "user
points Sett at their own SearXNG instance URL" (self-hosted provider
option). Search suggestions: remain off by default (established policy);
an opt-in, provider-labelled suggestions toggle is (b) and must carry
the privacy warning in-UI. Independent crawling/indexing is Phase 11,
not this phase.

## Phase 8 — Scholar Research Desk — ✅ SHIPPED as v0.12.0 (2026-11-01)

**As built:** ResearchActivity (menu → Research Desk) with live
search against OpenAlex, Crossref, DataCite and DOAJ (all keyless;
parsers unit-tested against sample payloads), an OA-only toggle
(OpenAlex filters server-side; other sources are filtered to results
their API explicitly marks open — no badge is ever guessed), DOI
lookup (OpenAlex by-DOI with Crossref fallback), per-result Cite
(BibTeX + RIS) and Save into **collections** (local JSON store:
DOI de-duplication, per-work notes, whole-collection .bib/.ris
export), DOI extraction from PDFs via the Phase 5 text extractor,
and Arabic/Urdu local-file search with diacritic-insensitive,
variant-unifying normalisation (line-level matching, stated in UI).
Unpaywall was not integrated: it requires an email parameter and
Sett sends none; OpenAlex carries the OA signal instead. 42 tests.

**Scope.** Academic search connectors (OpenAlex, Crossref, DOAJ,
Unpaywall, DataCite — all keyless/open APIs); DOI lookup; open-access
filtering; research collections + notes on the Phase 2 data layer;
BibTeX/RIS export; citation extraction from PDFs the user opens;
Arabic/Urdu: RTL-correct rendering, Arabic normalisation and
diacritic-insensitive search over *local* corpora the user imports.
**Feasibility:** (a) for API connectors + collections + exports — this
is client-side HTTP + local storage, and it aligns with the developer's
existing scholarly corpus work (Maliki-fiqh bilingual databases live
outside the browser today). (b) citation-graph and literature-review
tooling (visualisation effort on a phone UI), PDF annotation depth,
quotation verification (exact/approximate/unverified matching over local
texts is (a) as string science; doing it over the open web is (c)).
Arabic/Urdu **OCR: (c)** for this project today — no licence-clean,
offline, phone-viable Nastaliq OCR exists to integrate; do not promise
it. Root-word search: (b) — needs curated morphology data the project
does not yet own.

## Phase 9 — Study Center

**Scope.** Flashcards + spaced repetition (SM-2-class algorithm),
notes/highlights tied to pages and PDFs, vocabulary notebook, study
timer, reading tracker, offline library — all local-first on the Phase 2
data layer; quiz generation from user content (rule-based extraction,
not AI).
**Dependencies:** Phase 2 data layer; Phase 8 collections overlap.
**Feasibility:** (a) — the most constraint-friendly phase in the plan:
entirely local, testable, no network, no licences beyond the project's
own. Lecture transcription: (c) (needs speech models/services; revisit
only if an offline, licence-clean on-device ASR becomes practical).

## Phase 10 — AI Workspace

**Scope.** Optional, off-by-default, provider-configured assistance:
summarise/explain/translate the current page or selection, multi-tab
comparison, research outlines, note generation. Hard rules from the
master plan are adopted verbatim: never access passwords/cookies/private
tabs; no page content leaves the device without explicit per-use
consent; provider keys are the user's own, stored in the vault
(Phase 4), never shipped or logged.
**Feasibility:** (b). External-provider form is buildable (bring-your-
own-key, e.g. free-tier endpoints the user configures) — but note that
"free tier" is not a guarantee and the feature must degrade honestly
when unconfigured. Local on-device models: (c) for now — model size,
licence variance, and phone-class inference performance put a useful
local LLM out of solo reach; small on-device ML (language detection,
extractive summarisation) is (b) and can seed the feature set.

## Phase 11 — Independent search infrastructure

**Scope (aspirational).** Crawler + indexer + ranking as a separate
subsystem (Nutch/OpenSearch/Tantivy/Meilisearch class tooling).
**Feasibility: (c) blocked under current constraints — recorded, not
scheduled.** A public search index is a *hosting* problem before it is a
code problem: continuous crawling, storage, and serving need servers
that zero spend forbids, and a solo maintainer cannot operate one
responsibly. The constraint-compatible residue that *can* be built:
a **personal/local index** of the user's own library, bookmarks, history
and research collections (on-device full-text search — Tantivy-class
ideas, SQLite FTS implementation), which is genuinely useful and feeds
Phases 8/9. Revisit the public-engine question only if the constraint
set itself changes (funding/hosting), with a fresh ADR.

## Phase 12 — Final security & release hardening

**Scope.** Independent-style review pass over everything shipped:
repeat of this audit against the then-current code; PrivacyTests-style
category checks recorded per release (results published, failures
included); badssl-class TLS checks; dependency/licence re-inventory;
signed-release pipeline hardening (scripted verification gate from
Phase 1, key-backup verification); F-Droid submission (zero-cost
distribution + reproducible-build story); vulnerability-disclosure page
live; update-notification mechanism *within* zero spend (e.g. the app
checking the project's own GitHub Releases feed — the ADR-001
signed-manifest design, implemented at last, since S1 is the top
security finding).
**Dependencies:** all prior phases; the update check itself should
actually be pulled forward into Phase 1–2 if release cadence picks up.
**Feasibility:** (a)/(b) — F-Droid inclusion criteria (reproducible
builds, no proprietary deps) are satisfiable on this stack but the
vendored-maven step needs an F-Droid-acceptable form (build from
upstream repos in their builder); treat as (b) until attempted.

---

## Summary matrix

| Master-plan area | Flag | Phase |
|---|---|---|
| Prompts/permissions/fullscreen wiring | (a) | 1 |
| Bookmarks, history, reader, context menus, recently closed | (a) | 2 |
| Downloads manager (full) | (b) | 2 |
| Tab groups / pinned / workspaces | (b) | 2/4 |
| Multiple windows | (c) | — |
| Privacy dashboard | (b) — state, never counters | 3 |
| Real blocked-count stats | (c) — would require fabrication or an engine fork | — |
| Custom filter lists / cosmetic filtering (embedder-level) | (c) | — (extensions route: Phase 4) |
| Security dashboard / cert viewer | (b) | 3/4 |
| Password vault | (a) core · (b) autofill · (b/c) passkeys | 4 |
| Extensions | (b) | 4 |
| DoH choice / proxy / Orbot / leak tests | (a)/(b)/(b)/(a-lite) | 4 |
| Utilities suite | (a) · PDF (b) · OCR (b/c) | 5 |
| Developer Studio | (b) subset · (c) full devtools | 6 |
| Search categories via provider routing | (b) | 7 |
| Hosted SearXNG / own metasearch backend | (c) | — |
| Research Desk (open APIs, collections, exports, Arabic/Urdu local search) | (a)/(b) | 8 |
| Arabic/Urdu OCR | (c) | — |
| Study Center | (a) · transcription (c) | 9 |
| AI Workspace | (b) BYO-provider · (c) local LLM | 10 |
| Independent public search engine | (c) — hosting-blocked; local personal index instead | 11 |
| Sync with E2E encryption (server-based) | (c) zero-spend; encrypted backup export/import is (a) and belongs in Phase 2/4 scope | 2/4 |
| Final hardening + F-Droid + update check | (a)/(b) | 12 (update check: pull forward) |

**Sequencing note.** Nothing in Phases 5–11 should start while Phase 1's
prompt/permission hole and Phase 2's data layer are open: the first is a
live functional defect on real websites, and the second is the substrate
half the later phases store their data in.
