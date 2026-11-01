# SETT Search — white-label search design (v0.25.0)

SETT Search is a branded search *interface*, not a search engine:
Sett runs **no crawler, no index, no database and no server**
(zero spend is a hard project constraint). Results come from
existing providers under their own terms; Sett owns the design,
branding, settings and rendering. This document is the audit and
provider evaluation the design was built from, plus the standing
limitations.

Tagline: **Search freely. Search intelligently.**

## Audit of what existed (redesign v0.20.0 state)
- SETT Search screen with 11 categories, per-category providers,
  SafeSearch/language as real parameters, opt-in suggestions
  (default OFF), on-device recents with clear/disable, and a
  data-flow disclosure per provider.
- Custom (native) results from two sources: a user-configured
  SearXNG instance's JSON API, and a parser for DuckDuckGo's HTML
  endpoint. Scholarly cards from OpenAlex / Semantic Scholar.
- Everything else routed to provider result pages.
- Defect carried in from the address bar: every app-built
  DuckDuckGo URL used `html.duckduckgo.com`, which failed to
  resolve on the user's network (device report 2026-11-01).

## Provider evaluation (November 2026, from the providers' own docs)

| Provider | Real web results? | Key needed | Terms position | Privacy notes | In Sett |
|---|---|---|---|---|---|
| SearXNG (own instance) | Yes — metasearch over many engines | No | JSON API (`format=json`) is an official feature of the software | The instance is the user's own server; nothing goes elsewhere | Custom mode |
| SearXNG (public instances) | Yes | No | Same official JSON API; instances are listed by the project's registry at searx.space with measured reliability | The instance is a third-party operator: it sees query + IP under *its* policy. Sett names the instance in use before every search and never routes silently | Custom mode (new in v0.25.0) |
| DuckDuckGo | Yes | No | No general web-results API is offered; its terms do not permit scraping the HTML endpoint. The Instant Answer API returns instant answers only, not web results | Strong no-tracking policy | **Direct mode** — its own results page. HTML parsing removed in v0.24.0 for terms compliance |
| Brave Search | Yes | API is key-gated (free tier exists) | API use requires a key + plan terms; the results *page* is ordinary web navigation | Independent index; page use is standard browsing | Direct mode (results page). No key is bundled or required |
| Mojeek | Yes | API is key-gated | Same as Brave | Independent index | Direct mode |
| Startpage | Yes | No API | Results page navigation | Proxies Google results with privacy positioning | Direct mode |
| Google | Yes | Custom Search JSON API is key-gated, quota-limited and being wound down for new sign-ups | Page navigation is ordinary browsing; no API used | The provider with the weakest privacy posture of the set — included because users ask for it, labelled with the standard data-flow note | Direct mode, where already present via categories |
| OpenAlex / Semantic Scholar | Scholarly records | No (keyless, documented APIs) | Made for programmatic use | Catalogue metadata only | Custom mode (Academic) |

Rules that follow from this table, and that the code follows:
- No invented APIs, no scraping against terms, no hardcoded keys.
- Custom mode exists only where a legitimate API exists.
- Direct mode preserves each provider's own functionality and
  never disguises whose page it is once opened.

## What v0.25.0 ships
- **Branded search home**: Brock (bundled asset), SETT Search
  wordmark, tagline, category chips, provider picker, recents,
  settings — one design system with the browser (System/Dark/Light).
- **Public SearXNG instances**: the app fetches the searx.space
  registry at runtime, filters it (HTTPS, clearnet, HTTP 200,
  ≥99% measured search success, no-declared-analytics first, then
  fastest — `search/SearxngInstances.kt`, unit-tested), persists
  the user's pick, and names it on every results screen. If an
  instance answers without JSON results (some operators disable
  the format), Sett tries the **next instance of the same
  provider once, saying so on screen** — never a silent switch
  to a different provider. Own instance always takes precedence.
- **Result cards**: favicon fetched from each result's own site
  (no favicon service; letter-tile fallback), title, host,
  snippet, per-result engine name; long-press actions: Open,
  Open in new tab, Share, Copy link, Bookmark, Save to reading
  list. Scholarly cards gained the same action set.
- **Filters where the API supports them**: SafeSearch, language,
  and now SearXNG `time_range` (Any time / day / week / month /
  year). Load-more pagination for SearXNG (existing).
- **Home integration**: a general query typed on the browser's
  home page now opens SETT Search and runs there (addresses
  still navigate directly); the address bar keeps its fast path
  to the chosen provider.
- **States**: loading line naming the instance, a distinct
  no-results state, and an error state with instance-switch and
  open-provider-page actions. No dummy results anywhere.

## Privacy summary
- Sett receives and stores nothing; there is no Sett server.
- Recents live only on the device, behind a toggle, with Clear.
- Suggestions stay OFF by default; enabling them sends partial
  queries to DuckDuckGo's autocomplete — stated at the toggle.
- Queries go only to the provider/instance shown on screen.
- No claim of complete anonymity is made anywhere in the UI: a
  public SearXNG instance is a third party and is labelled as one.

## Limitations (standing, honest)
- Public instances are outside Sett's control: availability and
  whether an operator enables the JSON format vary; the app
  handles failure visibly but cannot guarantee an instance.
- Direct-mode providers show their own pages, branding included —
  that is what their terms intend.
- No screenshots from a device are attached to this work: the
  build environment has no emulator or test phone (the standing
  limitation of this project). Verification = the JVM suites
  (78 tests incl. the registry parser and routing), the
  from-file release gate, and served-bytes hash comparison.
  Visual acceptance remains the user's phone check.
