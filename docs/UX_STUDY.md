# UX Study — How Mature Open-Source Android Browsers Handle Browser UX

Study for **Sett** (GeckoView + Mozilla Android Components 159 embedder, Kotlin).
Compiled 2026-10-31 by reading the public sources of Fenix / Firefox for Android
(`mozilla-firefox/firefox`, `mobile/android/fenix/`), Firefox Focus
(`mobile/android/focus-android/`), Android Components
(`mobile/android/android-components/`), and DuckDuckGo Android
(`duckduckgo/Android`) through the GitHub API.

Clean-room note: this document describes **patterns, behaviour and file
locations only**. No code from these projects is copied into Sett; API/class
names are cited so the pattern can be re-implemented (or, for Android
Components, consumed as the same MPL-2.0 libraries Sett already depends on).

Sett context for every "Apply to Sett?" line: Sett serves its start page from a
`RequestInterceptor` (`HomeInterceptor` in `Components.kt`) for `sett://home`,
keeps a single-activity chrome (bottom floating bar in
`activity_browser.xml`), and already carries the v0.3.1 fixes (legacy-URL
alias, root insets, address-bar home aliases).

---

## 1. Start page / home integrity

**Fenix — the home screen is native UI, not a web page.**
Firefox renders its homepage as an Android Fragment
(`mobile/android/fenix/app/src/main/java/org/mozilla/fenix/home/HomeFragment.kt`)
living next to the browser fragment. There is no internal web URL whose load
can fail, hang, or be orphaned by a rename: pressing "new tab" shows the home
fragment; selecting a tab swaps in the browser fragment. The engine is only
ever asked to load real web content (plus error pages, §3).

**DuckDuckGo — the new-tab page is a native view supplied by a provider.**
DDG's new tab page lives in its own package
(`app/src/main/java/com/duckduckgo/app/browser/newtab/`) with
`NewTabPageView.kt`, `NewTabPageViewModel.kt`, and a plugin-style provider,
`NewTabPageProvider.kt` (`RealNewTabPageProvider.provideNewTabPageVersion()`
returns a `Flow<NewTabPagePlugin>`; `NewTabPage.getView(...)` builds the
native view). Same lesson as Fenix: the page users see most is native
Android UI, so it renders instantly and cannot come back blank.

**Session restore is verbatim state — which is why internal URLs are forever.**
Fenix wires Android Components' session persistence in
`mobile/android/fenix/app/src/main/java/org/mozilla/fenix/components/Core.kt`:
`val sessionStorage: SessionStorage by lazyMonitored { SessionStorage(context,
engine, crashReporter, applicationScope) }`. AC `SessionStorage`
(feature-session) serialises `BrowserState` — every tab's URL and engine
state — to disk and restores it at startup. Restored tabs come back with
their URLs byte-for-byte, across updates and renames. Consequence both
Mozilla apps live with: any internal URL scheme an old version ever put in a
tab must keep working in every later version. Both interceptors therefore
treat internal-URL handling as a permanent compatibility surface
(`onLoadRequest` overrides in Fenix's `AppRequestInterceptor.kt` and Focus's
`AppContentInterceptor.kt`, §3).

*Apply to Sett?* **Adapt.** Sett's interceptor-served start page works, but
the structural fix is Fenix/DDG's: render the home screen natively (a view
layer over the engine container, shown when the selected tab is the home
tab) so it can never be blank. Short of that, keep the v0.3.1 rule as
permanent policy: every internal URL ever shipped (`privatex://home`,
`sett://home`, trailing-slash variants) is served forever via
`Components.isHomeUrl`, because session restore will keep resurrecting them.

## 2. System bars & keyboard (insets / IME)

**Fenix — a named, lifecycle-aware feature owns home-screen insets.**
`mobile/android/fenix/app/src/main/java/org/mozilla/fenix/home/HomepageEdgeToEdgeFeature.kt`
is a `LifecycleAwareFeature` whose whole job is "window insets, background
styling, and toolbar visibility during the home screen lifecycle". Concrete
habits worth copying:

- It is **gated by a setting** (`settings.enableHomepageEdgeToEdgeBackgroundFeature`)
  so the behaviour can be rolled out/killed without a code change.
- It tracks the **status-bar height from `WindowInsetsCompat`** and positions
  a dedicated background view behind the system bars instead of padding the
  whole hierarchy.
