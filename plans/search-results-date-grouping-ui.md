# Search results grouped by date: the user experience

## Original goals

Let the user see search results grouped by the day they were taken, on top of the
backend delivered by [search-results-date-grouping.md](done/search-results-date-grouping.md)
(uncommitted on `feature/grouping` as of 2026-09-06). Each group is introduced by a
date header reading like **Saturday, January 2, 2025**. Continuous scrolling, sorting,
selection, drag and drop, and detail navigation keep working inside a grouped grid.

## Status

Planned on 2026-09-06. Units 1 to 3 (backend) implemented on 2026-09-07 and reshaped the same
day after a review of the whole branch; the contract is documented in
[altitude/AGENTS.md](../altitude/AGENTS.md) under "Search results and date grouping". Units 4
and 5 (frontend, docs) remain.

Amended on 2026-09-07, twice. First: the shadow results behind detail navigation were to be read
from the rendered cells instead of a second JSON request per page. Then, after the review: the
modal walks the grid's cells itself, so there is no shadow list and no JSON behind it at all;
grouped results are HTML only, continued by cursor only (no page numbers), and one statement
returns the page's assets with the overall count on the first page only.

## Confirmed decisions

Settled with the user on 2026-09-06 (alternatives in the last section):

| Concern | Decision |
| --- | --- |
| Rendering | **Server-rendered grid over HTMX.** The HTML search route accepts `groupBy`; it runs the existing grouped ID search (IDs, day groups, day totals, cursor), loads those assets by ID, and renders the grid with date headers. One statement per page, and nothing else is requested per page. |
| Paging | **Cursor only.** A grouped search has no page number: the page's last cell carries the cursor of the next page, and the page size may change between pages. |
| Detail navigation | **The modal walks the grid.** Next and previous move between the grid's cells, skipping headers; at the last cell the modal loads the next page exactly as the scroll observer does, then continues. No shadow ID list, no JSON. |
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
Detail navigation today walks a "shadow" list of the grid's IDs fetched as JSON
(`static/js/search-results/detail-navigator.js`, the `shadowResults` store); it will walk the
grid's cells instead.

Grouping adds two parameters to that set, one branch to the controller, one template
for the grouped grid, and teaches the last cell to carry a cursor instead of a page
number. The modal then walks those cells and shares the observer's page loading. Nothing else
changes shape.

## Backend (done)

Implemented and reshaped on 2026-09-07; `altitude/AGENTS.md` is the reference. In short:
`SearchResultsController` validates a grouped request once (`parseGroupedQuery`), answers JSON
negotiation with a 400, and renders either the full `includes/search_results` fragment (first
page, `HX-Replace-Url` with `groupBy` and `groupDirection`) or the bare `htmx/results_grid_grouped`
(continuation, 204 when empty). `LibraryService.searchGrouped` runs one statement per page
(`SearchQueryBuilder.buildGroupedSearchSql`): the page's asset rows with their day and day count,
the next cursor from the last row, and the overall count on a first page only. `GroupedSearchResult`
carries the groups (`AssetDateGroup`: day, full count, assets), `total: Option[Int]`, `nextCursor`
and `continuesDay`. `Util.humanReadableDate` formats the headers. A grouped search has no page
number: `p` with `groupBy` is a 400, and `after` must come with `isContinuousScroll`.

Still to remove, once the frontend no longer needs it: the ungrouped JSON branch of the route
(`ids`/`page`/`totalPages`), used today only by the shadow results.

## Templates (done)

