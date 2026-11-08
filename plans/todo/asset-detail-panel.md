# Asset detail: Detail panel, Face highlights and Face corrections

## Goals

- Asset detail optionally shows all of an asset's metadata, toggled with `m`.
- Faces can be highlighted with a name badge under each one, toggled with `p`. `f` puts Asset detail in fullscreen, for
  images as well as Videos.
- The metadata panel docks left on a wide screen and at the bottom on a tall one, with a sticky choice to change that,
  the same idea as the explorer's.
- The panel has a people section with the cover image of each person in the image or Video. Clicking a cover highlights
  that person.
- The user can correct a mismatch or remove a person. Clicking a person shows Change and Remove under the cover. Change
  turns into a combobox of all people with autocomplete, and an X beside it returns the section to its original state.
  Remove reads "Remove (click twice)", then "Remove (last click)", and the second click removes.
- These settings stick from asset to asset and across closing the dialog.

Out of scope: editing metadata, user metadata (the model is an unused draft), an undo for Change or Remove, and enforcing
roles on the new routes, which no route does yet.

## Language

The glossary in `CONTEXT.md` holds **Asset detail**, **Detail panel**, **Face highlight** and the widened **Dock side**.
The Detail panel's sections are People, Details and Metadata. Change and Remove act on a Face, never on a Person as a
whole; a Person goes only when their last Face goes.

## Decisions

| Concern | Decision |
|---|---|
| Keys | `m` Detail panel, `p` Face highlights, `f` fullscreen, on images and Videos, wherever focus is except in a text field. The player's own `m` and `f` are off; its mute and fullscreen buttons stay |
| Toolbar | Toggle buttons for all three, before the ×, each with its key in the tooltip. Fullscreen's is left out where `document.fullscreenEnabled` is false |
| Remembered | Per browser, in `localStorage`: panel open, highlights on, the Dock side once picked, the share of each axis and the open Metadata groups. A selection and fullscreen are never remembered. By default the panel is closed and highlights are off |
| Dock side | Left, right, top or bottom from a compact choice menu in the panel's header, with a draggable divider. Right by default in every viewport, until a pick. Independent of the explorer's. Shares default to 30 % of the width for left or right and 35 % of the height for top or bottom; the explorer's are 25 % and 35 % |
| Panel order | People, then Details, then Metadata. All read-only except Change and Remove |
| Details | File name, type, dimensions, duration, size, Date Taken with its source, Date Imported, the Folder breadcrumb, albums, Locations, GPS position, camera settings, Image quality, Sequence. No checksum |
| Links | Folder crumbs, albums and Locations are links to their search, each opening in a new browser tab. Asset detail stays open |
| Metadata | Every extracted directory is one collapsible group, closed by default |
| People rows | One row per Face, in one column: cover, then name, with the actions under the name when selected. Compact, with tighter padding than the app's list rows. In reading order for an image (by `x1`), by Frame time for a Video |
| Who shows | Every Face of the asset except those of bad-match people. A Hidden person's row is marked "Hidden" |
| Selection | Clicking a row selects it and shows its Face highlight; clicking it again or another row moves or clears the selection. Clicking a Face highlight selects its row, opening the panel if it is closed, and that stays open as if `m` were pressed |
| Face highlight look | An outline in the light neutral (`--theme-neutral-1`), 1px wide, with 14px corners, over a transparent box. The badge under it is a name in `--font-size-base`, Hidden marked |
| `p` | On shows every Face highlight, with the selected one emphasised. Off shows only the selected one. Pressing `p` flips the setting and clears the selection. Selection does not carry to the next asset |
| Video | Clicking a row seeks to the Face's Frame time, pauses and shows its Face highlight; playing or scrubbing away removes it. With `p` on, a Face highlight shows while the player is paused on that Face's Frame time, and none during playback |
| Remove | Deletes the Face row and its files. Not undoable. The label moves through "Remove (click twice)" and "Remove (last click)", and resets when the selection changes |
| Change | The combobox lists every live Person except the current one and bad matches: named first, then Unknowns, Hidden marked, each with cover and name, filtered as the user types. A name that does not exist offers "Create 'Alice'"; an existing name, in any case, picks that Person. X returns to the original state with nothing selected |
| Last Face | A Person left with no Face row is deleted, named or not. Faces on assets in the trash are rows and keep the Person |
| Cover | A Person who loses their cover Face takes their remaining Face with the highest detection score. A Person gaining a Face keeps their cover. A new Person's cover is the moved Face |
| Two Faces | On an image, two Faces of one Person are allowed and show as two rows. On a Video, a Change onto a Person already in it keeps the higher-quality Face and deletes the other, keeping one Face per Person per Video |
| Trash bin | The panel, highlights and links work; Change and Remove are absent and the server refuses them |
| After an edit | The row updates or disappears and the selection clears. A success snackbar names the result ("Moved to Bob", "Removed Alice"), adding "Alice had no other faces and was removed" when a Person is deleted. The People tab reloads at once |
| Grid | When the viewed Person has no Face left on the asset, Asset detail stays on it; its cells leave the grid on stepping away or closing, and navigation continues from where they were. The results title's count refreshes then, and if the viewed Person was deleted the results go back to the whole repository. A Search text result stays until the next search |
| Fullscreen | The whole of Asset detail, toolbar hidden, the media fitted to the screen and enlarged if smaller. The panel stays if open. Stepping stays fullscreen. Esc leaves fullscreen and a second Esc closes Asset detail. The player's fullscreen button does the same as `f` |