- It observes **toolbar state** (`BrowserToolbarStore`, via `flowScoped` on
  `Dispatchers.Main` with `distinctUntilChanged`) to restyle the status-bar
  background when the toolbar enters edit (keyboard-up) mode.
- **First frame correctness:** `start()` applies the last known wallpaper
  *before* subscribing to updates, with a code comment stating the reason —
  the first drawn frame must already look right.

**DuckDuckGo — one shared edge-to-edge handler for the whole app.**
DDG centralises inset handling in a single utility,
`common/common-utils/src/main/java/com/duckduckgo/common/utils/edgetoedge/EdgeToEdgeHandler.kt`
(built on `setOnApplyWindowInsetsListener`), reused by activities, dialogs
(`BottomSheetDialogExtensions.kt` in their design system) and onboarding
screens — not re-implemented per screen.

**Focus** keeps the browser surface simple (single session, toolbar +
engine view) and puts its effort into the interceptor layer instead (§3);
no per-screen inset feature exists there.

*Apply to Sett?* **Adopt the shape, keep the v0.3.1 mechanism.** Sett's root
padding (`systemBars`, bottom = max(nav, IME)) is the right primitive for a
single-activity browser. Borrow two refinements: (1) DDG's single-handler
rule — keep all inset logic in one place in `BrowserActivity`, never let it
spread into dialogs/tray code (bottom sheets and the tabs tray should reuse
it); (2) Fenix's first-frame rule — apply insets/theme synchronously in
`onCreate` before the first layout pass, and restyle the status-bar strip
when the address bar gains focus (keyboard up) so the bar area never flashes
a mismatched colour.

## 3. Failed-load UX — never a blank white page

**Fenix — every load error becomes a styled, engine-rendered error page.**
`mobile/android/fenix/app/src/main/java/org/mozilla/fenix/AppRequestInterceptor.kt`
overrides `onErrorRequest(...)` (the AC `RequestInterceptor` hook Sett
already uses for its home page). For each failure it:

- maps the AC `ErrorType` to a **risk level** (`getRiskLevel(...)` →
  internal `RiskLevel` enum: Low / Medium / High; e.g.
  `ERROR_UNKNOWN_PROTOCOL` → Low, `ERROR_PORT_BLOCKED` → Medium,
  `ERROR_HARMFULADDON_URI` → High),
- builds a data-URI page with AC's error-page factory (below) using the
  HTML/JS asset matching that risk level (bundled assets
  `app/src/main/assets/lowMediumErrorPages.js` and `highRiskErrorPages.js`),
- returns `RequestInterceptor.ErrorResponse(errorPageUri)`, so the **engine
  itself renders the error page in the tab** — address bar, history and
  reload all keep working, and the page offers retry.

**Android Components does the heavy lifting.**
`mobile/android/android-components/components/browser/errorpages/src/main/java/mozilla/components/browser/errorpages/ErrorPages.kt`
is an `object ErrorPages` with `createUrlEncodedErrorPage(...)` (plus an
`ErrorStringsProvider` / `DefaultErrorStringsProvider` for localised copy and
helpers such as `archiveUrlFor(errorType, uri)` that build a web-archive
fallback link for dead hosts). Any AC embedder — Sett included — can add the
`browser-errorpages` module and get the same pages Fenix ships.

**Firefox Focus — same pattern, 97 lines.**
`mobile/android/focus-android/app/src/main/java/org/mozilla/focus/engine/AppContentInterceptor.kt`
overrides both `onLoadRequest` and `onErrorRequest`; the error path is just
`ErrorPages.createUrlEncodedErrorPage(...)` wrapped in
`RequestInterceptor.ErrorResponse(...)`. Proof the pattern is cheap for a
small browser, not a Fenix-only luxury.

**DuckDuckGo — errors are native state, not web pages (WebView world).**
DDG's `app/src/main/java/com/duckduckgo/app/browser/BrowserWebViewClient.kt`
(~1,200 lines) classifies failures itself — including an `SSLErrorType` enum
(EXPIRED / GENERIC / UNTRUSTED_HOST / WRONG_HOST) and renderer
crash/killed events — and reports them through `WebViewClientListener.kt`
into `BrowserTabViewModel.kt`, which drives a native error presentation.
That fits a WebView app that owns its client callbacks; it is **not** the
model to copy on GeckoView, where the interceptor hook exists precisely for
this.

