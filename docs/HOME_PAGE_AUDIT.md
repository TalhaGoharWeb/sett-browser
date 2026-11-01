# SETT home page — audit (Phase 1, 2026-11-01)

## Implementation today

`HomeInterceptor` in `Components.kt` serves one static HTML string
from memory for `sett://home` and legacy `privatex://home`.
Contents: Brock image (base64 data-URI, ~90 KB of source), SETT
wordmark, tagline, a search form, four static protection chips,
four fixed tiles (DDG/Brave/Mojeek/Startpage homepages), a footer.

## What works (keep)

- Loads instantly, fully offline, zero network requests. ✔ brief §6H
- Both current and legacy internal URLs served — restored sessions
  from every shipped version keep working. ✔ §6/§4
- Visual identity exists (wordmark, Brock, navy/teal).

## Defects (detail in UX_PROBLEMS.md)

1. **Search field lies** (HOME-1): placeholder promises address
   entry; the form actually submits everything to the search
   engine as a query. A pasted URL is searched, not opened.
2. **Chips are theatre** (HOME-2): "Tracking protection on /
   HTTPS-Only on / GPC sent / Cookies partitioned" are hardcoded.
   Change the protection profile and the page still claims all four.
3. **Tiles are not shortcuts** (HOME-3): no user data at all.
4. **No dashboard** (HOME-4): nothing from the user's real library.
5. **No categories** (HOME-5).
6. **Dark-only** (HOME-6) and **weak placeholder contrast** (A11Y-3).
7. **Performance** (HOME-7/PERF-1): the full HTML incl. base64
   image is rebuilt as a Kotlin string on every request.

## Redesign shape (Phase 3, per brief §6)

- Page rendered by a testable `HomePageBuilder` from real state:
  SettingsStore (engine, profile, theme), HomeStore (shortcuts),
  places (recent visits, bookmarks), recently-closed storage.
- Header: wordmark + Brock (asset-served), links to Shield and
  Settings (native routes via sett:// actions).
- Search form → `sett://resolve` so the AddressResolver decides
  (fixes HOME-1 without changing the resolver's tested rules).
- Shortcuts grid from HomeStore with native add/edit/remove flows
  (sett://home/shortcuts actions open app-side UI); favicon =
  site's own /favicon.ico with a letter-tile fallback; no remote
  tracking pixels (favicons load only for user-chosen sites, and
  the page notes this).
- Dashboard sections (Continue browsing, Recently visited,
  Bookmarks, Recently closed) rendered only when data exists;
  each hideable in Settings; Reading list joins in Phase 5.
- Privacy summary built from actual setting values (fixes HOME-2).
- Category chips routing through SearchRouting (fixes HOME-5).
- Theme-aware palettes from Phase 2 tokens (fixes HOME-6),
  cached page per (theme, settings, data) signature (PERF-1).
