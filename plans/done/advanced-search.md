# Advanced search

## Goals

- A filter panel, anchored under the nav's quick search, that combines in one search:
  - the Search text, the same text the quick search input holds;
  - one folder, chosen from an autocomplete combobox;
  - several Locations and several people, each chosen from an autocomplete combobox, shown as removable chips, people
    with their cover Face;
  - a date range over the capture time (Date Taken) and one over the import time (Date Imported).
- Every filter is AND-ed with the others. Several Locations match any of them; several people match all of them or any
  of them, by a switch beside the People box.
- A folder's ⋯ menu has a **Search here** entry, which opens the panel with that folder already chosen.
- One design on both engines, sized for a library of a million assets on PostgreSQL and of 100,000 on SQLite.

Out of scope: metadata value filters, albums, Categories as a filter, several folders, free boolean nesting (OR across
different filters, "none of"), searching the trash, saved searches, and typed operators in the Search text
(`person:alice`).

## How it works

**Request parameters.** The search routes (results, map cells, map bounds) take, through `SearchRequestParser.parse`
over one `SearchRequestParser.Params` built by name per request:

| Parameter | Value |
|---|---|
| `q` | The Search text. |
| `folderId` | One folder ID. |
| `personId` | One person ID or several, comma-separated. |
| `locationId` | One Location ID or several, comma-separated. |
| `peopleMatch` | `all` or `any`; `all` when left out. It only matters with two people or more. |
| `takenFrom`, `takenTo` | ISO dates (`2024-06-01`), the Date Taken range. |
| `importedFrom`, `importedTo` | ISO dates, the Date Imported range. |

A 400: a date that is not an ISO day with a four-digit year, a range whose start is after its end, an unknown
`peopleMatch`, more than 10 distinct IDs in one parameter (`MAX_IDS`), a date range or several IDs with the trash view,
and an album or a Triage subset beside what only the panel combines. Empty segments of an ID list name nothing, so
`personId=` is no person filter. `triaged=true` still adds the triaged assets. `Scope` carries the people and Locations
as ordered lists, `peopleMatch` (`PeopleMatch.All` / `Any`) and the two ranges (`DateRange(from, to)`, either end
optional, which refuses a range with no end or a reversed one); `SearchQuery.copyWith` keeps them and
`SearchCursor.scopeFingerprint` includes all three, so a cursor of one search cannot continue another. The Triage
routes name one folder, person or Location for their scope dialogs and take none of the new parameters.

**Predicates.** All in `SearchQueries.matching`, so every page shape, count and map aggregate has them:

- **People, all**: one semi-join per chosen person (`personFilter` over a set of one, AND-ed in ID order). **People,
  any** is `personFilter` over the set. With one person the two render the same statement.
- **Date Taken range**: a comparison on `engine.day(asset, original_created_at)`, the expression
  `asset_search_date_taken` is built on. A day is the camera's wall-clock day, as the day grouping has it. `NULL` fails
  the comparison, so an asset with no capture time matches no range, an open-ended one included.
- **Date Imported range**: a half-open range on the raw `created_at`, from the start of the first day to the start of
  the day after the last, in the server's time zone on both engines, bound as an `OffsetDateTime` (an instant on
  PostgreSQL, UTC text on SQLite). The upper bound is left out when the day after the last is past year 9999.

A range is whole calendar days, both ends inclusive. On SQLite the grid and the Detail panel show an import time in
UTC, so on a server east of UTC an asset shown as imported late on one day can fall in the next day's range.

**Indexes.** `asset_search_created` carries `id`, `is_triaged` and `original_created_at`; `asset_search_date_taken`
carries `id`, `folder_id`, `is_triaged` and `created_at` (`INCLUDE` on PostgreSQL, trailing key columns on SQLite). A
Date Taken range sorted by Date Imported therefore builds its page from `asset_search_created` alone, and a Date
Imported range sorted by Date Taken from `asset_search_date_taken` alone, with or without `triaged=true`. The triage
indexes (`asset_triage_created`, `asset_triage_date_taken`) carry neither date of the other: only the triage view reads
them, and its cost follows the triage set. A count within a Date Taken range reads an index alone. People-all reads
`face_02` once per person on SQLite; on PostgreSQL the total reads `face_02` and the page probes `face_01` by asset
along the sort's index. Both `all.sql` files hold the definitions; there is no versioned migration.

