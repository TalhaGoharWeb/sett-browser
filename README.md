# PRIVATE X

**Private. Secure. In Control.**

A free, open-source, privacy-first web browser — real engine, no accounts,
no telemetry, no paid features. Developer: Muhammad Talha Bin Farid.

## Status (2026-10-31)

**v0.2.1 SHIPPED** — the current release (GitHub Releases, pre-release).
Release history, all signed with the project keystore (SHA-256
0a60cd9f…24f2) and verified from the built files before publishing:

- v0.1.0 — first ship. Failed to install on the developer's Android 16
  phone ("package appears to be invalid").
- v0.1.1 — install fix: compiled against API 36 instead of the API 37
  preview platform (which stamped a preview codename into the APK);
  v2+v3 signing. Installed, but crashed right after launch.
- v0.1.2 — launch-crash fix: Application.onCreate now initialises only
  in the main process (GeckoView's :gpu/:content child processes re-run
  it and must not build the engine); added a fully local crash recorder
  shown on next launch, never transmitted.
- v0.2.0 — interface redesign: floating chrome bar, pill address bar
  with host-only display, tab-count badge, new start page, card-grid
  tabs tray, bottom-sheet menu.
- v0.2.1 — new logo/icon: custom gradient shield-eye mark
  (design/make_icon.py), full adaptive icon + monochrome layer.

**Device acceptance still pending** — install on the developer's phone
is the test that counts.

Build notes (hard-won, see android/): maven.mozilla.org is unreachable
from Java in this sandbox (proxy read-timeouts) but fine via curl, so
`android/vendor_mozilla.py` vendors the org.mozilla.* closure into
`android/local-maven/`. AC 159 needs compileSdk 37.0 (minor API level,
new-DSL `release(37) { minorApiLevel = 0 }`) and Kotlin 2.4 (AGP 9
built-in Kotlin picks the version from the root plugin declaration).
SDK platform 37.0 + build-tools 37.0.0 were installed into the shared
toolchain from dl.google.com zips.

- Engine decision: [ADR-001](docs/ADR-001-engine-decision.md) —
  **GeckoView via Mozilla Android Components (prebuilt Mozilla AARs),
  Android first; desktop later on the same Gecko family.**
- Research summary: [docs/research-summary.md](docs/research-summary.md)
  (full sourced report archived with the project records).
- Architecture: [docs/architecture.md](docs/architecture.md)
- Roadmap, build requirements, risks: [docs/roadmap.md](docs/roadmap.md)

## Relationship to PRIVACY X (the suite)

PRIVACY X (the Flutter security suite in `~/workspace/privacy-x/`) already
ships a hardened WebView browser (PrivateX v2, APK v1.1.0). Private X is
its real-engine successor as a standalone browser. The suite browser
remains supported and honestly labelled; Private X replaces its engine,
not its promises: every claim must be implemented and tested, nothing is
faked, and limitations are stated in the product.

## Licence

GPL-3.0 (product). Components: GeckoView/Android Components (MPL-2.0),
EasyList/EasyPrivacy (elected under GPL-3.0), filter and privacy code
written for this project. DuckDuckGo's Tracker Data Set (CC BY-NC-SA) is
**not** used. See docs/research-summary.md §Licensing.
