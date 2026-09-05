# Folder menu native popover

## Original goals

Convert the folder context menu into a native HTML popover coordinated with
Alpine.js. The menu should appear directly under the meatball control of the
folder being clicked. Use the grill-me interview to resolve the design and
produce an implementation plan before changing application code.

## Status

Design interview complete on 2026-09-05. Implemented on 2026-09-05; see
"Implementation status and validation results" at the end for what was built,
what was verified, and the two deviations from the tasks below.

## Confirmed decisions

- Use the browser's native HTML popover with Alpine.js. Do not introduce the
  `@alpinejs/ui` plugin described in the supplied headless-popover reference.
- Align the menu's left edge with the clicked meatball control and place it
  directly below. Flip above when there is insufficient room below, keeping the
  actions visible. The user accepted this exception to the below-control rule.
- Arrange actions in a vertical list. Preserve their existing order: Add folder,
  Rename, Delete; the root folder continues to offer only Add folder.
- Create the menu actions alongside the folder tree so opening a menu is
  immediate and makes no menu-content request. Selecting an action still loads
  its existing dialog through HTMX.
- Close the open menu when the folder panel scrolls or resizes. Do not keep
  tracking its control while the panel moves.
- Every Escape keypress in the page dismisses any open folder menu, regardless
  of which control has keyboard focus.
- A mouse click anywhere outside the menu panel dismisses it, including blank
  or non-focusable areas and regions outside the folder explorer. Clicking its
  own meatball toggles it closed; clicking another folder's meatball closes the
  previous menu and opens the newly requested one.

## Existing behavior and implementation

- `altitude/static/js/common/folder-tree.js` builds both root and non-root
  folder controls. Each trigger is currently an anchor with the stable ID
  `folderMenuCtrl-<folderId>`. It requests menu content through HTMX and targets
  `menu-<folderId>`.
- `altitude/views/htmx/folder_context_menu.scala.html` supplies Add folder,
  Rename, and Delete actions. The repository's root folder has only Add folder.
  All actions request an existing modal; they do not immediately mutate folders.
- `altitude/views/htmx/folders.scala.html` styles the menu as a hidden flex row
  within the folder tree. Showing it currently consumes layout space.
  The tree's meatball controls are aligned on the left at every depth, with a
  separate trace cell providing indentation before each non-root folder icon.
- `altitude/static/js/listeners/htmx-folders.js` handles menu toggling and closes
  other menus before a content request; it shows the requested menu when the
  response arrives. `altitude/static/js/models/folder.js` owns the related DOM
  show, close, and expansion checks. Those responsibilities must be reconciled
  with native popover state rather than retained as a second visibility owner.
- The explorer scrolls and clips horizontal overflow, as specified in
  `altitude/views/index.scala.html`. Positioning must work inside that layout.
- Folder dialogs already return focus using `folderMenuCtrl-<folderId>` selectors;
  delete returns it to the parent folder. Tree rebuilds preserve focused controls
  by ID. Keep those addressing conventions in the migration.
- `altitude/static/js/global.js` handles Escape for modals and background inline
  editors. Popover dismissal must fit that priority without also cancelling a
  background edit.
- Alpine 3.13.10 and its focus plugin are vendored and registered in
  `altitude/static/js/app.js`. Component registration lives in
  `altitude/static/js/alpine/components/index.js`.
- The shared stylesheet is `altitude/static/css/core.css`. Reuse existing colors
  and spacing; place any new shared design variables under `:root`.

## Design defaults

These follow the selected native popover and existing folder-dialog behavior;
they are implementation defaults rather than additional user-confirmed choices.

- Keep one folder menu open at a time.
- Use real buttons for the meatball and each action. Keep the existing trigger
  IDs and provide a folder-specific accessible name. Use normal Tab navigation
  through actions, with Enter/Space activation and no focus trap. Close when
  focus leaves the trigger and panel; Escape returns focus to the trigger.
- Close the menu as an action requests its dialog, so it cannot remain above
  the dialog. Keep modal request failure reporting and the existing focus return
  selectors. Dismissal must not cancel the action's HTMX request.
- Preserve folder expansion, navigation restrictions, drag-and-drop, current
  colors and spacing, and the aligned meatball controls and indentation traces.
- Rebuilding or removing the folder tree discards open menus and their event
  listeners. A hidden or removed trigger must not leave a visible floating menu.

## Implementation tasks

One reviewable implementation unit is sufficient. Ship the new menu and removal
of its previous visibility and loading owners together; splitting those changes
would leave competing behavior or unused code.