## Design

### Server

`PersonService` gains the two corrections, each one transaction followed by a file purge:

- `removeFace(faceId)` locks the Person, re-reads the Face, deletes the row, lowers `num_of_faces` if the Person has no other
  Face on the asset, repicks the cover if it was this Face and deletes the Person if no row remains (`deletePeopleWithoutFaces`). Once committed, the files go through
  `purgeFaceFiles`, given the Person as re-read after the repick: the rule that a cover Face keeps its files would otherwise
  keep them.
- `moveFace(faceId, target)` takes an existing Person or a name, and a name resolves case-insensitively to a live Person or
  creates one. It locks source and target in ID order, re-reads the Face, updates `person_id`, adjusts both covers and both counts
  (the source's if it has no other Face on the asset, the target's if it had none), and deletes a source left with no row. On a Video where the target already has a Face, the lower-quality of the two goes as
  in `removeFace`, and a cover that pointed at it moves to the survivor. The vector moves with the Face, so later imports
  match it to the new Person.

Both refuse a Face of a recycled asset, and a Face already gone or moved by another request; the route reports the first as a
conflict and the second as not found, and the client reloads the section. Counts use the existing atomic increment. The lock
is a new `PersonDao.lockPeople(ids)`, `ORDER BY id` and `FOR UPDATE`. An import that matched a Person in the moment that Person
is deleted fails its foreign key and that asset is dropped as any stage failure is; the window is that of one short
transaction.

`AlbumService` and `LocationService` gain a read of an asset's albums and Locations, through `album_asset_02` and
`location_asset_02`. `PersonService` gains the assignable list (live, not bad match, named first, by `name_for_sort`) and a
read of an asset's Faces with their people, leaving out bad matches, on top of `getAssetFacesWithPeople`.

Routes, under `requireLogin`, each logging the correction on INFO:

| Route | Returns |
|---|---|
| `GET /htmx/asset/r/:repoId/detail-panel/:assetId` | The panel's three sections |
| `PUT /htmx/asset/r/:repoId/face/:faceId/person` | The People section, with the result message and any deleted Person in its data attributes |
| `DELETE /htmx/asset/r/:repoId/face/:faceId` | The same |
| `GET /htmx/people/r/:repoId/assignable` | The combobox's options |
| `GET /htmx/people/r/:repoId/p/:personId/title` | `person_inner`, for the results title's count |

A Folder link is `/r/:repoId?folderId=…`, and an album's or a Location's is the same with `albumId` or `locationId`; the
page seeds its search from these already.

### Dock

The side, share, divider and remembered-state logic of `js/common/explorer-dock.js` moves to `js/common/dock.js`, a factory
taking the storage key, the pick attribute, the container, the divider, the minimums and the defaults: the side and the two
shares, since the explorer's (left or bottom by orientation, 25 % and 35 %) differ from the Detail panel's (right, 30 % and
35 %). The explorer keeps its boot script and its `<html>` attributes. The Detail panel writes its side and share on the Asset
detail fragment and opens long after first paint, so it has no boot script, and it does not follow the viewport's orientation.
Its picks use their own `data-app-detail-dock-side`, since the explorer's listener is on `document.body`.

### Layout

