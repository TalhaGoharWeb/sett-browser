# PRIVATE X

**Private. Secure. In Control.**

A free, open-source, privacy-first web browser — real engine, no accounts,
no telemetry, no paid features. Developer: Muhammad Talha Bin Farid.

## Status (2026-10-31)

**v0.1.0 SHIPPED** — developer approved the architecture the same day
("you choose the best options possible and build"). Debug and release
builds compile in the project VM; the signed release APKs are published
as GitHub Release assets (repo: TalhaGoharWeb/private-x, tag v0.1.0,
marked pre-release). Verified from the built files: badging
`app.privatex.browser` v0.1.0, minSdk 26, targetSdk 36; apksigner
signature matches the project keystore (SHA-256
0a60cd9f…24f2); arm64 APK 234 MB contains libxul.so (Gecko 159).
**Not yet device-tested** — first phone run is the acceptance step.

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
