# ADR-001 — Browser engine selection

Status: APPROVED (developer, 2026-10-31 — "you choose the best options possible and build… firstly for android")
Pinned versions at approval: Android Components 159.0 ↔ GeckoView 159.0.20261022150525 (org.mozilla.geckoview:geckoview-omni), Kotlin 2.4.20, compileSdk 36 — verified from maven.mozilla.org metadata + the browser-engine-gecko POM on 2026-10-31.
Deciders: Project research phase (master assignment §4, §24 Tasks 4–6)

## Decision

**Android: GeckoView, embedded via Mozilla Android Components, consuming
Mozilla's prebuilt GeckoView Maven AARs** — a thin Fenix-style embedder
(the IronFox/Fennec model), not a from-source Gecko fork.

**Desktop (later phase): same Gecko family** — Firefox base with a
LibreWolf-style configuration + small patch set. Revisit only if the
Android product is established.

**Rejected:** Chromium patch-set fork (Cromite model), Brave-layer fork
(brave-core over chromium/src), WebView as the final engine (remains only
inside the PRIVACY X suite app, honestly labelled).

## Evidence (all sourced in research-summary.md)

1. **Build cost decides it.** Chromium from source needs ≥100 GB disk,
   >16 GB RAM recommended and hours-long builds; Chrome stable now ships
   on a **2-week cadence** (since Chrome 153, Sep 2026), so a patch-set
   maintainer rebases ~300+ patches (Cromite's list) 26 times a year.
   Cromite sustains this only as a near-full-time effort with a
   **self-hosted** runner — incompatible with zero budget and hosted CI's
   6-hour job cap. The Gecko path compiles no C++: GeckoView arrives as a
   Maven AAR (`org.mozilla.geckoview:geckoview-*`) and the browser is a
   Gradle/Kotlin build that fits free public-repo CI.
2. **Privacy is inherited, not re-invented.** Gecko's Enhanced Tracking
   Protection (incl. Total Cookie Protection / dynamic first-party
   isolation), bounce-tracking protection, query stripping and cookie
   banner handling are engine-level and exposed through GeckoView's
   `ContentBlocking` settings — a small embedder gets protections that a
   Chromium patch-set must build and maintain itself.
3. **Extensions are real.** GeckoView's `WebExtensionController` installs
   persistent WebExtensions with native messaging — uBlock Origin can run
   as what it is (GPL-3.0 extension) instead of being ported.
4. **Fingerprinting resistance exists to enable.** `privacy.
   resistFingerprinting` + letterboxing (Tor/Mullvad lineage, in-tree)
   can be switched on and measured; stock Chromium offers no equivalent
   a fork can simply enable.
5. **Licensing fits.** GeckoView/Android Components are MPL-2.0
   (GPL-compatible); a renamed fork shipping Mozilla's AARs is the
   intended distribution path — no Firefox branding may be used, and
   Private X uses its own name and signing key (the Iceraven debug-key
   precedent is the anti-example).
6. **Solo precedent exists.** IronFox (Mull's successor) and Fennec
   F-Droid maintain Fenix-family browsers with tiny teams. No solo
   precedent exists for a Brave-layer fork, and Brave itself is a
   company-scale codebase.

## Known costs of this choice (accepted, with mitigations)

- **Site compatibility**: Gecko is the minority engine; some sites
  degrade. Mitigation: ETP Standard default (Strict optional), per-site
  shields-down, honest breakage reporting in-app.
- **One runtime per process**: GeckoView's GeckoRuntime initialises once
  per process — Private Spaces are built on contextual identities
  (cookieStoreId) + per-space storage management, not separate runtimes.
  The architecture states this ceiling plainly (docs/architecture.md).
- **Update lag risk**: the Fenix family has a documented worst case
  (Fennec F-Droid lagged ~2 months in autumn 2024). Mitigation: ship
  first via our own signed GitHub Releases with an in-app update check;
  F-Droid is a secondary channel. Release policy (assignment §20): if we
  fall behind a critical upstream security fix, we say so in the release
  notes and block "production" labelling.
- **Mozilla-controlled keys**: Safe Browsing / location service keys are
  Mozilla's; what a renamed embedder may use needs one direct Mozilla
  source confirmed before shipping those features (research open item).
  Until then those features stay off rather than borrowed.

## Consequences

- Phase 3 becomes: scaffold the embedder on Android Components, consume
  the pinned GeckoView AAR, verify a signed release build in CI.
- All Phase 5–7 privacy work is configuration + original code on top of
  engine APIs — no engine patch maintenance burden.
- The PRIVACY X suite's filter engine (pure Dart) is *not* ported into
  the engine path; on Gecko, uBlock Origin (user-installable) + ETP do
  content blocking. Our own engine work stays with the suite.
