# PRIVATE X

**Private. Secure. In Control.**

A free, open-source, privacy-first web browser — real engine, no accounts,
no telemetry, no paid features. Developer: Muhammad Talha Bin Farid.

## Status (2026-10-31)

Phase 1 (research) and Phase 2 (architecture) are complete and **awaiting
the developer's approval** before implementation begins, per the master
assignment §24. No engine code has been written yet.

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
