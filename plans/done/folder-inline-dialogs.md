# Folder inline dialogs

## Original goals

Convert the three folder menu action modals (Add folder, Rename, Delete) into
lighter HTML popovers. Use the grill-me interview to resolve the design and
produce an implementation plan before changing application code.

## Status

Design interview complete on 2026-09-05. Implemented on 2026-09-05; see
"Implementation status and validation results" at the end for what was built,
what was verified, and the deviations from the tasks below.

## Confirmed decisions

- Scope is the three folder dialogs only. Merge people, choose cover face, and
  view settings stay modal dialogs. The mechanism must not preclude migrating
  them later, but this plan does not touch their behavior.
- The form appears inside the open folder menu panel, in place of its actions.
  The action's HTMX request swaps the server-rendered form into the panel; the
  existing `folderMenu` component keeps owning placement, dismissal, and cleanup.
  No second popover host and no client-built forms.
- The form keeps a title and nothing else of the modal's chrome: no close
  control. The three title constants stay.
- Dismissal follows the menu's rules with no confirmation: an outside click,
  Escape, focus leaving, scrolling, and resizing all close the panel and discard
  typed text.
- Initial focus: Add focuses the empty name field; Rename focuses and selects
  the name; Delete focuses the panel itself so a held or repeated Enter from the
  menu cannot fire the deletion, with one Tab reaching the button. On close
  after a successful operation, focus returns to the declared control (the
  folder's ⋯ button, the parent's after a deletion), as today.
- The panel stays `max-content` wide in both states; the name field gets an
  explicit size instead of `width: 100%`.
- Language: "dialog" is the generic term for a server-rendered form completing
  one action; "modal dialog" and "inline dialog" are its two presentations.
- The five fragment attributes that describe the dialog rather than the modal
  host are renamed from `data-app-modal-*` to `data-app-dialog-*` in all six
  dialog templates. Title, anchor, and kind stay `data-app-modal-*`.

## Language

Terms resolved during the interview. They belong to the frontend's working
vocabulary (`altitude/views/AGENTS.md`), not to a domain glossary: they describe
presentation, not the asset-management domain, so no `CONTEXT.md` is created,
consistent with the folder menu plan.

- **Dialog**: a server-rendered form that completes one user action, hydrated
  from `data-app-fragment`. Avoid: modal (for the form itself), popup.
- **Modal dialog**: a dialog shown in a modal host (`#modalContent`), with a
  backdrop, focus trap, and an inert page. The three people and view-settings
  dialogs.
- **Inline dialog**: a dialog shown inside an open folder menu panel in place of
  its actions, with no backdrop and no focus trap. The three folder dialogs.
- **Folder menu**: the native `popover="auto"` panel each folder's ⋯ button (its
  **trigger**) opens. It shows either its **actions** or one inline dialog.
- **Fragment**: a server-rendered piece of HTML that `frontend-app.js` hydrates
  by its `data-app-fragment` kind: `modal`, `inline-dialog`, and the others.

## Existing behavior and implementation

- `altitude/static/js/common/folder-tree.js` builds each folder's ⋯ `button`
  (`popovertarget`, `#folderMenuCtrl-<id>`) and its `popover="auto"` panel
  (`#menu-<id>`) of action buttons. Each action is an HTMX `GET` of
  `/htmx/folder/r/:repoId/modals/<kind>` into `#modalContent`. The root folder
  offers Add folder only.
- `altitude/static/js/alpine/components/folder-menu.js` (`x-data="folderMenu"`
  on the `.menu-ctrl` cell) places the panel in `beforetoggle`, attaches
  scroll/resize/explorer-resize listeners while open, closes on focus-out, and
  closes when any button in the panel is clicked (`handleActionClick`), so the
  menu never sits above the dialog. `altitude/static/js/common/folder-menu.js`
  closes a menu from outside: Escape in `global.js`, ancestor collapse in
  `models/folder.js`.
- `altitude/views/htmx/{add,rename,delete}_folder_modal.scala.html` are the
  three fragments. Add and Rename are forms with `hx-target="this"`,
  `hx-swap="none"`, and `hx-json-enc`; Delete's fragment root is the confirm
  `button` itself, issuing `hx-delete` with `hx-vals`. All three declare
  `data-app-modal-anchor-x="#explorer"` and `data-app-modal-anchor-y` on the
  folder's ⋯ button; they are the only fragments that use anchors. Each carries
  a `<style>` block that targets `#modalContent` or sets the input to 100%.
