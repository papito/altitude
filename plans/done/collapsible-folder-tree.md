# Collapsible folder navigation tree

## Original goals

Add classic folder-tree expand/collapse controls immediately to the left of
each folder icon:

- Single-click expands only the next level.
- Double-click expands all descendants recursively.
- The root folder has no expand/collapse actions.
- Use Font Awesome square plus/minus icons if available.

Use the grill-me interview to resolve behavior and produce a plan before
changing application code.

## Status

Design interview complete on 2026-09-05; viewed-folder highlighting added to
the plan the same day. Implemented and verified in the browser on 2026-09-05;
see **Verification results** at the end.

Implementation notes that differ from or refine the task text:

- The viewed-scope marker (`alt-viewed-scope`) is set on the viewed folder's
  node only. Its descendants are DOM descendants, so one CSS rule colors the
  whole subtree; adding or moving a folder into or out of the subtree is
  therefore colored by position with no per-descendant marking.
- The results fragment carries `data-results-repo-id`, `data-results-view`,
  and `data-results-folder-id`; the server view name for the repository view
  is `default`, so the client treats every view other than triage and trash
  as having a folder scope.
- Second and later clicks of a pointer multi-click sequence are recognized by
  `event.detail` (1 for the first click, 0 for keyboard activation).
- Unused folder-model methods (`incrementNumOfChildren`,
  `decrementNumOfChildren`, `updateVisualState`, `addChild`, `remove`) were
  removed while the model was rewritten; the tree is rebuilt from JSON after
  every mutation, so nothing called them.

## Confirmed decisions

- Revised scope: reuse the existing `fa-folder-plus` / `fa-folder-minus`
  icons. Do not add separate square plus/minus icons, extra controls, or a new
  grid column. This supersedes the original request for controls to the left
  of each folder icon.
- A folder with no child folders keeps its plain `fa-folder` icon and has no
  expansion indicator, even if it contains assets. With no additional icon
  column, no empty alignment slot is needed.
- Reopening a collapsed folder reveals only its direct children. For example,
  after collapsing an expanded A → B → C branch, single-clicking A's plus shows
  B while C stays hidden. Collapsing a branch resets descendant expansion.
- Double-click toggles the whole branch based on its state before the gesture:
  when A starts collapsed, expand A and every descendant with children; when
  any levels below A are visible, collapse A and reset every descendant.
  A partially expanded branch therefore closes on double-click.
- Single-click on an expanded folder also closes its branch and resets
  descendant expansion. The distinction between single and double click is
  the depth revealed when opening a collapsed folder.
- The existing folder-plus/minus icon controls expansion only. Folder names
  navigate. Plain root and leaf folder icons also navigate, as they do today.
- Root stays expanded and has no expansion gestures.
- Green indicates the folder scope currently being viewed: the viewed folder
  and all its descendants have green folder icons because the results include
  assets from that entire subtree. Ancestors and unrelated branches keep their
  normal colors. This replaces the current expansion-driven green styling.
- Expanding or collapsing a branch never changes which folders are green.
  Navigating to another folder replaces the highlighted scope; highlights do
  not accumulate from previously viewed folders.

## Interaction contract

Here, a **branch** is a non-root folder with child folders and its descendants.
Its starting state is the state before the first click of the gesture.

| Target and starting state | Single-click | Double-click |
|---|---|---|
| Collapsed branch icon (`fa-folder-plus`) | Reveal direct children only | Expand all levels in this branch |
| Partly or fully expanded branch icon (`fa-folder-minus`) | Close the branch and reset descendants | Close the branch and reset descendants |
| Folder name | Navigate to the folder | No expansion action |
| Plain root or leaf icon (`fa-folder`) | Navigate to the folder | No expansion action |

Preserve existing defaults outside these changes: initial rendering shows the
root and its direct children, with non-root branches collapsed; mutation
reloads restore applicable expansion state; a full page reload starts fresh.
No persistent browser storage or server-side expansion preferences are added.
Navigation remains disabled in triage and trash views, while branch expansion
remains available for reaching drag-and-drop destinations.

