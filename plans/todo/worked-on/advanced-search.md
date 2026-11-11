# Advanced search

## Original goals

- A filter panel, anchored under the nav's quick search, that combines in one search:
  - the Search text, the same text the quick search input holds;
  - one folder, chosen from an autocomplete combobox;
  - several Locations and several people, each chosen from an autocomplete combobox, shown as removable chips, people
    with their cover Face;
  - a date range over the capture time (Date Taken) and one over the import time (Date Imported).
- Every filter is AND-ed with the others. Several Locations match any of them; several people match all of them or any
  of them, by a switch beside the People box.
- A folder's ⋯ menu has a **Search here** entry, which opens the panel with that folder already chosen.
- A library of a million assets on PostgreSQL and of 100,000 on SQLite answers within the targets under
  **Verification**. The design is the same on both engines; SQLite gets no tuning of its own.

Out of scope: metadata value filters, albums, Categories as a filter, several folders, free boolean nesting (OR across
different filters, "none of"), searching the trash, saved searches, and typed operators in the Search text
(`person:alice`).

## Status

Not started.

## Confirmed decisions

| Decision | Choice |
|---|---|
| Filters in the first version | Search text, one folder, Locations, people, Date Taken range, Date Imported range. No metadata. |
| How filters combine | AND across filters. Locations: any. People: an Any/All switch, **All** when the URL does not say. |
| Where the panel lives | A popover anchored under the nav's quick search form. |
| Explorer scope | An advanced search replaces it: it clears the album, the Triage subset, the map area and the view, and searches the repository view by the panel alone. A click in the explorer clears the panel's filters in turn. |
| People offered | Named, not hidden, not merged away, not a bad match: the people the Search text resolves names against (`SearchQueries.searchablePeople`). |
| Folder menu | **Search here** opens the panel with the folder chosen and the other fields as the current search has them. |
| Scale | 1M assets on PostgreSQL, 100k on SQLite, one generic design. |
| Date fields | Flatpickr in range mode, vendored. |
| Selection size | At most 10 people and 10 Locations in one search. |

Defaults taken, to change on request:

- A date range is whole calendar days, both ends inclusive, either end optional. A Date Taken day is the camera's
  wall-clock day, as the day grouping has it; an asset with no capture time matches no Date Taken range. A Date
  Imported day is a day in the server's time zone, the zone import times are shown in.
- A chosen folder means the folder and every folder below it, as a folder scope does today.
- Each date range is one Flatpickr field in range mode. One day picked alone is that single day; the panel offers no
  open-ended range, though the server takes one.

## Current behavior

- `SearchQueries.matching` is the one relation every page, count, group count and map aggregate reads. It AND-s the
  view flags, a folder set, the Search text, a person set (any), an album set, a Location set (any), a Triage subset
  and a map box. There is no date predicate and no "all of these people".
- `SearchResultsController.htmxSearchResults` and the Triage and map routes take one `folderId`, `personId`, `albumId`
  and `locationId` each; `SearchRequestParser.parse` turns each into a set of at most one. The server accepts text
  together with a folder, a person or a Location.
- The browser never sends such a combination: in `stores/search-params.js` text, a folder, a person, an album, a
  Location and a subset each clear the others (`CLEARS`).
- `LibraryService.withResolvedFolderScope` expands one folder to its subtree and refuses more than one.
- `SearchCursor.scopeFingerprint` identifies a search to its cursors by its text, flags and ID sets.
- `SearchDao.searchNames` reads the repository's people, Locations, Categories, folders and albums as `SearchName`s,
  and `SearchTerm.isIn` matches a typed term against a name's `SearchWords.variants`: a word matches the start of a
  word.
- The nav's quick search (`includes/nav.scala.html`, `js/search-results/search-input.js`) is a form on every page; its
  value follows `searchParams.q`.
- The only combobox is `faceCombobox` (the Change combobox of the Detail panel), built on the ARIA combobox pattern
  with server-rendered options (`htmx/assignable_people`). It is specific to Faces.
- A folder's ⋯ menu is built by `js/common/context-menu-markup.js`.
- The results title (`includes/results_title`) names one scope: a folder's path, an album, a Location, a person or
  the Search text.

## Design

### Request parameters

The search routes (results, Triage counts, map cells, map bounds, map Locations) take, through
`SearchRequestParser.parse`:

| Parameter | Value |
|---|---|
| `q` | The Search text, unchanged. |
| `folderId` | One folder ID, unchanged. |
| `personId` | One person ID or several, comma-separated. |
| `locationId` | One Location ID or several, comma-separated. |
| `peopleMatch` | `all` or `any`; `all` when left out. It only matters with two people or more. |
| `takenFrom`, `takenTo` | ISO dates (`2024-06-01`), the Date Taken range. |
| `importedFrom`, `importedTo` | ISO dates, the Date Imported range. |

