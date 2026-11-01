# SETT design system (redesign Phase 2, v0.16.0)

One source of truth: `ui/SettTheme.kt` (tokens) + `ui/SettUi.kt`
(shared components). Screens take every colour/size from these —
local colour constants are a defect (audit DS-2) and are being
migrated away screen by screen.

## Palettes

| Slot | Dark (night burrow) | Light (warm paper) |
|---|---|---|
| bg | #050B18 deep midnight navy | #F7F4EE warm off-white |
| surface | #0B1626 | #FFFFFF |
| elevated | #10233A | #FFFFFF |
| accent | #2DD4BF refined teal | #0F766E deep teal |
| accent2 | #38BDF8 cool blue | #0369A1 |
| onAccent | #04241F | #FFFFFF |
| text | #E6F1F5 soft white | #0B2436 dark navy |
| dim | #8FB3C7 muted slate | #52606D neutral grey |
| faint | #7E97A8 (≥4.5:1 on bg) | #8494A3 |
| warn | #FBBF24 warm amber | #B45309 |
| error | #F87171 restrained red | #B91C1C |

Mode: Settings → Appearance → Theme: Match system / Dark / Light,
persisted (`theme_mode`); platform dialog theme follows via
`Theme.PrivateX` / `Theme.PrivateX.Light`. Density: Comfortable /
Compact (`ui_density`), driving shared-component padding.

## Type & spacing

Title 20sp · Section 16sp · Body 14sp · Small 13sp · Caption 12.5sp.
Page padding 18dp, section gap 16dp, card radius 16dp, pill radius
24dp, minimum touch target 48dp. System fonts (legible sans with
full Arabic/Urdu shaping and fallback — no bundled font can beat
the platform's Arabic coverage, and none is worth its APK weight).

## Components (SettUi)

`page(title)`, `section()`, `body()`, `field()`, `actionButton()`
(48dp min height), `primaryButton()` (accent pill), `card()`,
`emptyState()`. Every migrated screen gets the palette, the type
scale and the states from these.

## Migration status

- v0.16.0: Shield, Spaces, Library Search migrated (proof set).
- v0.18.0: BrowserActivity chrome + tab tray.
- v0.19.0: **all activities migrated** — Library, Vault, Extensions,
  Network, Utilities, Developer, Research, Study, AI, SETT Search.
  Palette getters replaced every local colour constant; the only
  `Color.parseColor` left in the codebase is inside
  BrowserActivity's `roundedBg` helper itself.