### Viewed-folder highlighting

For the chain **1 → 2 → 3**, with 3 a leaf:

| Folder being viewed | Green folder icons | Ancestors outside the viewed scope |
|---|---|---|
| 3 | 3 only | 1 and 2 keep their normal colors |
| 2 | 2 and 3 | 1 keeps its normal color |
| 1 | 1, 2, and 3, plus any other descendants of 1 | Any ancestors of 1 keep their normal colors |
| Repository root | Root and every descendant | None |

These rules apply to plain, plus, and minus folder icons alike. A collapsed
descendant remains part of the viewed scope and appears green when revealed;
collapse does not clear its highlight or change the displayed assets. Green
represents the folder scope even when that folder has no matching assets.
Keep the existing color treatment on the icons and reuse
`var(--success-font-color)`.

Implementation defaults: update highlighting when the corresponding results
are displayed, so a pending or failed navigation leaves the previous viewed
scope intact. Sorting, pagination, and tree rebuilds preserve the effective
folder scope. A view without a folder scope, triage/trash view, or repository
switch clears the previous highlight. Opening a folder menu or dialog does
not change the viewed scope.

## Existing implementation

- `altitude/src/altitude/core/routes/api/FolderController.scala` already returns
  the complete non-recycled repository folder tree, including direct child
  counts. Expansion needs no additional server requests or API changes.
- `altitude/static/js/common/folder-tree.js` builds all folder rows and their
  children in the browser. Non-root folder icons currently toggle children;
  leaf icons navigate like the folder name. Root is always displayed expanded.
- `altitude/static/js/models/folder.js` owns expansion state and visibility,
  updates folder-plus/folder-minus icons, and closes descendant folder menus
  when collapsing. Today, collapse retains descendant expansion state.
- `reloadFolderTree(repoId)` restores expanded folder IDs and focused controls
  after rebuilding. Add-folder handling expands the parent to reveal the new
  folder. These programmatic paths must remain compatible with the controls.
- `altitude/views/htmx/folders.scala.html` supplies the host and scoped styles.
  The non-root row currently has columns for the menu, indentation trace,
  folder icon, and name. Root omits the trace. Colors should reuse existing
  variables from `altitude/static/css/core.css`. Its `i.fa-folder-minus` and
  `:has(i.fa-folder-minus) .folder i` selectors currently color expanded
  folders and their descendants green; those selectors must be replaced.
- Folder-name and root/leaf-icon navigation requests carry `folderId`.
  `routes/web/partial/SearchResultsController.scala` merges each request's
  parameters with the current browser URL, so sorting and pagination can
  retain a folder scope without repeating `folderId` in the request URL.
  `views/includes/search_results.scala.html` and
  `static/js/fragments/search-results.js` provide the results-fragment
  hydration path for synchronizing the scope of the displayed results.
- The bundled Font Awesome Free 5.13.0 already supplies the folder-plus,
  folder-minus, and plain folder icons used by the tree. No icon assets or
  library upgrade are needed.
- `altitude/static/js/dragdrop/folders.js` excludes `.menu-ctrl` from dragging.
  The existing branch icon control will also be excluded to protect its
  single/double-click expansion gestures.
- Folder inline dialogs are being developed separately. Retain the shared
  menu-closing behavior and reconcile any overlapping edits at implementation
  time.

## Language

These decisions concern folder navigation presentation. Record interaction
terms here and the resulting frontend conventions in `altitude/views/AGENTS.md`
when implemented; no new asset-management domain term has been resolved that
requires a `CONTEXT.md` entry.

## Implementation tasks

### Folder interaction — one mergeable unit

Tasks 1–5 belong together: they share the folder model, stable control IDs,
expansion state, and viewed-folder highlighting. Keep this as a small frontend
change, with documentation and verification completed before merging.