**Name options.** `SearchPanelController` (`htmx/search`), every route any account's with access to the Library:

| Route | Answer |
|---|---|
| `GET /htmx/search/r/:repoId/options/folders?q=` | Folders by path (`Trips › 2024 › Lisbon`), root left out. |
| `GET /htmx/search/r/:repoId/options/people?q=&triaged=` | Searchable people (named, not hidden, not merged away, not a bad match) with something to find: cover Face, name, and the number of assets a search of the person finds, which leaves the triaged assets out unless `triaged=true` (a 403 for a Guest). |
| `GET /htmx/search/r/:repoId/options/locations?q=` | Locations with their Category as a prefix (`Portugal › Lisbon`). Categories are not options. |
| `GET /htmx/search/r/:repoId/panel` | The panel's form, filled in from the parameters of a search. |

An options route answers at most 50 (`Const.Search.MAX_FILTER_OPTIONS`) bare `li[role=option]` elements for the text
typed, matched as the Search text matches names (`SearchTerm.isIn` over the option's own name), in name-key order, the
first 50 kept without sorting the rest. Each reads only its own name source (`SearchDao.searchNames(sources)`). The
panel's chips are resolved by ID (`SearchService.filterNames`: `FolderService.getPath`, `PersonService.getLive`,
`LocationService.getLocationsByIds`); an ID that names nothing in the Library, or another Library's, is dropped from
the chips and the form's values. The cover is the People tab's content URL.

**The panel.** A native `popover="auto"` dialog (`#searchPanel`) in `includes/nav`, on every page, placed under
`#searchForm` by CSS anchor positioning: centered, 6px below, 26rem wide. A sliders button inside the quick search form
opens it and carries the number of filled filters as a badge; a plain folder, person, Location or text scope counts
as one. Each open loads the form for the search in force plus the nav input's unsubmitted text
(`js/search-results/search-panel.js`, `openSearchPanel(filters)`); the panel's text and the nav's input are written to
each other on open and on search.

- **Comboboxes**: `filterCombobox` (`js/alpine/components/filter-combobox.js`), on the ARIA combobox pattern, with an
  options URL and a `multiple` flag. Typing requests options 150 ms after the last key and only the latest answer is
  shown; arrows, Enter and a click choose; Enter while an answer is pending picks the first option of that answer; a
  chosen item becomes a chip with a × and leaves the list; Backspace in an empty input removes the last chip; the
  folder box replaces its one chip. The matched start of a word is marked on the client. At 10 chips the field
  requests nothing and says so. A status line announces options, adds and removes.
- **Date ranges**: `dateRange` (`js/alpine/components/date-range.js`) over Flatpickr 4.6.13 in range mode, two months,
  the week starting by the browser's locale. The calendar is a manual popover inside its field, so it is drawn above
  the panel and a click in it does not dismiss the panel. The field reads `8 Jun – 21 Jun 2024`, `8 Jun 2024` for one
  day (sent as both ends), `from …` / `until …` for an open end; the month names are the same three letters on the
  server and the client. A × clears the field. A range across two years takes width from the other date field, which
  goes under it when both cannot share the row.
- **People switch**: All / Any, shown once two people are chosen; `peopleMatch=any` is sent only then.
- **Actions**: **Search** (also Enter in the text field) and **Clear**, which empties every field without searching.
  Escape closes the listbox, then the calendar, then the panel; a click outside closes the panel; neither searches.
  Focus returns to the control that opened the panel, or to the filter button when a menu opened it.

Flatpickr is vendored byte-identical to its release (`static/js/lib/flatpickr.min.js`, `flatpickr.LICENSE`,
`static/css/flatpickr.css`) and loaded on first use (`loadFlatpickr`), its stylesheet in a CSS layer of its own, below
the app's rules; it is not imported into `app.css`. The panel's styles are `static/css/htmx/search_panel.css`; the
tokens `--radius-xl`, `--search-panel-background-color`, `--search-panel-backdrop-filter`, `--search-panel-width`, the
cover sizes and `--listbox-max-height` are in `core.css`. Field boxes, the listbox and the calendar wear the app's
field and floating-surface styles. The field labels are "Search text", "Folder", "People", "Locations", "Date Taken"
and "Date Imported".

