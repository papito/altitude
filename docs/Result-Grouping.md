# Search Result Grouping by Date

How the search results grid groups images by calendar day, how a grouped page is
fetched with one SQL statement on either engine, and how the client walks the
result set as you scroll.

Introduced on `feature/grouping`. The design notes that preceded it are in
`plans/done/search-results-date-grouping.md` (implementation),
`plans/done/search-results-date-grouping-ui.md` (client), and
`plans/done/search-results-date-grouping-benchmark.md` (the query experiment the
shape below was chosen from).

## What "a day" is

Grouping is by calendar day, taken from one asset column
(`core/util/SearchGrouping.scala`):

| API value   | Column                      | Meaning                              |
| ----------- | --------------------------- | ------------------------------------ |
| `dateTaken` | `asset.original_created_at` | EXIF capture time, camera wall-clock |

`GroupBy` is an enum with one case rather than a bare flag: the day column and
the API value belong together, and another grouping dimension would be one more
case. Grouping by import day (`asset.created_at`) existed and was removed — an
import dumps a whole archive into one or two day groups, which says nothing
about the photos. Import time remains available as a *sort*.

A day only means something if the timestamp is not re-projected through a zone,
so capture time is stored **without** one. On PostgreSQL `original_created_at`
was changed from `TIMESTAMP WITH TIME ZONE` to `TIMESTAMP WITHOUT TIME ZONE`;
its day is `original_created_at::date`. On SQLite it is wall-clock text, so the
day is plain `date(col)` with no modifier.

To keep pgJDBC from handing wall-clock columns back as `java.sql.Timestamp` —
which represents an instant in the JVM zone, and would shift a value sitting in
the server's DST gap — `PostgresOverrides` installs a `RowProcessor` that reads
temporal columns as `LocalDateTime` / `OffsetDateTime` / `LocalDate`.

The capture day has its own index: the visibility predicate, then the day
expression, then the raw timestamp. A day range or a single-day count probe
seeks straight into it, and a grouped page ordered by that day reads in index
order.

```sql
-- postgres (resources/migrations/postgres/all.sql)
CREATE INDEX asset_search_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, (original_created_at::date), original_created_at);

-- sqlite (resources/migrations/sqlite/all.sql): same shape
CREATE INDEX asset_search_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, date(original_created_at), original_created_at);
```

Both engines index NULL entries, so an undated asset — one no metadata rung
produced a capture time for — is a seek away by `IS NULL`, and a prefix scan
crosses into that block in index order along with every dated day.

## Ordering

A grouped page is a fully ordered slice on three keys:

```
day <grouping direction>, <sort column> <sort direction>, id ASC
```

Date groups are the primary order; the search's own sort (filename, size,
dimensions, either date) applies **within** a day. The ID is a deterministic
tiebreaker — without it, a cursor cannot name an exact position. Grouping
direction and sort direction are independent: "oldest day first, largest file
first" is a legal combination.

`SearchQuery` enforces the preconditions: a grouped search needs a sort, needs a
page size, and is never continued by page number (`core/util/SearchQuery.scala`).

## One statement per page

`SearchQueryBuilder.buildGroupedSearchSql`
(`core/dao/jdbc/querybuilder/SearchQueryBuilder.scala`) emits a single statement
that yields the page's rows, each day's **full** match count, and — on a first
page only — the count of all matches. Every branch reuses the exact same
`FROM` / `WHERE` / `GROUP BY` / `HAVING`, so the counts can never drift from the
rows they describe.

### PostgreSQL

Group by day taken descending, sort by filename ascending, 50 per page, first
page:

