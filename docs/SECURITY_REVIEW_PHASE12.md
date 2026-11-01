# Security review — Phase 12 (2026-11-01)

A re-run of the Phase 0 audit (`SECURITY_AUDIT_INITIAL.md`) against
the code as shipped through v0.15.0. Each original finding is
revisited with its current state; new attack surface added by
Phases 4–12 is reviewed after. Nothing here is a guarantee — it is
the honest state of the work, including what remains open.

## Original findings, revisited

| ID | Finding | State at v0.15.0 |
|----|---------|------------------|
| S1 | No update mechanism (High) | **Mitigated.** Menu → "Check for updates" compares the installed version against the project's own GitHub Releases feed (the signed channel every release ships through) and opens the release page. Install is still manual — without a store there is no silent update path, and none is faked. Residual risk: a user who never checks still runs stale. |
| S2 | No Safe Browsing (Medium) | **Unchanged, deliberate.** Mozilla's service keys are not reused by a renamed embedder. The About screen claims no phishing protection. Open gap, documented in the README's honesty notes. |
| S3 | No permission/prompt UI (Medium) | **Closed in code (v0.4.1).** `feature-prompts` is wired: site permission requests, dialogs and file choosers have real consent UI, with runtime permissions requested only on site demand. Device verification is still the user's (no emulator exists in the build environment). |
| S4 | R8/minification off (Low) | **Unchanged, deliberate.** Open-source app; the cost is size, not secrecy. Recorded in TECHNICAL_DEBT. |
| S5 | AAR-metadata gate disabled (Low) | **Unchanged.** AC 159 declares minCompileSdk 37, Sett compiles at 36. Must be re-examined at every Android Components bump; noted in DEPENDENCY_INVENTORY. |
| S6 | Vendored-artifact integrity (Low) | **Partially improved.** Built APKs are verified from the file and their SHA-256s published; the bytes GitHub serves are re-downloaded and hash-compared before any delivery is announced (this caught process failures twice). Input-side checksums at vendor time are still not pinned — residual. |
| S7 | Keystore handling (Low/process) | **Closed as verified.** Phase 1 confirmed via the GitHub API that the keystore, `key.properties`, vendored maven repo and release APKs are absent from the published repository; `.gitignore` covers them. One disclosed incident: build logs under `hidden_files/` were briefly tracked in one push, reviewed line-by-line (no credential values), then untracked and ignored. |
| S8 | No certificate detail UI (Low) | **Partially addressed.** Sett Shield shows the engine-reported connection state for the current site. A full certificate-chain viewer is not exposed by the embedder API Sett uses; not faked. |
| S9 | Private-tab disk persistence (Info) | **STILL UNVERIFIED.** Whether AC SessionStorage writes private-tab state to disk could not be established from the build environment; it needs a device test. Sett's claims are worded to avoid asserting it. This is the review's top remaining verification item. |
| S10 | Download safety (Info) | **Mostly unchanged.** Downloads are engine-handled with a Library list; there is no in-app dangerous-filetype warning. Android's own sideload-install prompts still apply. |
| S11 | No disclosure page (Info) | **Closed (Phase 1).** `SECURITY.md` is published: contact, scope, and the signing-certificate fingerprint for verifying downloads. |

## New surface since the audit

- **Vault (Phase 4).** Argon2id (ops 3, 256 MiB) → XChaCha20-Poly1305
  record encryption with a random vault key; recovery-key slot;
  biometric unlock via an AndroidKeyStore AES-GCM key requiring
  user authentication and invalidated by new enrolments. The crypto
  runs in JVM tests against real libsodium (3/3). Password-derived
  key encoding is deterministic (UTF-16LE code units) by design.
- **Extensions (Phase 4).** Installed from user-supplied .xpi only;
  updates that seek NEW permissions are refused by policy — a
  deliberate anti-bait-and-switch stance. Extension metadata
  permissions are shown from the engine-reported data.
- **Network Protection (Phase 4).** Writes real Gecko preferences
  (DoH mode/URI, proxy). A manual proxy is a MITM position by
  definition; the screen labels the Orbot preset as "not the Tor
  Browser, not anonymity". No proxy is ever set by default.
- **AI Workspace (Phase 10).** Off until configured; the provider
  key exists only inside the encrypted vault; private tabs and
  internal pages are refused; every send passes a consent dialog
  (host, model, exact character count); page text is capped at
  12 000 characters. The remaining risk is inherent to the feature:
  text the user approves goes to a provider the user chose, under
  that provider's terms. Localhost/LAN endpoints may run keyless —
  that exception is shown in the UI copy.
- **On-device stores (Phases 8/9/11).** Research, study and index
  data live in app-private storage as JSON; the library index is
  memory-only, rebuilt per open, never persisted or transmitted.
- **Update checker (Phase 12).** One HTTPS GET to api.github.com;
  detection only. A compromised feed could at worst point the user
  at a release page — installation still requires the APK to be
  signed with the project key, which Android enforces on update.

## Dependencies added after the audit (licences)

| Dependency | Use | Licence |
|---|---|---|
| lazysodium-android 5.1.0 (+ JNA 5.12.1) | Vault cryptography (libsodium) | MPL-2.0 (JNA: Apache-2.0/LGPL-2.1 dual) |
| androidx.biometric 1.1.0 | Vault biometric unlock | Apache-2.0 |
| ZXing core 3.5.3 | QR make/read (Utilities) | Apache-2.0 |
| pdfbox-android 2.0.27.0 (+ BouncyCastle 1.72) | PDF tools | Apache-2.0 (BC: MIT-style) |
| org.json (test only) | JVM test runtime | JSON licence |

## Release gate (every release since Phase 1)

`verify_release.sh` on the built file: package/label/version/SDK
levels, apksigner v2+v3, project-certificate match, zipalign 16K,
zip integrity, and per-phase DEX presence checks (42 at v0.15.0);
full JVM unit suite green (56 at v0.15.0); served bytes re-downloaded
and SHA-256-compared before the delivery email is sent. This gate
caught a real defect during Phase 10 (an unsigned APK produced by a
build-daemon failure was rejected and never published).

## Still open after Phase 12

1. **S9** — private-tab disk persistence: device test required.
2. **Phone acceptance of every release** — the build environment has
   no emulator; all on-device behaviour awaits the maintainer's
   phone checks (per-release lists are in the delivery emails).
3. **F-Droid submission** — metadata is prepared in-repo
   (`fastlane/metadata/android/en-US/`); the fdroiddata merge
   request itself needs the maintainer's GitLab account.
4. **S2/S4/S5/S10** — standing, deliberate, documented above.
