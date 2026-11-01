# Sett redesign — test report (Phase 10, final)

Date: 2026-11-01 · Codebase: v0.23.0 (versionCode 29) · Method:
JVM unit suites executed in the build VM + from-file release
verification (`verify_release.sh`) + served-bytes hash comparison
for every release. **There is no emulator or physical test device
in the build environment** — everything labelled
"Implemented — not fully tested" below is code-complete and
covered by the checks that exist here, with the device pass
explicitly left to the user's acceptance. Nothing in this report
claims a device result that was not observed.

## Executed regression — full unit suite (clean run, 2026-11-01)

| Test class | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|
| AiToolsTest | 4 | 0 | 0 | 0 |
| ContrastTest (Phase 9 WCAG guard) | 1 | 0 | 0 | 0 |
| DevToolsTest | 9 | 0 | 0 | 0 |
| HomePhase3Test | 4 | 0 | 0 | 0 |
| LocalIndexTest | 3 | 0 | 0 | 0 |
| ReadingListTest | 1 | 0 | 0 | 0 |
| ScholarSearchTest | 1 | 0 | 0 | 0 |
| ScholarToolsTest | 8 | 0 | 0 | 0 |
| SearchPhase7Test | 7 | 0 | 0 | 0 |
| SearchRoutingTest | 5 | 0 | 0 | 0 |
| SettLogicTest | 7 | 0 | 0 | 0 |
| SettThemeTest | 2 | 0 | 0 | 0 |
| StudyToolsTest | 5 | 0 | 0 | 0 |
| UpdateCheckerTest | 2 | 0 | 0 | 0 |
| UtilityToolsTest | 10 | 0 | 0 | 0 |
| VaultCoreTest (real libsodium crypto) | 3 | 0 | 0 | 0 |
| **Total** | **72** | **0** | **0** | **0** |

Release verification on the final build: 51/51 checks (package,
label, version, SDK levels, apksigner v2+v3, project cert,
zipalign, zip integrity, 41 DEX presence checks). Every redesign
release v0.16.0 → v0.23.0 additionally passed a served-bytes
SHA-256 comparison after upload to GitHub Releases.

## Per-feature status (brief §16 labels)

### Design system & themes
- Design tokens + shared components (`SettTheme`/`SettUi`) — **Implemented and tested** (SettThemeTest, ContrastTest).
- System / Dark / Light theme modes — **Implemented — not fully tested** (selection logic unit-tested; visual pass on device pending).
- All 13 activities on the design system — **Implemented and tested** at code level (grep proof: no local colour constants remain); visual pass pending.
- Density (Comfortable/Compact) — **Implemented — not fully tested** (spacing wired through SettUi; device check listed in v0.22.0 notes).

### Home page
- Search field resolves addresses (HOME-1) — **Implemented and tested** (AddressResolver suites).
- User shortcuts (add/remove/reorder, https-only) — **Implemented and tested** (HomePhase3Test).
- Live protection card (real settings values only) — **Implemented — not fully tested** (values traced to hard-coded/live settings in code; on-page render is device-side).
- Dashboard sections from real Library data, user-toggleable — **Implemented — not fully tested** (builder gating unit-tested; snapshot data path is device-side).
- Category chips (first 8 of 11; all in SETT Search) — **Implemented and tested**.

### Chrome & tabs
- Grouped menu (5 groups), Share, Navigation group — **Implemented — not fully tested** (device visual pass pending).
- Shield state icon + description (HTTPS/HTTP/local) — **Implemented — not fully tested**.
- Address-bar suggestions — **Not supported** (deliberate: suggestions live in SETT Search as an opt-in; the omnibox sends nothing while typing. CHR-5 closed as a documented decision, not an omission).
- 48dp targets across chrome/menu — **Implemented and tested** at code level.

### SETT Search
- Native results: DuckDuckGo HTML (Web/PDFs/Forums) — **Implemented — not fully tested** (parser unit-tested on fixtures; live fetch is device-side).
- Native results: user SearXNG JSON (all mapped categories, paging) — **Implemented — not fully tested** (URL builder + parser unit-tested; needs the user's instance to exercise live).
- Native scholarly: OpenAlex + Semantic Scholar — **Implemented — not fully tested** (parsers unit-tested incl. fixtures; live fetch device-side).
- Categories: 11 with real destinations — **Implemented and tested** (route assertions).
- SafeSearch + language/region as real provider params — **Implemented and tested** (parameter mapping asserted).
- Suggestions opt-in (default OFF, disclosed) — **Implemented — not fully tested**.
- Recent searches (on-device, clear, disable) — **Implemented and tested** (store suite).
- Providers without a usable API (Brave, Mojeek, Startpage, Scholar Archive, maps, books…) — open their own results pages, labelled as such in-app. **Implemented and tested** (routing).

### Carried-over ecosystem features (unchanged behaviour, re-verified by the suite)
- Vault (libsodium Argon2id/XChaCha20) — **Implemented and tested** (VaultCoreTest, real crypto).
- Sett Shield per-page tracker list — **Implemented — not fully tested** (engine behaviour is device-side; unchanged by the redesign).
- Utilities, Developer Studio, Study Center, Research Desk, AI Workspace (BYOK), Library Search, update checker — **Implemented and tested** at logic level (respective suites above); screens restyled only.

### Accessibility (Phase 9)
- Contrast ≥ 4.5:1 both palettes — **Implemented and tested** (ContrastTest; LIGHT faint defect found at 2.84:1 and fixed).
- TalkBack descriptions on chrome + search cards — **Implemented — not fully tested** (no screen reader in the build environment).
- RTL — **Partial**: symmetric native layouts + `dir="auto"` web input; no RTL-locale audit performed.
- Full audit: `docs/ACCESSIBILITY.md`.

### Not supported / deliberately out (unchanged from program start)
- Hosted search index or hosted SearXNG (zero spend — no servers exist).
- Voice search (no speech API integrated).
- Unpaywall (its API requires an email parameter; not integrated).
- Arabic OCR, on-device LLM, DOM inspector (desktop-Firefox remote debugging instead) — ecosystem-program decisions, unchanged.

## Standing limitations
- **Phone acceptance is pending for every Sett version v0.4.0 →
  v0.23.0.** The per-release emails list the highest-value checks.
- **S9 (ecosystem security review): private-tab disk persistence
  remains unverified** — it can only be settled on a device.
- Two build-environment failures occurred during the program and
  were caught by the verify gate before anything invalid shipped:
  a Gradle daemon death producing a partial APK (v0.19.0 attempt 1)
  and a runtime restart mid-build (v0.21.0 attempt 1). Both were
  disclosed in their release emails.
