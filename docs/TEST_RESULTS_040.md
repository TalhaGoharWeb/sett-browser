# Sett v0.4.0 — test results (2026-10-31)

Built and verified under the phased process in `docs/SETT_040_PLAN.md`
(learn → plan → execute → test → deliver). All checks below are from
the built files and the Gradle test runner, not from build-log claims.

## Unit tests — PASS
`:app:testDebugUnitTest` — **7 tests, 0 failures, 0 errors**
(`app/src/test/java/app/privatex/browser/SettLogicTest.kt`):
- Start-page matching: `sett://home`, legacy `privatex://home`, both
  with trailing slashes; non-home URLs rejected.
- Home input words: "home", "sett:home", case/space tolerant.
- Resolver classification: empty → Home; home words → Home; full URLs
  pass through; bare hosts → HTTPS (incl. `localhost:8080/x`,
  `localhost`, LAN IPs); phrases and single words → Search.
- The suite caught one real bug during Phase 3a: `localhost:8080/x`
  was classified as a search (the host rule required a dot). Fixed in
  the classifier; the regression is pinned by the test above.

## Build — PASS
`:app:assembleRelease` green, including lint (an earlier AGP-lint UAST
crash on a lambda-typed helper in SettingsStore.kt was removed by the
classify() refactor — the resolver no longer uses function-type
parameters). Two compile-time API mismatches in new code were caught
and fixed during Phase 4: `RequestInterceptor.onErrorRequest` in AC
159 takes (session, errorType, uri), and `UndoAction.ClearRecoverableTabs`
requires the undo-history tag (now read from state, not hardcoded).

## From-the-file verification (arm64 APK) — PASS
- Badging: `app.privatex.browser`, versionCode **9**, versionName
  **0.4.0**, label **Sett**, compileSdk 36, targetSdk 36.
- apksigner: **v2 ✓, v3 ✓**; signer cert SHA-256
  `0a60cd9f…f24f2` — the project release key, unchanged since v0.1.0,
  so v0.4.0 installs as an update over every earlier build.
- zipalign 16K page check ✓; zip integrity ✓; adaptive icon present.
- DEX contents: `sett://home` and `privatex://home` both served;
  `ErrorPages`/`createUrlEncodedErrorPage` present (failed-load error
  pages wired); `UndoMiddleware` present; "Tab closed" undo snackbar
  string present.
- Files: arm64 235,795,238 B, SHA-256
  `dbf336aebd611c1742b5f80e295a41b75673142fd3333ce7d54d51637b353f0a`;
  armv7 192,471,561 B, SHA-256
  `f329211930453dee3711f87d41b66a66e63dade87d4671c01ba27aac87c9840d`.

## Not covered here (stated plainly)
This VM has no Android emulator (no KVM, no system images), so
rendering and touch behaviour were **not** machine-tested. The
on-phone acceptance pass is Talha's: install over v0.3.0, then
(1) the restored home tab renders the Sett start page with the search
box; (2) pages no longer slide under the status bar; (3) the keyboard
lifts the chrome bar instead of covering it; (4) with Wi-Fi off, a
failed load shows the error page with a refresh button; (5) closing a
tab offers Undo.