*Apply to Sett?* **Adopt — this is the highest-value item in this study.**
Sett currently has no `onErrorRequest` override, so any failed load (offline,
dead host, unknown scheme such as a restored `privatex:` URL the alias
misses) can strand the user on GeckoView's blank surface — the exact failure
in the v0.3.0 device screenshots. Add the `browser-errorpages` AC module,
override `onErrorRequest` in the existing interceptor, classify risk at
Fenix's granularity only if needed (a single Low/Medium template is enough
to start), and return `ErrorResponse(ErrorPages.createUrlEncodedErrorPage(...))`.
Unknown-scheme errors for legacy internal URLs should additionally be
covered by the §1 alias so they never reach the error path at all.

## 4. Omnibar → search flow

**Classification is a dedicated, testable converter (DuckDuckGo).**
DDG isolates input classification in
`app/src/main/java/com/duckduckgo/app/browser/omnibar/QueryUrlConverter.kt`
(`convertQueryToUrl(...)`, private `convertUri(...)`), with a sibling
`OmnibarEntryConverter.kt` and a dedicated unit test
(`app/src/test/java/com/duckduckgo/app/browser/QueryUrlConverterTest.kt`).
The converter — not the activity — decides "navigable URL vs search query",
builds the search URL when it is a query, and even folds in remotely
delivered configuration (it caches the downloaded privacy config and
re-checks on `onPrivacyConfigDownloaded()`). Callers are the tab layer
(`DefaultTabManager.kt`, `BrowserTabViewModel.kt`), so every entry point
(omnibar submit, intents, new tabs) classifies identically.

**Suggestions are a provider stack, individually gated (Fenix / AC).**
Fenix builds its suggestion list in
`mobile/android/fenix/app/src/main/java/org/mozilla/fenix/search/awesomebar/SearchSuggestionsProvidersBuilder.kt`
from AC awesomebar providers (`feature/awesomebar`): local ones
(`HistoryStorageSuggestionProvider`, `BookmarksStorageSuggestionProvider`,
`SessionSuggestionProvider`, `RecentSearchSuggestionsProvider`) plus the
network one, `mozilla.components.feature.awesomebar.provider.SearchSuggestionProvider`,
which fetches from the current search engine's suggestion endpoint. The
network provider is only included when the user's settings allow search
suggestions (and is separately gated for private mode) — local providers
keep working when it is off.

**Focus — suggestions exist but are an opt-in overlay.**
Focus wires the same AC provider in
`mobile/android/focus-android/app/src/main/java/org/mozilla/focus/searchsuggestions/ui/SearchOverlay.kt`
(`SearchSuggestionProvider(..., mode = Mode.MULTIPLE_SUGGESTIONS)`) driven by
its own `SearchSuggestionsViewModel` / `State` in the `searchsuggestions`
package — a self-contained, settings-gated feature rather than a default.

**DDG suggestions** follow the same shape: autocomplete state
(`AutoCompleteViewState.kt`) is produced by `BrowserTabViewModel.kt`, which
is also where the user's autocomplete on/off preference is consulted before
anything is fetched from DDG's autocomplete service.

*Apply to Sett?* **Adopt the converter shape; adapt suggestions.** Sett's
`AddressResolver` (in `SettingsStore.kt`) already is the single classifier —
keep it that way (all entry points funnel through it; the v0.3.1 home-alias
rules live there too) and give it a unit-testable surface like DDG's.
Suggestions: Sett currently sends keystrokes nowhere, which is the
privacy-correct default. If suggestions are ever added, copy the
Fenix/Focus policy, not just the code path: network suggestions **off by
default**, a visible settings toggle, never in private tabs, using the
chosen engine's own suggestion endpoint via AC's `SearchSuggestionProvider`.

## 5. Tabs UX essentials

**Undo is middleware, not UI bookkeeping (Android Components).**
`mobile/android/android-components/components/feature/session/src/main/java/mozilla/components/feature/session/middleware/undo/UndoMiddleware.kt`:
when tabs are removed, the middleware snapshots them as
`RecoverableTab`s (with their original index) into undo state under a tag,
then auto-clears them after `clearAfterMillis` (**default 5,000 ms** —
comment notes a LENGTH_LONG Snackbar is 2,750 ms, so state outlives the
snackbar). Dispatching `UndoAction.RestoreRecoverableTabs` re-inserts the
tabs at their original positions and restores the previous selection.

