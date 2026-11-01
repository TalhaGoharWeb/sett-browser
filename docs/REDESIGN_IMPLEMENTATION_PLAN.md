# SETT redesign — implementation plan (Phase 1, 2026-11-01)

Releases continue the established line (same applicationId, same
signing key, GitHub Releases + email delivery, 42+-point verify
gate, JVM tests per phase). One phase at a time, in the brief's
order (§13). Scope corrections stated once, up front:

- **No emulator/device exists in the build environment.** "Run the
  app / screenshots" is therefore: builds + full JVM suites +
  from-the-file verification, plus headless-Chrome renders of the
  HTML start page. Native-screen screenshots and device passes
  remain the maintainer's acceptance step, as with every release
  so far. `docs/REDESIGN_TEST_REPORT.md` is written at Phase 10
  with executed results only.
- **SearXNG hosting is not created** (zero spend; the brief's §7E
  deployment items describe the user's own instance). Sett gains
  the client side: JSON API use when the user configures one.
- **Voice search** stays out (no speech API integrated; brief
  allows this).

## Phase 2 — Design foundation → v0.16.0 — ✅ SHIPPED (2026-11-01)

**As built:** `ui/SettTheme.kt` tokens (raw ARGB ints — Color.
parseColor is an Android stub that breaks JVM tests), `ui/SettUi.kt`
components, `Theme.PrivateX.Light` platform theme, Settings →
Appearance (theme mode + density), Shield/Spaces/Library Search
migrated as the proof set, `docs/DESIGN_SYSTEM.md`. 58 tests.

`ui/SettTheme.kt` (dark+light token sets: bg, surface, elevated,
accent, accent2, text, dim, warn, error, radii, type scale, spacing)
+ `ui/SettUi.kt` shared components (page/section/body/field/button/
card/empty-state) + SettingsStore `themeMode` (system/dark/light)
and `density` prefs + theme picker in Settings. Migrate 3 screens
as proof (Shield, Spaces, Index). Tests: token/pref tests.
Gate add: no hex constants outside SettTheme in migrated files.

## Phase 3 — Home page → v0.17.0 — ✅ SHIPPED (2026-11-01)

**As built:** `home/HomeStore` (user shortcuts JSON) +
`home/HomePageBuilder` (pure, token-themed) + `home/HomeActions`.
Interceptor handles `sett://resolve` (AddressResolver — HOME-1
fixed), `sett://action/...` bounce pages (BrowserActivity performs
shield/settings/shortcut-editor/section-picker/search-category
natively), and renders the page from a BrowserActivity-refreshed
`HomeSnapshot` (bookmarks/recent/closed) because places reads are
suspend and the interceptor is synchronous. Protection card reads
real settings (ETP profile, clear-on-exit, DoH label; HTTPS-Only
and GPC are engine-level constants — verified in DefaultSettings).
Brock stays a data-URI: AC 159's InterceptionResponse.Content is
text-only (javap-verified), so binary asset serving does not exist;
the page is cached by a data key instead (PERF-1 mitigated).
62 tests.

## Phase 3 — Home page → v0.17.0
`HomeStore` (shortcuts JSON) + `HomePageBuilder` (pure, tested) +
interceptor rework: sett://resolve (HOME-1 fix), dynamic privacy
chips (HOME-2), shortcuts grid + native edit flows (HOME-3),
dashboard sections from places/recently-closed (HOME-4), category
chips (HOME-5), themed palettes (HOME-6), cached page (PERF-1).
Favicon: /favicon.ico of user-chosen sites only, letter fallback.
Tests: builder fixtures (empty/full), store round-trip, resolve
mapping. Headless-Chrome render check of both palettes.

## Phase 4 — Browser chrome → v0.18.0 — ✅ SHIPPED (2026-11-01)

**As built:** menu regrouped into Navigation / Tabs & page /
Library & tools / Protection & data / Sett with section headers,
48dp rows, distinct glyphs, and two additions — a Navigation group
(Back/Forward when history exists, Reload, Sett home) and Share
this page (CHR-4 fixed). A fifth bar button (Home) does not fit a
360dp bar beside four 48dp targets + pill, so Home lives in the
Navigation group — layout-permits clause, recorded here. Bar:
real vector shield (state-tinted: accent HTTPS / amber HTTP /
muted home, with matching content descriptions), vector reload and
dots icons, all targets 48dp (CHR-1/CHR-6). Chrome + tab tray +
tray cards now take the live SettTheme palette (applyChromeTheme
on resume), so Light mode is real on the main screen. CHR-5
address-bar suggestions remain Phase 7 scope (they belong to the
search system, with its privacy gate).

## Phase 4 — Browser chrome → v0.18.0
Bundled shield vector (CHR-1), menu regrouped per brief §8 with
headers + Share row (CHR-3/4), navigation additions (CHR-2), 48dp
targets (CHR-6), address-bar local suggestions from places with
engine suggestions OFF (CHR-5), tab tray header polish.
Tests: suggestion provider fixtures.

## Phase 5+6 — Library, tools & privacy screens → v0.19.0 — ✅ SHIPPED (2026-11-01)

**As built:** every activity migrated to SettTheme palette getters
+ platform theme (grep proof: no stray hex outside roundedBg);
Reading list added (`library/ReadingListStore`, Library section,
menu save action, dedupe by URL — audit SCR-3); Library history
render capped at 300 with an honest header note (SCR-2). 63 tests.
Migrate remaining activities to SettUi/SettTheme (Library, Vault,
Extensions, Network, Utilities, Developer, Search shell, Research,
Study, AI). Reading list store + Library section + menu action
(SCR-3). History cap note (SCR-2). States normalised (SCR-1).

## Phase 7 — SETT Search → v0.20.0 — ✅ SHIPPED (2026-11-01)

**As built:** `tools/SearchResults` (SearXNG JSON + DDG-HTML
parsers, SafeLevel mapping to real kp/safesearch params, suggestion
parsing); native results in SearchActivity for DDG (Web/PDFs/
Forums) and SearXNG (all mapped categories, Load more paging),
visible error + provider-page fallback, never silent; five new
categories (Books, PDFs, Code, Forums, Open source) with real
destinations; opt-in suggestions OFF by default with disclosure;
recent searches on-device with clear + disable; home chips capped
at 8. 70 tests.

## Phase 7 — SETT Search → v0.20.0
Provider layer (`SearchProvider` capabilities + disclosure),
structured sources: SearXNG JSON (when configured) and DDG-HTML
parser (default), native results UI with actions (open, new tab,
copy, share, bookmark), load-more where the source supports it,
error/empty states, optional suggestions (OFF default, disclosed),
local recents with clear-all, SafeSearch/language params where
real, + Books/PDFs/Code/Forums/Open-source routed categories.
Tests: HTML/JSON fixtures, fallback decisions, param building.

## Phase 8 — Scholarly in Search → v0.21.0 — ✅ SHIPPED (2026-11-01)

**As built:** Academic category renders natively for OpenAlex
(Phase-8-ecosystem parser reused) and Semantic Scholar (new
`ScholarTools.parseSemanticScholar` for its keyless Graph API):
cards with authors/year/venue, cited-by, OPEN ACCESS badge and
DOI; long-press Open / Open OA PDF / Copy DOI / Save to reading
list; header labels results as catalogue metadata. Scholar
Archive still opens its own page (no keyless JSON API). 71 tests.

## Phase 8 — Scholarly in Search → v0.21.0
Academic category renders OpenAlex results natively inside Search
(reusing ScholarTools), with DOI/OA display and Research Desk
deep-link for collections. Tests: mapping fixtures.

## Phase 9 — Accessibility & responsiveness → v0.22.0
Content descriptions sweep, 48dp audit, font-scale behaviour,
start/end (RTL-aware) padding in migrated screens, contrast fixes,
landscape/split-screen sanity via layout review, reduced-motion
respect in the few animated spots.

## Phase 10 — Release
Full regression (all suites), REDESIGN_TEST_REPORT.md with
executed results, README + architecture refresh, final release
and programme report. Acceptance criteria §15 checked line by
line; anything not device-verifiable is labelled as such.