A malformed date, a range whose start is after its end, an unknown `peopleMatch`, or more than 10 IDs in one
parameter is a 400. An ID that names nothing in the repository matches nothing, as today. A date range or several
IDs with the trash view is refused, as text is: an advanced search searches the repository view. `triaged=true`
still adds the triaged assets.

`Api.Field.Search` names the new parameters; `Scope` and `SearchQuery` carry `peopleMatch`, `takenRange` and
`importedRange` (a `DateRange(from: Option[LocalDate], to: Option[LocalDate])`), and `SearchQuery.copyWith` keeps
them. `SearchCursor.scopeFingerprint` includes all three, so a cursor of one range cannot continue another.

### Predicates

All in `SearchQueries.matching`, so every page shape, count and map aggregate has them:

- **People, all**: one semi-join per chosen person (`personFilter` over a set of one, AND-ed). Each reads `face_02`
  (`person_id` first, carrying `asset_id`), so the cost follows the smallest of the chosen people's Face sets.
  **People, any** is `personFilter` as it stands.
- **Date Taken range**: a comparison on `engine.day(asset, original_created_at)`, the exact expression
  `asset_search_date_taken` is built on, so a range is a seek in that index and a count over it reads the index
  alone. `NULL` fails the comparison, which is the "undated assets never match" rule.
- **Date Imported range**: a half-open range on the raw `created_at` column, from the start of the first day to the
  start of the day after the last, both taken in the server's zone and bound as the engine stores an import time
  (an instant on PostgreSQL, UTC text in `SqliteOverrides.DATETIME_FORMATTER` on SQLite). It is a seek in
  `asset_search_created`.

No index is added and no existing one changes. Neither `all.sql` file is touched.

### Expected performance

By the shape of the plans, not measured:

| Search | What bounds it | Expected at 1M (PostgreSQL) |
|---|---|---|
| Date range alone, any sort | The range, read from its index; the total stops at 10,001 | As Browse today |
| People or Locations, any size of selection | The chosen sets, through the membership indexes | Tens of ms for ordinary sets |
| Text plus filters | The text path as today; the filters only remove rows | As the text alone, or faster |
| A date range sorted by the other date | Either the range is read and sorted, or the sort's index is walked and each row's other date is read from the table | Fast for a narrow or a wide range; a mid-size range is the case to measure |
| Filters that match almost nothing in a large scope | The capped total, which reads on until the scope ends | The slowest case; bounded by the read statement timeout |

On SQLite at 100k every one of these is a pass over at most 100k index entries.

### Name options

Three routes in a new `SearchPanelController` (`htmx/search`), each returning an HTML fragment of at most 50
`role="option"` elements for the text typed so far, matched as the Search text matches names (`SearchTerm.isIn` over
the names of `SearchDao.searchNames`, read in a read-only transaction):

| Route | Options |
|---|---|
| `GET /htmx/search/r/:repoId/options/folders?q=` | Folders by path (`Trips › 2024 › Lisbon`), root left out. |
| `GET /htmx/search/r/:repoId/options/people?q=` | Searchable people: cover Face (the signed cover URL the People tab uses), name, number of assets. |
| `GET /htmx/search/r/:repoId/options/locations?q=` | Locations with their Category as a prefix (`Portugal › Lisbon`). Categories themselves are not options. |

With nothing typed, people and Locations list their first 50 by name and folders their first 50 by path. Matching on
the server keeps a 28k-folder tree out of the DOM and keeps one matching rule.

### The panel

`GET /htmx/search/r/:repoId/panel` takes the same parameters as a search and returns the panel's form with them
filled in: the text, the folder as a chip with its path, each person as a chip with cover and name, each Location as
a chip, the Any/All switch, and the two date ranges. The names behind the IDs in a URL are therefore resolved on the
server, and an ID that no longer exists is dropped.

- **Host**: a native `popover="auto"` panel in `includes/nav`, anchored under `#searchForm` by CSS anchor
  positioning, as the context menus are. A filter button inside the quick search form opens it (`popovertarget`),
  and opening loads the fragment for the current `searchParams`. Its surface and measures are under **Look**
  below. It is the second inline dialog in a popover panel after View settings; the rule in `views/AGENTS.md`
  says so.
- **Text**: the panel's text field and the nav's input are the same value. Typing in either is copied to the other
  when the panel opens and when it searches.
