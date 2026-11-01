# Sett v0.4.0 — phased fix & polish plan

Written 2026-10-31, from the device feedback on v0.3.0 and the study in
`docs/UX_STUDY.md` (Fenix, DuckDuckGo Android, Firefox Focus — patterns
only, clean-room). Delivery rule (Talha, 2026-10-31): learn → plan →
execute phase-wise → test → ONE final APK when every gate passes.

## Phase 3a — Start-page & session integrity
- Start page served for `sett://home` AND legacy `privatex://home`
  (restored sessions keep pre-rename tabs; dropping the old address is
  what blanked the home tab in v0.3.0). Trailing-slash tolerant.
- AddressResolver: "home" and both start-page URLs resolve home; empty
  input goes home; hosts → https; everything else → search engine.
- Refactor for testability: home-URL matching and resolver become pure
  functions (no Android types) so JVM unit tests can pin them.
- GATE: unit tests for resolver + home matching pass (offline host,
  localhost, IP, port, multi-word search, home aliases, legacy URL).

## Phase 3b — System bars, keyboard, failed loads
- Window insets: root padded by system bars (content never under the
  status bar); bottom = max(navigation, IME) so the chrome bar rides
  above the keyboard; status/nav icons light on the navy chrome.
- Failed-load error pages (study priority #1): vendor AC
  `browser-errorpages`, override `onErrorRequest`, classify ErrorType
  → friendly Sett-branded error page with retry. A failed load can
  never again present as a blank white tab.
- GATE: builds green; error-page strings + both home URLs present in
  the shipped DEX; manual code-path review of interceptor branches.

## Phase 3c — Browser & search feel (from the study)
- Tab-close Undo (AC UndoMiddleware — already in the dependency tree;
  Fenix's UX is a thin snackbar over it). Closing a tab offers Undo
  for a few seconds instead of being permanent.
- State-flow hygiene: chrome updates only on actual change
  (distinctUntilChanged-style guards) to cut needless re-renders.
- Search policy (documented decision): no network search suggestions —
  Fenix/Focus gate them behind settings and private-mode rules; Sett
  sends nothing while typing, by design.
- Home stays web-served for now (branding, Brock) with the 3a/3b
  safety nets; native home (Fenix/DDG pattern) is a possible later
  phase, not required to close the reported failures.
- GATE: builds green; undo middleware wired into the store; snackbar
  shown from the tab-close path (code-reviewed).

## Phase 4 — Test
- `testDebugUnitTest` green (resolver, home matching).
- Release build green; from-the-file verification: badging (label
  Sett, code 9, compileSdk 36), apksigner v2+v3 same cert, zipalign
  16K, zip integrity, icon layers present, DEX string checks.
- Results written to `docs/TEST_RESULTS_040.md`, including what is NOT
  covered (no emulator in this VM; on-phone pass is Talha's final
  acceptance, with a short checklist).

## Phase 5 — Deliver
- v0.4.0 (versionCode 9) GitHub release on sett-browser, both ABIs,
  served bytes re-downloaded and hash-matched, source pushed, link
  emailed to muhaqqiqcreates@gmail.com, report in chat.