The fragment `view_image_detail_modal` becomes a grid of the media stage, the divider and the panel, one rule per Dock side as
`pages/index.css` does for `main`. The stage is a size container, and the media element fits it from the asset's ratio. Closed,
the panel takes no room and the box is as fitted today.

`showMedia` hands the box the size of the shown element (`naturalWidth`, `videoWidth`), not the stored one: `asset.width` and
`asset.height` are read from the raw file header, while a browser and the face detector both see an image turned upright by its
EXIF orientation. The Details dimensions read the same element.

### Face highlights

An overlay layer shares the media's box, positioned over it with `pointer-events: none`; each Face highlight is a percentage
box of the shown element's natural size, taking pointer events itself, its badge under it and inside the media's edge. The box
is a 1px outline in `--theme-neutral-1` with 14px corners and no fill, and the badge's text is `--font-size-base`; the selected
one is emphasised by the overlay's own rule, not by a change of colour token. It sits
inside the fullscreen element, above the player, and leaves the control bar usable. The layer is built from the rows' data
attributes (Face ID, Person name, box, Frame time), names set as text, and is emptied as soon as another asset starts loading.
On a Video it is recomputed on `pause`, `play` and `seeked`, a Face counting as current within 100 ms of its Frame time.

### Loading

The panel's fragment loads when the panel is open or highlights are on, on open, on each step and when either is switched on,
under the coordinator's existing request token, so a superseded response is ignored.

### The combobox

Hand-built with core Alpine as `js/alpine/components/face-combobox.js`, following the ARIA combobox pattern of
`docs/reference/alpine-components/headless-combobox.md`: an input with `role="combobox"` and a `role="listbox"` popover
anchored by CSS, as the app's other popovers are. `@alpinejs/ui` is not vendored, and a native `<datalist>` cannot show covers or
a Create row. Options load once per opening of the combobox and filter on the client, the first 50 matches rendered, covers
lazy.

### Keys

`js/global.js` gains the three toggles, ignoring a repeat or any modifier so Ctrl+F and Ctrl+P keep working. It must also:

- leave Space alone on a control in the panel, since today it plays or pauses instead of pressing the focused control;
- leave the arrows alone on the panel's divider, which steps with them;
- on Escape, while fullscreen, leave Asset detail open and let the browser leave fullscreen;
- on Escape, with the panel's menu or listbox open, close only that. `closeModal` runs first today and would take the dialog with it.

`hotkeys` on the player becomes `noarrowleft noarrowright nof nom`, and its `fullscreenElement` property is set to the Asset
detail container.

## Tasks

Each group is one reviewable change; its guide, style guide and comment updates go in the same change. Server tasks are
test-driven with integration tests on both engines. Front-end tasks are verified in the browser.

### A. Face corrections on the server

1. **`PersonService.removeFace`, `PersonDao.lockPeople`, `FaceDao` delete.** Tests: the count with and without another Face on the asset, cover repick, a deleted Person
   named or not, a Person kept by a Face on a trashed asset, the files gone, a cover Face's files gone too, a recycled asset
   refused. Rationale: Remove's rules live in one transaction.
2. **`PersonService.moveFace` to an existing Person.** Tests: both counts, with and without another Face on the asset, both covers, the source deleted at its last Face, two
   Faces of one Person on an image, a Hidden target, a Video keeping the better Face in both orders with its cover. A concurrent
   test proved red on Postgres (`make test-focused-psql`), since SQLite's one write connection hides the race. Rationale: Change
   teaches recognition by moving the vector with the Face.
3. **`moveFace` by name.** Tests: creates a named Person whose cover is the Face, reuses a name in any case, a duplicate lost to
   a race reuses the winner. Name length rules follow `editPersonName`. Rationale: a face can belong to someone with no Person
   yet.
4. **The correction routes and the assignable list**, with controller tests for the status codes and the list's membership and
   order. Rationale: the UI's only way in.
5. **Docs.** `docs/faces.md`: the corrections under People, the last-Face rule, and the Video rule now applying beyond import.
   `PersonService.deletePeopleWithoutFaces`' comment, which says only an import leaves a Person faceless. `docs/test-coverage.md`.

### B. The Detail panel

6. **Albums and Locations of an asset**, with tests. Rationale: Details needs them and no read exists.
7. **`htmx/detail_panel` template, its stylesheet and the route.** People rows, Details with `.crumbs`, Metadata as `<details>`
   groups, links with `target="_blank"`, read-only rows when the asset is recycled, an empty People line for an asset with no
   Faces. Controller test for bad matches being left out and Hidden marked. Rationale: server-rendered, as every fragment is.