1. **Build native popovers and their actions with the tree.** Update
   `altitude/static/js/common/folder-tree.js` to create a `button type="button"`
   for each meatball, associate it with its uniquely identified
   `popover="auto"` panel using `popovertarget`, and populate the panel while
   rendering the tree. Share one action builder between root and non-root
   folders. Add folder passes `parentId`; Rename and Delete pass `id`. Each
   action declares its own HTMX request, `hx-target="#modalContent"`, and
   `hx-swap="innerHTML"`. Use DOM APIs and `textContent` for folder names and
   serialized values for `hx-vals`. Preserve the stable trigger and panel IDs.
   This removes menu-opening latency while retaining the established dialog
   and server-side mutation flows.

2. **Give Alpine the small amount of coordination native popovers need.** Add
   a focused component in `altitude/static/js/alpine/components/folder-menu.js`
   and register it through the existing component index. Keep its scope around
   the trigger and panel, outside ancestor panels, so folder nesting does not
   create nested popovers that can stay open together. Native popover state is
   the visibility authority: use `:popover-open` and native toggle events;
   do not add `x-show` or a separate writable `open` flag for visibility.
   Coordinate positioning, focus-out dismissal, action-to-dialog dismissal,
   and cleanup. Attach scroll/resize listeners and any observer only while
   a menu is open, removing them on close and component destruction. Observe
   the explorer's dimensions as well as window resize, since the Split.js
   divider changes panel size without resizing the window. Ignore scrolling
   inside the menu itself if its actions need an internal scrollbar.

   Integrate a small shared close helper with `altitude/static/js/global.js`:
   every document-level Escape dismisses an open folder menu, even when focus
   is outside the component. Ensure an early return from modal handling cannot
   leave a menu open. Retain the active modal's Escape behavior and let its
   focus restoration take precedence if a modal was also open; otherwise,
   return focus to the menu trigger. Consume Escape when either closes so it
   does not also broadcast to background inline editors.

   Use native auto-popover outside-click dismissal across the whole document;
   do not limit it to the explorer or rely on focus changes, which miss clicks
   on blank areas. The click that opens a menu must not immediately dismiss
   that same menu. Keep focus on the trigger when opening; Tab enters the
   action buttons. Outside clicks and focus-out must not steal focus from the
   newly chosen control. Closing for an action must leave its
   HTMX click/request intact and let the modal owner place and restore focus.
   Close a descendant menu when its ancestor folder collapses. Ensure menu
   controls do not start a folder drag; adjust the folder drag binding only as
   needed. These rules prevent menus from surviving their trigger or affecting
   unrelated keyboard and dialog interactions.

3. **Position and style the vertical panel without moving folder rows.** Update
   `altitude/views/htmx/folders.scala.html` and use `:root` variables in
   `altitude/static/css/core.css` for any new reusable values. Reset native
   popover centering (`margin` and `inset`), use fixed positioning in the top
   layer, and apply a one-column Grid or Flexbox layout only while
   `:popover-open`. Replace the old `.menu span` and anchor-trigger styling
   with appropriate button styling, retaining the current appearance and
   providing visible keyboard focus.

   Measure the actual trigger and rendered panel on opening, and apply the
   final coordinates before painting the visible panel. Default to the
   trigger's left edge and bottom edge. Flip above if the panel does not fit
   below; if neither side fits, choose the side with more space and constrain
   its height with internal scrolling. Constrain width and horizontal position
   to the viewport so actions remain reachable in narrow windows. Panel
   clipping must not restrict the native top-layer menu. Use measured
   coordinates rather than adding a positioning library or requiring CSS
   anchor positioning: the agreed close-on-scroll behavior needs no continuous
   position tracking. Comment the placement and overflow logic.

4. **Remove the obsolete menu delivery and visibility code.** Delete
   `altitude/views/htmx/folder_context_menu.scala.html` and the unused
   `/htmx/folder/r/:repoId/context-menu` action in
   `altitude/src/altitude/core/routes/web/partial/FolderActionController.scala`.
   Remove menu request interception and show-on-response logic from
   `altitude/static/js/listeners/htmx-folders.js`. Remove the now-empty
   folder-before-request delegation in `frontend-app.js` and its listener in
   `listeners/htmx-search.js` after verifying no callers remain. Preserve
   non-menu folder error handling, including folder-tab load failures, and the
   separate document-level modal request handlers.

   In `altitude/static/js/models/folder.js`, remove menu-content-based expansion
   checks, inline display mutations, and content clearing. Replace the collapse
   integration with the shared native-menu close helper. Remove unused imports
   and helpers only after checking references, including `clearInnerNodes` and
   `common/nodes.js` if nothing else uses them. Update the tree renderer's
   introductory comment, which currently promises HTMX menu loading. This
   leaves one visibility owner and no retired menu endpoint or dead helpers.

