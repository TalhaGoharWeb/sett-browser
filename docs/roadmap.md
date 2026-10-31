# Private X — Roadmap, build requirements, risks (Phase 2)

Maps the master assignment §21 phases onto the ADR-001 engine choice.
Phases 1–2 are complete (research + this architecture), pending the
developer's approval gate at the end of Phase 2.

## Phases

| Phase | Work | Exit evidence |
|---|---|---|
| 3. Foundation | Repo scaffold (Kotlin/Gradle), Android Components wired, pinned GeckoView AAR, own signing keystore created + backed up, CI build on GitHub Actions (public repo = free) producing a signed release APK | CI-signed APK installs and opens a page |
| 4. Core browser | Navigation, tabs + tray + undo, session restore, bookmarks (local), history (local, clearable), settings, downloads manager (pause/resume/SHA-256), find, reader | Feature checklist run on-device by developer |
| 5. Privacy engine | ETP Standard/Strict mapping, per-site shields-down, dashboard with measured counts only, fingerprinting profiles (Balanced/Strict/Custom), param stripping verified, GPC state shown | PrivacyTests-style self-check list recorded per profile |
| 6. Security engine | HTTPS-Only enforced, cert-error hard stops, permission default-deny, sandbox = Gecko's own (verified present, never disabled), download safety flow | Test pages: badssl suite results recorded |
| 7. Advanced | VaultX password manager + autofill service, WebExtension manager (uBO), Private Spaces (contextual identities), Orbot hookup, DoH choices | Each feature demoed on-device; spaces isolation test (cookies don't cross) recorded |
| 8. Sync & productivity | Encrypted backup export/import (no server); reading list; page notes (local) | Restore-from-backup test passes |
| 9. QA | Compatibility pass (banking/education/video/WebRTC apps), PrivacyTests categories, network-activity audit (every external connection listed with purpose) | Published QA report with the release |
| 10. Production | Signed releases, update manifest, docs, F-Droid submission, vulnerability-disclosure page | First public release |

Desktop (Gecko-family, LibreWolf model) is a separate roadmap after the
Android product reaches Phase 10.

## Build requirements (the zero-spend envelope)

- Dev machine: any PC that runs Android Studio/Gradle (the current VM
  can also build it — Gradle/Kotlin only, no C++ compilation).
- CI: GitHub Actions on a public repo (free, unlimited standard runners;
  6 h job cap is ample for this build class — the Fenix family builds
  this way).
- Signing: one release keystore, generated once, backed up offline;
  losing it means losing update continuity — treated like the PRIVACY X
  keystore.
- Costs: $0 mandatory. Optional later: a domain for the project site.

## Risks & maintenance requirements (Task 9)

1. **Upstream cadence**: Firefox ships ~4-week majors; GeckoView bumps
   are small but Android Components APIs occasionally break — budget a
   fixed weekly maintenance slot; pin versions, never float.
2. **Update lag = security risk**: the family's documented worst case is
   ~2 months (Fennec, autumn 2024). Our mitigation is process, not hope:
   own GitHub Releases first, F-Droid second, lag disclosed in notes.
3. **Mozilla policy shifts**: AAR availability and key-gated services
   (Safe Browsing/location) are Mozilla's call. Features depending on
   Mozilla keys stay off until their terms are confirmed in writing
   from a Mozilla source (open research item).
4. **Small-userbase fingerprinting**: RFP's uniformity argument weakens
   at small scale — marketing must never claim Tor-level resistance.
5. **Solo-maintainer bus factor**: the design deliberately minimises
   owned surface (no engine patches at all) so the project survives
   pauses; a handover note lives in the repo from Phase 3.
6. **Scope discipline**: extensions/Tor/sync are the classic solo-project
   sinkholes — they sit in Phases 7–8, after a shippable core exists.
