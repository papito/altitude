# Search results grouped by date: the user experience

## Original goals

Let the user see search results grouped by the day they were taken, on top of the
backend delivered by [search-results-date-grouping.md](done/search-results-date-grouping.md)
(uncommitted on `feature/grouping` as of 2026-09-06). Each group is introduced by a
date header reading like **Saturday, January 2, 2025**. Continuous scrolling, sorting,
selection, drag and drop, and detail navigation keep working inside a grouped grid.

## Status

Planned on 2026-09-06. Not implemented. The backend contract this builds on is
documented in [altitude/AGENTS.md](../altitude/AGENTS.md) under "Search results and
date grouping"; the only backend change here is a grouped **HTML** branch of the same
route, which today answers `400 Grouped results are available as JSON only`.

Amended on 2026-09-07: the shadow results behind detail navigation are read from the
rendered cells and follow the grid's own continuation (cursor or page number) instead of a
second JSON request per page. One grouped statement per scrolled page, and the modal steps
through the same sequence the grid does.

## Confirmed decisions

Settled with the user on 2026-09-06 (alternatives in the last section):

| Concern | Decision |
| --- | --- |
| Rendering | **Server-rendered grid over HTMX.** The HTML search route accepts `groupBy`; it runs the existing grouped ID search (IDs, day groups, day totals, cursor), loads those assets by ID, and renders the grid with date headers. Two queries per page instead of one. The shadow results behind detail navigation are read from the rendered cells, so no further request per page. |
| Control | **A "Group" dropdown beside Sort**, styled like it, with five options: No grouping / Date Taken (Newest first) / Date Taken (Oldest first) / Date Imported (Newest first) / Date Imported (Oldest first). Sort keeps ordering within each day. |
| Header text | `EEEE, MMMM d, yyyy` in English, from the group's calendar day, never through a timezone: **Saturday, January 2, 2025**. |
| Day count | Each header shows the day's total match count across all pages (the backend's group `total`), kept current when assets leave the grid. |
| Sticky headers | The current day's header stays pinned at the top of the results pane until the next day's header replaces it. CSS `position: sticky`, no JS. |
| Persistence | Grouping is a search parameter like sort: in the `searchParams` store and the bookmarkable URL. **Not** remembered in `localStorage`. |
| Scope | Grouping applies in every view (library, triage, trash) and every scope (folder, person, album); it is a reorder, so choosing a folder keeps it, exactly as sort behaves. |

Domain terms are in [CONTEXT.md](../CONTEXT.md): Date Taken, Date Imported, Date Group.

## How it fits the current architecture

Every search goes through `runSearch` (`static/js/search-results/search.js`) with the
whole parameter set in the `searchParams` store (`static/js/stores/search-params.js`).
The server reads only its query string and pushes a friendly URL back (`HX-Replace-Url`).
The results fragment (`views/includes/search_results.scala.html`) is re-swapped on every
search; `views/htmx/results_grid.scala.html` renders the cells, the last of which carries
the next page for the `IntersectionObserver` in `static/js/fragments/search-results.js`.
Detail navigation walks a "shadow" list of the grid's IDs
(`static/js/search-results/detail-navigator.js`, the `shadowResults` store), today filled by a
JSON request per page; the modal fetches further pages itself only when stepping past what
the grid has loaded.

Grouping adds two parameters to that set, one branch to the controller, one template
for the grouped grid, and teaches the last cell to carry a cursor instead of a page
number. The shadow list is then read from the rendered cells and follows that same
continuation instead of re-fetching each page. Nothing else changes shape.

## Backend

### Grouped HTML branch of `GET /htmx/search/r/:repoId`

`SearchResultsController.htmxSearchResults` today dispatches to `groupedJson` when
`groupBy`, `groupDirection` or `after` is present and rejects HTML mode. New flow:

1. Extract the parameter validation of `groupedJson` into
   `parseGroupedQuery(...): Either[String, SearchQuery]` (grouping, direction, sort,
   `rpp` bounds, `p`/`after` exclusivity, cursor decoding). Both branches use it.
2. A validation error is `{"error": ...}` 400 in JSON mode (unchanged) and a `text/plain`
   400 in HTML mode. The client already reports a failed search request through the
   snackbar (`FrontendApp.handleAfterRequest` via `isSearchRequest`).
