# Sett accessibility audit — redesign Phase 9 (v0.22.0)

Method: code-level audit + computed WCAG contrast ratios from the
design tokens (guarded by `ContrastTest`) + the touch-target rules
in the design system. **No emulator or device exists in the build
environment**, so the TalkBack walk-through and large-font device
pass remain the user's acceptance step — stated plainly, as
everywhere in this program.

## Fixed in this phase

1. **LIGHT faint contrast 2.84:1 → 4.52:1.** `LIGHT.faint` was
   #8494A3 on the warm off-white background — unreadable for the
   captions and metadata lines that use it. Now #5F7285 (4.52:1 on
   bg, 4.96:1 on white surfaces). A JVM contrast guard now asserts
   ≥ 4.5:1 for text/dim/faint on bg and surface, and for
   accent/accent2/warn/error on bg, in both palettes.
2. **Density is now fully wired.** The Settings "Lists: Compact"
   option previously affected only body-text padding in `SettUi`;
   page, section and card spacing now follow it too. Compact
   tightens spacing only — touch targets never shrink.
3. **Home-page category chips enlarged** (CSS padding 10px → 13px
   vertical, ≈44px tall); shortcut tiles were already ≥84px.
4. **TalkBack**: search-result and scholarly cards in SETT Search
   now carry a single content description (title + host / origin +
   year) instead of reading as three separate fragments. The
   search input on the home page has an aria-label and `dir="auto"`
   for right-to-left queries.

## Verified state (code-level)

- **Touch targets**: browser chrome buttons and menu rows are
  48dp minimum (Phases 2/4); `SettUi.actionButton` enforces
  `TOUCH_MIN = 48` on every migrated screen.
- **Contrast (computed)**: DARK text 17.1, dim 8.8, faint 6.4 on
  bg; LIGHT text 14.5, dim 5.9, faint 4.5 on bg; all signal tokens
  ≥ 4.5 on bg in both palettes.
- **Content descriptions**: chrome shield (state-aware: HTTPS /
  not secure / local page), tabs (with count), reload, menu (XML);
  menu rows are text-labelled.
- **Font scaling**: all native text is set in sp and follows the
  system font-size setting. The home page is web content rendered
  by GeckoView, which applies its own text scaling.
- **Reduced motion**: Sett has no decorative animation in native
  screens; the home page uses none either. Nothing to disable.

## Known limits (honest)

- **RTL**: native layouts are built programmatically with
  symmetric left/right padding, so an RTL locale does not break
  layout, but there is no start/end mirroring to verify and no
  RTL-specific layout pass has been done. Web content gets
  `dir="auto"` on the home search field. A full RTL audit needs a
  device pass in an RTL locale.
- **TalkBack order** on the long tool screens (Utilities,
  Developer) follows visual order because views are added in
  reading order; a screen-reader-only walk-through on device is
  still the real test.
- **Colour-only signals**: the shield icon pairs colour with a
  changing content description (secure / not secure / local), so
  state is not carried by colour alone.
