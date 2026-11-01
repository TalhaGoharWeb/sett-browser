# Security policy — SETT Browser

## Reporting a vulnerability

Email **muhaqqiqcreates@gmail.com** with "SETT security" in the subject.
Please include steps to reproduce and the affected version (see
Settings → About in the app, or the release tag). There is no bug
bounty — SETT is a zero-budget, single-developer, GPL-3.0 project —
but reports are taken seriously, credited in release notes unless you
prefer otherwise, and fixed in the next release.

Do not open a public issue for an exploitable vulnerability before a
fix is available.

## Supported versions

Only the **latest release** on GitHub Releases is supported. SETT has
no auto-updater; installing the newest release APK (same signing key,
installs as an update) is the update path.

## Security posture (summary)

- Engine: Mozilla GeckoView (Android Components 159) — renderer
  sandboxing and site isolation are the engine's, kept intact; the app
  never disables them.
- HTTPS-Only mode on; Enhanced Tracking Protection (Standard/Strict,
  user-selectable); Global Privacy Control sent; tracking URL
  parameters stripped by the engine; cookies partitioned (dFPI).
- No telemetry, no analytics, no accounts, no remote logging. The
  crash recorder is local-only and never transmitted.
- No Safe Browsing / phishing service is integrated: Mozilla's API-key
  terms for embedders are unverified, and SETT does not send browsing
  data to third parties. This is a deliberate, documented gap — see
  `docs/SECURITY_AUDIT_INITIAL.md`.
- Signing: all releases are signed with the project keystore
  (certificate SHA-256
  `0a60cd9f3270e0929c1c0866aa8544dcd10b2a561ccca8bed9d4cf39c44424f2`).
  Verify downloads against the SHA-256 sums in the release notes. The
  keystore and its password file are never committed to this
  repository (verified 2026-10-31: both return 404 on GitHub).

Full audit: `docs/SECURITY_AUDIT_INITIAL.md`. Known limitations:
`docs/TECHNICAL_DEBT.md`.