```sql
WITH candidates AS MATERIALIZED (
  SELECT asset.id AS id, asset.original_created_at::date AS day, asset.filename AS sort_value
    FROM asset
   WHERE asset.repository_id = ? AND asset.is_recycled = ? AND asset.is_pipeline_processed = ?
   ORDER BY asset.original_created_at::date DESC, asset.filename ASC, asset.id ASC
   LIMIT 51                       -- one past the page: the "is there more" probe
), page AS MATERIALIZED (
  SELECT id, day, sort_value FROM candidates
   ORDER BY day DESC, sort_value ASC, id ASC
   LIMIT 50
), day_counts AS MATERIALIZED (
  SELECT p.day AS day,
         (SELECT count(*) FROM (
            SELECT asset.id FROM asset
             WHERE asset.repository_id = ? AND asset.is_recycled = ? AND asset.is_pipeline_processed = ?
               AND asset.original_created_at::date = p.day) AS m) AS n
    FROM (SELECT DISTINCT day FROM page) AS p
), total AS MATERIALIZED (
  SELECT count(*) AS n FROM (
    SELECT asset.id FROM asset
     WHERE asset.repository_id = ? AND asset.is_recycled = ? AND asset.is_pipeline_processed = ?) AS m
)
SELECT asset.*, p.day AS day, p.sort_value AS sort_value, d.n AS day_total,
       (SELECT count(*) FROM candidates) AS candidate_count, t.n AS total
  FROM page AS p
       JOIN asset ON asset.id = p.id
       LEFT JOIN day_counts AS d ON d.day = p.day
       CROSS JOIN total AS t
 ORDER BY p.day DESC, p.sort_value ASC, p.id ASC;
```

The `WHERE` above is the minimum. A real search appends whatever the request
carried, identically in every branch: full-text (`search_document.tsv @@
to_tsquery(?)`), folder / person / album `IN` filters, and user-metadata filters
with their `GROUP BY` + `HAVING count(...) >= n`.

### SQLite

Same skeleton; two engine-specific twists, and this one is a *continuation*
page (reached by cursor), so it also shows the cursor predicate and the missing
`total`:

```sql
WITH candidates AS MATERIALIZED (
  SELECT asset.id AS id, date(asset.original_created_at) AS day, asset.filename AS sort_value
    FROM asset
   WHERE asset.repository_id = ? AND asset.is_recycled = ? AND asset.is_pipeline_processed = ?
     AND date(asset.original_created_at) <= ?          -- redundant bound; lets the day index seek
     AND (date(asset.original_created_at) < ?
          OR (asset.filename > ? OR (asset.filename = ? AND asset.id > ?)))
   ORDER BY date(asset.original_created_at) DESC, +asset.filename ASC, asset.id ASC
   LIMIT 51
), page AS MATERIALIZED (
  SELECT id, day, sort_value FROM candidates
   ORDER BY day DESC, sort_value ASC, id ASC
   LIMIT 50
), day_counts AS MATERIALIZED (
  SELECT p.day AS day,
         (SELECT count(*) FROM (
            SELECT asset.id FROM asset
             WHERE asset.repository_id = ? AND asset.is_recycled = ? AND asset.is_pipeline_processed = ?
               AND date(asset.original_created_at) = p.day) AS m) AS n
    FROM (SELECT DISTINCT day FROM page) AS p
)
SELECT asset.id, asset.filename, ..., p.day AS day, p.sort_value AS sort_value, d.n AS day_total,
       (SELECT count(*) FROM candidates) AS candidate_count
  FROM page AS p
       JOIN asset ON asset.id = p.id
       LEFT JOIN day_counts AS d ON d.day = p.day
 ORDER BY p.day DESC, p.sort_value ASC, p.id ASC;
```

- **`+asset.filename`** — the unary plus keeps the term from matching any index.
  Without it SQLite's planner is tempted away from the day index by an indexable
  sort term, and then sorts every matching row. PostgreSQL needs no such hint
  (`secondarySortExpression` is overridden per engine).
- **Nullable import times.** SQLite's `created_at` can be null on legacy rows.
  Nothing groups by it, but it is a valid *sort* column, so `isNullableTimestamp`
  still lists it: the cursor comparison has to honor where SQLite places those
  nulls within a day.

### Why it is shaped this way

- **Materialized everywhere.** `candidates` is read twice (the page, and the
  probe count); `day_counts` is materialized so the per-day count runs once per
  distinct day rather than once per page row.
