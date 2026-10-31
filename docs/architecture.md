# Private X — Technical architecture (Phase 2)

Engine: **GeckoView via Mozilla Android Components**, prebuilt AARs
(ADR-001). Everything below names the component that provides it; "ours"
means original code written for Private X. No component is listed that
does not exist.

## Layer map

| Layer | Provided by | Notes |
|---|---|---|
| Rendering/engine | GeckoView (`GeckoRuntime` + `GeckoSession`, delegates: Navigation/Content/Permission/Prompt/History) | One runtime per process — a hard platform fact, stated in-app |
| Browser state | Android Components: browser-state, browser-session-storage | Tabs, undo-close, session restore are library features |
| Engine glue | browser-engine-gecko | Settings → `ContentBlocking` mapping is ours |
| Tabs UI, toolbar, awesomebar | feature-tabs / feature-toolbar / feature-awesomebar + our UI | Search/bookmark/history suggestions from local stores only |
| Downloads | feature-downloads + our manager UI | Pause/resume/retry, SHA-256 verify action, .apk warning (pattern proven in the suite app) |
| Find in page | feature-findinpage | — |
| Reader mode | feature-readerview | Mozilla's own component |
| Context menus / prompts | feature-contextmenu, feature-prompts | Permission prompts default-deny for camera/mic at app level too |
| WebExtensions | GeckoView `WebExtensionController` + feature-webextensions | uBO installable; extension manager lists permissions honestly |
| Passwords | **VaultX reuse**: the suite's libsodium vault core (Argon2id/XChaCha20) ported to Kotlin | Autofill via Android AutofillService API — Phase 7, never plaintext |
| Sync (optional, later) | Out of scope for v1: export/import of encrypted vault backup only | No server exists; nothing to leak to |

## Privacy configuration (engine settings we set, all testable)

- ETP: Standard default; Strict one tap away. `ContentBlocking`:
  anti-tracking categories on, bounce-tracking protection on,
  query-parameter stripping on, cookie-banner handling on.
- Cookies: Total Cookie Protection (dFPI) is engine default in current
  Gecko; third-party cookies blocked in Strict.
- HTTPS-Only Mode on; no silent downgrade; certificate errors hard-stop.
- WebRTC: `media.peerconnection.enabled` off in Strict fingerprinting
  profile; on in Balanced with mDNS host concealment (engine default).
- Fingerprinting profiles (assignment §6C): **Balanced** (engine
  defaults + ETP), **Strict** (RFP + letterboxing on, WebGL restricted
  prefs), **Custom** (per-pref toggles). Claims policy: the dashboard
  shows what is *on*, never an invented "fingerprint score"; the limits
  screen repeats the uniformity caveat (RFP protects best in a crowd).
- GPC: Gecko sends it with ETP — shown in the dashboard as measured
  engine behaviour, not our marketing.

## Private Spaces (assignment §7) — within the platform's real limits

Spaces (Personal / Work / Banking / Research / Temporary / custom) map
to Gecko **contextual identities** (`cookieStoreId`): separate cookie
jars and storage partitions per space, per-space permissions, and a
Temporary space whose storage is wiped on exit. What spaces are **not**:
separate engine processes or runtimes (impossible in one app process).
The UI says exactly this. No anonymity claim is attached to any space.

## Network (assignment §8)

- DoH: Gecko's built-in DNS-over-HTTPS (TRR) with provider choice
  (default off-or-provider per region choice at onboarding; modes shown).
- Proxy: system proxy + per-profile SOCKS via Gecko prefs where exposed.
- Optional Tor: **Orbot integration** (BSD-3) — route via Orbot's SOCKS
  when the user has it; we do not bundle a Tor daemon in v1 and never
  label proxying as "Tor anonymity".
- Leak self-tests (Phase 9): WebRTC/DNS checks run against public test
  pages in the QA plan; results published with the release notes.

## Updates (assignment §20)

- GeckoView version is a pinned Maven dependency; bumping it is a small
  PR + CI build — this is the security-update pipeline.
- Distribution: signed APK via GitHub Releases + in-app update check
  against a signed manifest on the same repo; F-Droid as second channel.
- Release policy: critical upstream fix → out-of-cycle release; if we
  lag, release notes must say so (no silent staleness).

## Identity & brand (assignment §16)

Name **Private X**, tagline **Private. Secure. In Control.** Midnight
navy + electric cyan (the suite's shield-eye icon carries over). Screens:
welcome/limits-first onboarding, home, tabs, privacy dashboard (measured
values only), downloads, passwords (vault), extensions, spaces, network,
settings, updates, about. No decorative dead buttons — enforced by the
ship gate: every control maps to a working component above.
