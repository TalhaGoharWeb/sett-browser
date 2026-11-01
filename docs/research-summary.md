# Private X — Phase 1 research summary

Condensed from the full sourced research report (61 KB, archived with the
project records; every load-bearing claim carries its source there) plus a
first-hand repository status check run against the GitHub/GitLab APIs on
2026-10-31.

## Task 1 — Repository status (first-hand API check, 2026-10-31)

| Project | Status | Licence |
|---|---|---|
| Chromium (chromium/chromium) | Active (pushed same day) | BSD-3 |
| Brave core / adblock-rust | Active | MPL-2.0 |
| Helium (imputnet/helium) | Active | GPL-3.0 |
| ungoogled-chromium | Active | BSD-3 |
| Cromite (uazo) | Active, single maintainer | GPL-3.0 |
| Vanadium (GrapheneOS) | Active | Mixed (Chromium base) |
| mozilla-firefox/firefox | Active — Android lives here now (`mobile/android/`) | MPL-2.0 (mixed tree) |
| mozilla-mobile/firefox-android | **ARCHIVED 2024-06-17** | MPL-2.0 |
| Tor Browser / Mullvad Browser | Active (GitLab, ESR-based branches) | MPL-2.0 |
| LibreWolf | Active — home is **Codeberg** since Aug 2023; GitLab tree is a stale mirror | MPL-2.0 |
| uBlock Origin | Active | GPL-3.0 |
| PrivacyTests | Active | MIT |
| dnscrypt-proxy | Active | ISC |

Fork vital signs: **Mull is discontinued** (Dec 2024, DivestOS shutdown);
its successor **IronFox** is active. **Iceraven** ships every ~1–3 weeks
but signs binaries with a **debug key** — an anti-example. **Fennec
F-Droid** is current (158.0.0) but had a ~2-month known-vulnerability lag
in autumn 2024.

## Tasks 2–3 — Strongest implementations, mapped to sources

- **Content blocking**: Brave's adblock-rust (MPL-2.0 library; network +
  cosmetic + scriptlets; `src/filters/cosmetic.rs`) is the strongest
  *embeddable* engine. On Gecko it is unnecessary: engine ETP +
  user-installed uBlock Origin cover it. uBlock Origin itself is an
  extension — reuse it *as* an extension, never port its code.
- **Tracking protection**: Firefox ETP + Total Cookie Protection
  (dynamic first-party isolation) are engine-level in Gecko and exposed
  to embedders via GeckoView's `ContentBlocking` (AntiTracking categories
  AD/ANALYTIC/CONTENT/CRYPTOMINING/FINGERPRINTING/STP/EMAIL; DEFAULT and
  STRICT levels; bounce-tracking protection; query stripping; cookie
  banners).
- **Fingerprinting**: Tor/Mullvad resistFingerprinting (RFP) + letterboxing
  live in Gecko (`toolkit/components/resistfingerprinting`, prefs
  `privacy.resistFingerprinting*`, 200×100 letterbox stepping) plus a
  font whitelist. Caveat that shapes our claims: RFP's protection comes
  from *uniformity across a large user base*; a small browser enabling it
  gains less. PrivacyTests confirms the pattern: default Firefox fails
  most fingerprinting tests because RFP ships off; Tor/Mullvad/LibreWolf
  pass by turning it on.
- **Chromium-side privacy**: Cromite = ~300+ patches (incl. an Eyeo-based
  adblock patch GrapheneOS publicly criticises, and a multiple-
  fingerprinting-mitigations patch). Vanadium's hardening is real but it
  is not offered as a reusable stock-Android base, and building it is a
  full Chromium build.
- **Patch management models**: ungoogled = GNU Quilt series
  (`patches/core` + `patches/extra`); Helium = layered quilt merging
  ungoogled's series (desktop-only, beta); Brave = brave-core mounted at
  `src/brave` with `chromium_src/` overrides (company-scale); LibreWolf =
  mostly a config file on Firefox (least work of all — the desktop model
  ADR-001 adopts for later).

## Task 4 — Chromium vs Gecko (summary; ADR-001 has the decision)

Chromium wins site compatibility and nothing else that matters here.
Gecko wins build cost (prebuilt AAR vs ≥100 GB source builds), release
survival (Chrome stable is now on a **2-week** cadence since Sep 2026 —
a patch-set maintainer rebases 26×/year; the Gecko embedder mostly bumps
a Maven version), inherited privacy (ETP/dFPI/RFP), real extensions, and
licensing fit. Full scored evidence table is in the research report.

## Tasks 5–6 — Licensing & reuse assessment

- Compatible in one GPL-3.0 product: BSD-3 (Chromium/ungoogled) ✔,
  MPL-2.0 (GeckoView, Android Components, adblock-rust, Brave) ✔,
  GPL-3.0 (uBO as extension, Cromite patches per-patch check) ✔,
  EasyList/EasyPrivacy — dual-licensed GPL-3.0 **or** CC BY-SA 3.0;
  **elect GPL-3.0** ✔. **DuckDuckGo TDS (CC BY-NC-SA 4.0) ✘ — never
  bundle.** Orbot/Tor daemon: BSD-3 ✔ for optional Tor support.
- Trademarks: no Firefox/Chrome/Brave names or logos in a fork. A
  renamed embedder shipping Mozilla's GeckoView AARs is the intended
  path; our own signing key is generated once and guarded permanently.
- API keys: Chromium's Google API keys are per-entity and not for
  distribution (Safe Browsing, geolocation) — Chromium forks ship
  without them. Mozilla-key reuse by a renamed embedder has one open
  verification item (see Limitations) and stays OFF until confirmed.

## Technical limitations (honest list)

- PrivacyTests' exact numeric pass totals for the current run could not
  be reliably extracted; rankings are used qualitatively only.
- No supported multi-runtime GeckoView API exists (once per process);
  containers exist as cookieStoreId/contextual identities, and there is
  no Mozilla containers UI component to reuse.
- Which Vanadium patches require GrapheneOS OS features could not be
  fully verified (moot under ADR-001).
- Exact Fenix build times on named hardware and Cromite's precise
  current lag rest on third-party sources; treated as indicative.
- Brave's "−75% adblock memory" figure for its 2026 FlatBuffers rewrite
  is a vendor claim, not independently measured.
