# Box selection as another trigger for existing multi-select

## Original goals

Let the user select thumbnails in the results grid by dragging a rectangle, using Viselect to
draw the box, collect candidates, and autoscroll, while the existing selection code (the
`selectable` component's `toggle()` and the `selectedAssets` store's `reset()`) does every
selection change, and only on release.

- Dragging from empty grid space creates a replacement selection.
- Shift-dragging anywhere in the grid, thumbnails included, adds to the selection. Shift is read
  when the button goes down; the box starts after 10px of movement.
- Any overlap with the thumbnail image counts; metadata and padding do not.
- Nothing visible changes during the drag except the rectangle. Shrinking it drops candidates.
- Release commits. Escape, window blur, page hiding, or grid removal discards the box.
- Clicks and Shift-clicks keep their behavior.
- Vendor `@viselect/vanilla` 3.9.0 unmodified with its MIT license and provenance; autoscroll
  within 32px of the vertical edges of `#content`; continue through the infinite-scroll funnel,
  refreshing candidates after appended cards initialize; keep thumbnail geometry through the
  offscreen placeholder swap; report a failed page through the snackbar; style the rectangle with
  `--result-border`; update agent documentation.

## Status

Implemented and verified in Chromium on 2026-09-06; see **Verification results**. Firefox and
Safari have not been checked. Not committed.

## Confirmed decisions

- A plain drag may start anywhere inside `#assets` except over a thumbnail; a cell's metadata
  strip counts as empty space, so native text selection of metadata is given up during the press.
- Thumbnail geometry survives the placeholder swap by keeping the loaded image's rendered size as
  inline `width`/`height` in the lazy-load observer (JS only; no template change).
- The library is vendored byte-identical as `static/js/lib/viselect.esm.js` (renamed from
  `dist/viselect.mjs` so it is served as JavaScript) with `viselect.LICENSE` and a README row.
- The post-drag click swallow was extracted from `dragon-drop.js` into
  `search-results/click-suppression.js` and is shared by asset drags and box gestures.

## Implementation notes

- `static/js/search-results/box-selection.js`: one controller per displayed grid, created and
  replaced from `fragments/search-results.js`. Viselect runs with `startAreas: #assets`,
  `boundaries: #content`, `intersect: touch`, touch/range/single-tap features off. Its `cancel()`
  is silent, which is what every discard path uses; `stop` fires only on a real release.
- Viselect re-evaluates the rectangle, and starts its scroll loop, only from a `mousemove`. Its
  loop ends for good when the container cannot scroll further, so a pointer resting at the bottom
  edge while continuous scroll appends a page would stall; a pointer whose last movement lands in
  the edge band would never start scrolling at all. The controller replays the last pointer
  position as a synthetic `mousemove` after a page settles, an image loads, the panel resizes, and
  after any frame that finds the pointer in an edge band with room to scroll. This is the
  version-bound adapter; the library file is unchanged.
- interact.js ignores `actionChecker` inside `draggable()` options; it is set with the
  Interactable's `actionChecker()` method so a Shift-drag over a thumbnail never starts an asset drag.
- A failed search page is reported by `FrontendApp.handleAfterRequest` (`isSearchRequest`), the
  same path as other failed HTMX requests; htmx's `ajax` promise resolves on 4xx/5xx, so a
  `.catch` would not have seen it.
- Dev server: `make compile` does not refresh the served copy of `static/`; `mill altitude.resources`
  does, without a restart.

## Verification results

Driven with synthesized mouse and pointer events in the Chrome MCP tab (168 assets, 50 per page),
reading state from the DOM and the `selectedAssets` store:

- Replacement box over three partially covered thumbnails: nothing selected during the drag, three
  selected on release, the rectangle removed. Shift-box starting over a thumbnail added two more
  without toggling the existing three off. Shift released mid-drag stayed additive. Shrinking to one
  thumbnail selected one. An empty box cleared the selection. A press without movement did nothing.
- Escape and window blur discarded the box with the selection unchanged; the click on the later
  release was swallowed. The click right after a completed box was swallowed, and a Shift-click
  after the suppression window toggled as before. Plain click still opens asset detail.
- Pointer parked 10px above the bottom edge: autoscroll ran through pages 2, 3, and 4 to the end
  (50 to 168 cells) with the rectangle's origin preserved, no selection during the drag, 28
  selected on release matching an independent hit test, and no change afterwards. The reverse
  gesture from the bottom over 50 placeholder-swapped rows (all still 200px wide) selected 132,
  again matching the hit test.
- Replacing the grid through the sort control during a drag left no rectangle and no selection;
  a box on the new grid worked.
- Shift + pointer drag over a thumbnail started no asset drag; a plain pointer drag still did and
  cleaned up.
- Search pages answered 400 and 500 showed the error snackbar with the status; the grid stayed intact.
- `make compile`, eslint, and prettier pass.
