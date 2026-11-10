# Search People and Locations from Triage, describe folders, and summarize Triage

## Goals

- People and Locations in the Triage explorer can be clicked and searched. The results show only triaged assets.
- A folder in the Triage explorer can be clicked. A popover dialog reads "This folder has X results in the repository", with three
  buttons: "Show in new tab | Quick view | Close".
- Quick view is a wide dialog showing the folder's first 100 assets. Its header shows the folder's path as crumbs that are not
  clickable.
- The warning snackbar ("You can manage items here but not perform search - use Browse for that") goes away.
- An info callout, the Triage summary, sits on top of the explorer: "There are X items in triage. Y have unknown people. Z have no
  Locations." Each number is a link that searches in Triage. A tally search works like a Person or folder search: it has its own
  Results title, and its × goes back to all of Triage, not to Browse.
- Merging two People runs no search, in Browse or in Triage. The results stay as they are unless they show one of the two: results
  showing the destination are refreshed, and results showing the source switch to the destination.

## Decisions

| Concern | Decision |
|---|---|
| A Person or Location click in Triage | Searches Triage scoped to it (`view=triage` with `personId` or `locationId`) when it has triaged assets |
| A Person with no triaged assets | A popover dialog by the tile: "This person has no assets assigned in Triage", with "Show person in new tab \| Close" |
| A Location with no triaged assets | The same dialog: "This Location has no assets in Triage", "Show Location in new tab \| Close". A Location with no assets anywhere reads "This Location has no assets", with Close only |
| A folder click in Triage (name, or a leaf's icon) | A popover dialog by the row: "This folder has X results in the repository", "Show in new tab \| Quick view \| Close". X is the folder's Browse count: the folder and its subfolders, without triaged and recycled assets |
| An empty folder | "This folder has no assets", with Close only |
| The root folder in Triage | Does nothing, with no dialog and no warning, and has no pointer cursor |
| A branch folder's expand control, Category rows, ⋯ menus, add buttons, the pencil | Unchanged |
| Show in new tab | A new browser tab on Browse, scoped to the folder, Person or Location, with Browse's defaults |
| Quick view | Opens as a centered wide modal in place of the popover. It shows the first 100 assets by newest import, read-only: no selection, drag or Asset detail. Its header holds the folder's path as plain crumbs. When there are more than 100, the footer reads "Showing 100 of X" with "Show in new tab" |
| Counts in the Triage explorer | Still none. The People tab still lists every Person of its buckets |
| Cursor in Triage | The pointer is back on folder names, a leaf folder's icon, Location names and icons, and Person tiles. The root folder alone has none |
| The warning snackbar | Removed. The Trash's silent block on folder triggers stays |
| Merge, Browse and Triage alike | No search. Results scoped to the destination are searched again. Results scoped to the source search the destination, in the same view. Anything else stays as it is. The People tab reloads and the success snackbar shows, as today |
| Triage summary placement | One callout above the tabs (`#explorerHeader`), on every tab, in Triage only. It does not reload with the tabs |
| Triage summary wording | "There are X items in triage. Y have unknown people. Z have no Locations." Singular forms: "There is 1 item in triage. 1 has unknown people. 1 has no Location." An empty triage reads "There are no items in triage." A zero tally reads "None have unknown people." or "None have a Location." as plain text |
| Triage summary links | X goes back to all of Triage, clearing any scope as the Results title's × does. Y and Z search their tally. Only numbers above zero are links |
| Unknown people | A triaged asset with a Face of an Unknown person: a Person with no name who is neither hidden nor discarded, whatever their bucket |
| No Location | A triaged asset that no Location points at. A GPS position of its own does not count |
| Tally Results titles | "Unknown people" with a person-question icon, and "No Location" with a slashed map-marker icon. The × goes back to all of Triage |
| The tally scope as a search parameter | `subset=unknownPeople` or `subset=noLocation`. It is a place like a folder, Person, album or Location: choosing it clears those and they clear it, and a view change or Search text clears it. The server takes it in every view, and only Triage offers it |
| Triage summary freshness | Reloaded on `peopleChanged` (which fires on every asset mutation, every People change and every Face correction), whenever the nav reloads, and whenever Location counts reload or a Location is deleted |

## Constraints

- The Search text is unaffected. From Triage it goes to Browse, as it does today.
- Triaged assets have no folder, so a folder search in Triage is never run.
- No schema change.

## Changes

Server work in groups A and B is red-green with integration tests, each test opening with the Setup / Assertions Scaladoc. Filters are
proven on SQLite and on Postgres (`make test-sqlite`, `make test-psql`). Groups C to E are front-end code, checked in the app.

### A. The tally scopes as a search parameter

1. **Tests first (`SearchResultsControllerTests`, the library search tests).** The tests cover:
   - `subset=unknownPeople` in Triage matches a triaged asset with a Face of an unnamed, visible, live Person. It leaves out assets
     whose People are all named, hidden or discarded, and assets with no faces.
   - `subset=noLocation` matches triaged assets no Location points at, including one with its own GPS position.
   - Both work in the Browse view as well.
   - An unknown value is a 400.
   - The first page renders the Results title "Unknown people" or "No Location", and the replaced URL carries `subset`.
2. **Parse and carry the subset (`SearchRequestParser`, `SearchQuery`, `Api.Field.Search`, `Const.Search`).** A `subset` argument
   on `htmxSearchResults` is parsed into a `TriageSubset` enum (`UnknownPeople`, `NoLocation`) on the `Scope` and the `SearchQuery`.
   The map layout honors it too, through the same `matching`.
3. **The filters (`SearchQueries.matching`).** `unknownPeopleFilter` matches an asset with a Face whose Person is unnamed and not
   hidden, deleted or a bad match. `noLocationFilter` matches an asset with no `location_asset` row, the predicate `inNoLocation`
   already uses, extracted so both share it.
4. **The Results title (`includes/search_results`, `SearchResultsController.browserViewUrl`).** The template takes `subset` and
   renders the title through `results_title` with the kind's icon. The existing ×, which clears `personId`, clears every place once
   change 13 makes `subset` one. `browserViewUrl` keeps `subset` in the URL.

### B. Triage routes: summary, scope dialogs, Quick view

5. **Tests first (`TriageControllerTests`).** The tests cover:
   - The summary renders the three counts with the wording rules (plural, singular, none, empty triage). Tallies above zero are
     search triggers, and zeros are plain text.
   - The scope dialog for a Person or Location with triaged assets is a 204. Without them, it is the dialog with a Browse link. A
     Location empty everywhere has no link.
   - The folder dialog counts the folder and its subfolders, without triaged or recycled assets. An empty folder reads "no assets"
     with Close only. The root folder is a 400.
   - Quick view lists the first 100 by newest import, with the path crumbs and "Showing 100 of X" past 100.
   - Every route is a 404 for an unknown or foreign ID.
6. **Counts (`LibraryService`).** `triageSummary(): TriageSummary(total, unknownPeople, noLocation)` counts the Triage view three
   times with `count`. A scope count reuses `count` on the view's query. The folder count goes through the existing folder
   expansion.
7. **`TriageController` (new, `routes/web/partial`, registered in `App`).**
   - `GET /htmx/triage/r/:repoId/summary` renders the summary.
   - `GET /htmx/triage/r/:repoId/dialogs/scope` takes one of `folderId`, `personId` or `locationId`, plus `anchor`. It answers 204
     when a Person or Location has triaged assets, and otherwise the dialog.
   - `GET /htmx/triage/r/:repoId/modals/quick-view?folderId=` renders Quick view.
8. **Templates (`views/htmx/triage_summary`, `triage_scope_dialog`, `quick_view_modal`).**
   - The summary's links are `data-app-search` triggers. X carries `data-app-search-person-id=""`, and Y and Z carry
     `data-app-search-subset`.
   - The dialog is a popover dialog (`data-app-fragment="modal"`) anchored by the `anchor` it is sent. "Show … in new tab" is an
     `<a target="_blank" rel="noopener">` to the Browse URL. Quick view is an `hx-get` into `#modalContent`.
   - Quick view reuses `result_cell` markup with no selection, drag or detail hooks, in a wide modal styled in
     `static/css/htmx/quick_view_modal.css`.

### C. Clicks in the Triage explorer

9. **The guard routes instead of warning (`search-triggers.js`).** `blockedSearchOf` becomes a routing step, and the warning
   message and its snackbar call go.
   - In Triage, a non-empty `folderId` that is the root does nothing. Any other folder loads the scope dialog.
   - A non-empty `personId` or `locationId` asks the scope route, and runs the search on a 204.
   - Empty values and every other view run as before, and the Trash's silent folder block stays.
   - The dialog anchors to the trigger, so the guard passes the trigger's ID.
10. **Anchor IDs on the triggers (`folder-tree.js`, `location-list.js`, `people_list.scala.html`).** These elements get IDs and an
    `anchor-name`:
    - a folder's name and a leaf's icon control: `folderName-<id>`, `folderIcon-<id>`
    - a Location's name and icon: `locationName-<id>`, `locationIcon-<id>`
    - a Person tile's link: `personLink-<pane>-<id>`, since a Person can be in both panes.
11. **Cursor and labels (`static/css/pages/index.css`, `folder-tree.js`).** The Triage `cursor: inherit` rule is narrowed to the root
    folder's name and icon. The leaf icon's label reads "Open folder <name>" in every view again, and the root's control in Triage is
    labelled by the folder's name alone.

### D. The Triage summary in the explorer

12. **Host and refresh (`views/index.scala.html`, `static/js/listeners/triage.js` new, `frontend-app.js`).**
    - The host is a `#triageSummary` callout in `#explorerHeader`, above the `tablist`, rendered only in Triage. It loads on page
      load.
    - `app.reloadTriageSummary()` reloads it on `peopleChanged`. `reloadNav`, `reloadLocationCounts` and the Location delete
      listener call it too, and it does nothing outside Triage.
    - The callout is styled as an info callout in `static/css/pages/index.css`, using the style guide's info colors.
13. **The subset in the store (`static/js/stores/search-params.js`).** Add `subset` to `DEFAULTS` and to `PLACES`, so it clears and
    is cleared like a folder, Person, album or Location, and the scope comments name it.

### E. Merging

14. **No search on a merge (`static/js/listeners/people.js`).** The `personMerged` listener follows one rule in both views:
    - Scoped to the destination: `runSearch()`.
    - Scoped to the source: `runSearch({ params: { personId: mergeDestId } })`.
    - Anything else: no search.

    The People reload and the snackbar stay, and the header comment is rewritten to match.

### F. Docs and comments

15. **`altitude/views/AGENTS.md`.** Cover the search hydrator's routing in Triage, the scope dialogs and Quick view, the Triage
    summary and its refresh, the `subset` place in **Search parameters**, the merge rule in **Event flow**, the Triage cursor note,
    and the removal of the warning snackbar.
16. **`altitude/AGENTS.md`.** Add `TriageController` and the new listener to the architecture lists.
17. **`docs/faces.md`.** Describe Unknown people as the Triage summary counts them, the Person dialog in Triage, and the merge rule.
18. **`docs/test-coverage.md`.** Add the new controller and search tests.
19. **`views/style_guide.scala.html`.** Add the info callout and Quick view.
20. **Code comments.** Update the comments in these files:
    - `search-triggers.js` header
    - `folder-tree.js` (`_buildFolderIconCtrl`, `_makeSearchTrigger`)
    - `location-list.js` (search triggers)
    - `people-tab.js` header
    - `results_title_inner` (the × clears every place, the subset included)
    - `SearchRequestParser` and `SearchResultsController` Scaladocs
    - the Triage CSS rules in `index.css`.

## Critical files

- `altitude/src/altitude/core/routes/SearchRequestParser.scala`, `routes/web/partial/SearchResultsController.scala`,
  `routes/web/partial/TriageController.scala`, `dao/sql/search/SearchQueries.scala`, `util/SearchQuery.scala`,
  `service/LibraryService.scala`
- `altitude/views/index.scala.html`, `views/includes/search_results.scala.html`, `views/htmx/triage_summary.scala.html`,
  `views/htmx/triage_scope_dialog.scala.html`, `views/htmx/quick_view_modal.scala.html`, `views/htmx/people_list.scala.html`
- `altitude/static/js/search-results/search-triggers.js`, `static/js/stores/search-params.js`, `static/js/listeners/people.js`,
  `static/js/listeners/triage.js`, `static/js/common/folder-tree.js`, `static/js/common/location-list.js`
- `altitude/static/css/pages/index.css`, `static/css/htmx/quick_view_modal.css`

## Verification

1. `make test-sqlite`, `make test-controllers` and `make test-psql`; `make lint`.
2. In the app, in Triage:
   - The Triage summary shows above the tabs on every tab. Each link above zero shows its results, with the right Results title, and
     × goes back to all of Triage. X clears a Person scope.
   - Recycling, moving to a folder, naming a Person, a Face correction, a drop on a Location and deleting a Location each update the
     summary.
   - A Person or Location with triaged assets searches. One without them shows its dialog by the clicked tile or row, and the new
     tab opens Browse scoped to it.
   - A folder shows its count dialog. Quick view shows up to 100 assets under plain crumbs, and nothing in it is selectable or
     draggable. An empty folder offers Close only. The root folder does nothing. No warning snackbar appears anywhere.
   - From either Split view pane, a dialog anchors to the tile that was clicked.
3. Merging, in Browse and in Triage: with an unrelated scope the results stay as they are. Viewing the destination refreshes it.
   Viewing the source switches to the destination.
4. In Browse: no Triage summary. Clicks search as before, and `?subset=noLocation` in a URL works.
5. In the Trash: no explorer, as before.
