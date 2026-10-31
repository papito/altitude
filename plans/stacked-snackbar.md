# Stacked snackbar: errors top center, notices bottom left

## Goals

- The snackbar shows up to three messages at a time instead of one. Errors stack at top center and stay until dismissed.
  Successes and warnings stack at bottom left and dismiss themselves.
- Every message paints above the modal hosts, the popover dialog and the map, and screen readers announce it, including
  while a modal is open.
- The three functions in `js/common/snackbar.js` keep their names and signatures, so no call site changes.

## Status

Not started.

## Decisions

| Concern | Decision |
|---|---|
| API | `showSuccessSnackBar`, `showWarningSnackBar`, `showErrorSnackBar` (one `message` argument, plain text) stay the module's only exports for producers. No `notify` window event: every producer is a JS module, and the server sends no messages through `HX-Trigger` |
| Placement | Two stacks. Errors: top center, at the snackbar's existing 50px top offset, newest at the bottom of the stack. Successes and warnings ("notices"): bottom left, above the footer, newest at the bottom, the stack growing upward |
| Lifetime | Error: until dismissed. Warning: 6 s. Success: 3 s. Every message has a dismiss button. The notices' timers pause while the pointer is over the stack or focus is inside it, and while the document is hidden; they resume with the time they had left |
| Capacity | Three messages per stack. A message whose type and text equal a message already in its stack (and not leaving) is not added: that message's count goes up, shown as `×n`, and its timer restarts. Otherwise, a fourth message removes the oldest one in its stack. A dismissed message leaves no trace: the same message arriving later is shown again |
| Rendering | An Alpine store, `Const.state.snackbar`, holds the messages; `html_common` renders each stack with `x-for`. Text is bound with `x-text`, so a message stays plain text, as `textContent` keeps it today |
| Layering | Each stack is a `popover="manual"` element, so it is in the top layer: above `#modalContainer` and `#imageDetailModalContainer` (`z-index: 4`) and Leaflet's panes with no z-index of its own. A stack is shown when its first message arrives and hidden when its last one leaves. A top-layer element opened after it (the popover dialog, a context menu, a select's picker) paints above it while both are open |
| Light dismiss | Clicking a message while the popover dialog is open is an outside click, so the browser closes the dialog. Accepted: what is lost is the dialog's typed input, and the error stays on screen |
| Announcements | Separate from the visible stacks. A visually hidden `role="status"` region exists in three places: at the top level of `html_common`, and inside the `.modal-box` of each of the two trapped hosts. `x-trap.inert` sets `aria-hidden` on every sibling of the trapped box and of its ancestors up to `body` (`setInert` in `alpine-focus.esm.js`), and both hosts carry `aria-modal="true"`, so only a region inside the active box is heard while a modal is open. A message is written into the region of the active host (`getActiveModalHost()`: `general`, `assetDetail`), otherwise into the top-level one; the popover dialog has no trap and uses the top-level region. An error is written as a `<p role="alert">` (assertive), a notice as a plain `<p>` (polite); a merged repeat is written again with its count, so its text changes and it is announced again |
| Type signal | Each message carries an icon (`fa-check-circle`, `fa-exclamation-triangle`, `fa-times-circle`, `aria-hidden`) next to its color, so the type is not conveyed by color alone |
| Motion | Enter: `@starting-style` (opacity 0 and a `--space-4` offset from the stack's edge), the pattern `#popoverDialog` uses. Exit: a dismissed message is marked `leaving`, fades out, and is removed from the store 150 ms later. All motion sits under `prefers-reduced-motion: no-preference`; with reduced motion a dismissed message is removed at once |
| Width | `min(var(--snackbar-width), 100vw - 2 * var(--viewport-gap))`, so a stack fits a phone. A message wraps instead of having a fixed height; `--snackbar-height` goes |
| Settings page | `settings.scala.html` includes `html_common` but starts no Alpine today. `settings-page.js` starts it: it registers the focus plugin (`x-trap` on the modal hosts), the `modal` store (bound by the modal hosts and the popover title) and the `snackbar` store, then calls `Alpine.start()`. The shared nav and the Settings form carry no Alpine bindings |

## Current implementation

- `js/common/snackbar.js`: one `#snackbar` element. `_showSnackbar` replaces its text and type class, adds `show`, and
  clears it after 3 s. A new message replaces the one shown, and the timer restarts.
- `views/includes/html_common.scala.html`: `<div id="snackbar"></div>` before the three dialog hosts. On the index page
  `html_common` sits inside `main`, on Settings and the style guide at the end of `body`.
- `static/css/core.css`: `#snackbar` is fixed at top 50px, centered with `left: 50%` and a negative half
  `--snackbar-width` margin, `z-index: 1` and `--snackbar-height` tall. `.show` makes it visible with `@keyframes fadein`
  (used nowhere else), which animates `bottom` on an element placed by `top`, so only its opacity changes. It has no
  `role` or `aria-live`.
- Alpine starts on the index page and the style guide (`initApp()` → `FrontendApp.start()` →
  `initializeFrontendStores()` → `Alpine.start()`). `js/settings-page.js` registers the htmx request rule and the save
  listener only.
- No `.visually-hidden` class exists.

## Implementation units

### 1. The store and its producers (`js/common/snackbar.js`, `js/constants.js`)

- `Const.state.snackbar = "snackbar"`.
- `createSnackbarStore()`, exported, returns the reactive store:
  - `items`: `{ id, type, message, count, leaving }`, in arrival order.
  - `errors` and `notices`: getters filtering `items` by stack (`error` vs `success`/`warning`).
  - `add(type, message)`: merges a repeat or appends, enforces the cap of three per stack, starts the timer, announces.
  - `dismiss(id)`: marks the message `leaving`, clears its timer, and removes it after the exit transition.
  - `pause()` / `resume()`: for the notices stack, called by its pointer and focus events.
  - `syncStack(el, messages)`: called from each stack's `x-effect`. It shows the popover when `messages` has an entry
    and hides it when it is empty, guarded by `el.matches(":popover-open")`.
- Timers live in a module-level `Map` keyed by message id (`{ handle, remaining, startedAt }`), outside the reactive
  object. One `visibilitychange` listener pauses and resumes them.
- The announcer: `announce(type, text)` picks the region (see **Announcements**), empties it, and appends one `<p>`.
- `_showSnackbar` calls `Alpine.store(Const.state.snackbar)?.add(type, message)`. With no store on the page it logs
  with `console.warn` and returns, as `modal.js` tolerates a missing store.
- `htmx.find` is no longer used.

### 2. Registration

- `js/stores/app-stores.js`: `Alpine.store(Const.state.snackbar, createSnackbarStore())` in
  `initializeFrontendStores()`, next to the `modal` store.
- `js/settings-page.js`: imports Alpine, the focus plugin, `createModalStore` and `createSnackbarStore`; registers the
  plugin and both stores; calls `Alpine.start()`. Its doc comment says what the page starts and why.

### 3. Markup (`views/includes/html_common.scala.html`)

- `#snackbar` is replaced by two stacks, each with an `x-data` root:

  ```html
  <div id="snackbarErrors" class="snackbar-stack" popover="manual" x-data
       x-effect="$store.snackbar.syncStack($el, $store.snackbar.errors)">
      <template x-for="item in $store.snackbar.errors" :key="item.id">
          <div class="snackbar" :class="[item.type, { leaving: item.leaving }]">
              <i class="fas" :class="…icon for item.type…" aria-hidden="true"></i>
              <span class="snackbar-message" x-text="item.message"></span>
              <span class="snackbar-count" x-show="item.count > 1" x-text="'×' + item.count"></span>
              <button type="button" class="snackbar-dismiss" aria-label="Dismiss"
                      x-on:click="$store.snackbar.dismiss(item.id)">&times;</button>
          </div>
      </template>
  </div>
  ```

  `#snackbarNotices` is the same over `$store.snackbar.notices`, with `x-on:pointerenter`, `x-on:pointerleave`,
  `x-on:focusin` and `x-on:focusout` (the last only when `relatedTarget` is outside the stack) calling `pause()` /
  `resume()`. Event bindings use `x-on:`, never Twirl's `@@` shorthand.
- One `<div class="visually-hidden" role="status" data-app-snackbar-announcer></div>` at the top level, and one inside
  each trapped `.modal-box`, as a sibling of `#modalContent` and `#imageDetailModalContent`, which are replaced on
  every open.
- The template's comment states the two stacks, their layer and the announcer rule.

### 4. CSS (`static/css/core.css`)

- `#snackbar`, its type rules and `@keyframes fadein` go. `--snackbar-height` goes; `--snackbar-width` stays.
- `.snackbar-stack` resets the popover UA style (`inset: auto`, `margin: 0`, `padding: 0`, `border: 0`,
  `background: none`, `overflow: visible`, `color: inherit`), has `pointer-events: none` so the gaps between messages
  do not catch clicks, and sets the width above. `display: flex` (column, `gap: var(--space-3)`) is set only on
  `:popover-open`, so the UA's `display: none` for a closed popover is not overridden.
- `#snackbarErrors`: `top: 50px` (the literal AGENTS.md already allows), centered with `inset-inline: 0;
  margin-inline: auto`.
- `#snackbarNotices`: `left: var(--viewport-gap)`, `bottom: calc(var(--footer-height) + var(--viewport-gap))`.
- `.snackbar`: `pointer-events: auto`, a grid of icon, message, count and dismiss button, `--radius`, `--soft-shadow`,
  the border and text colors of today's snackbar, and the background tokens `--success-background-color`,
  `--warning-background-color` and `--error-background-color`. `.snackbar-count` is a badge (`--radius-small`).
- Motion as in **Decisions**, inside `@media (prefers-reduced-motion: no-preference)`.
- `.visually-hidden` in UTILITIES: `position: absolute; width: 1px; height: 1px; overflow: hidden;
  clip-path: inset(50%); white-space: nowrap`.

### 5. Style guide (`views/style_guide.scala.html`)

- The Feedback section's text describes the two stacks, the lifetimes, the cap and the merge. Its three buttons stay
  as they are.

### 6. Docs

- `views/AGENTS.md`:
  - the **Snackbar** paragraph: placement, lifetimes, cap, merge, layer, announcements and the light-dismiss consequence
    (it no longer says "auto-dismisses after 3 s");
  - line 14: the `.visually-hidden` 1px sizes join the allowed literals;
  - **Alpine.js Stores**: the `snackbar` store;
  - **Settings screen**: Settings starts Alpine with the focus plugin and the `modal` and `snackbar` stores;
  - line 42 and the file tables (`snackbar.js`, `html_common`, `settings-page.js`): `#snackbar` becomes the two stacks
    and the announcers.
- `docs/READINESS.md` (Not usable on a phone): the snackbar's `min-width` is no longer one of the overflows.
- `docs/reference/alpine-components/notifications.md`, **Notes for Altitude**: the snackbar uses this shape, with the
  differences: called through functions rather than an event, two stacks in the top layer, announcements routed into
  the active modal.

## Verification

- `make lint` (it rewrites files: check that `make watch` is still running afterwards).
- In the browser on the dev server (`mill altitude.resources` for JS; a restart for the CSS bundle):
  - each style guide button: placement, icon, color, timing (3 s, 6 s, error stays), dismiss button;
  - four distinct successes in a row, sent from a script (the style guide's buttons each send one fixed text, which
    merges): the first leaves when the fourth arrives; the same success twice: one message, `×2`, timer restarted;
  - an error dismissed and then sent again: it is shown again;
  - the notices' timer pauses on hover and resumes with the time it had left;
  - an error while the general modal is open (a dialog submit with the server stopped) and while asset detail is open
    (an asset whose detail fails to load): the message is above the scrim and the backdrop; its region inside the
    `.modal-box` holds the text (`aria-hidden` is not set on it);
  - an error while the popover dialog is open: above it; dismissing it closes the dialog;
  - Settings: a save shows a success at bottom left; a failed save (server stopped) shows an error at top center; the
    console shows no Alpine errors;
  - reduced motion (emulated): no entry offset, no fade, a dismissed message disappears at once;
  - a 360px-wide `body` (the viewport cannot be resized here): the stacks fit with the gutter.
- `make test-controllers`: the pages that include `html_common` still render.