**Scope.** `searchParams` holds `peopleMatch`, `takenFrom`, `takenTo`, `importedFrom` and `importedTo`, serialized
after `locationId`, and normalizes them: an invalid day, an unknown match, a reversed range, a match with fewer than
two people, more than 10 IDs, and an album or subset beside panel filters are dropped. `replaceFilters(filters)` sets
`q`, `folderId`, `personId`, `locationId` and the five as given and resets `view`, `albumId`, `subset` and `bbox`; the
layout, grouping, sort, page size and triage filter survive. In Browse the panel calls `runSearch`; on any other page
it navigates to Browse with the query string. A folder, person, album, Location, subset or view chosen in the explorer,
and the quick search input, drop the panel's filters. A search of several Locations marks no row in the explorer.
`replaceId` swaps one ID of a search for another and keeps the other filters: a merged person, a deleted Location.

**Results title.** A search is a **filtered search** when it has a date range, two people or more, two Locations or
more, or more than one of text, a folder other than the root, a person and a Location
(`SearchResultsController.isFiltered`). Its title is "Search results" over a row of chips in the panel's field order:
the text, the folder's path, "all of" / "any of" before two people or more, each person with cover, each Location,
`Taken 1 Jun – 30 Jun 2024`, `Imported …`; a chip that is no person leads with its kind's icon. A chip's × removes the
chip and searches by the chips left, so an ID with no chip goes with it. **Edit** opens the panel. A first page
resolves the names; a continuation does not. An unknown ID in a filtered search matches nothing and has no chip; a
search that is not filtered keeps its title, and its one unknown ID is a 404. A renamed person's chip is refreshed by
searching again. The panel and the title share `includes/search_chip`, `search_person_cover` and
`search_filter_icon`.

**Search here.** A non-root folder's ⋯ menu starts with **Search here**, then a separator, then the folder's actions.
It opens the panel with that folder chosen and every other field as the search in force has it; nothing is searched
until **Search**. It needs no Permission. The root folder has no such entry.

## Decisions

| Decision | Choice |
|---|---|
| Filters | Search text, one folder, Locations, people, Date Taken range, Date Imported range. No metadata. |
| How filters combine | AND across filters. Locations: any. People: an Any/All switch, **All** when the URL does not say. |
| Where the panel lives | A popover anchored under the nav's quick search form. |
| Explorer scope | An advanced search replaces it; a click in the explorer clears the panel's filters in turn. |
| People offered | Searchable people with at least one asset the search would find. |
| Selection size | At most 10 people and 10 Locations in one search. |
| Date fields | Flatpickr in range mode, vendored; the week starts by the browser's locale. |
| Date Imported day | A day in the server's time zone, on both engines. |
| Several Locations | No row is marked in the explorer. |
| Indexes | Each of the two search indexes carries the other date, on both engines. |
| Scale | 1M assets on PostgreSQL, 100k on SQLite, one design. |

## Changes

### A. Query model and predicates

1. `DateRange`, `PeopleMatch`; `SearchQuery` and `Scope` fields `peopleMatch`, `takenRange`, `importedRange`, kept by
   `copyWith`; `scopeFingerprint` includes them.
2. `SearchQueries.matching`: people-all as per-person semi-joins, the Date Taken predicate through `engine.day`, the
   Date Imported predicate in the server's zone.
3. `asset_search_created` and `asset_search_date_taken` carry the other date, in both `all.sql` files.
4. Tests: `SearchQueryModelTests`, `SearchSqlTests`, `SearchCursorTests`, and `SearchServiceTests` for all versus any,
   inclusive ends, undated assets, server-zone midnights, each filter with text, a folder subtree and a grouping, and
   the plans on both engines.

### B. Routes

1. `SearchRequestParser`: `Params`, the comma-separated IDs, `peopleMatch`, the four dates, the 400s; the results and
   map routes pass them through, and `HX-Replace-Url` carries them.
