# SETT redesign — UX problem register (Phase 1, 2026-11-01)

Format per issue: file/component · current behaviour · expected ·
severity · root cause · proposed fix · verifying test.

## Home page

**HOME-1 — Start-page search searches URLs instead of navigating.**
`Components.kt` (HomeInterceptor). Current: the form GETs the raw
text to the search engine (`action` = engine base, param q/query).
Expected: text is resolved by the same AddressResolver as the
address bar — URLs navigate, everything else searches. Severity:
**High**. Root cause: a web form cannot run Kotlin resolution.
Fix: submit to a `sett://resolve?q=…` interception handled by the
interceptor/app, which resolves and redirects to the final URL.
Test: JVM test that sett://resolve maps a URL input to itself and
a phrase to the engine URL (resolver already tested; add the
interception mapping test).

**HOME-2 — Protection chips are static claims.** Same file.
Current: four chips always claim protections "on". Expected: chips
reflect the real settings (profile, HTTPS-Only, GPC) or are removed.
Severity: **High** (misleading UI in a privacy product). Root cause:
static HTML. Fix: interceptor builds the page from SettingsStore
values at serve time. Test: page HTML contains the disabled state
when the profile is standard (unit test on the page builder).

**HOME-3 — Tiles are not shortcuts.** Same file. Current: 4 fixed
search-engine links. Expected: user shortcuts with add/edit/remove,
custom names, persistence. Severity: Medium. Root cause: feature
never built. Fix: `HomeStore` (JSON in app storage) + interceptor-
rendered tiles + `sett://home/edit` flows handled natively.
Test: store round-trip JVM test.

**HOME-4 — No dashboard sections.** Expected: optional Continue
browsing / Recently visited / Bookmarks / Recently closed /
Downloads sections from real stores, each hideable. Severity:
Medium. Fix: interceptor renders from places + recently-closed
storage; visibility prefs in SettingsStore. Test: page builder
test with fixture data; empty data → sections absent (no fake rows).

**HOME-5 — No search-category shortcuts.** Fix: category chips on
the home page routing through SearchRouting. Test: routing tests
exist; add chip-link assertions to the page builder test.

**HOME-6 — Dark-only page.** The page ignores any theme choice.
Fix: page built with the active palette (Phase 2 tokens). Test:
builder emits light palette when theme=light.

**HOME-7 — ~90 KB base64 mascot inlined in a Kotlin string.**
Severity: Low (startup/compile weight, unreadable source). Fix:
serve the image from a bundled asset/resource via a sett:// asset
path or keep data-URI but generated from the packaged PNG resource.
Test: page references the asset; release DEX/size sanity in gate.

## Browser chrome

**CHR-1 — Shields button wears a stock info icon**
(`activity_browser.xml`: `ic_dialog_info`). Expected: a shield
glyph that reads as protection. Fix: bundled vector drawable.
Test: visual (device); resource presence in gate.

**CHR-2 — No back/forward/home in chrome.** Current: system back
only; forward and home live in the tab/menu flows. Expected per
brief §8: back/forward/home reachable. Fix: long-press or compact
additions without bloating the bar — back/forward added to the tab
tray header and menu Navigation group; a Home action in the menu
group. Severity: Medium. Test: menu structure unit coverage is
manual; behaviour unchanged for existing paths.

**CHR-3 — Menu is a 22-row flat list with duplicate icons.**
`BrowserActivity.showMenu`. Expected: brief §8 groups (Navigation,
Library, Protection, Tools, Settings). Fix: grouped sections with
headers, unique icons, Share row added (currently absent from the
menu entirely — **CHR-4**). Severity: Medium. Test: build + device
pass; rows keep their existing actions (no behaviour change).

**CHR-5 — Address bar has no suggestions.** Expected: local
suggestions from bookmarks/history (on-device, no network) with the
search-engine suggestion source OFF by default and disclosed if
enabled. Severity: Medium. Fix: dropdown under the address bar
backed by places queries. Test: suggestion provider JVM tests.

**CHR-6 — Touch targets under 48dp** (tabs badge 34dp, reload
40dp). Fix: 48dp targets in the Phase 4 chrome pass. Test: layout
values in XML (gate grep) + device.

## Design system

**DS-1 — No theme system.** `themes.xml` has one dark theme; no
light, no system mode, no user choice. Fix (Phase 2): SettTheme
tokens + `themeMode` pref (system/dark/light) applied app-wide.
Test: pref persistence + token selection JVM tests.