5. **Document the resulting behavior and verify it.** Update
   `altitude/views/AGENTS.md` and the relevant frontend overview in
   `altitude/AGENTS.md` to describe client-built folder actions, native popover
   ownership, and the revised listener responsibilities. Review the root
   `AGENTS.md` and retain its general guidance unless an actual instruction
   changes. Update the folder-menu note in
   `docs/agents/alpine-components/dropdown.md`; add a concise Altitude note to
   `headless-popover.md` explaining that this feature uses native popovers.
   Keep those component reference documents accurate about the components they
   describe. Preserve unrelated in-progress edits to documentation and completed
   plans. Record implementation status and actual validation results in this
   plan when execution finishes.

   Keep nontrivial lifecycle and placement comments current. Follow existing
   logging conventions: important folder mutations retain their INFO events;
   menu open/close diagnostics may use DEBUG with folder identity and dismissal
   reason. Do not add noisy pointer, scroll, or resize event logging.

## Verification requirements for implementation

Follow `altitude/AGENTS.md`: run `make compile` after application edits and use
browser verification for frontend changes. Do not run the server test suite for
frontend-only work. The planned Scala edit removes a frontend fragment renderer;
it does not change service behavior. Run the existing folder controller checks
when removing that route (`make test-controllers` is the existing supported
target) to verify the retained dialog endpoints. Do not add tests that merely
mirror markup or the deleted route. If execution expands into server behavior
beyond this frontend-facing cleanup, use the required integration-test
red-green-refactor cycle for that behavior. Do not use `make test`.

| Scenario | Expected result |
|---|---|
| Open a root or nested folder menu | Correct actions appear immediately in a vertical list; no menu-content request is sent |
| Open a menu with room below | Its left and top edges align with the trigger's left and bottom edges; surrounding folder rows do not move |
| Open near the bottom or in a narrow window | Menu flips above as needed; viewport constraints keep every action reachable |
| Scroll internally in a height-constrained menu | Actions scroll and the menu remains open |
| Open A, then B; activate B again | Only B remains open after switching; its second activation closes it |
| Click anywhere outside the menu, including blank space, a non-focusable element, the asset area, or navigation | Menu closes without stealing focus or cancelling the clicked control's normal action |
| Click the opening meatball, then click it again | Opening click leaves the menu open; the next click closes it |
| Click non-action padding inside the menu | Menu remains open |
| Tab out of the trigger/panel | Menu closes without stealing focus from the destination |
| Open with Enter/Space, then Tab through actions | Trigger and actions are usable with visible focus; no focus trap or hidden action receives focus |
| Press Escape with focus on the trigger, an action, or elsewhere in the page | Any open folder menu closes; dismissal does not depend on component-local focus |
| Press Escape while a menu and background inline edit exist | Menu closes, focus returns to its trigger, and the inline edit survives |
| Press Escape if a modal and folder menu are both open | Neither remains open; modal focus restoration takes precedence and background inline editors are unaffected |
| Scroll the explorer, resize the window, or resize the Split.js panel | Open menu closes |
| Collapse an ancestor, refresh the tree, or replace the folder tab | No orphan menu or surviving component listeners remain |
| Click or drag from the meatball and menu actions | Controls activate normally and do not initiate folder dragging |
| Drag folders and assets using their normal drag areas | Existing drag-and-drop behavior is preserved |
| Choose Add folder, Rename, or Delete | Menu closes and the correct existing dialog opens for that folder |
| Delay or fail a dialog request | Menu stays closed; existing modal request ownership and error reporting continue to work |
| Finish or cancel a folder dialog, including after a tree rebuild | Existing focus return remains correct; deleting a folder returns focus to its parent control |
| Use repository, triage, and recycle-bin views | Existing folder navigation restrictions and action availability are preserved |

Review nearby comments and update the applicable existing agent guides when the
implementation changes their documented behavior. No `CLAUDE.md`,
`ARCHITECTURE.md`, or domain glossary was found during exploration. Native
popover and meatball control are UI terms, so this decision does not establish
a new domain glossary entry.

## References