3. JSON mode is unchanged: `groupedJson` serializes `library.searchIds`.
4. HTML mode calls the new `library.searchGrouped(query)` (below), then:
   - `isContinuousScroll`: render `htmx.html.results_grid_grouped(results, isContinuousScroll = true)`;
     an empty page is a `204`, like the ungrouped branch, so htmx swaps nothing.
   - otherwise render the full `includes.html.search_results` fragment with
     `HX-Replace-Url`.
5. `browserViewUrl` gains `groupBy` and `groupDirection` after `sort`, only when present,
   so the same grouped search always yields the same URL and a reload restores it
   (`index.scala.html` seeds the store from the URL).
6. A `SearchCursorException` from the service is a 400 in both modes (already so in JSON).

### `LibraryService.searchGrouped`

```scala
/** A grouped page with its assets: the grouped ID page, then the assets by ID, in page order */
def searchGrouped(query: SearchQuery): GroupedSearchResult =
  txManager.asReadOnly {
    val idResult = searchIds(query)                 // nested asReadOnly reuses the connection
    val byId = app.DAO.asset.getByIds(idResult.ids.toSet).map(a => a.persistedId -> a).toMap
    GroupedSearchResult.from(idResult, byId, continuesDay = query.cursor.map(_.day))
  }
```

`BaseDao.getByIds` exists and returns models; it is currently unused by any caller.
The ID list is the order; the map only supplies the assets. An asset purged between
the two statements is skipped and a group that loses every asset is dropped, so the
template never sees a group without cells.

New types in `altitude/src/altitude/core/util/GroupedSearchResult.scala`:

```scala
/** One day of a grouped page: its calendar day, its full match count, and the assets of it on this page */
case class AssetDateGroup(date: LocalDate, total: Int, assets: List[Asset])

case class GroupedSearchResult(
    groups: List[AssetDateGroup],
    total: Int,
    page: Int,
    rpp: Int,
    grouping: SearchGrouping,
    sort: SearchSort,
    nextCursor: Option[SearchCursor],
    /** The day the previous page ended on, when this page was reached by cursor: its first group continues it */
    continuesDay: Option[LocalDate]):
  val assets: List[Asset] = groups.flatMap(_.assets)
  val isEmpty: Boolean = groups.isEmpty
  val totalPages: Int = Math.ceil(total / rpp.toDouble).toInt
```

`GroupedSearchResult.from` slices `idResult.ids` by each `IdSearchGroup`'s
`startIndex`/`length` and looks the assets up.

### Header text

`Util.humanReadableDate(date: LocalDate): String` with
`DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH)`, next to the existing
`humanReadableDateTime` (also `Locale.ENGLISH`). The input is the `LocalDate` group key
straight from the DAO, so the day never passes through the JVM zone.

## Templates

### `views/htmx/result_cell.scala.html` (new, extracted)

The cell markup now inlined in `results_grid.scala.html`, as one partial:

```scala
@(asset: Asset, isContinuousScroll: Boolean, isLast: Boolean, nextPage: Option[Int] = None, nextCursor: Option[String] = None)
```

The last cell gets class `last-cell` plus exactly one of `data-app-search-next-page`
(ungrouped) or `data-app-search-after` (grouped, the encoded cursor). Both grids use
this partial, so the cell has one definition.

### `views/htmx/results_grid.scala.html` (ungrouped, unchanged behavior)

Loops over `results.records` and calls the partial. Same output as today.

### `views/htmx/results_grid_grouped.scala.html` (new)

```scala
@(results: GroupedSearchResult, isContinuousScroll: Boolean = false)
```

For each group, a header then its cells; the page's last cell carries the cursor.
The header is skipped for the first group when `group.date == results.continuesDay`,
because the previous page already opened that day: the new cells simply land after
the previous last cell and read as the same group. The header:

```html
<div class="date-group" data-group-date="2025-01-02">
  <time datetime="2025-01-02">Saturday, January 2, 2025</time>
  <span class="count">37</span>
</div>
```

`data-group-date` is the ISO day for the JS; the count is the group's `total`.

### `views/includes/search_results.scala.html`

- Signature becomes `(total: Int, sort: SearchSort, grouping: Option[SearchGrouping], grid: Html, person, view, folderId, albumId)`.
  The controller renders the right grid partial and passes it in as `Html`; the fragment
  no longer needs a `SearchResult` in grouped mode and stays one template for both.
- `#searchControl` gets a fourth cell for the Group dropdown between View and Sort
  (`grid-template-columns: .5fr 1fr 1fr 1fr`). The exact proportions are the user's call
  and can be tuned in the browser.