**DS-2 — Colour constants copy-pasted per activity** (~12 files,
BG #0B1626 etc.), disagreeing with the XML palette (#050B18).
Fix: single `ui/SettTheme.kt` + `ui/Components.kt` helpers; migrate
screen by screen (Phases 2/4/5/6). Test: full build + grep gate
(no stray hex constants outside the token file).

**DS-3 — Emoji as the icon system.** Inconsistent across devices,
poor TalkBack labels. Fix: keep emoji only where harmless in rows
during migration; chrome and menu get bundled vector icons
incrementally. Severity: Medium. Test: content descriptions set.

**DS-4 — Ad-hoc typography.** 11–20sp with no scale. Fix: type
scale in tokens (title 20, section 16, body 14, caption 12.5).
Test: migration greps.

**DS-5 — No density option.** Brief asks compact/comfortable.
Fix: `density` pref scaling list paddings in shared components.
Test: pref + component parameter test.

## Search

**SEA-1 — No native results UI** (`SearchActivity.kt`,
`tools/SearchRouting.kt`). Current: choose category+provider →
open provider page in a tab. Expected (brief §7D): native results
list with title/domain/snippet/URL and actions, when a structured
source exists. Fix (Phase 7): provider layer — SearXNG JSON (user
instance) and DuckDuckGo HTML parsing as structured sources;
everything else keeps honest routed pages. Test: parser fixture
tests (recorded HTML/JSON fixtures), failure → error state test.

**SEA-2 — Ten brief categories, six exist.** Books, PDFs, Code,
Forums/Discussions, Open-source missing. Fix: add as *routed*
categories with real destinations (Open Library for Books;
filetype:pdf on the web provider for PDFs; grep.app/GitHub for
Code; provider+site filters for Forums; GitHub/GitLab explore for
Open-source) and native results where Phase 7 sources support
them. Test: routing tests per category.

**SEA-3 — No suggestions anywhere in search UI.** Fix: optional,
off by default, provider-disclosed suggestions in the SETT Search
screen (Phase 7), local recents with a clear-all control.
Test: suggestion parsing fixtures; recents store round-trip.

**SEA-4 — No SafeSearch / language / region settings.** Fix:
settings on the Search screen mapped to real provider parameters
(DDG kp/kl, SearXNG safesearch/language) — never cosmetic toggles.
Test: URL-building tests include the parameters.

**SEA-5 — SearXNG JSON API unused.** Fix: when an instance is
configured, SETT Search queries `format=json` for native results;
falls back to the web page on failure with a visible notice —
never silently switching providers (brief §7E). Test: JSON fixture
parse + fallback decision test.

**SEA-6 — Academic category is a link, Research Desk is separate.**
Fix (Phase 8): Academic results render natively from OpenAlex
(already integrated and tested in the Research Desk). Test:
existing ScholarTools tests + results mapping test.

**SEA-7 — Unpaywall absent.** Its API requires an email parameter;
under the project's no-account/privacy stance it stays out (as
decided in ecosystem Phase 8). Documented, not a defect.

## Screens, accessibility, performance

**SCR-1 — Empty/loading/error states vary by screen.** Fix:
shared components carry the three states during migration.
**SCR-2 — Library loads 1,000 history rows eagerly.** Fix: cap at
300 with a "showing recent" note (honest, cheap). Test: build.
**SCR-3 — No Reading list.** Fix (Phase 5): a real lightweight
store (URL+title+saved-at) with Add from the menu, listed in
Library, shown on the home dashboard. Test: store round-trip.
**A11Y-1 — Emoji rows + tiny 11sp labels** in places. Fix: token
type scale + content descriptions during migration.
**A11Y-2 — No RTL verification.** Arabic/Urdu strings render via
system shaping; layouts are programmatic LTR. Fix: Phase 9 pass —
start/end padding where feasible, documented limits.
**A11Y-3 — Contrast**: DIM #8FB3C7 on #0B1626 ≈ 7.0:1 (fine);
start-page placeholder #5E7484 on dark ≈ 4.0:1 (weak) — raise in
Phase 3 palette.
**PERF-1 — Start page string rebuilt per request** incl. base64
image. Fix: build once per settings/theme change (cache in the
interceptor), asset-served image (HOME-7).
**PERF-2 — Library index rebuild per open** (Phase 11 of the
ecosystem program) is memory-only and bounded (2,000 visits);
acceptable, recorded.