- **Narrow candidates.** The ordered slice selects only `(id, day, sort_value)`.
  Only the 50 rows that survive are joined back to `asset` for their columns.
- **`LIMIT rpp + 1`, not a second query.** `candidate_count > rpp` is the whole
  "has more" signal.
- **A cursor page skips the overall total.** Counting every match dominates the
  statement on a large library, and the footer total was already established by
  the first page. `total` is `Option[Int]`, present on first pages only.
- **No explicit `NULLS FIRST/LAST`.** Nulls land where each engine puts them
  natively (PostgreSQL last, SQLite first) and the cursor comparison is generated
  to agree (`nullsFirst(direction)`). Spelling out a `NULLS` clause would forfeit
  index-ordered reads on both engines.

`SearchDao.searchGrouped` reads `day`, `sort_value`, `day_total`,
`candidate_count` and `total` off the rows into a `GroupedSearchPage`;
`GroupedSearchResult.groupsOf` folds consecutive same-day rows into
`AssetDateGroup(date, total, assets)` in page order.

## The cursor

`SearchCursor` (`core/util/SearchCursor.scala`) is an opaque, versioned,
base64url JSON token holding `{day, sortValue, id, scope}`. It supplies a
**position only**: every request re-applies authorization and every filter, and
the values are bound, never inlined.

`sortValue` is a `SortValue` — `Text`, `Num`, `LocalTimestamp`, `UtcInstant` or
`Null` — keeping the sort key in exactly the type the engine returned, so a
continuation can bind it straight back against the stored column with no
conversion in between.

The predicate for "strictly after this position" is a lexicographic comparison
with independent directions: an earlier day, or the same day and a later sort
value, or the same sort value and a greater ID. Where the sort column is
nullable, the comparison honors the engine's native null placement.

`scope` is a SHA-256 prefix over engine, repository, text, params, metadata
filters, folder / person / album IDs, grouping and sort
(`SearchCursor.scopeFingerprint`). `LibraryService.searchGrouped` recomputes it
and rejects a mismatch with a 400, so a cursor can never be replayed against a
different search. It fingerprints a folder filter **as requested**, not as
expanded — descendant folders are re-resolved on every page, so a folder that
gained children mid-scroll does not invalidate the cursor.

Page size is deliberately *not* part of the cursor: a continuation may ask for a
different one.

## Request / response contract

`GET /htmx/search/r/:repoId` (`routes/web/partial/SearchResultsController.scala`).
Grouped parameters:

| Parameter          | Notes                                                    |
| ------------------ | -------------------------------------------------------- |
| `groupBy`          | `dateTaken` — presence turns grouping on                  |
| `groupDirection`   | `asc` \| `desc`, default `desc`                            |
| `sort`             | field name + direction digit, e.g. `filename0`; must be one of `Const.Search.SORT_FIELDS` |
| `rpp`              | 1 … `Const.Search.MAX_GROUPED_RPP` (500)                  |
| `after`            | the previous page's cursor; only valid with `isContinuousScroll` |
| `p`                | **rejected** — a grouped search is continued by cursor     |

`parseGroupedQuery` validates all of it up front and returns the problem as the
body of a `400 text/plain`. Results are HTML only; a JSON `Accept` is refused.

Responses:

- **First page** — the whole `includes/search_results` fragment (controls +
  grid), rendered with `htmx/results_grid_grouped`, plus an `HX-Replace-Url`
  header carrying the bookmarkable URL. The Group dropdown's selected option is
  the *effective* grouping the server used.
- **Continuation** (`isContinuousScroll=true`) — `htmx/results_grid_grouped`
  alone: headers and cells, nothing else.
- **Continuation that ran dry** — `204 No Content`. Results are live; the images
  past the cursor may be gone by the time the page is asked for.

## Continuous loading