- **Combobox**: one Alpine component, `filterCombobox` (`js/alpine/components/filter-combobox.js`), on the ARIA
  combobox pattern of `../../../docs/reference/alpine-components/headless-combobox.md`, with an options URL and a `multiple`
  flag. Typing requests the options (debounced, the latest request wins); arrows, Enter and a click choose; a chosen
  item becomes a chip with a remove (×) button and leaves the list; Backspace in an empty input removes the last
  chip. Single mode (the folder) replaces its one chip. The three boxes differ only in their options fragment and
  chip markup. `faceCombobox` stays as it is: its modes, Suggested group and armed options are the Face
  correction's own.
- **Date ranges**: two fields, Date Taken and Date Imported, each one Alpine component, `dateRange`
  (`js/alpine/components/date-range.js`), over Flatpickr in range mode (`mode: "range"`, `dateFormat: "Y-m-d"`): both
  ends are picked on one calendar with the span highlighted, and the arrow keys move through it. The component holds
  the range as its `from` and `to` ISO dates, which the form submits as `takenFrom`/`takenTo` or
  `importedFrom`/`importedTo`; one day picked alone is sent as both. Each field has a clear (×) control. The calendar
  is appended inside the panel (`appendTo`), not to `body`: the panel is in the top layer, so a calendar outside it
  would be drawn beneath it and a click on it would light-dismiss the panel. Flatpickr is vendored as the other
  libraries are (`static/js/lib/flatpickr.min.js` with its license, `static/css/flatpickr.css`, both byte-identical
  to the release); its colors, radii and spacing are overridden with the theme tokens in
  `static/css/htmx/search_panel.css`, so it follows the light and dark themes.
- **People switch**: the switch control of the style guide, labelled All / Any, shown once two people are chosen.
- **Actions**: **Search** (also Enter in the text field) and **Clear** (empties every field without searching).
  Escape and light dismiss close the panel without searching.

### Look

Settled in `../advanced-search-playground.html`. Styles are in `static/css/htmx/search_panel.css`.

| Part | Look |
|---|---|
| Position | Centered under the quick search form (`justify-self: anchor-center`), 6px below it, 26rem wide. |
| Surface | Frosted glass of its own, a little clearer and more blurred than the popover dialog's: the neutral-2 surface at 81% under a 15px blur, with the popover's hairline border and `--float-shadow`. Two new veil tokens in `core.css` hold it, `--search-panel-background-color` and `--search-panel-backdrop-filter`. |
| Corners | 12px, a new size token `--radius-xl` (0.75rem); the scale stops at `--radius-large` (8px). |
| Spacing | Chosen as 12px of padding and 21px between fields. Neither is a step of the `--space-*` scale, so each takes the nearest one: `--space-5` (10px) of padding, `--space-7` (20px) between fields. |
| Fields | One column, each label above its field in sentence case, the two date ranges side by side, 14px text. |
| Chips | Rounded rectangles (`--radius`) with the app's chip gradient (`--chip-fill`), inside the input. A person's chip leads with the cover Face as a 20px circle. |
| All / Any | The switch control. |
| Options list | Regular rows, 28px cover Faces, each person's number of assets, the matched start of a word highlighted. |
| Calendar | Two months; a chosen range reads `8 Jun – 21 Jun 2024`; a calendar icon leads each date field. |
| Actions | Clear at the left, a secondary button; Search at the right, the accent button. |
| Nav | The filter button is a sliders icon and carries the number of active filters as a badge. |

### What a search from the panel does to the scope

`searchParams` gains `peopleMatch`, `takenFrom`, `takenTo`, `importedFrom` and `importedTo`, serialized after
`locationId`, and a `replaceFilters(filters)` method beside `merge`: it sets `q`, `folderId`, `personId`,
`locationId` and the five new parameters as given, and resets `view`, `albumId`, `subset` and `bbox` to their
defaults. The layout, grouping, sort, page size and the triage filter survive, under the same rules as `merge`
(`withoutRefusedCombinations`). On the main page the panel calls `runSearch`; on any other page it navigates to
Browse with the query string, as the quick search does.

The other direction stays the existing rule, extended: the five new parameters join what "looking somewhere else"
clears, so a folder, person, album, Location, subset or view chosen in the explorer drops the panel's filters, and
the quick search input, which sets `q` alone, drops them too.

### Results title