- The Group dropdown, same `sort-dropdown` class, declarative like Sort:

  ```html
  <select id="groupOptions" class="sort-dropdown" data-app-search="change" data-app-search-from-selected-option>
    <option data-app-search-group-by="" data-app-search-group-direction="" selected?>No grouping</option>
    <option data-app-search-group-by="dateTaken"    data-app-search-group-direction="desc">Date Taken (Newest first)</option>
    <option data-app-search-group-by="dateTaken"    data-app-search-group-direction="asc">Date Taken (Oldest first)</option>
    <option data-app-search-group-by="dateImported" data-app-search-group-direction="desc">Date Imported (Newest first)</option>
    <option data-app-search-group-by="dateImported" data-app-search-group-direction="asc">Date Imported (Oldest first)</option>
  </select>
  ```

  The selected option is the effective grouping the server used (`grouping`), the same
  way the Sort dropdown reflects `sort`. Empty values mean "back to the default", which
  the store serializes as absent.
- Header styles join the fragment's `<style>` block (results styles live there):

  ```css
  /* A date header spans the whole row; sticky against the top of the scrolling #content pane.
     Every header sticks at the same offset, so the next day's header paints over the previous
     one as it arrives - which reads as a replacement only because the background is opaque. */
  #assets .date-group {
    flex-basis: 100%;
    position: sticky;
    top: 0;
    z-index: 1;
    display: flex;
    align-items: baseline;
    gap: 8px;
    padding: 8px 6px;
    background: linear-gradient(var(--view-tint, transparent), var(--view-tint, transparent)) var(--background-color);
  }
  #assets .date-group .count { color: var(--faded-font-color); }
  /* Keeps the headers' z-index inside the grid, so the drag stand-in and other overlays still paint above them */
  #assets { isolation: isolate; }
  ```

  `--view-tint` is a small refactor in `core.css`: `#content.triage` and
  `#content.trashbin` define it and use it for their own tint, so the opaque header
  matches the pane in every view. Font size and weight of the date are not specified;
  start with the `.dialog-title` treatment (bold, 1.1em) and adjust with the user.

## Frontend JS

### `stores/search-params.js`

`DEFAULTS` gains `groupBy: null, groupDirection: null`, serialized after `sort` and
before `rpp`. No `CLEARS` entry: grouping is a reorder and survives folder, person,
album and view changes like sort does. Any change still returns to page 1.

### `search-results/search-triggers.js`

A third source of parameters, for a `<select>` whose options each set several:

```
data-app-search-from-selected-option   take every data-app-search-<param> of the selected <option>
```

`searchParamsOf` merges the selected option's `data-app-search-*` literals when the
attribute is present. `appSearchFromSelectedOption` and `appSearchAfter` join
`RESERVED_KEYS`. The Sort dropdown keeps `data-app-search-from-value`.

### `fragments/search-results.js` (infinite scroll)

- The observed selector becomes `.last-cell[data-app-search-next-page], .last-cell[data-app-search-after]`.
- On intersection: a cursor cell requests `transient: { after: cursor, p: null, isContinuousScroll: true }`
  (the `p: null` override keeps a seeded `p` from a bookmarked URL out of a cursor request,
  which the server rejects); a page cell keeps `transient: { p: nextPage, isContinuousScroll: true }`.
  Both attributes are deleted as they fire, as today.
- The shadow results are fed from the cells, not fetched. At hydrate: reset the store to
  `searchParams.p`, append the IDs of the `#assets` cells in document order (the
  `alt-asset-id` inside each `.cell`), and set `next` from the page's last cell; the
  `syncShadowResults()` call goes. On `htmx:after:settle` on `#assets` (the bound-once
  listener that already observes the new last cell): append the cells among
  `event.detail.newContent` in order and, when at least one ID was new, set `next` from the
  new last cell. A page the modal already fetched leaves `next` alone, so the continuation
  never moves backwards. The observer's `.then(appendShadowResultsPage)` goes; it only
  requests the page.
- `continuationOf(lastCellEl)`: `data-app-search-after` gives `{ after }`,
  `data-app-search-next-page` gives `{ p }`, neither gives `null`. The settle handler runs
  synchronously on swap, while the IntersectionObserver callback that deletes the attribute
  is delivered asynchronously, so the attribute is still there to read.

### `stores/app-stores.js`