1. **Centralize branch expansion and collapse in `models/folder.js`.**
   Keep local expansion available to existing callers and add recursive
   expansion for the gesture. Change collapse to clear the selected folder
   and all descendant expansion flags, hide their children, and synchronize
   their existing icons. Root is never collapsed; leaves never acquire an
   expanded state. Share the per-folder state update between operations and
   walk only the selected subtree, once per operation. Use stable folder IDs
   and `Const.attributes` to identify folder nodes. Close any descendant menu
   once before hiding the branch through `closeOpenFolderMenu`; avoid repeated
   subtree menu searches and per-descendant DEBUG logging. A concise DEBUG
   summary per branch operation is sufficient. If focus is inside content
   being hidden, move it to the collapsing branch's icon control. Add comments
   explaining the reset invariant and subtree traversal. This ensures a closed
   branch cannot silently retain expansion that reappears on its next
   single-click. Expansion methods must not change the viewed-folder scope.

2. **Wire single/double-click behavior in `common/folder-tree.js`.**
   Keep the existing icon in its current position and retain
   `expand-folder-children-<id>` and `folder-icon-<id>`. Use a native
   `button type="button"` for the branch icon's existing wrapper, with an
   accessible folder-specific label, `aria-controls="children-<id>"`, and
   `aria-expanded` synchronized by the model. Mark its icon decorative and
   describe recursive expansion in its tooltip. Enter/Space activate the
   single-click action; no custom keyboard tree-navigation system is needed.
   Keep the existing root/leaf navigation paths and folder-name HTMX behavior.

   On the first pointer click, capture whether this branch was expanded and
   immediately perform the single-click action. Ignore the second click's
   ordinary toggle, then use `dblclick` to apply the recursive action based on
   that captured starting state. A collapsed branch therefore opens one level
   immediately and opens the rest on double-click; an expanded branch closes
   on the first click and stays closed on double-click. Do not decide from the
   icon or state after the first click, introduce a single-click delay, or
   replace the control between clicks. Keyboard-generated clicks must remain
   independent single activations, and further clicks in the same pointer
   multi-click sequence must not introduce extra toggles. Stop navigation and
   propagation for branch gestures. Keep gesture state local to its rendered
   control and ignore events from a control detached by a tree rebuild. Comment
   the event-order handling because two ordinary toggles would reverse the
   intended double-click result.

3. **Protect the icon interaction and retain its appearance.**
   In `dragdrop/folders.js`, extend `ignoreFrom` to include the branch icon
   control and its contents, alongside `.menu-ctrl`. Names and the rest of the
   row remain available for folder dragging; drop targets stay as they are.
   In `views/htmx/folders.scala.html`, give the branch button only the scoped
   reset styles needed to keep the current icon size and position, while
   retaining a visible keyboard focus indicator. Preserve the existing grid,
   indentation trace, gaps, and default colors outside the viewed scope. Keep
   the folder-plus/folder-minus glyphs; replace their expansion-driven color
   rules as described in task 4. Reuse `core.css` variables where a style needs
   a value; add no square icons, blank icon slots, theme changes, or dependencies.

4. **Color the viewed folder and its descendants independently of expansion.**
   Track the repository and effective folder scope of the displayed results,
   and synchronize highlighting through the results-fragment lifecycle.
   Initialize it on a full-page load as well as after HTMX navigation. Use the
   effective search state after the server has combined parameters; the last
   clicked folder and the raw request URL alone are insufficient. If needed,
   expose the resolved repository, view, and folder ID as declarative metadata
   on the search-results fragment. Keep search behavior unchanged and feature
   logic outside the `frontend-app.js` composition root.

   Mark the viewed folder and its descendants using stable folder IDs and a
   dedicated viewed-scope marker, separate from `alt-expanded` and icon glyph
   classes. Share the highlight update between navigation and tree rebuilds.
   Clear the previous scope before applying the new one; do not highlight
   ancestors or unrelated siblings. Include root and leaves, and preserve the
   state of hidden descendants without expanding them. Scope CSS to the folder
   icons so icons inside folder menus or dialogs are unaffected. Remove the
   existing `fa-folder-minus` / `:has(...)` green rules and use
   `--success-font-color` for viewed-scope icons, including the root override.

   Apply changes only for results actually displayed; failed, cancelled, or
   superseded responses must not color a different folder from the visible
   results. Preserve the scope across sorting and pagination. Clear it when
   the displayed view has no applicable folder scope or changes repository.
   Keep keyboard focus and expansion state independent of this update.