- `altitude/src/altitude/core/routes/web/partial/FolderActionController.scala`
  renders the fragments with `Const.UI.*_MODAL_TITLE`. Validation failures
  re-render the form through `BaseController.modalFormValidationResponse`
  (`HX-Retarget: this`, `HX-Reswap: outerHTML`).
  `altitude/test/src/altitude/core/controller/FolderActionControllerTests.scala`
  asserts the three routes, the fragment IDs, the folder name, and the two
  validation headers; nothing asserts titles or attributes.
- `altitude/static/js/common/modal.js` is the modal owner: hosts, `openId`,
  the latest open request (superseded responses are cancelled), initial and
  return focus, and the anchored placement (`getAnchoredPlacement`,
  `attachAnchoredPlacement`, `placeAnchoredBox`, `measureAnchor`,
  `viewportGap`, `resetPlacement`, the `.anchored`/`.placed` classes in
  `core.css`).
- `altitude/static/js/fragments/modal.js` hydrates `data-app-fragment="modal"`
  (opens the host) and owns the operation lifecycle every dialog submission
  relies on: tracked from `htmx:before:request` with its `openId`, success
  event, detail, and close-on-success; settled in `htmx:after:request`
  (success event exactly once, validation replacements swapped only into the
  still-active dialog or reported through the snackbar, close on success);
  cleared in `htmx:finally:request`. `listeners/modal.js` registers this on
  `document` because htmx dispatches on the document once the issuing element
  has left the DOM. `frontend-app.js` skips these events in its own
  `htmx:after:request` handling; failed folder requests it does see are
  reported by `listeners/htmx-folders.js`.
- `fragments/helpers.js` reads `data-app-modal-success-detail-target-attr-*`
  (used by the cover-face dialog).
- `global.js` handles Escape once: close the modal, then any open folder menu
  (focus returns to the trigger unless a modal closed), and consumes the key so
  the inline person-name editor survives.
- Two folder listeners depend on the dialog flow: `listeners/folders.js`
  reloads the tree on `folderAdded`/`folderRenamed`/`folderDeleted`, and
  `reloadFolderTree` restores focus to the control focused when the tree is
  replaced, by ID.
- Documentation describing the current flow: `altitude/views/AGENTS.md`
  (Modals, Folder context menus, key files), `altitude/AGENTS.md` (frontend
  layout), and the Altitude notes in `docs/agents/alpine-components/`
  (`dropdown.md`, `headless-popover.md`, `modal.md`, `README.md`).

## Design defaults

Implementation choices that follow from the confirmed decisions; not
separately confirmed by the user.

- The panel has two states, actions and inline dialog. The component keeps the
  action buttons it was built with and puts them back whenever the panel closes
  while showing a dialog, so the next open shows the actions again. A tree
  rebuild discards everything, as it does now.
- Choosing an action no longer closes the menu. The panel stays open through
  the request; the response replaces the actions. Nothing indicates the
  in-flight request, matching the modal flow today.
- A dialog response may only be swapped into a panel that is still open and
  still waiting for that exact request. A response for a panel that closed,
  or was closed and reopened, is dropped. Failed opens keep being reported by
  the folder request listener.