8. **`js/common/dock.js`** extracted from `explorer-dock.js`, the explorer's behaviour unchanged. Rationale: one owner for
   docking, rather than a second copy.
9. **The panel's shell.** The fragment's grid and divider, the panel header's Dock side menu, one rule per side, the box fitted
   from the shown element, and the fragment loaded under the request token. Rationale: the media and the panel share the viewport.
10. **`m`, the toolbar button and remembered state**: `Const.localStore.assetDetailView` holding panel open, highlights on, the
    dock and the open groups. The `global.js` guards for Space and the divider, and `nom`. Rationale: settings stick across assets
    and closing.
11. **Docs.** `views/AGENTS.md`: Detail panel under Detail navigation, Explorer dock's move to the shared module, the key files
    and JS directory tables. Style guide: the panel and its rows.

### C. Face highlights

12. **The overlay and `p`.** Layer, percentage boxes, badges kept inside the media, emptied on load, the toolbar button. Rationale:
    the boxes must never show on the wrong asset or at the wrong scale.
13. **Selection.** Row and Face highlight clicks, the emphasis, opening the panel from a Face highlight, cleared by `p` and by
    stepping. Rationale: the one state Change and Remove act on.
14. **Video.** Seek and pause from a row, the Frame time check on `pause`, `play` and `seeked`. Rationale: a box holds only at its
    Frame time.
15. **Docs.** `views/AGENTS.md`, and the style guide's Face highlight specimen.

### D. Face corrections in the UI

16. **Change and Remove under a selected row**, Remove's three labels, and the section swapped from the response. Rationale: the
    two clicks are the only guard, since Remove cannot be undone.
17. **`face-combobox`**: the options fetch, filtering, keyboard, the Create row, X, and Escape closing only the listbox. Rationale:
    the app has no combobox, and the list can be thousands long.
18. **After an edit.** The snackbar from the response, `peopleChanged`, and on leaving: `removeAssetsFromGrid`, a step that picks
    its target before the cells go, the results title's count, and the whole-repository search when the viewed Person was deleted.
    Rationale: a grid that disagrees with its title reads as a bug.
19. **Docs.** `views/AGENTS.md`, the style guide's combobox, and `docs/READINESS.md` if its roles item lists the routes a role
    must guard.

### E. Fullscreen

20. **`f`, the toolbar button, `nof` and the player's `fullscreenElement`**, the toolbar hidden and the media enlarged under
    `:fullscreen`, leaving fullscreen on close, and the Escape guards in `global.js`, including the panel's menu. Rationale: one
    fullscreen for images and Videos, keeping the panel in reach.
21. **Docs.** `views/AGENTS.md`'s Asset detail keys and Detail navigation.

## Verification

- `make test-*` on SQLite and Postgres, `make lint` clean.
- A rotated phone photo: Face highlights land on the faces, and the Details dimensions match what is shown. Also a rotated Video.
- The panel opens docked right by default in a landscape and a portrait viewport, at 30 % of the width; each Dock side by pick,
  top and bottom at 35 % of the height, the divider and its keys, the settings surviving a step, a close and a reload, and the
  explorer's dock unchanged beside it, still following orientation with its own shares.
- Face highlights across steps, with `p` on and off and the panel closed; a Face highlight's click opening the panel; a Video's
  row seeking and pausing. The outline is 1px of the light neutral with 14px corners and the badge's text is the body size, on
  a light and on a dark photo; People rows read as compact next to the Details rows.
- Change to an existing Person, to a Hidden one, to a new name and to an existing name in another case; Remove's two clicks and
  its label resetting; a last Face deleting a named Person with the snackbar saying so; a mirror image's two rows.
- Viewing a Person: removing them from a photo, stepping on and closing; removing their last Face; a map pin's Asset detail, which
  has no cell.
- The trash bin shows no Change or Remove, and the routes refuse them.
- Keys: `m`, `p` and `f` with focus in the player, on a panel control and in the combobox; Space on a row; arrows on the divider;
  Escape with the listbox open, the Dock side menu open, in fullscreen and otherwise.