The client owns the complete parameter set. The `searchParams` Alpine store
(`static/js/stores/search-params.js`) holds every parameter, and `runSearch()`
(`static/js/search-results/search.js`) is the single funnel that serializes all
of them onto every request. The server therefore reads nothing but its own query
string — no merging with the browser URL, no "is this a new search" flag. The
friendly URL pushed back via `HX-Replace-Url` is a projection of the store for
bookmarking, and is never read back.

A widget contributes only the parameter it knows about
(`data-app-search-*`, `static/js/search-results/search-triggers.js`); the store's
`CLEARS` table decides what that change invalidates. Grouping behaves like the
sort: a reorder, surviving a change of folder, person, album or view. `p` is
dropped from serialization whenever `groupBy` is set, so a hand-edited URL cannot
turn a grouped search into a 400.

```
  server                                   client
  ------                                   ------
  page N  ──────────────────────────────▶  cells appended to #assets
   last cell: data-app-search-after=<cursor>
                                              │
                                    IntersectionObserver sees the last cell
                                              │
                                    loadNextPage(cell)
                                      • delete the attribute  → one request per cell, ever
                                      • WeakMap dedupe        → modal + scroll share one flight
                                              │
   GET ?…&after=<cursor>&isContinuousScroll=true  ◀── runSearch({transient})
                                                    target: that cell, swap: afterend
                                              │
  page N+1 (grid fragment) ─────────────▶  htmx:after:settle
   or 204 when the cursor ran dry            • observe the new last cell
                                             • observe the new images (lazy load)
```

Each page's last cell is rendered with `data-app-search-after` (grouped) or
`data-app-search-next-page` (ungrouped), never both
(`views/htmx/result_cell.scala.html`). The detail modal reuses the same
`loadNextPage` when it steps past the last loaded cell
(`static/js/search-results/detail-navigator.js`), which is why the in-flight
request is shared rather than issued twice.

## Date headers

A header (`.date-group`) is rendered before each day's cells with the day's
**full** match count — across every page, loaded or not — and sticks to the top
of the scrolling pane.

Two details make headers work across page boundaries:

- **`continuesDay`.** A page reached by cursor knows the day the previous page
  ended on. If its first group is that same day, the header is suppressed, so the
  new cells land directly after the previous page's last cell and read as one
  continuous group.
- **Counts are server-owned.** `static/js/search-results/date-groups.js` is the
  only place a header changes on the client: removing a cell decrements its day
  (found by walking back over siblings, which crosses page boundaries precisely
  because a continued day repeats no header), and the header disappears at zero.
  A header whose loaded cells are all gone but whose count is still positive
  stays — that day still has matches on pages not yet fetched.

## Where things live

| Concern | File |
| --- | --- |
| Grouping / group-by enum | `core/util/SearchGrouping.scala` |
| Cursor, scope fingerprint | `core/util/SearchCursor.scala` |
| Typed sort key | `core/util/SortValue.scala` |
| Page rows → groups | `core/util/GroupedSearchResult.scala` |
| The grouped statement | `core/dao/jdbc/querybuilder/SearchQueryBuilder.scala` |
| Engine specifics | `core/dao/{postgres,sqlite}/querybuilder/AssetSearchQueryBuilder.scala` |
| Row reading, typed columns | `core/dao/jdbc/BaseDao.scala`, `{Postgres,Sqlite}Overrides.scala` |
| Cursor scope check, folder expansion | `core/service/LibraryService.scala` |
| Group assembly, next cursor | `core/service/SearchService.scala` |
| Route, validation | `core/routes/web/partial/SearchResultsController.scala` |
| Templates | `views/htmx/results_grid_grouped.scala.html`, `views/htmx/result_cell.scala.html`, `views/includes/search_results.scala.html` |
| Client | `static/js/search-results/{search,search-triggers,date-groups}.js`, `static/js/stores/search-params.js`, `static/js/fragments/search-results.js` |

Tests: `test/.../integration/SearchGroupingTests.scala`,
`SearchCursorTests.scala`, `AssetDateStorageTests.scala`, and
`controller/SearchResultsControllerTests.scala`.