- After the swap, and after every validation replacement, the component
  re-places the open panel against its trigger (the panel's height changed) with
  the same below/flip/cap/clamp rules. Nothing tracks a moving trigger.
- The heading reuses the modal title's type treatment and sits inside the
  fragment root, so a validation replacement (which replaces the root) carries
  it. Delete's root becomes a wrapper holding the heading and the existing
  confirm button, which keeps issuing the request unchanged. Dialog padding
  reuses `--modal-content-padding`; the field's size is a starting value to
  confirm in the browser.
- Escape while a dialog shows closes the panel and returns focus to the
  trigger, as for the menu. Only a successful operation uses the declared
  return-focus control (the parent's ⋯ button after a deletion).
- An operation submitted from an inline dialog keeps running after the panel
  closes: the success event still fires and the tree still reloads; only a
  still-open dialog is closed or has its form replaced. A validation
  replacement for a dialog that is gone is reported through the snackbar. This
  is the modal rule, unchanged.
- Opening any modal closes an open folder menu, whichever state it is in, so a
  modal never appears over one.
- Clicking the heading, the label, or padding inside a dialog must keep it
  open. If such a click moves focus to the body (focus-out with no related
  target), the component must recognise the pointer is inside the panel and
  not close.
- No `CONTEXT.md` or ADR is created; the language lives in
  `altitude/views/AGENTS.md`.

## Implementation tasks

Four units, each reviewable and mergeable on its own, in this order. Each unit
updates the documentation it affects (`altitude/views/AGENTS.md`,
`altitude/AGENTS.md`, and the `docs/agents/alpine-components/` notes) in the
same change, per the root `AGENTS.md`.

1. **Adopt the dialog vocabulary without changing behavior.** In all six
   templates under `altitude/views/htmx/`, rename `data-app-modal-autofocus-selector`,
   `-select-on-focus`, `-return-focus`, `-success-event`, `-success-detail`,
   `-success-detail-target-attr-*`, and `-close-on-success` to
   `data-app-dialog-*`; `data-app-modal-title`, `-anchor-x`, `-anchor-y`, and
   `-kind` stay. Move the operation lifecycle (`trackModalOperationRequest`,
   `settleModalOperation`, `finalizeModalOperationRequest`,
   `isModalOperationRequest`) out of `fragments/modal.js` into a new
   `fragments/dialog-operations.js` under dialog names, reading the new prefix,
   with `parseFragmentTargetDetail` in `fragments/helpers.js` defaulting to the
   new prefix. Give each tracked operation an `isActive()` and a `close()`
   supplied by the fragment kind, so the modal check (`isModalOpenActive`) is
   one implementation rather than the only one; in this unit the modal is the
   only kind. Rename `listeners/modal.js` to `listeners/dialogs.js` and update
   `frontend-app.js` imports. Update the views guide's Modals section to the
   new attribute names. Rationale: the shared attributes describe the dialog,
   and the tracker must serve two presentations in the next unit; landing the
   rename alone keeps that unit's diff about behavior.

2. **Show the three folder dialogs inline in the folder menu panel.**
   - Templates: `add_folder_modal`, `rename_folder_modal`, and
     `delete_folder_modal` become `data-app-fragment="inline-dialog"`, drop the
     anchor and title attributes, render the title as a heading inside the
     fragment root, and drop their `<style>` blocks. Delete's root becomes a
     wrapper around the heading and the unchanged confirm button. Add and
     Rename keep `hx-target="this"`, `hx-swap="none"`, and `hx-json-enc`; the
     name field gets an explicit size in place of `width: 100%`. Delete's
     return-focus stays the parent's ⋯ button.
   - `common/folder-tree.js`: actions target their own panel (`#menu-<id>`,
     `innerHTML`). Update the renderer's introductory and `_buildMenuActions`
     comments.
   - `alpine/components/folder-menu.js`: remember the built actions and
     restore them when the panel closes while showing a dialog; remove
     `handleActionClick` (choosing an action keeps the panel open); record the
     panel's own open request from `htmx:before:request` and cancel, in
     `htmx:after:request`, any response for the panel that is not that request
     or arrives while the panel is closed; re-place the open panel after
     `htmx:after:settle` on it; keep focus-out from closing on clicks inside
     the panel that land on non-focusable content; and clear the request
     record on close and destroy. Keep the listeners scoped to the component
     so they die with the tree.
   - `common/folder-menu.js`: `closeFolderMenu` accepts the element focus
     should go to, defaulting to the trigger, so a successful operation can
     hand focus to the declared control.
   - New `fragments/inline-dialog.js`, registered in `fragments/index.js`:
     confirms the fragment sits in an open folder menu panel, places initial
     focus (declared selector with optional select, else the panel with
     `tabindex="-1"`), and registers the fragment's `isActive()` (connected and
     panel open) and `close()` (close the menu, focus the declared return
     control) with the operation tracker from unit 1.
   - `common/modal.js`: `openModal` closes an open folder menu first.
   - `views/htmx/folders.scala.html`: styles for the panel's dialog state
     (heading, field row, error line, confirm button) using existing `:root`
     variables; the panel's `max-content` width and grid stay.
   - `Const.UI` titles keep their values; `FolderActionController` is
     unchanged in this unit.
   - Docs: the views guide's Folder context menus and Modals sections, key
     files, and the JS directory table; `altitude/AGENTS.md` frontend list;
     the Altitude notes in `dropdown.md`, `headless-popover.md`, and `modal.md`.
   Rationale: this is the feature. The panel already has placement, native
   light dismiss, one-open-at-a-time, and invoker-based Tab order; reusing it
   is what makes the dialogs lighter without a second popover implementation.

3. **Remove anchored modal placement.** With no fragment declaring anchors,
   delete the anchor handling from `common/modal.js` (`anchors` parameter,
   `anchoredPlacement`, `detachPlacementListeners`, `getAnchoredPlacement`,
   `attachAnchoredPlacement`, `placeAnchoredBox`, `measureAnchor`,
   `viewportGap`, and the anchor half of `resetPlacement`; keep the default
   placement reset if anything still needs it), the `anchors` argument in
   `fragments/modal.js`, the `.anchored`/`.placed` rules and comments in
   `core.css` (`--modal-viewport-gap` stays: `#modalContent` uses it), and the
   anchored-placement paragraph of the views guide. Rationale: the folder
   dialogs were the only anchored ones; leaving the machinery would be dead
   code, which the agent guides forbid.

4. **Align names with the language.** Rename the templates to
   `add_folder_dialog`, `rename_folder_dialog`, `delete_folder_dialog`; the
   routes from `/htmx/folder/r/:repoId/modals/<kind>` to `/dialogs/<kind>` in
   `FolderActionController`, `common/folder-tree.js`, and the controller
   tests; the six `Const.UI.*_MODAL_TITLE` constants to `*_DIALOG_TITLE` with
   their three controllers; and `BaseController.modalFormValidationResponse`
   to `dialogFormValidationResponse`. The people and view-settings template
   file names stay (they are modal dialogs). Rationale: after unit 2 these
   names claim a modal that no longer exists; the change is mechanical and
   separate so unit 2's review stays about behavior.

## Verification requirements for implementation

Follow `altitude/AGENTS.md`: run `make compile` after every unit; run
`make test-controllers` for units 1, 2, and 4 (templates and routes change;
the tests check IDs, names, and validation headers); do not run `make test`.
Run `npm run lint:fix` and `npm run format` on the JavaScript. Verify the
frontend in the browser after unit 2 and again after unit 3. Server behavior
does not change, so no TDD cycle is triggered; if execution grows into service
changes, use the required red-green-refactor cycle for them. Do not add tests
that mirror markup.

| Scenario | Expected result |
|---|---|
| Choose Add folder, Rename, or Delete from a menu | The panel stays open and its actions are replaced by the dialog with its heading; no modal host opens and no backdrop appears; the page stays scrollable |
| Dialog taller than the room below | The panel flips above or caps its height with the same rules as the menu |
| Add folder | The empty field has focus; Enter submits; on success the dialog closes, the tree reloads, the parent expands, focus is on the parent's ⋯ button |
| Rename | The field has focus with the name selected; on success the tree reloads and focus is on the folder's ⋯ button |
| Delete | Focus is on the panel, not the button; Tab reaches the button; Enter held from the menu does not delete; on success the tree reloads, the snackbar reports, focus is on the parent's ⋯ button |
| Submit an invalid or duplicate name | The form is replaced in place with the error and the typed value, the heading stays, the panel is re-placed, focus is usable |
| Close the dialog (Escape, outside click, Tab out, scroll, window or divider resize) | The panel closes without confirmation; Escape returns focus to the trigger, the others do not steal focus; reopening the menu shows the actions again |
| Close the panel while the dialog request is in flight, reopen it before the response | The actions stay; the late form is dropped |
| Close the dialog after submitting, before the response | The operation completes: tree reload and success event still happen; a validation rejection is reported through the snackbar |
| Click the heading, label, or padding inside a dialog | The dialog stays open |
| Open a modal (view settings) while a dialog shows | The panel closes; the modal opens normally |
| Escape with a modal open and no menu | Unchanged modal behavior |
| Merge people, cover face, view settings | Unchanged, including cover face's success detail from the clicked face |
| Root folder menu, triage view | Add folder only for root; navigation restrictions preserved |
| Rebuild the tree with a dialog open | No orphan panel; no surviving component listeners |

## References

- [Folder menu popover plan](done/folder-menu-popover.md)
- [Modal migration plan](done/alpine-modal-migration.md)
- [MDN: Using the Popover API](https://developer.mozilla.org/en-US/docs/Web/API/Popover_API/Using)

## Implementation status and validation results

All four units were implemented on 2026-09-05, in order, each with its
documentation. `make compile` and `make test-controllers` pass after every unit
(16 controller tests); `npm run lint:fix` and `npm run format` are clean.

### Deviations from the tasks

- **The panel keeps its actions in the DOM instead of removing and restoring
  them.** htmx 4 strips its listeners from the children it removes on an
  `innerHTML` swap, so buttons put back after a swap would be dead. The panel
  therefore holds an `.actions` wrapper and an empty dialog host
  (`.dialog`, `#menuDialog-<id>`); the actions target the host, and the
  component hides one and shows the other (`hidden`). Closing the panel empties
  the host and shows the actions again. Visually this is what the plan
  describes: the dialog appears in place of the actions.
- **The panel always carries `tabindex="-1"`** (set by the tree renderer, not
  by the fragment). It serves both stated needs: Delete rests focus on the panel,
  and a click on a heading, label, or padding moves focus to the panel rather
  than out of it, so no pointer tracking is needed.
- **A validation swap is recognised in the focus-out handler.** When the
  rejected form is replaced while its field has focus, Chrome reports the field
  losing focus with no related target before htmx focuses the copy. The handler
  ignores a focus-out whose target sits in an element htmx marked
  `htmx-swapping`.
- **Validation responses now ask for `outerHTML settle:0`.** htmx's settle step
  gives the new form's fields the old ones' attributes for the settle delay and,
  for an unfocused text input, writes the old empty value attribute over the
  submitted value; by the time it restores the attributes the field is focused,
  so the property is left empty. Settling immediately skips that step, and the
  typed value is kept as the scenario table requires. This is a server change
  (`BaseController.dialogFormValidationResponse`) made red-green through the
  controller tests. The same htmx behavior applied to the modal presentation.
- **`closeFolderMenu` takes a `focusTarget`** that defaults to the trigger only
  after the panel null check (an earlier draft evaluated the default first and
  threw when Escape was pressed with no menu open).

### Verified in the browser (Chrome, dev server)

Driven through the page's own DOM APIs and events, because the automation tab
was hidden and unfocused: the browser dispatched no focus events in it and
delivered no synthesized keys or clicks, and its timers were throttled.

- Choosing Add folder, Rename, or Delete keeps the panel open, hides the
  actions, shows the dialog with its heading below the trigger; no modal host
  opens.
- Add folder: field focused; submit creates the folder, reloads the tree,
  expands the parent, closes the panel, focus on the parent's ⋯ button.
- Rename: field focused with the name selected; submit renames, reloads the
  tree, focus on the folder's ⋯ button.
- Delete: focus on the panel, the confirm button is the only focusable control
  in the dialog; Enter on the panel does nothing; the confirm click deletes,
  reloads the tree, shows the snackbar, focus on the parent's ⋯ button; the
  removed folder's panel is gone.
- Duplicate name in Add and in Rename: the form is replaced in place with the
  error, the heading, and the typed value; the panel stays open and is
  re-placed for the new height; the field keeps focus.
- Escape closes the panel and returns focus to the trigger; reopening shows the
  actions. Escape with nothing open throws nothing.
- Panel closed while the dialog request is in flight: the late response is
  dropped and the actions stay.
- Submit then close before the response: the operation completes (folder
  created); a validation rejection is reported through the snackbar.
- Opening the view-settings modal while a dialog shows closes the panel and
  opens the modal normally.
- Scroll and window resize close the panel. The root menu offers Add folder
  only. Actions request `/htmx/folder/r/:repoId/dialogs/<kind>`.

### Not verified here; please check by hand

- Real keyboard and pointer interaction: Tab and Shift+Tab out of the panel
  (focus-out dismissal, unchanged code from the menu plan), a held Enter on
  the Delete action, an outside click, and the Split.js divider resize.
- The look of the dialog state (heading, `size="30"` field, confirm button,
  padding) and whether a tall panel flips or caps correctly near the bottom of
  the viewport.
- Merge people and cover face still open as modals; the cover-face success
  detail from the clicked face was not exercised (no people in the dev data).
