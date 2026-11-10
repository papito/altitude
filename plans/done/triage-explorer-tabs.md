# Manage folders, Locations and People from Triage

## Goals

The Triage explorer shows the same tabs as Browse, with these differences. Triage is not for finding results, so nothing in the
explorer searches there.

- **Albums:** no tab in Triage.
- **Locations:** add, rename and move to a Category, with no search on click, as with folders.
- **People:** the list as in Browse, with no counts, since they read confusingly against what Triage holds. Rename and drag-and-drop
  work; a click does not search.
- A click on anything the user may expect to search shows a warning snackbar: "You can manage items here but not perform search -
  use Browse for that".

## Decisions

| Concern | Decision |
|---|---|
| Tabs in Triage | Folders, People, Locations. A URL fragment opens its tab as in Browse; `#albums` falls back to Folders |
| What warns | In Triage, a search trigger naming a folder, Person or Location. A trigger with an empty value (the Results title's ×) still runs |
| What does not warn | Controls that never searched: a branch folder's expand control, a Category row, ⋯ menus, the add buttons, the pencil |
| Repeated clicks | The snackbar's own rule: the same message counts up (`×n`) rather than stacking |
| Counts in Triage | None on People, Locations or folders. Browse is unchanged |
| Who the People tab lists in Triage | Every Person in the buckets switched on, including people found only in triaged assets |
| People tab controls | Buckets and Split view as in Browse; the choices are shared with Browse |
| Location actions in Triage | Add location, Add category, Rename, Delete, Move to category and asset drops, as in Browse. An asset dropped on a Location stays in Triage |
| Renaming a Person | A pencil on the tile, revealed on hover or focus, in Browse and Triage, opens a popover dialog. A name another Person holds offers the merge, as the inline editor does |
| Merge in Triage | Reloads the People tab and confirms. It runs no search, and the results stay on all of Triage |
| Merge in Browse | Unchanged: the results show the destination Person |
| The Folders tab's navigation banner | Removed. The snackbar explains the rule |
| Look of the disabled clicks | In Triage, names, leaf folder icons, Location icons and Person tiles lose the pointer cursor. Draggable rows keep the drag cursor |
| Trash | Unchanged: no explorer, and folder triggers stay silently blocked |

## Constraints

- This lands after the People list route takes `triaged`, where `triaged=true` lists every Person and the list renders `ListedPerson`.
  Group C is written against that route.
- The Search text is unaffected: from Triage it goes to Browse, as it does today.
- No schema change.

## Changes

Server work in group D is red-green with integration tests, each opening with the Setup / Assertions Scaladoc. The other groups are
front-end code, checked in the app.

### A. The tabs and how the explorer looks in Triage

1. **Show the tab list in Triage (`views/index.scala.html`).** Remove `x-data x-show` from the `tablist`, so it shows in every view
   that has an explorer. The Trash hides the whole explorer through CSS.
2. **Leave Albums out in Triage (`views/index.scala.html`).** Wrap `#albumsTab` in `@if(current != Const.NavEntry.TRIAGE)`, so the
   tab is not in the page.
3. **Open the tab the URL names (`views/index.scala.html`).** `loadView` uses `window.location.hash || "#folders"` for every view.
   The existing fallback to the first tab handles `#albums` in Triage, so no Triage branch is left.
4. **Remove the navigation banner.** Delete `#folderNavWarning` from `views/htmx/folders.scala.html` with its rules in
   `static/css/htmx/folders.css`, and fix the comments there that place other things relative to it.
5. **Hide counts in Triage (`static/css/pages/index.css`).** One rule under the existing `main:has(> #content.triage)` form hides
   `.asset-count` in the explorer and the count span of a People tile. The refresh code is untouched, since it patches cells that
   are simply not displayed.
6. **Drop the pointer cursor in Triage (`static/css/pages/index.css`).** The rule sets `cursor: inherit` on `.folder-name`, the root
   folder's icon, a leaf's control (`.expand-ctrl:not([aria-expanded])`), a Location's name and icon, and `#people .cell a`. Inherit
   rather than `default`, so a draggable row keeps the cursor interact.js gives it. Branch folders and Category controls keep theirs.

### B. Clicks that do not search

7. **Generalize the guard (`static/js/search-results/search-triggers.js`).** Before `runSearch`, a trigger in Triage with a non-empty
   `folderId`, `personId` or `locationId` stops and calls `showWarningSnackBar` with the message in Goals. Empty values pass, so the ×
   keeps working on a scoped Triage URL. The Trash's silent block on `folderId` stays. Every explorer entry point goes through this
   one guard, including a leaf folder's icon, which clicks the name.
8. **No search after a merge in Triage (`static/js/listeners/people.js`).** The `personMerged` listener skips `runSearch` when the
   view is Triage. The People reload and the success snackbar still happen.
9. **A drag does not count as a click (`static/js/dragdrop/people.js`, `folders.js`).** The `end` listener of Person and folder drags
   also calls `suppressNextClick()`, so releasing a dragged tile or row is never taken for a click, in Triage or Browse.

### C. The People list in Triage

10. **List everyone (`static/js/alpine/components/people-tab.js`).** `loadPane` sends `triaged: "true"` when the view is Triage as well
    as when the store's `triaged` says so.
11. **Keep the count out of a hidden Person's accessible name (`views/htmx/people_list.scala.html`).** Replace the `aria-label` with a
    `.visually-hidden` ", hidden" inside the link. The name then comes from the content, and the count span, once hidden by change 5,
    leaves it in Triage alone.

### D. Renaming a Person from the tile

12. **Controller tests, red first (`PeopleActionControllerTests`).** The rename dialog renders for a Person with the current name.
    Saving a new name returns an empty success. An invalid name re-renders the dialog with its error. A name another Person holds
    offers the merge, and the holder's hidden state is named. A change of case alone saves.
13. **Share the rename outcome (`PeopleActionController`).** Extract the steps of `editPersonName` (scrub, validate, the no-change
    case, `updateName`, the conflict lookup) into one private method the inline route and the dialog both call. Each renders its own
    response.
14. **The dialog routes (`PeopleActionController`).** `GET /htmx/people/r/:repoId/dialogs/rename-person` and
    `PUT /htmx/people/r/:repoId/p/:personId/rename`. Errors go through `dialogFormValidationResponse`, as in Location dialogs.
15. **The dialog (`views/htmx/rename_person_dialog.scala.html`).** A `data-app-fragment="modal"` form modelled on
    `rename_location_dialog`, with the success event `PERSON_NAME_EDITED_EVENT` and the Person's ID as detail. The name-taken row
    moves from `edit_person_name` into an include both templates use, with its "Merge into" button. The merge modal replaces the
    popover, since the dialog hosts hold one dialog at a time.
16. **The pencil (`views/htmx/people_list.scala.html`, `static/css/htmx/people.css`).** A `button` sibling of the name link, so it is
    not a search trigger, with an accessible name carrying the Person's name, loading the dialog into `#popoverContent` with
    `data-app-dialog-anchor`. Revealed on tile hover and focus, as the Results title's pencil is.
17. **A unique anchor per pane.** A Person can be in both panes, so the anchor's ID carries the pane. `loadPane` sends `pane`, the list
    route validates it and passes it to `people_list`.
18. **One rename outcome (`static/js/listeners/people.js`).** `personNameEdited` carries the Person's ID and announces `peopleChanged`,
    so every pane reloads, in place of patching the tile's text. A rename can move a Person from Unmatched to Matched, and the reload
    puts them in the right bucket with the tile's count intact. When `#person` shows that Person, the listener reloads `#personName`
    from the existing name route.

### E. Docs and comments

19. **`altitude/views/AGENTS.md`.** The explorer tabs and `#explorerHeader` in Triage, the Dock side menu's Triage note, the search
    hydrator's guard, **Event flow** for `personNameEdited`, the People tab's pencil and Triage behavior, and the file table's mention
    of the navigation warning.
20. **`docs/faces.md`.** The tile's rename and the People list in Triage.
21. **`docs/test-coverage.md`.** The new controller tests.
22. **`views/style_guide.scala.html`.** The People tile's pencil.
23. **Code comments.** `search-triggers.js`, `folder-tree.js` (the blocked-navigation notes on `_buildFolderIconCtrl` and
    `_makeSearchTrigger`), `location-list.js` (icon and name search triggers), `people-tab.js` header, `listeners/people.js`,
    `people_list.scala.html`, `results_title_inner` if its pencil notes change, and the `loadView` comment in `index.scala.html`.

## Critical files

- `altitude/views/index.scala.html`, `views/htmx/folders.scala.html`, `views/htmx/people_list.scala.html`,
  `views/htmx/edit_person_name.scala.html`, `views/htmx/rename_person_dialog.scala.html`
- `altitude/static/js/search-results/search-triggers.js`, `static/js/listeners/people.js`,
  `static/js/alpine/components/people-tab.js`, `static/js/dragdrop/people.js`, `static/js/dragdrop/folders.js`
- `altitude/static/css/pages/index.css`, `static/css/htmx/folders.css`, `static/css/htmx/people.css`
- `altitude/src/altitude/core/routes/web/partial/PeopleActionController.scala`
- `altitude/test/src/altitude/core/controller/PeopleActionControllerTests.scala`

## Verification

1. `make test-sqlite`, `make test-controllers` and `make test-psql`; `make lint`.
2. In the app, on the dev server, in Triage:
   - The tabs read Folders, People, Locations. Opening `#albums` lands on Folders; `#people` opens People.
   - Each of these shows the warning and leaves the results alone: a folder name, the root folder, a leaf folder's icon, a Location's
     name and icon, a Person's face and name, by mouse and by keyboard where focusable. Repeats count up.
   - None of these warns: expanding a folder or a Category, ⋯ menus, the add buttons, the pencil.
   - No count shows on any tab, and a hidden Person's link is read without one.
   - The People tab lists a Person found only in triaged assets. Buckets and Split view work.
   - Dragging a Person onto another confirms, merges, reloads the tab and leaves the results on all of Triage, with no warning at
     release. A dropped folder shows no warning either.
   - The pencil renames from either Split view pane, anchored to the tile used. A taken name offers the merge. An Unmatched Person,
     once named, moves to Matched.
   - Add, rename, move and delete a Location; add a Category; drop an asset on a Location and see it stay in Triage.
   - The cursor is not a pointer over names and faces, and still is over a branch folder's control and a Category's.
3. In Browse: tabs include Albums and counts show. Clicks search. A merge shows the destination Person. The pencil renames a Person
   with that Person's Results title open, and the title follows. The tile's count survives a rename from the title's inline editor.
4. In the Trash: no explorer, as before.