The `shadowResults` store becomes: `items` (the ordered IDs) with a `Set` mirror for
membership, `firstPage` (the ordinal of the first loaded page, `searchParams.p` at hydrate,
default 1), `next` (the forward continuation: `{ p }` for an ungrouped search, `{ after }`
for a grouped one, `null` when nothing follows) and `currentAssetId`. `append(ids)` and
`prepend(ids)` skip IDs already held and return how many were new, so a page that arrives
twice (the grid and the modal both following the same continuation) adds nothing.
`page` and `totalPages` go: `totalPages` only ever decided whether a next page exists,
which `next` now says for both paging modes, and one `page` field meant both "last
appended" (next) and "first loaded" (previous), which is why opening the first image after
scrolling two pages and pressing previous prepends a page already held today.

### `search-results/detail-navigator.js`

The modal fetches JSON only to step past what the grid has loaded:

- `handleShowNext` past the loaded IDs: when `store.next` is set, fetch
  `currentSearchUrl({ ...store.next, p: store.next.p ?? null })`, `append(data.ids)`, and
  set `next` from the response: grouped, `data.nextCursor ? { after } : null`; ungrouped,
  `data.page < data.totalPages ? { p: data.page + 1 } : null`.
- `handleShowPrevious` past the loaded IDs: when `store.firstPage > 1`, fetch
  `{ p: store.firstPage - 1 }`, `prepend`, decrement `firstPage`. Offset only: the backend
  has no backward cursor, and the first page of any search is an offset page anyway. Reached
  only from a URL seeded with `p`.
- `syncShadowResults`, `appendShadowResultsPage` and the sync token are removed;
  `fetchSearchResultsJson` stays. The ungrouped and grouped JSON contracts are then used by
  the modal alone.
- Live results: if the grid's page differs from the one the modal already fetched for the
  same continuation, the new IDs land at the end of the list rather than at their grid
  position; the next fetch continues from the grid's continuation and self-corrects.

### `assets/asset-actions.js` and a new `search-results/date-groups.js`

`removeAssetsFromGrid` is the one place cells leave the grid (move out of scope,
recycle, purge, restore, remove from album). Before removing a cell it calls
`decrementDateGroupOf(cellEl)`: walk `previousElementSibling` to the nearest
`.date-group`, decrement its count, and remove the header when the count reaches zero.
A header whose loaded cells are all gone but whose count is still positive stays: the
day still has matches on pages not loaded yet. No other module touches headers.

### Nothing to do

- Box selection: selectables are `#assets div[alt-asset-id]`, so headers are never
  selected; a drag begun on a header is a box from empty grid space, as intended.
- Asset drag: interact targets `#assets .drag-drop`; headers are not draggable.
- Lazy loading and metadata visibility iterate inserted nodes and look for `img` /
  `.metadata`; a header has neither and is skipped.
- `syncViewedScope`, `resultsTotal`, `ensureViewSettingsControl`: unchanged.

## Implementation units (review and merge in order)

1. **Date formatting.** `Util.humanReadableDate` with a unit test
   (`2025-01-02` -> `Saturday, January 2, 2025`; a single-digit day is not zero-padded).
2. **Grouped page with assets.** `GroupedSearchResult` and `LibraryService.searchGrouped`.
   Integration test in `SearchGroupingTests` (both engines): assets come back in page
   order under the right groups with the day totals, `continuesDay` is the cursor's day
   and absent on a first page, and an ID whose asset is missing is dropped along with an
   emptied group. Red-green-refactor.