- [Supplied headless popover note](../docs/agents/alpine-components/headless-popover.md)
- [MDN: Using the Popover API](https://developer.mozilla.org/en-US/docs/Web/API/Popover_API/Using)
- [MDN: Using CSS anchor positioning](https://developer.mozilla.org/en-US/docs/Web/CSS/Guides/Anchor_positioning/Using)

## Implementation status and validation results

Implemented on 2026-09-05 as one unit; nothing is committed.

What was built:

- `altitude/static/js/common/folder-tree.js` builds each folder's ⋯ `button`
  (`popovertarget`, `aria-label="Actions for folder <name>"`) and its
  `popover="auto"` panel of action buttons inside the `.menu-ctrl` cell, sharing
  one action builder between root and non-root folders. IDs `folderMenuCtrl-<id>`
  and `menu-<id>` are unchanged.
- `altitude/static/js/alpine/components/folder-menu.js` (registered with
  `Alpine.data("folderMenu")` from the component index) places the panel, closes
  it on focus-out, action choice, scroll, window resize, and explorer resize
  (`ResizeObserver`), and detaches everything on close and destroy.
  `altitude/static/js/common/folder-menu.js` is the shared close helper used by
  `global.js` (Escape) and `models/folder.js` (ancestor collapse).
- `altitude/views/htmx/folders.scala.html` styles the panel as a fixed top-layer
  popover (`margin: 0; inset: auto`; `display: grid` only under `:popover-open`)
  and the trigger/actions as buttons; no new `:root` variables were needed.
- Removed: `folder_context_menu.scala.html`, the `context-menu` route, the folder
  before-request interception and show-on-response logic, the app-level
  `htmx:before:request` delegation, `Folder` menu helpers and `clearChildren`
  (unused), `common/nodes.js`, and `getRequestPathname` (its only caller went).
  `js/dragdrop/folders.js` excludes `.menu-ctrl` from folder dragging.
- Documentation: `altitude/views/AGENTS.md`, `altitude/AGENTS.md`, and the
  Altitude notes in `docs/agents/alpine-components/{dropdown,headless-popover,README}.md`.

Deviations from the tasks above:

- Placement runs in `beforetoggle`, not after the panel is rendered: the panel
  is given its open-state `display` inline for the duration of the measurement.
  Measuring after the show (in `toggle`, or in an animation frame) left a gap
  in which the panel was displayed at the browser's default position or was not
  yet focusable, and animation frames do not run in a hidden tab.
- `reloadFolderTree` no longer calls `Alpine.initTree`: Alpine's mutation
  observer already initializes the appended tree, and the explicit call
  initialized every component (including the pre-existing folder-name
  bindings) a second time, duplicating listeners.

Validation:

- `make compile`: passes. `make test-controllers`: 16 tests, all passed
  (the retained folder dialog endpoints included). Prettier and ESLint: clean.
- Browser (Chrome, dev server on :8080, repository with a Root folder, folder
  "1" with children): verified the scenarios below. Where placement depends on
  the viewport, `clientHeight`/`clientWidth` were overridden in the page for the
  measurement because the 3440px-wide window could not be resized.

| Scenario | Result |
|---|---|
| Open root / nested folder menu | Add folder only for root; Add folder, Rename, Delete for "1"; no request to `context-menu` |
| Room below | Panel left = trigger left, top = trigger bottom; folder rows unchanged |
| Near bottom / narrow | Flips above (bottom = trigger top); neither side: capped at 112px on the roomier side, top = 8px, internal scroll; narrow: width 54px, left clamped to 8px |
| Internal scroll | Panel stays open |
| Open A then B; B again | Only B open; second activation closes it |
| Outside click on blank explorer area | Closes; focus not stolen (`body`) |
| Own meatball twice | Opens, then closes (Enter and Space verified too) |
| Padding click inside panel | Stays open |
| Tab out | Delete → Tab closes menu, focus lands on the expand link |
| Enter/Tab through actions | Trigger keeps focus on open; actions get the browser's focus ring; no trap |
| Escape (focus on trigger, action, or elsewhere) | Closes, focus returns to trigger |
| Escape with a dialog open | Dialog closes and restores focus to the trigger; verified after Add/Rename/Delete |
| Scroll / window resize / Split.js divider drag | Closes (scroll and resize dispatched as events; divider dragged with the mouse) |
| Collapse ancestor / rebuild tree | Child menu closes on collapse; after `reloadFolderTree` no open panel, old component's listeners detached, new tree works |
| Drag from meatball | No drag, no move request, menu not opened |
| Drag from folder name to blank area | Drag runs and snaps back; no move request |
| Add folder / Rename / Delete | Menu closes; correct dialog opens (Rename field selected, Delete button host) |
| Triage view | Menu opens and works; folder-name navigation stays disabled |

Not exercised in the browser: a modal and a folder menu open at the same time
(the modal host covers the page and traps focus, so it cannot be reached
through the UI; `global.js` handles the ordering in code), a failed dialog
request (unchanged modal listener path), and the people inline editor with
Escape (Escape is consumed only when a menu or modal actually closed).