2. `SearchPanelController`: the three options routes and the panel route, with `htmx/search_panel`,
   `htmx/search_options_folders`, `_people`, `_locations`.
3. Tests: `SearchRequestParserTests`, `SearchResultsControllerTests`, `MapControllerTests`,
   `SearchPanelControllerTests`, `RouteTableTests`, `PermissionControllerTests`.

### C. Panel and combobox

1. `filterCombobox`, its styles, its style guide specimen.
2. Flatpickr vendored; `dateRange`; the tokens in `core.css` and the style guide.
3. The popover host and filter button in `includes/nav`, the load on open, the text kept in step.
4. `searchParams`: the five parameters, `replaceFilters`, `replaceId`, the clearing rules; Search and Clear.

### D. Results title, Search here, docs

1. The filtered search's title with its chips and Edit (`includes/search_filter_chips`,
   `js/search-results/filtered-title.js`).
2. **Search here** in the folder menu; script and separator actions in `context-menu-markup.js`.
3. Docs: `altitude/AGENTS.md`, `altitude/views/AGENTS.md`, `CONTEXT.md` (**Advanced search**, **Filtered search**,
   **Search filter**), `docs/READINESS.md`, `docs/test-coverage.md`, `static/js/lib/README.md`.

## Verification

- `make test-unit` (307), `make test-controllers` (286), `make test-sqlite` (703, one cancelled as PostgreSQL-only)
  and `make test-psql` (702) pass, and `make lint` is clean. `PersonServiceTests` "The live person holding a name is
  looked up through the name-key index" fails now and then on PostgreSQL on a planner tie between `person_01` and
  `person_03`, and passes on a rerun.
- In the browser, on the real app with trusted input: the panel on Browse, Settings, Users and Import; the comboboxes
  by keyboard and mouse; ranges by mouse and keyboard in both themes; a Date Taken range under the day grouping, the
  Location grouping and the map; the scope rules; reload and infinite scroll; the title's chips, Edit and Search
  here; the style guide. The dev library holds no named people, Categories, nested folders or albums, so people
  options, the cap of 10 and the folder's replacement are checked with a stubbed options answer, and a rename or a
  merge by its client event. Not checked in the browser: a chip refreshed after a person is named in the Detail panel.
- Measured once on scratch databases, seeded and analyzed, removed afterwards: 1M assets on PostgreSQL in its
  container defaults, 100k on SQLite, 28,000 folders on both. Medians over HTTP, warm cache, first page with its
  total. The PostgreSQL medians of the first, second and fifth rows are with `asset_search_created` carrying
  `original_created_at`; SQLite is measured with the indexes that carry no other date.

  | Request | PostgreSQL 1M | SQLite 100k | Target |
  |---|---|---|---|
  | Date Taken range of one month, default sort | 105 ms | 37 ms | 150 ms |
  | Date Taken range of half the library, sorted by Date Imported | 56 ms | 52 ms | 500 ms |
  | Two people, all, each on about 60k assets (6k on SQLite) | 937 ms | 61 ms | 300 ms |
  | Text with at most 5,000 hits plus a folder, a person and a range | 160 ms | 119 ms | 300 ms |
  | Filters matching nothing inside half the library: a person | 354 to 688 ms | 39 to 44 ms | 1 s, 500 ms |
  | The same with a broad Search text, or a folder subtree | 3.4 s, 1.6 s | 100 ms, 66 ms | 1 s, 500 ms |
  | Folder options for one typed word | 25 ms | 39 ms | 100 ms |

  On PostgreSQL the third row and the sixth are over their targets: `asset`'s IDs are stored out of line
  (`toast_tuple_target = 128`), so a plan that reads them from the table pays a TOAST lookup per row. With the IDs in
  the row the same requests measure 125 ms, 202 ms and 478 ms. Folder options with nothing typed read their own name
  source and keep the first 50 without a full sort: 28 ms on PostgreSQL and 42 ms on SQLite in the service, not
  measured over HTTP. A Date Imported range of 72% of the library sorted by Date Taken runs its statement in 41 ms
  with `asset_search_date_taken` carrying `created_at`.