`views/htmx/result_cell.scala.html` is the one cell partial, used by both grids; a page's last
cell carries exactly one of `data-app-search-next-page` (ungrouped) or `data-app-search-after`
(grouped). `views/htmx/results_grid_grouped.scala.html` renders a `date-group` header per day
(`<time datetime="2025-01-02">`, the day's full count in `.count`), skipping the first header when
the group continues `continuesDay`. `views/includes/search_results.scala.html` takes the rendered
grid, the effective sort and grouping, and renders the Group dropdown (`#groupOptions`, options
carrying `data-app-search-group-by` and `data-app-search-group-direction`, with
`data-app-search-from-selected-option`) beside Sort; the header styles and `--view-tint`
(`core.css`) are in place.

## Frontend JS

### `stores/search-params.js`

`DEFAULTS` gains `groupBy: null, groupDirection: null`, serialized after `sort` and
before `rpp`. No `CLEARS` entry: grouping is a reorder and survives folder, person,
album and view changes like sort does. Any change still returns to page 1. A grouped search has
no page number, so the serializer leaves `p` out whenever `groupBy` is set (a `p` seeded from a
hand-edited URL would otherwise be a 400).

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
- Loading the next page becomes one exported function, `loadNextPage(lastCellEl)`: it reads the
  cell's continuation (`{ after }` or `{ p }`), deletes the attribute, requests the page through
  `runSearch` with `transient: { ...continuation, p: continuation.p ?? null, isContinuousScroll: true }`
  and swap `afterend`, and returns the request's promise. It keeps the pending promise per cell
  (a `WeakMap`), so a second caller while the page is in flight gets the same promise and nothing
  is requested twice. The observer calls it on intersection; the modal calls it at the end of the
  grid.
- The `.then(appendShadowResultsPage)` goes, as does `syncShadowResults()` at hydrate.

### `search-results/detail-navigator.js`

The grid is the modal's source of truth; there is no shadow list.

- The current asset ID lives in the coordinator (it was `shadowResults.currentAssetId`;
  `listeners/htmx-search.js` sets it when the modal opens).
- `handleShowNext`: from the current asset's cell (`#asset-<id>`), the next `.cell` sibling,
  skipping `.date-group` headers. At the end of the grid: if the last cell carries a continuation,
  `await loadNextPage(lastCell)`, then the cell that now follows; otherwise nothing. Cells inserted
  while the modal is open lazy-load their thumbnails as usual.
- `handleShowPrevious`: the previous `.cell` sibling; none means the start of the results.
- `syncShadowResults`, `appendShadowResultsPage`, `fetchSearchResultsJson`,
  `fetchShadowSearchResultsPage` and the `shadowResults` store (`stores/app-stores.js`) are
  removed. Assets removed from the grid (`removeAssetsFromGrid`) drop out of navigation with
  their cells.

### Backend follow-up in this unit

With the shadow list gone, nothing requests search results as JSON: remove the ungrouped JSON
branch of `SearchResultsController` and the JSON part of its "unchanged" controller test.

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

1. **Date formatting.** Done: `Util.humanReadableDate` with a unit test.
2. **Grouped page with assets.** Done: `GroupedSearchResult` and `LibraryService.searchGrouped`,
   one statement per page, covered by `SearchGroupingTests` and `SearchCursorTests` on both
   engines.
3. **Grouped HTML branch.** Done: the controller, `browserViewUrl`, the templates, and
   `SearchResultsControllerTests`.
4. **Frontend.** Store parameters (and no `p` for a grouped search), the selected-option trigger
   source, the Group dropdown wiring, `loadNextPage` shared by the observer and the modal,
   grid-walking detail navigation with the shadow store removed, header count maintenance.
   Then the backend follow-up: remove the ungrouped JSON branch and its test. Verified in the
   browser, not by tests (frontend-only per `altitude/AGENTS.md`).
5. **Docs.** `altitude/AGENTS.md` (the JSON branch is gone; the frontend drives the grouped grid)
   and `altitude/views/AGENTS.md` (Search parameters: `groupBy`, `groupDirection`, `after`; the
   Group control; date headers; cursor infinite scroll and `loadNextPage`; the new trigger
   source; "Detail navigation" walks the grid, no shadow results). Move this plan to `plans/done/`.

## Verification

- `make compile`, `make test-unit`, `make test-controllers`, `make test-sqlite`;
  `make test-psql` while `altitude-core-postgres-test` is up. Never bare `make test`.
- `make lint` (`npm` needs `source ~/.nvm/nvm.sh`).
- Dev server: templates hot-reload; run `mill altitude.resources` after JS/CSS edits and
  confirm with `curl -s http://localhost:8080/static/js/... | grep <symbol>`. No schema
  change, so no restart or migration.
- Browser (Chrome MCP, DOM checks since the tab is hidden):
  - choose each Group option: headers appear in the right order with counts, cells follow
    the sort within each day, the URL carries `groupBy`/`groupDirection`,
    and a reload restores the grouping and the dropdown selection;
  - scroll to the end of a page: the next page lands with no duplicate header for a
    continued day and a header for a new day; the last page loads nothing further;
  - `getComputedStyle(header).position === "sticky"` and, after scrolling within a long
    day, the header's `getBoundingClientRect().top` equals `#content`'s;
  - open a thumbnail and step next/previous across a day boundary and across a page
    boundary: the order matches the grid;
  - the network log shows one HTML request per scrolled page and no JSON request at all;
  - open a thumbnail and step next past the loaded grid: one HTML page request, the grid grows
    behind the modal, and navigation continues in grid order; at the first cell, previous does
    nothing; at the true end, next does nothing;
  - recycle an asset from the modal, then step next and previous: the removed asset is skipped;
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
| Rendering | Client-rendered from the grouped JSON plus a "cells for these IDs" endpoint; or grouping in the full-asset SQL. | Server-rendered chosen: HTMX-first, no duplicate grouped SQL, one bounded statement per page. |
| Control placement | Radios in the View settings panel; a single Date Taken toggle; direction fixed to newest first. | Dropdown with direction chosen; it mirrors Sort and exposes the whole backend contract. |
| Header text | `Intl.DateTimeFormat` in the browser's locale. | Server-side English, consistent with every other date the app renders. |
| Remembering the grouping | `localStorage` like the metadata fields. | Declined: grouping is a search parameter, like sort, restored from the URL. |
| Fresh day totals on scroll | Re-sending a continued day's total with each page. | Not now: the header keeps its first total minus removals; live imports show after a refresh, as the backend plan documents. |
| Detail navigation | A shadow ID list, fed by JSON per page (as today) or read from the rendered cells (the first amendment). | Walk the grid: no list to keep in sync, no JSON branch, removed cells drop out by themselves. |
| Page numbers | Keep `p` for grouped results as direct page access. | Removed: nothing sends it, and it forced a page ordinal into the cursor and a count onto every page. |
| Sort label | The Sort dropdown says "Date Created" where the glossary says Date Taken. | Out of scope; worth aligning when the bar is touched. |
| Follow-ups | Relative labels (Today, Yesterday) in front of the date; a date scrubber or jump-to-day built on the headers; a View Transition when the grouping changes (`transition:true` swap). | Not requested; noted for later. |
