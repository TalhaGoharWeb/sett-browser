# SETT Search — audit (Phase 1, 2026-11-01)

## What exists today (verified in code)

- `tools/SearchRouting.kt`: pure routing — 6 categories
  (WEB/IMAGES/NEWS/VIDEOS/MAPS/ACADEMIC) × providers, each with a
  plain-language `dataFlow` disclosure; `route()` builds the
  provider URL. 5 JVM tests.
- `SearchActivity.kt`: category chips + per-category provider
  spinner (persisted per category), SearXNG instance URL field,
  data-flow section, "suggestions OFF" stance. Choosing a provider
  loads its results *page* in a browser tab. There is no results
  UI inside Sett.
- `SettingsStore`: default web engine for the address bar/home
  (DDG, Brave, Mojeek, Startpage, SearXNG-instance), per-category
  providers under `search_cat_*`, `searxng_url`.
- Scholarly: the Research Desk (ecosystem Phase 8) already queries
  OpenAlex/Crossref/DataCite/DOAJ natively with tested parsers —
  but it is a separate screen, not the Search Academic category.
- Address bar: `AddressResolver` (tested) decides URL vs search;
  no suggestions anywhere.

## Gaps against the redesign brief (§7)

| Brief item | State | Plan |
|---|---|---|
| Native results UI (title/domain/snippet/URL, actions) | Missing | Phase 7: SearXNG JSON + DDG-HTML structured sources |
| Suggestions | Absent by design | Optional, OFF by default, disclosed source (Phase 7) |
| Recent searches | Absent by design | Local-only recents with clear-all (Phase 7) |
| SafeSearch / language / region | Absent | Real provider params only (Phase 7) |
| 10 categories | 6 | + Books, PDFs, Code, Forums, Open-source as routed categories (Phase 7) |
| SearXNG as metasearch backend | URL used as web page only | JSON API integration with visible fallback (Phase 7) |
| Provider abstraction | Routing table only | `SearchProvider`-style layer in Phase 7 (id, categories, capabilities, disclosure, structured-search where available) |
| Academic native results | In Research Desk, not Search | Phase 8 renders OpenAlex results inside Search too |
| Unpaywall | Absent | Stays out — API requires an email parameter (recorded ecosystem Phase 8 decision) |
| Voice search | Absent | Stays out — no speech API is integrated; the brief permits "only if actually supported" |

## Honesty constraints carried into Phase 7

Results are only ever what a real provider returned, attributed
on-screen ("Results from DuckDuckGo" / "from your SearXNG
instance"). Parse failure shows an error state with a button to
open the provider page — never invented results. No query is sent
anywhere before the user submits. Switching providers mid-flow is
always the user's tap, never silent.
