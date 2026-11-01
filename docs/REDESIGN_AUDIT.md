# SETT redesign — repository audit (Phase 1, 2026-11-01)

Scope: the whole `TalhaGoharWeb/sett-browser` tree at v0.15.0
(56 JVM tests green, 42-point release gate). Method: source
inspection of every Kotlin file (10,260 lines), manifest, resources,
Gradle pins, docs (README, `docs/architecture.md`, ADR-001, both
security audits, the ecosystem roadmap), and the test suite.
Classification key: **A** working · **B** works, needs improvement ·
**C** partial · **D** UI-only/non-functional · **E** broken ·
**F** missing · **G** security-sensitive.

## 1. What the app actually is (verified, not from the README)

A thin native embedder over GeckoView via Android Components 159:
`BrowserActivity` (XML layout, bottom chrome) + 15 programmatic-UI
activities (Library, Shield, Vault, Extensions, Network, Spaces,
Utilities, Developer, Search, Research, Study, AI, Index, Settings
dialogs) + 4 on-device stores (places, vault, research, study) +
a local start page served from memory by `HomeInterceptor`.

## 2. Feature classification

| Area | Class | Evidence |
|---|---|---|
| Engine, ETP/TCP, HTTPS-Only, GPC | A/G | Components.kt prefs; verified per release |
| Tabs, session restore, undo close | A | AC store + middleware; JVM tests |
| Start page loads offline, both home URLs | A | HomeInterceptor serves from memory |
| Start page search field | **E** | Form posts raw text to the search engine; URLs typed there are *searched, not navigated*, despite the placeholder "Search privately or enter address" |
| Start page protection chips | **D/G** | Hardcoded "on" claims; do not read actual settings — misleading when the user changes the profile |
| Start page tiles | **D** | Four fixed search-engine links presented as a grid; not user shortcuts; no add/edit/remove/persistence |
| Start page dashboard sections | F | No recent/bookmarks/closed/downloads sections exist |
| Start page categories | F | No category shortcuts |
| Address bar | B/C | Pill input + resolver (tested); no suggestions, no share/copy/paste-and-go, shields button uses stock `ic_dialog_info` |
| Bottom chrome | B | Back/forward/home absent (system back only); tabs badge 34dp, reload 40dp — under the 48dp target guidance |
| Menu | B | 22 rows, flat, ungrouped; duplicate emoji (🗂 Library *and* Spaces, 🔍 Find *and* Library Search); no Share row |
| Tab tray | B | Works with search + Spaces filter; no previews beyond titles |
| Library (places) | A/B | Real data ops; loads up to 1,000 history rows eagerly |
| Reading list | F | Does not exist (README never claimed it; the redesign brief requires it) |
| Vault | A/G | Real libsodium crypto, JVM-tested |
| Shield / Network / Spaces | A/B | Real engine data/prefs; Spaces honestly labelled tab groups |
| Utilities / Developer | A | Real tools, tested logic |
| SETT Search | **C** | A routing screen: picks provider per category and opens the provider's page. No native results UI, no suggestions, no recents, no SafeSearch/language/region, 6 of 10 brief categories |
| SearXNG | C | Instance URL is stored and used as a *web page*; its JSON API is never called |
| Research Desk | A | Real keyless APIs, tested parsers |
| Study / AI / Library index | A/B | Real on-device data; AI is BYOK with consent gate |
| Theming | **F/B** | One dark Material3 theme; no light theme, no system mode; 15 activities hardcode their own colour constants — surfaces disagree (#050B18 XML vs #0B1626 activities vs gradient start page) |
| Shared UI components | F | The page/section/body/field helpers are copy-pasted into ~12 files |
| Accessibility | C | Chrome buttons have contentDescriptions; emoji menu rows are TalkBack-hostile; no RTL checks; sp units used (good) |
| Update checker | A | Menu row vs signed GitHub feed (Phase 12) |

## 3. Issue register (summary — detail in UX_PROBLEMS.md)

HOME-1..7 (start page), CHR-1..6 (chrome/menu), DS-1..5 (design
system), SEA-1..7 (search), SCR-1..3 (screens), A11Y-1..3, PERF-1..2.
Highest severity: **HOME-1** (address text searched, not navigated),
**HOME-2/D** (static protection claims), **SEA-1** (no native results),
**DS-1** (no theme system).

## 4. Constraints honoured by the plan

applicationId, signing key, engine and storage formats are
untouched. No new heavy dependencies: the redesign is tokens,
shared components, one HTML page rewrite, one provider/result
layer for search, and screen-by-screen token migration. Device
verification remains the maintainer's step (no emulator exists in
the build environment); the HTML start page can additionally be
render-checked in headless Chrome here.