5. **Preserve state correctly across rebuilds and folder mutations.**
   In `reloadFolderTree`, capture expanded IDs immediately before replacing
   the DOM, alongside focus, so a gesture during the request is not undone by
   an older snapshot. Restore only surviving non-root folders with children,
   keeping icon and accessibility state synchronized. Normalize descendants
   beneath a collapsed parent to collapsed: moving an expanded folder into a
   collapsed parent must not make the parent's next single-click reveal
   multiple levels. Retain stable focus restoration, superseded-response
   protection, `htmx.process`, and Alpine's existing hydration ownership.
   Check `listeners/folders.js` compatibility: adding the first child reveals
   it through parent expansion, deleting the last child yields a plain folder
   icon, and renaming preserves branch state. Keep any changes here limited to
   those needed for this behavior. Reuse the folder model for normalization
   rather than duplicating state manipulation in the renderer.

   Reapply the latest viewed scope to the rebuilt tree, independent of the
   expansion snapshot. Renaming preserves it; adding or moving a descendant
   into or out of that scope updates its color according to the new hierarchy.
   If the viewed folder no longer exists, remove its highlight without
   implicitly selecting its parent. A tree rebuild must not restore a scope
   from before a navigation that completed while the tree request was pending.

### Documentation and verification — complete with the feature

6. **Document the implemented contract and update nearby comments.**
   Update `altitude/views/AGENTS.md` with icon/name responsibilities, the
   gesture table, descendant reset behavior, accessible control IDs, drag
   exclusion, viewed-folder highlighting and its independence from expansion,
   and reload-state handling. Update the folder-tree overview in
   `altitude/AGENTS.md` to point to that contract. Review the root `AGENTS.md`
   for drift; its general rules need no feature-specific duplication. No
   `CLAUDE.md` or `ARCHITECTURE.md` exists in the current checkout; update them
   if present and affected at implementation time. Revise renderer/model
   comments to describe the resulting behavior, and reconcile overlapping
   folder-inline-dialog changes without replacing that work. No domain
   glossary or ADR is needed for this presentation change.

7. **Compile and manually verify the behavior in the browser.**
   Run `make compile` after implementation. Follow the repository's frontend
   guidance: manually exercise the scenarios below without adding a test
   framework or running server integration suites for these frontend changes.
   Use browser network inspection to confirm expansion sends no requests.
   Record the actual verification results when the feature is implemented.

## Browser acceptance scenarios

Use A → B → C → D, a second child under A, and an unrelated sibling of A.

- Single-click A's plus: only A's direct children appear. Single-click B to
  reveal C. Collapse A and reopen it: B is visible, C and D remain hidden,
  and B has a plus icon.
- Double-click collapsed A: all descendants appear. Double-click A when only
  B is visible, and again when the whole branch is open: either starting
  state closes everything. A subsequent single-click still reveals one level.
- Repeat the gestures on B: A and unrelated branches keep their state.
  Exercise quick double-clicks, slower separate clicks, and triple-clicks;
  verify the first click's state change cannot reverse the final action.
- Root never collapses. Leaf folders, including those containing assets,
  have no plus/minus indicator. Root/leaf icons and all names navigate;
  branch icons never navigate. Triage/trash continue to block navigation
  while allowing branch expansion.
- With 1 → 2 → 3, view 3: only 3 is green. View 2: only 2 and 3 are green.
  View 1: 1 and every descendant are green; unrelated branches retain their
  default colors. View the root: root and all descendants are green. Repeat
  with an empty folder to confirm highlighting follows scope, not asset count.
- Expand or collapse any branch while viewing 3: 1 and 2 never turn green
  from expansion. While viewing 1, collapse and reopen it: its revealed
  descendants remain green and the displayed asset scope stays unchanged.
