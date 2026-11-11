# Name or merge a Person with no name from the Detail panel

## Goals

- In Asset detail's People section, Change on a Face of a Person with no name acts on the whole Person. Picking a named
  Person merges them into that Person, and typing a new name names them. Every asset of theirs follows, not just the image
  shown.
- Change on a Face of a named Person keeps today's behaviour: it moves that one Face to the Person picked or created.
- A wrongly matched Face of a Person with no name can still be moved on its own.

## Decisions

| Concern | Decision |
|---|---|
| Which Faces | A Face whose Person has no name (`is_named` false), Hidden or not. The plan says "a Person with no name", not "Unknown person": the glossary's Unknown person leaves Hidden people out |
| Named Face's Person | Unchanged: every pick moves this Face only, and the combobox shows no toggle |
| The toggle | For a Face of a Person with no name, a "This face only" switch sits beside the combobox input. It is off at every opening and never remembered. On, every pick moves this Face only, as for a named Person's Face |
| Picking a named Person (toggle off) | Merges the Face's Person into the picked one, whose ID, name and Hidden state survive. The row reads "Andrei · merge Unknown 7 (5 assets)" |
| Merge confirmation | Inline, two steps, as Remove's guard. The first pick arms the row, whose label becomes "Merge Unknown 7 into Andrei? Pick again", and the second pick on the armed row merges. A change of the active option, typing, the toggle or closing disarms it. Asset detail stays open |
| Picking a Person with no name | Always moves this Face only, toggle or not, and never merges two nameless people from here. With the toggle off the row reads "Unknown 9 · this face" so the difference shows |
| A new name (toggle off) | The Create row reads "Name Unknown 7 'Andreea' (5 assets)" and names the whole Person, with no confirmation since a rename is undone by another. With the toggle on it reads "Create 'Andreea'" and moves this Face to a new Person, as today |
| Enter's default | Unchanged: the Person of the exact name, else the first match, else the Create row. With the toggle off, Enter on a named Person arms the merge and a second Enter merges |
| The count | "N assets" is the Face's Person's `num_of_faces`, the assets outside the trash with a Face of theirs, triaged ones included |
| Results scoped to the Person | Named: the results title is read again at once and shows the new name. Merged: Asset detail keeps its cells, since every one of them is the destination's now, and on close the results become the destination's (`personId` = the destination). Results scoped to the destination are searched again on close, gaining the source's assets. Merging or naming another Person changes nothing in the grid |
| Snackbar | "Unknown 7 is now Andreea" for a name, "Unknown 7 merged into Andrei" for a merge |
| Refusals | The server enforces what the client offers. A nameless merge target, the Face's own Person or a name held by someone is a 400 with its message, and nothing changes. A Face that is gone or now someone else's, or a target that is not live, is a 404. A Person who has a name by now, and an asset in the trash, are a 409. Each is answered as Change's refusals are (a 400 shows its message; a 404 or 409 reloads the section with the message) |
| Out of scope | Merging two nameless people from the Detail panel (the People tab's drag and drop does it), whole-Person actions for a named Person (renaming stays with the results title and the People tab pencil), and an undo for a merge |

## Constraints

- The routes find the Face through `FaceDao.getInRepository` and the target Person through `getLiveInRepository`, so another
  Library's IDs are a 404.
- `PersonService.merge` and `updateName` already hold the merge and name rules: a held name is `person_01`'s
  `DuplicateException`, and the merged name is decided from the stored rows. The new service methods wrap them and add no new
  rule of their own. `withTransaction` joins an outer transaction, so the check and the action are one transaction.
- No schema change.

## Changes

Group A is server work, red-green with integration tests, each test opening with the Setup / Assertions Scaladoc. Group B is
front-end code, checked in the app. Group C is docs.

### A. Naming and merging a Face's Person

1. **Tests first (`FaceCorrectionTests`, `AssetActionControllerTests`).**
   - Naming: a Face of a Person with no name on three assets, named "Andreea". The Person (same ID) is named, `is_named` is
     set, `name_for_sort` is the name key, and all three assets' Faces are theirs.
   - Naming refusals: the Person is named meanwhile (`ValidationException`), the name is held by another live Person in
     another case (`DuplicateException`, a 400 at the route with `PERSON_NAME_ALREADY_EXISTS`), a reserved name, a Face that
     is now another Person's (`NotFoundException`), a Face of a recycled asset (`IllegalOperationException`, a 409).
   - Merging: a Face of a nameless Person on three assets, merged into a named Person on two. The source is soft-deleted,
     every source Face is the destination's, the destination's count is 5, and their name and Hidden state are unchanged.
   - Merge refusals: a source who is named meanwhile, a nameless target, the Face's own Person (each a `ValidationException`),
     a target merged away, discarded or in another Library, a Face now someone else's (`NotFoundException`), a recycled
     asset.
   - The routes answer the People section with the result message, and for a merge the root carries the merged Person's ID
     and the destination's ID.
2. **The service (`PersonService.nameFacePerson`, `mergeFacePerson`).** `nameFacePerson(faceId, personId, name)` and
   `mergeFacePerson(faceId, personId, destId)` take the Person the client showed (`personId`). In one transaction they lock
   the Person (and the destination, in ID order, through `lockAndGetLive`), re-read the Face under the lock (`lockedFace`, so a
   Face gone, moved or in the trash is refused as Change refuses it), and refuse a Person who has a name by then. Then they
   call `updateName` or `merge(dest, source)`. The merge also refuses a nameless or non-live destination and the Person
   themselves. Each logs on DEBUG, and each says in its Scaladoc why the client's `personId` is checked: the Face may have
   moved since the section was rendered.
3. **The routes (`AssetActionController`).** `PUT /htmx/asset/r/:repoId/face/:faceId/person/name` with `{personId, name}`
   (the name trimmed and held to the name editor's length rules, as `changeFacePerson` holds it) and
   `PUT /htmx/asset/r/:repoId/face/:faceId/person/merge` with `{personId, destPersonId}`, both under `requireLogin` and
   `answering`, each logging on INFO. Both answer `peopleSection` with `Const.Msg.FaceCorrection.NAMED` ("%s is now %s") or
   `MERGED` ("%s merged into %s"). A held name is caught as `renamePerson` catches it and answered as a plain-text 400.
   `peopleSection` and `htmx/detail_panel_people` take an optional merge (`mergedPerson`, `mergedInto`) and render it as
   `data-app-merged-person-id` and `data-app-merged-into-id` on the root.

### B. The combobox

4. **The data the combobox reads (`htmx/detail_panel_people`, `htmx/assignable_people`).** A row carries
   `data-person-named="true"` for a named Person and `data-person-asset-count` (the Person's `numOfFaces`). An option of the
   assignable fragment carries `data-person-named="true"` for a named Person. The comments over both templates describe the
   new attributes.
5. **The toggle and the labels (`alpine/components/face-combobox.js`, `htmx/detail_panel_people`).** The component reads
   from its row whether the Person has a name, along with their name and count. `parseAssignablePeople` reads `named`. State
   `faceOnly` (false at `open()` and `reset()`), and `wholePerson` is true when the row's Person has no name and `faceOnly`
   is off. The template adds the switch (a labelled checkbox, shown only for a nameless row, inside the component root so
   focus moving to it keeps the listbox open) and an option suffix rendered from the component: "· merge Unknown 7
   (N assets)" for a named option and "· this face" for a nameless one while `wholePerson` is true. `createLabel` gives
   "Name Unknown 7 'X' (N assets)" while `wholePerson` is true, else "Create 'X'". Asset counts use "asset" for 1.
6. **The pick and the two-step merge (`face-combobox.js`).** `pick(key)` announces `facePersonPicked` with an `action`:
   `"move"` (today's detail, `personId` or `name`), `"name"` (`{sourcePersonId, name}`) or `"merge"`
   (`{sourcePersonId, destPersonId}`). A merge pick arms the row (`armedKey`) on the first pick, and merges only on a second
   pick of the armed key. `armedKey` clears when `activeKey` changes, on input, when `faceOnly` changes, and on `reset()`. The
   armed label replaces the option's suffix, and a visually hidden `role="status"` inside the component announces it, since
   the active option's text changing under `aria-activedescendant` is not announced reliably. The class docblock describes
   the two modes, the toggle and the guard.
7. **The requests (`search-results/face-corrections.js`).** The `facePersonPicked` listener sends `move` as today, `name` to
   `…/person/name`, and `merge` to `…/person/merge`, through the same `send` (one request per Face, the same refusal and
   stale handling). `parsePeopleSection` also returns `mergedPersonId` and `mergedIntoId`. The module docblock names the two
   routes.
8. **After a whole-Person change (`search-results/detail-panel.js`).** `afterEdit` takes the merge. When the viewed Person
   (`searchParams.personId`) is the one merged, it notes the destination, and on close (not on a step) `flushLeaving` runs
   `runSearch({ params: { personId: mergedIntoId } })`. The asset's cells stay, since they are the destination's now. When
   the viewed Person is the one named, the results title (`#person`) is read again at once from
   `/htmx/people/r/:repoId/p/:personId/title`. `peopleChanged` is dispatched as for every correction, which reloads the People
   tab and the Triage summary. The module docblock describes both.
9. **Styles (`static/css/htmx/detail_panel.css`).** The switch sits on the input's row, after it and before the X, at the
   combobox's small control size. The option suffix uses the secondary text color, and the armed label the warning color
   that Remove's armed label uses. Only `core.css` tokens, per the style guide. If the style guide renders the combobox, it
   shows the switch and an armed row.

### C. Docs

10. **`docs/faces.md` Corrections.** The section stops saying the panel "never" acts on a Person as a whole. It describes
    Name and Merge for a Face of a Person with no name, the two routes, the service methods, their locks and refusals, and
    the `personId` check. The People section's rename list names the Detail panel as a third way to name a nameless Person.
11. **`altitude/views/AGENTS.md` Detail panel and Event Flow.** The combobox's two modes, the toggle, the two-step merge,
    the `action` in `facePersonPicked`'s detail, the merge attributes on the section's root, and what the grid does after a
    name or a merge.

## Verification

- `make test` passes, and `make test-psql` passes for the new service and route tests.
- In the app, on a second instance over a copied `data/` (these change people):
  - Open a Face of an Unknown on several assets, Change, type a new name, and Enter. The snackbar says "… is now …", every
    asset of theirs shows the name, and the People tab cell follows. Opened from their results, the title shows the name.
  - Change on another Unknown's Face, type an existing name, Enter: the row arms. Enter again: they merge. Close Asset
    detail: the results are the destination's, their earlier assets included.
  - Move the active option or type after arming, and the row disarms.
  - Switch "This face only" on and pick a named Person: only this Face moves, as today.
  - Pick another Unknown: only this Face moves, with the toggle on or off.
  - Change on a named Person's Face shows no switch and moves only that Face.
  - Name the Person in another tab first, then pick in the stale panel: the 400 message shows and nothing changes.