**Fenix's UI is a thin layer over that middleware.**
`mobile/android/fenix/app/src/main/java/org/mozilla/fenix/utils/Undo.kt`
provides `showUndoSnackbar(...)`; it is invoked from the tab tray's
controller (`tabstray/controller/TabManagerController.kt`, UI in
`tabstray/ui/TabManagementFragment.kt`) and from
`home/TabsCleanupFeature.kt` for automatically closed tabs. The tray itself
is store-driven (`BrowserState` tabs + a tray store) with a normal/private
segmented switch; private tabs are just `TabSessionState`s with
`content.private = true`, filtered by the switch. Tab titles come from
`TabSessionState.content.title` (falling back to the URL in the tray) —
internal screens like the homepage never appear as tabs at all (§1), so no
special-casing of internal-page titles is needed in the tray.

**DuckDuckGo** manages its tab list in
`app/src/main/java/com/duckduckgo/app/browser/tabs/DefaultTabManager.kt`
and has **no private-tab mode** — every tab is private by product design —
so its tray is a single grid. **Focus** has no tabs whatsoever (single
session); its "tab tray" lessons don't transfer.

*Apply to Sett?* **Adopt UndoMiddleware.** Sett already builds on
`BrowserStore`; adding AC's `UndoMiddleware` (feature-session, already a
transitive part of the stack) plus a snackbar in the tabs tray — model the
plumbing on Fenix's `Undo.kt`/`TabManagerController.kt` split (middleware
owns recovery, UI only shows the snackbar) — gives close-tab undo with the
industry-standard ~5 s window. Keep Sett's existing tray title rule
(home URLs → "Sett — Start") but drive it from `Components.isHomeUrl` so
legacy-URL tabs title correctly too; no other internal pages exist.

## 6. Responsiveness — keeping chrome instant

**First frame is treated as a feature (Fenix).**
Beyond the wallpaper detail in §2, `HomepageEdgeToEdgeFeature` observes
state with `flowScoped(dispatcher = Dispatchers.Main)` +
`distinctUntilChanged`, i.e. UI work is confined to the main thread and
de-duplicated; nothing polls. The same discipline shows in Fenix's
store/middleware architecture generally: state changes are computed in
middleware off the UI path and rendered as diffs.

**Chrome motion follows the page's own scroll stream (DuckDuckGo).**
DDG's omnibar show/hide is a `CoordinatorLayout` behaviour,
`app/src/main/java/com/duckduckgo/app/browser/omnibar/TopAppBarBehavior.kt`
(an `AppBarLayout.Behavior` overriding `onStartNestedScroll`,
`onNestedPreScroll`, touch interception) — the bar moves *with* the web
content's nested scrolling instead of running its own animation loop or
scroll listener, which is what keeps it feeling attached to the page.
Fenix exposes the equivalent hook for its toolbar in
`components/toolbar/gestures/ToolbarVerticalGesturesHandler.kt`.

**Work is pushed off the chrome's critical path (AC/Fenix).**
The undo snapshot in §5 is taken in middleware with the expiry handled by a
delayed dispatch, not by UI timers; suggestion providers in §4 fetch
asynchronously and stream results into the awesomebar as they arrive, so
typing never blocks on the network provider.

*Apply to Sett?* **Adapt two cheap wins.** (1) Keep Sett's chrome
interactions synchronous and allocation-light: tab-count/address updates
already arrive via the store flow — collect them with
`distinctUntilChanged`-style de-duplication so the pill and badge only
redraw on real changes. (2) If a hide-on-scroll chrome is ever wanted, copy
DDG's nested-scroll behaviour pattern rather than bolting a scroll listener
onto the `EngineView`. Skip Fenix's wallpaper/first-frame machinery — Sett's
start page is a single static render; the applicable residue is just "apply
theme + insets before first layout", already noted in §2.

---

## Priority order for Sett (from this study)

1. **§3 error pages** — add `browser-errorpages` + `onErrorRequest`. This is
   the structural guarantee behind "never a blank page", complementing the
   §1 alias that already shipped in v0.3.1.
2. **§1 native home (longer term)** — the only change that makes the start
   page un-failable by construction, as in Fenix and DDG.
3. **§5 UndoMiddleware + tray snackbar** — small, fully supported by the AC
   version already in the tree.
4. **§2/§6 refinements** — single-owner inset handling (done in v0.3.1;
   keep it that way) and flow de-duplication for chrome updates.
5. **§4 suggestions** — only if ever requested, and only behind the
   Fenix/Focus policy (off by default, toggle, off in private tabs).