- Check highlighting after a direct page load with a folder URL, sorting,
  and loading another results page. Failed navigation preserves the previous
  highlight; rapid navigation keeps it consistent with the results actually
  displayed. A new view without a folder scope, triage/trash, or a repository
  switch clears the previous scope. Opening menus and dialogs leaves it intact.
- Tab to a branch icon and use Enter/Space. Focus remains visible, the
  accessible name and expanded state match the UI, and expanding/collapsing
  does not submit a form or jump the page to `#`.
- Clicking or slightly dragging a branch icon does not drag its folder.
  Dragging from the rest of the row and dropping folders/assets still works.
- Collapse a branch with an open descendant menu: its menu closes and no
  focus remains in hidden content. If focus must be recovered, place it on
  the collapsing branch's icon control. Repeat with an inline dialog if the
  separate folder-inline-dialog work has landed.
- Add a first child, rename a folder, delete its last child, and move an
  expanded folder into a collapsed parent. Check icons, direct-level opening,
  preserved expansion elsewhere, and focus on surviving controls after reload.
  Check that green follows membership in the viewed subtree after add/move,
  survives rename, and clears when the viewed folder is deleted.
- With a tree request in flight, expand or collapse a folder before it
  completes. The replacement respects the latest visible state; superseded
  requests cannot restore older state. A fresh page load uses the existing
  collapsed defaults. Also navigate to a different folder while a tree reload
  is pending: the replacement uses the newly displayed folder scope.
- Check a deeper/wider tree for correct indentation, green only within the
  viewed scope, responsive subtree operations, and absence of per-descendant
  console noise. Menu/dialog icons keep their own styling.

## Verification results (2026-09-05)

`make compile` passed (only pre-existing unused-import warnings from generated
Twirl code). ESLint and Prettier passed on the changed JS. Verified in Chrome
against the dev server with the tree Root → lol (2; 3 → 4) and Root → lol2 →
1 → 2 → 3, using lol2 → 1 → 2 → 3 as A → B → C → D and lol as the unrelated
sibling. The browser tab was hidden, so pointer gestures were driven by
dispatching the browser's own event sequence (click `detail` 1, click
`detail` 2, `dblclick`; `detail` 0 for keyboard activation) and results were
read from the DOM and computed styles.

- Gestures: single-click opens one level; collapse resets descendants, so a
  reopened A shows B collapsed with a plus icon; double-click on collapsed A
  opens every level; double-click on a fully or partly open A closes
  everything; a following single-click opens one level; a triple-click adds
  no extra toggle; keyboard clicks are single toggles; gestures on B leave A
  and the sibling unchanged; `aria-expanded` tracked every change. Root has
  no control; leaf controls carry no `aria-expanded`. No network requests
  were made by any expansion.
- Highlighting: viewing D, C, B, A, root, and a sibling leaf colored exactly
  the viewed subtree each time, ancestors never; collapsing and expanding
  while viewing a folder changed no colors; a sort change kept the scope; the
  leaf icon and root icon navigated; no icons exist inside tree menus to be
  affected. Direct page load with a folder URL highlighted that subtree with
  collapsed defaults. Triage view: no highlight, names disabled, warning
  shown, branches expand, leaf icon does not navigate.
- Mutations (with throwaway folders, since removed): adding a first child
  expanded the leaf into a green minus-icon branch; an expanded folder moved
  into a collapsed parent came back collapsed, and the parent's next click
  revealed one level; rename kept expansion and scope; deleting the last
  child restored the plain icon; deleting the viewed folder removed the
  highlight without selecting the parent. Collapsing an ancestor closed an
  open descendant menu and moved focus to the ancestor's icon control.
- Races: expanding during a pending tree reload survived the rebuild;
  navigating during a pending reload left the new folder highlighted.
- Drag: interact.js `ignoreFrom` is `.menu-ctrl, .expand-ctrl`; a real
  pointer drag could not be performed in the hidden tab.
- Not exercised: real Enter/Space keypresses (keyboard-generated clicks were
  simulated with `detail` 0) and a real pointer drag on the icon.