A search is a **filtered search** when it has a date range, two people or more, two Locations or more, or more than
one of text, folder, person and Location. Its results title is "Search results" over a row of chips, one per filter
(the text, the folder's path, each person with cover, each Location, each date range as `Taken 1 Jun – 30 Jun 2024`),
each with a × that removes that filter and searches again (a `searchParams` change through `replaceFilters`), and an
**Edit** control that opens the panel. A search that is not filtered keeps the title it has today, so a single
person's title, with its actions, is unchanged.

### Search here

`context-menu-markup.js` adds **Search here** to a folder's ⋯ menu, above the folder's actions. It opens the panel
with `folderId` set to that folder and every other field as the current search has it; nothing is searched until
**Search**. The root folder has no such entry: a root scope is no filter.

## Tasks

Each server task is red-green-refactor with integration tests; the frontend tasks are verified in the browser.

### A. Query model and predicates

1. `DateRange`; `SearchQuery` fields `peopleMatch`, `takenRange`, `importedRange`, kept by `copyWith`;
   `scopeFingerprint` includes them.
2. `SearchQueries.matching`: people-all as per-person semi-joins, the Date Taken predicate through `engine.day`, the
   Date Imported predicate with engine-specific binds.
3. Tests: `SearchSqlTests` for the rendered predicates; `SearchServiceTests` for all versus any, inclusive range
   ends, undated assets, a range across a server-zone midnight, and each filter combined with text, a folder subtree
   and a grouping; plan tests over `SearchPlans` that a Date Taken range reads `asset_search_date_taken`, a Date
   Imported range reads `asset_search_created`, and people-all reads `face_02`, on both engines.

### B. Routes

1. `SearchRequestParser.parse`: comma-separated `personId` and `locationId`, `peopleMatch`, the four dates, the 400s
   and the trash refusal; every caller (results, Triage, map) passes the new parameters through.
2. `SearchPanelController`: the three options routes and the panel route, with their templates
   (`htmx/search_panel`, `htmx/search_options_folders`, `_people`, `_locations`).
3. Tests: parser unit tests, `SearchResultsControllerTests` for the new parameters, controller tests for the options
   (matching rule, the cap of 50, repository isolation, hidden and unnamed people left out) and the panel (chips
   resolved, a stale ID dropped).

### C. Panel and combobox

1. `filterCombobox`, registered in `js/alpine/components/index.js`; its styles in `static/css/htmx/search_panel.css`;
   a specimen in the style guide.
2. Flatpickr vendored (script, license, stylesheet, a row in `static/js/lib/README.md`, the stylesheet in `app.css`'s
   import order); `dateRange`, registered beside `filterCombobox`; its token overrides; a specimen in the style guide.
   The tokens of **Look** (`--radius-xl` and the panel's two veil tokens) in `core.css` and in the style guide.
3. The popover host and filter button in `includes/nav`, the fragment load on open, the text field kept in step
   with the quick search input.
4. `searchParams`: the new parameters, `replaceFilters`, the extended clearing rules; Search and Clear.

### D. Results title, Search here, docs

1. The filtered search's results title with its chips and Edit.
2. **Search here** in the folder menu.
3. Docs in the same change as the code they describe: `../../../altitude/AGENTS.md` (the parameters, the predicates and
   which index each reads, the options routes), `altitude/views/AGENTS.md` (the panel, `filterCombobox`, the scope
   rules, the results title, the popover rule, `dateRange` and the vendored stylesheet), `CONTEXT.md` (**Advanced search**, **Filtered search**, **Search
   filter**), `docs/READINESS.md` (autocomplete now exists), `docs/test-coverage.md`.

## Verification

- `make test-unit`, `make test-sqlite`, `make test-controllers` and `make test-psql`, each task red before its change
  and green after; then `make lint`.
- Browser checks on `localhost:8080`:
  - open the panel from the nav on the main page and on Settings; search from both;
  - choose and remove people and Locations by keyboard and by mouse; flip All / Any and see the total change;
  - pick a range by mouse and by keyboard, a single day, and clear it, in the light and the dark theme; the calendar
    stays inside the panel and does not close it;
  - a Date Taken range under the day grouping, the Location grouping and the map layout;
  - remove filters one by one from the results title; Edit reopens the panel with the rest;
  - **Search here** on a folder, with and without a search already running;
  - a folder click in the explorer after a filtered search returns to the plain folder scope;
  - reload a filtered search's URL and continue it by infinite scroll.
- A one-off check on scratch databases, seeded and analyzed, removed afterwards: 1M assets on PostgreSQL, 100k on
  SQLite. Medians, warm cache, first page with its total:

  | Request | PostgreSQL 1M | SQLite 100k |
  |---|---|---|
  | Date Taken range of one month, default sort | ≤ 150 ms | ≤ 150 ms |
  | Date Taken range of half the library, sorted by Date Imported | ≤ 500 ms | ≤ 500 ms |
  | Two people, all, each on about 60k assets | ≤ 300 ms | ≤ 300 ms |
  | Text with at most 5,000 hits plus a folder, a person and a range | ≤ 300 ms | ≤ 300 ms |
  | Filters matching nothing inside a 500k-asset range | ≤ 1 s | ≤ 500 ms |
  | Folder options for one typed word, 28k folders | ≤ 100 ms | ≤ 100 ms |

  A request over its target is the trigger for an index change (the first candidate is carrying
  `original_created_at` in `asset_search_created`); none is made ahead of a measurement.