- By hand, since the browser tool cannot make a gesture or resize: `f` and the player's button on an image and a Video, the panel,
  tooltip, snackbar and combobox listbox inside fullscreen, and a rotation leaving the panel's Dock side where it is.

## Addendum

Two additions to the work above: a bug fix that goes first, and a follow-up that ships last.

### F. A person's face count follows their assets (before group A)

`num_of_faces` is the number of assets not in the trash on which the Person has a Face: what a search for that Person finds. A
Person with two Faces on one asset counts 1. `PersonService.merge` already writes this number, through `LibraryService.count`, and
stays as it is, with its test. The other writers still count Face rows: `addFace` adds one per Face, and `addFacesOfAssets` adds
or subtracts the asset's rows. So the count drifts for such a Person:

| Step on that Person | Count | Assets found |
|---|---|---|
| Merge | 1 | 1 |
| Recycle the asset | -1 | 0 |
| Restore the asset | 1 | 1 |
| Import an image holding them twice | 2 | 1 |

This goes before group A, whose corrections follow the same rule: a Face leaving an asset lowers the count only if the Person has
no other Face there, and a Face arriving raises it only if they had none. The count also decides the Matched line. The schema does
not change, and the database is recreated rather than migrated.

22. **The writers.** `addFace` increments only for the Person's first Face on the asset, through a new `FaceDao` read over
    `face_01`. `addFacesOfAssets` counts `COUNT(DISTINCT f.asset_id)`, which also serves `discardImport`. In `PersonServiceTests`,
    "Same person can appear in the same image more than once" expects 1 in place of 2, keeping its two Faces in the asset's Faces,
    and "Merging of people in the same asset results in correct face counts" gains the recycle and restore steps, expecting 0 and
    then 1, red first on both engines. Rationale: one meaning for the number, so that every writer adds to the same thing.
23. **Words.** `CONTEXT.md`'s Matched person and Unmatched person read in assets with a Face in place of Faces. `docs/faces.md`: the
    People section's `addFace`, recycling and Matched lines. A comment on `Person.numOfFaces` and on the column in both `all.sql`
    files says what it counts, since the name does not. Rationale: the name stays, so the docs have to carry the meaning.

### G. Suggested people in the combobox (after group D)

With the combobox's input empty, a **Suggested** group sits above the list: the people whose Faces are nearest this Face's vector,
closest first, at most 3. Most wrong matches then take one click. Typing leaves the group out and filters the whole list, and a
suggested Person is not repeated below it. The group is absent when nobody is near enough, which happens with a poor Face, whose
vector has drifted; Hidden people are marked as they are in the list.

The group reads what recognition reads, so it needs no new Setting: enrolled Faces, no bad-match people, within
`face.recognition.cosine_distance_threshold`, in the context repository. Unlike recognition it leaves out the Face's current
Person, in the SQL, since a Person with thousands of Faces would otherwise fill every candidate slot. On Postgres the HNSW index's
iterative scan carries on past the excluded Faces, and on SQLite the full scan filters afterwards. Nothing is stored and nothing
votes: the suggestion only orders what the user chooses from.

24. **`FaceDao` per engine**: reading one Face's vector, which the model never carries, and a nearest-Faces search excluding a
    Person, inside `withFaceVector`, beside `searchClosestFaceMatches`. Tests in `FaceRecognitionServiceTests` on known vectors
    (`addTestFace`) on both engines: closest first, the current Person left out, bad-match people and match-only Faces left out,
    nothing past the threshold, another repository's Faces left out. Rationale: the one per-engine part, tested alone.
25. **`PersonService.suggestedPeople(faceId)`**, reducing the nearest Faces to the first 3 distinct people, and the assignable
    route taking `faceId` and returning the group with the list. Controller test for the group's order and for a suggested Person
    not repeating. Rationale: one request per combobox opening, as the list has now.
26. **The combobox's group**, its heading announced in the listbox, shown only while the input is empty. Docs: `docs/faces.md`,
    `views/AGENTS.md`, the style guide's combobox, `docs/test-coverage.md`. Rationale: the visible half, and no drift.

Verification, in addition to the above:

- Merge two People found in one image: the count reads 1, recycling reaches 0 and restoring returns to 1. Removing one of the two
  Faces leaves 1, and removing the other leaves no Person. Importing a mirror image counts 1.
- A wrongly matched Face suggests its real owner first. A Person with many Faces does not crowd the group, a poor Face shows no
  group and the list still works, and typing hides the group.