3. **Grouped HTML branch.** Controller refactor (`parseGroupedQuery`), the `searchGrouped`
   call, `browserViewUrl`, and the templates (`result_cell`, `results_grid`,
   `results_grid_grouped`, `search_results`). Controller tests in
   `SearchResultsControllerTests` (the route's test home):
   - first page: 200 `text/html`; headers in order with the formatted dates and counts
     (`2026-09-06` is a Sunday); each cell after its day's header; the Group dropdown's
     selected option; the last cell has `data-app-search-after` and no
     `data-app-search-next-page`; `HX-Replace-Url` carries `groupBy` and `groupDirection`;
   - continuation with `after` and `isContinuousScroll`: no header for the continued day,
     a header for the next day, the final page without a cursor attribute, and 204 past
     the end;
   - invalid grouped HTML requests are 400 (the current assertion that HTML mode is
     rejected with "JSON" is removed);
   - the ungrouped HTML and JSON contracts stay byte-for-byte as tested today.
4. **Frontend.** Store parameters, the selected-option trigger source, the Group
   dropdown, cursor-driven infinite scroll, the `shadowResults` store rework with the
   settle feed from the cells and the modal's own paging, header styles with
   `--view-tint`, header count maintenance. Verified in the browser, not by tests
   (frontend-only per `altitude/AGENTS.md`).
5. **Docs.** `altitude/AGENTS.md` (the HTML branch, `searchGrouped`, the templates; the
   400-in-HTML sentence goes) and `altitude/views/AGENTS.md` (Search parameters,
   the Group control, date headers, cursor infinite scroll, the new trigger source; the
   "Detail navigation" paragraph, which says the JSON fetches use `currentSearchUrl({ p })`,
   and the "Search parameters" mention of the shadow results, which are now read from the
   cells and fetched only by the modal). Move this plan to `plans/done/`.

## Verification

- `make compile`, `make test-unit`, `make test-controllers`, `make test-sqlite`;
  `make test-psql` while `altitude-core-postgres-test` is up. Never bare `make test`.
- `make lint` (`npm` needs `source ~/.nvm/nvm.sh`).
- Dev server: templates hot-reload; run `mill altitude.resources` after JS/CSS edits and
  confirm with `curl -s http://localhost:8080/static/js/... | grep <symbol>`. No schema
  change, so no restart or migration.
- Browser (Chrome MCP, DOM checks since the tab is hidden):
  - choose each Group option: headers appear in the right order with counts, cells match
    the JSON groups for the same parameters, the URL carries `groupBy`/`groupDirection`,
    and a reload restores the grouping and the dropdown selection;
  - scroll to the end of a page: the next page lands with no duplicate header for a
    continued day and a header for a new day; the last page loads nothing further;
  - `getComputedStyle(header).position === "sticky"` and, after scrolling within a long
    day, the header's `getBoundingClientRect().top` equals `#content`'s;
  - open a thumbnail and step next/previous across a day boundary and across a page
    boundary: the order matches the grid;
  - the network log shows one HTML request per scrolled page and no JSON request; the
    modal's next past the loaded grid issues one JSON request carrying `after` (grouped) or
    `p` (ungrouped), and the image order matches the grid once it scrolls there;
  - open the first image after scrolling two pages and press previous: nothing is fetched
    and the shadow list holds each ID once;
  - step next in the modal past the loaded grid, close it, scroll the grid to that page: the
    cells appear and the shadow list still holds each ID once;
  - recycle or move an asset out of the viewed folder: its header's count drops by one,
    and the header disappears with the last asset of the day; the footer total still drops;
  - triage and trash views: header background matches the tinted pane;
  - "No grouping" restores today's grid exactly (page-number infinite scroll, no headers).
- Hand checks to list for the user (native input does not reach the hidden tab): a
  box selection dragged across a header, dragging a thumbnail past a stuck header (the
  stand-in must paint above it), keyboard use of the dropdown.

## Alternatives considered and follow-ups

| Topic | Considered | Outcome |
| --- | --- | --- |
| Rendering | Client-rendered from the grouped JSON plus a "cells for these IDs" endpoint; or grouping in the full-asset SQL. | Server-rendered chosen: HTMX-first, no duplicate grouped SQL, two bounded queries per page. |
| Control placement | Radios in the View settings panel; a single Date Taken toggle; direction fixed to newest first. | Dropdown with direction chosen; it mirrors Sort and exposes the whole backend contract. |
| Header text | `Intl.DateTimeFormat` in the browser's locale. | Server-side English, consistent with every other date the app renders. |
| Remembering the grouping | `localStorage` like the metadata fields. | Declined: grouping is a search parameter, like sort, restored from the URL. |
| Fresh day totals on scroll | Re-sending a continued day's total with each page. | Not now: the header keeps its first total minus removals; live imports show after a refresh, as the backend plan documents. |
| Shadow results | Keep the JSON mirror per page, with `nextCursor` added to it. | Read from the rendered cells: the grouped count runs once per page, and the JSON branch serves only the modal's paging beyond the grid. |
| Backward paging in the modal | A backward cursor in the backend. | Offset from the first loaded page: no backend change, and it is reached only from a `p`-seeded URL. |
| Sort label | The Sort dropdown says "Date Created" where the glossary says Date Taken. | Out of scope; worth aligning when the bar is touched. |
| Follow-ups | Relative labels (Today, Yesterday) in front of the date; a date scrubber or jump-to-day built on the headers; a View Transition when the grouping changes (`transition:true` swap). | Not requested; noted for later. |
