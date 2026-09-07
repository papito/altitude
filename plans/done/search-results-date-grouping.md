# Optional search result grouping by date

## Original goals

Add optional grouping to search results, limited to the backend for this phase.
Make the date source explicit through `groupBy=dateTaken` or
`groupBy=dateImported` on both SQLite and PostgreSQL. Explore performant queries
and JSON delivery while preserving sorting and continuous scrolling. Produce a reviewable implementation
plan; frontend changes and feature implementation are outside this planning task.

## Status

**Implemented (backend), 2026-09-06.** All five units below are done and verified
on both engines: `make test-unit`, `make test-sqlite`, `make test-psql` (against the
`altitude-core-postgres-test` container) and `make test-controllers`. The
implemented contract is documented in [altitude/AGENTS.md](../../altitude/AGENTS.md)
under "Search results and date grouping". Frontend work remains out of scope.

Before implementing, the SQL design was re-verified empirically on both engines at
100,000 and 1,000,000 rows (schema-shaped fixtures, `EXPLAIN QUERY PLAN` /
`EXPLAIN ANALYZE`, medians of five runs). Decisions that differ from the design
text below, and why:

| Decision | Outcome |
| --- | --- |
| Index shape | `(repository_id, is_recycled, is_pipeline_processed, <day expr>, <raw timestamp>)` instead of `(..., <day expr> DESC, id ASC)`. Smaller (SQLite 4.5 MB vs 6.2 MB, PostgreSQL 7.6 MB vs 11 MB per index at 100k), index-only day counts, and a same-field grouping/sort reads in index order (first page 0.1 ms vs 52 ms on SQLite, 0.05 ms vs 3.7 ms on PostgreSQL). With the `DESC, id` shape SQLite's planner also picked the wrong index for first pages (52-72 ms vs 3.5-13 ms). |
| Null placement | No `NULLS LAST`: on PostgreSQL it forces a sequential scan and top-N sort (137 ms at 1M vs 0.1 ms), on SQLite it forfeits the index-ordered block sort. Nulls sort where each engine puts them natively and the cursor comparison honors that placement. Only a legacy SQLite `created_at` can be null; such a row has no import day and is excluded from Date Imported grouping and its totals rather than reported as a `date: null` group. |
| Day counts | One correlated count per distinct page day, in a materialized CTE. The `IN (...) OR ... IS NULL` shape stopped using the index for the nullable column on SQLite (15-170 ms vs 6-63 ms), and an unmaterialized CTE re-ran the count per page row. |
| SQLite secondary sort | Unary `+` on the sort term when it is the other date source's column; without it SQLite picks that source's index and sorts every row (291 ms vs 3.9 ms at 1M). |
| PostgreSQL import day | `(created_at AT TIME ZONE 'UTC')::date` directly in the index: `timezone(text, timestamptz)` is IMMUTABLE, no wrapper function needed. |
| Inclusive day bound | Kept; the OR-only shape costs 13-600 ms per page on SQLite. |

Representative complete-statement medians (page + overall count + day counts,
cursor at 80% depth, `rpp` 50): SQLite 3.5-6 ms at 100k and 43-63 ms at 1M;
PostgreSQL 8 ms at 100k and 43 ms at 1M. The exact overall count is the dominant
cost at 1M on both engines; page retrieval alone stays under 1 ms. Offset paging
at that depth is 60-180 ms (100k) and 0.6-2.1 s (1M); the window-count variant
1.3-3.4 s at 1M. Cursor traversal was checked to equal the complete order at
both sizes.

The earlier planning text follows unchanged as the design record.

## Agreed behavior

| Concern | Decision |
| --- | --- |
| Date Taken | `groupBy=dateTaken` uses `original_created_at`. |
| Date Imported | `groupBy=dateImported` uses `created_at`. The generic `date` value is replaced. |
| Granularity | One calendar day. |
| Capture-day boundary | The camera's recorded calendar date, independent of the viewer's timezone. |
| Import-day boundary | UTC, independent of the viewer's or server's timezone. |
| Missing/invalid capture metadata | Use the existing import-time fallback in `original_created_at`, consistent with existing date sorting. Do not reconstruct missing capture dates or introduce an unknown-capture-date feature. |
| Ordering | Date groups are the primary order, newest or oldest first. The existing `sort` applies independently within each day. |
| Pagination | `rpp` counts images. A day may span pages; a page may finish one day and begin another. |
| JSON content | Image IDs and group information, without thumbnails or asset metadata. |
| Counts | Each returned group includes its full matching image count across all pages, using the same search filters as its IDs. |
| Continuous scroll | Subsequent portions retain the same date key so the client can append to an existing group. |
| Changes during scrolling | Live results. Later pages and counts may reflect imports, deletions, and edits. Refresh restarts the sequence; images inserted into a portion already passed may require refresh to see. |
| Scale | Design for 100,000 images as baseline and 1 million as stress case, including days with thousands of images and deep scrolling. No additional benchmark work is required. |

Domain terminology is recorded in [CONTEXT.md](../CONTEXT.md).

## API and JSON design

Extend the existing `/htmx/search/r/:repoId` JSON branch. Preserve the existing
ungrouped JSON and HTML contracts. Grouped requests require JSON negotiation;
recognize `Accept: application/json` and retain the current GET `Content-Type`
compatibility. Return a clear HTTP 400 JSON error for grouping requested in HTML
mode, rather than shipping a partially implemented grouped grid. No new endpoint,
frontend controls, browser URL wiring, or grouped HTML templates are needed now.

| Parameter | Engineering choice |
| --- | --- |
| `groupBy` | Omitted means existing ungrouped behavior. Only `dateTaken` and `dateImported` are accepted; unknown/empty values, including `date`, return 400. |
| `groupDirection` | `asc` or `desc`, default `desc`. Reject it without `groupBy`. |
| `sort` | Existing field-plus-direction encoding and default (`created_at1`). For grouped requests validate against the five existing UI fields: `original_created_at`, `created_at`, `filename`, `size_bytes`, `area_size`. |
| `rpp` | Default 50. For grouped requests accept 1–500; reject zero/unbounded, negative, or larger values. Preserve legacy ungrouped behavior. |
| `p` | Retain page-number requests using an offset, including direct page access. Require a positive integer. |
| `after` | Optional opaque continuation cursor for grouped JSON scrolling. Follow `nextCursor` from the preceding response and omit `p`. Reject it without `groupBy`, or with an explicitly supplied `p`. |

`groupDirection` and the other details in this section are engineering choices,
not additional product requirements supplied by the user. Values resolve to fixed
field names/directions in the backend; bind data values as SQL parameters.

Example grouped response with `rpp=3`: page 1 contained the first three September 6
images; this response completes that day and starts September 5.

```json
{
  "ids": ["asset-4", "asset-5", "asset-6"],
  "page": 2,
  "total": 8,
  "totalPages": 3,
  "groupBy": "dateTaken",
  "groupDirection": "desc",
  "groups": [
    {"date": "2026-09-06", "startIndex": 0, "length": 2, "total": 5},
    {"date": "2026-09-05", "startIndex": 2, "length": 1, "total": 3}
  ],
  "nextCursor": "opaque-continuation-token"
}
```

Keep the flat `ids` list for existing navigation conventions. Each group's range
indexes that page's `ids`; ranges are contiguous, non-overlapping, and cover the
whole list. Group `total` counts the entire matching day, while `length` counts
only its portion on this page. Return groups only for dates represented on the
page. Dates are ISO `YYYY-MM-DD` keys, with no localized labels.

The client appends a group's IDs when its date equals the preceding group's date.
It replaces the group's total with the latest reported total; it never adds the
same full-day total again. Changing grouping, direction, sort, or filters starts
a new sequence. This documents future client behavior without implementing it.

For offset requests, `page` is the requested page. A cursor carries the next page's
sequence number; under live changes this is an ordinal, not a stable position in
the current full result set. `totalPages = ceil(total/rpp)` reports current matching
images, while `nextCursor` determines whether that scrolling sequence can continue.
Do not stop cursor scrolling by comparing its ordinal to `totalPages`.

Return `nextCursor: null` at the end. Grouped JSON always uses HTTP 200 for a valid
empty page, with `ids: []`, `groups: []`, and accurate totals; it does not use the
HTML continuous-scroll branch's 204 response. The existing ungrouped empty-page
behavior remains outside this change.

JSON alternatives considered: nested groups containing IDs are readable but either
remove the existing flat list or duplicate IDs. A date key on every image repeats
metadata and makes full-day counts awkward. Flat IDs plus small group ranges keep
one ordered list, omit large asset objects, and permit both navigation and grouping.

## Pagination and live-result guarantees

Use the complete SQL ordering key: **day, selected sort value, asset ID**. Keep the
ID direction ascending as a deterministic tiebreaker. Apply the day expression
before pagination: ordering by the full timestamp first would incorrectly make
time-of-day outrank filename/size within a day.

Use cursors for continuous scrolling; retain offsets for compatibility/direct page
access. The experiments below show why deep scrolling should prefer cursors.
Offsets can skip or repeat images when earlier rows are inserted/deleted. A cursor
continues from values, so deleting its anchor image does not invalidate it, and
changes strictly before the boundary do not shift the remaining unchanged rows.
Edits that move an image across the boundary can still make it reappear or be
missed until refresh; live results do not promise exactly-once traversal under
arbitrary edits.

A versioned, Base64URL-encoded cursor contains the last returned day, typed sort
value, ID, next sequence number, page size, and a fingerprint of the normalized
request scope, grouping, and ordering. Include repository and original scope
filters in that fingerprint, not the current expanded descendant-folder set.
Resolve current folder membership on each request. Reject malformed tokens or
scope/order/page-size mismatches with 400. Reapply authorization and all filters;
a cursor supplies a position, never access or a trusted SQL fragment. No server
session, result cache, signed access grant, or long-lived database cursor is needed.

Generate lexicographic comparisons for the independent directions. Do not use a
single tuple `>` comparison when day and image directions differ. A redundant
inclusive bound on the first day key can help an index seek reach the boundary.
Use the database's existing field collation consistently for sorting/comparison.

Fetch `rpp + 1` candidates to detect continuation, but return at most `rpp` IDs.
Build the next cursor from the last returned image, not the lookahead image.
Counts must ignore cursor/offset predicates and describe the whole matching search.

Defensive nullable-field handling belongs in the shared ordering/comparison
implementation: null sort values come last in either direction. Although normal
asset inserts supply import times, SQLite permits legacy null `created_at` values;
represent such a returned import group with `date: null`, ordered last, and use
null-safe count joins. This does not change the existing capture-time fallback.

## Date storage and extraction

| Mode | SQLite | PostgreSQL after capture-type normalization |
| --- | --- | --- |
| Date Taken | `date(asset.original_created_at)` on its stored local timestamp text; no UTC/localtime modifier. | `asset.original_created_at::date` on a timestamp **without** time zone. |
| Date Imported | `date(asset.created_at)` on the stored UTC timestamp text. | `(asset.created_at AT TIME ZONE 'UTC')::date`. |

There is a concrete PostgreSQL mismatch to address first. `AssetDao.add` parses
capture metadata into `LocalDateTime`, but the column is `TIMESTAMP WITH TIME ZONE`.
pgJDBC 42.7.4 sends the JVM timezone at connection startup, so conversion currently
depends on that timezone. PostgreSQL does not retain the original assumed timezone.
A bare date cast or unconditional UTC conversion therefore cannot establish the
camera-day rule for all existing records. See the [driver source](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.4/pgjdbc/src/main/java/org/postgresql/core/v3/ConnectionFactoryImpl.java),
[PostgreSQL timestamp semantics](https://www.postgresql.org/docs/current/datatype-datetime.html#DATATYPE-DATETIME-INPUT-TIMESTAMPS),
and [JDBC local date/time mapping](https://jdbc.postgresql.org/documentation/query/#using-java-8-date-and-time-classes).

Normalize only PostgreSQL `asset.original_created_at` to `TIMESTAMP WITHOUT TIME
ZONE`. Migrate through the same application timezone used to decode existing
values, preserving their current local representation, including fallback values.
The migration must use the pre-migration JVM decoding timezone explicitly and log
it. Set the migration transaction's timezone to that value, then an explicit
`USING original_created_at AT TIME ZONE current_setting('TimeZone')` expresses the
conversion. Do not assume a URL-supplied PostgreSQL timezone override matches JVM
decoding. Fresh schemas
start with the correct type. Use a timezone-free JDBC mapping for capture values;
do not route them through an instant conversion. SQLite capture parsing should
likewise use `LocalDateTime` directly instead of a `SimpleDateFormat`/instant
roundtrip. Verify day boundaries across JVM timezone changes after migration.

This preserves existing represented values, not unknowable historical camera
settings. Records previously written under different application timezones may
already be ambiguous; this task does not infer or repair those capture dates.
Likewise, grouping applies UTC to stored import timestamps without retroactively
repairing historical import times. For new assets, bind `created_at` explicitly
in `AssetDao.add`: an `OffsetDateTime` in UTC for PostgreSQL and the equivalent UTC
text for SQLite. This avoids relying on the existing timezone-sensitive SQL
defaults (`now() AT TIME ZONE 'utc'` cast back into a timezone-aware column and
`datetime('now', 'utc')`). SQLite's `now` is already UTC; applying its `utc` modifier
assumes local input. See [SQLite date/time modifiers](https://www.sqlite.org/lang_datefunc.html).
Limit this correction to asset insertion; broader timestamp defaults and historical
data repair are separate concerns. Do not normalize capture timestamps to UTC as
if they were known instants. This storage prerequisite is planned, not executed or
PostgreSQL-tested.

Prefer expressions/indexes over separately maintained day columns. The two fields
remain the source of truth; no new date counters or duplicated day state are needed.

### SQLite implementation and migration

Keep the existing `DATETIME` declarations and stored timestamp text. SQLite does
not need the PostgreSQL capture-column type conversion, a table rebuild, or a
rewrite of existing date values. Both timestamps continue to use the existing
`yyyy-MM-dd HH:mm:ss` text format, with different meanings: camera-local time for
`original_created_at` and UTC for newly written `created_at`.

In `dao/sqlite/SqliteOverrides.scala`, replace the `SimpleDateFormat` and instant
roundtrip with direct `LocalDateTime.parse` using a shared formatter for the stored
format. Retain the existing null handling. Group day keys come directly from
SQLite's `date(...)` result as ISO date text; do not convert them through
`java.sql.Timestamp`, epoch milliseconds, or the JVM timezone. This also avoids
normalizing a camera time merely because it falls in the server's DST gap.

In the shared `dao/jdbc/AssetDao.add`, explicitly supply the UTC import timestamp
in the SQLite insert, as described above. Leave existing timestamp values and the
legacy database default untouched; the normal asset-insertion path will supply
the value instead. This gives fresh and upgraded databases the same application
behavior without rebuilding `asset` solely to change its default. Preserve the
existing local-current-time fallback for missing/invalid capture metadata.

Add these indexes in the next SQLite migration and in
`resources/migrations/sqlite/all.sql`, alongside the PostgreSQL storage/index
migration. The SQLite migration changes indexes only. Keep `asset_02` for the
overall count.

```sql
CREATE INDEX asset_search_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed,
  date(original_created_at) DESC, id ASC
);

CREATE INDEX asset_search_date_imported ON asset (
  repository_id, is_recycled, is_pipeline_processed,
  date(created_at) DESC, id ASC
);
```

Use the same `date(...)` expressions in grouped selection, cursor comparisons,
and day counts. Implement the grouped ID query through
`dao/sqlite/querybuilder/AssetSearchQueryBuilder.scala` and
`dao/sqlite/SearchDao.scala`, sharing clause construction with the JDBC builder:

- Use the single-statement candidate/page/count structure below. The pinned
  SQLite JDBC version supports the bounded `AS MATERIALIZED` CTEs; materialize
  only the page candidates and page, not all matching asset rows.
- Retain SQLite's `body MATCH ?` text search, integer boolean representation,
  repository/visibility predicates, and metadata-filter deduplication in every
  count branch. Reuse their bound values through the shared predicate builder.
- Read narrow ID/day/sort-key rows into the new result type. Do not call the
  full-asset `makeModel`, parse asset metadata JSON, or inherit the generic
  builder's automatic `count(*) OVER()` projection in this path. Use the existing
  numeric result conversion helper for count values.
- Use `d.day IS p.day` for SQLite's null-safe day-count join. Include null days
  explicitly when selecting days to count, since `IN` alone will not match them.
  Distinguish an empty-page summary by its null **asset ID**, not by a null day.
- Use the shared direction-aware cursor comparison and explicit final ordering.
  Keep filename comparison consistent with SQLite's existing collation; keep
  numeric sort values numeric and timestamp sort values in the stored format.
- Keep the query within the existing request connection, consuming and closing
  its result before returning. One statement supplies consistent page/count data;
  later scrolling requests get fresh data. No connection or read transaction is
  retained between pages, and no SQLite journal/locking configuration changes are
  required for this feature.

Expected behavior by engineering judgment: the indexes help locate days and count
their matches; cursor queries avoid traversing every earlier page. The database
may still sort the matching rows within a large day for the selected secondary
sort, and exact overall counts still inspect the filtered matching set. Start
with these two indexes and the shared query; additional indexes, cached counts,
and precomputed day columns are outside this implementation unless a concrete
issue arises. These expectations are not new benchmark results or latency promises.

## Query approach and counting

**Chosen starting design:** select narrow ordered page rows in SQL, combine them
with an independent overall count and batched counts for the days on that page in
one statement, and assemble group ranges in the service. Serialize in the controller.
This preserves consistent IDs/counts within one statement while keeping later
requests live. It avoids changing global transaction behavior: `asReadOnly`
currently opens a connection but does not create a multi-statement snapshot.

| Approach | Assessment |
| --- | --- |
| Narrow page + independent count relations in one statement | Selected. Each relation can use an appropriate index; group counts are restricted to returned days; no per-day round trips. |
| Global and per-day window counts before page slicing | Correct and concise, but much slower in the earlier isolated SQLite experiment. Retain as a documented alternative; no additional comparison benchmark is required. |
| SQL JSON aggregation over an already limited page | Correct if ordered carefully, but adds database-specific serialization without a demonstrated benefit for a small page. |
| Build complete day arrays before paginating | May construct huge arrays only to discard most IDs; conflicts with bounded image pages. |
| Fetch day directory, then fetch each day's images | Useful for a different future date-navigation feature; unnecessary coordination for the agreed sequential scrolling contract. |
| Separate page and count statements | Potentially useful plans, but consistency would require a real read transaction. One statement avoids that additional infrastructure. |
| Cache full search results or day counts | Adds invalidation and memory costs for live results. Defer until profiling proves necessary. |

Conceptual statement structure (day expressions and predicates differ by engine;
this is a design sketch, not executable application SQL):

```sql
WITH candidates AS MATERIALIZED (
  -- Narrow, deduplicated filtered image relation with day and sort key.
  -- Apply cursor OR offset, ORDER BY day / sort / ID, LIMIT rpp + 1.
), page AS MATERIALIZED (
  -- Preserve the same order; take the first rpp candidates.
), total AS (
  -- COUNT of all deduplicated matches, without paging or ordering.
), day_counts AS (
  -- COUNT per day over all matches restricted to DISTINCT days in page.
  -- Handle NULL days explicitly and reuse exactly the same search filters.
)
SELECT page_columns, total, day_total, has_lookahead
FROM total
LEFT JOIN page ON true
LEFT JOIN day_counts ON null_safe_same_day
ORDER BY day_direction, selected_sort_direction, asset_id;
```

The outer ordering is explicit and covers only the page-sized result. The left
join preserves a summary row when the page is empty; the DAO distinguishes it by
its null image ID. Do not group that summary row into an image/date group.

Factor the existing search predicate and deduplication builder so all three
branches share repository, view, pipeline, text, folder, person, album, and
metadata semantics. Preserve the existing `GROUP BY asset.id` / `HAVING` used by
metadata filters; date grouping must not replace it or count metadata-join rows
as separate images. Do not materialize every matching full `Asset`, and do not
carry the existing automatic `count(*) OVER()` into the narrow page projection.
A multiply referenced CTE can force materialization, so check plans rather than
assuming a shared CTE also means efficient execution. See
[PostgreSQL CTE behavior](https://www.postgresql.org/docs/current/queries-with.html#QUERIES-WITH-CTE-MATERIALIZATION).

Expose a separate typed ID-search result rather than changing the HTML path's
`SearchResult.records: List[Asset]`. Route it through `LibraryService` so root-folder
handling and recursive folder expansion are shared, then `SearchService` and the
existing engine-specific DAOs. The service assembles ordered groups; the DAO returns
typed rows/counts and the controller builds camelCase JSON.

## Indexes and expected performance

Start with one index per day source, shaped like
`(repository_id, is_recycled, is_pipeline_processed, day_expression, id)`.
Keep the existing visibility index for broad overall counts. Reuse the exact day
expression in selection, comparisons, counting, and indexing. Use fixed SQL field
mapping for the two sources. SQLite requires matching expression-index syntax;
see [SQLite expression indexes](https://www.sqlite.org/expridx.html).

These small indexes support day ranges and counts; sorting inside a day may still
require work. They do not cover every secondary field or mixed direction. Keep
additional secondary-sort indexes as a follow-up for a demonstrated bottleneck;
they are not a prerequisite for this implementation. Reversing a composite index
reverses all its directions, not just the day; see
[PostgreSQL index ordering](https://www.postgresql.org/docs/current/indexes-ordering.html).

The earlier [query experiment report](search-results-date-grouping-benchmark.md) records
SQL, fixture construction, timing method, and results using the project's SQLite
JDBC 3.51.2.0. Representative median complete-query times:

| Fixture | Window counts, deep page | Combined counts + cursor, small day index |
| --- | ---: | ---: |
| 100,000, Date Taken | 221.22 ms | 3.57 ms |
| 100,000, Date Imported | 226.12 ms | 3.87 ms |
| 1 million, Date Taken | 2,836.92 ms | 31.57 ms |
| 1 million, Date Imported | 2,168.52 ms | 32.47 ms |

These compare complete query strategies, with their respective indexes. This is
warm in-memory synthetic data with simple visibility filters and one sort
combination, not HTTP latency or a full application benchmark. No PostgreSQL server
was available through the local PostgreSQL command-line tools; no PostgreSQL timing
is claimed. Exact totals still require counting matches even with cursor paging.

Use this as background evidence and engineering guidance, not as a requirement to
repeat or extend the benchmarks. No end-to-end latency SLA or timed benchmark
gate is set for either engine. The design bounds application memory and response
size by `rpp`, fetches no full asset metadata, and keeps the number of grouped DAO
statements independent of the number of returned dates. Exact overall counting
and secondary sorting within unusually large days remain the expected expensive
parts. Functional tests should exercise those shapes with manageable fixtures;
they do not need to generate a million-image dataset.

## Reviewable implementation units

Each numbered item is a logical unit that can be reviewed and merged independently
in the order shown. Apply strict integration-test red–green–refactor within each
backend behavior change; do not commit a knowingly failing intermediate state.

1. **Preserve date semantics in storage and decoding.** Add integration coverage
   for camera dates near midnight/DST boundaries, timezone changes, import-day UTC
   extraction, and the existing missing/invalid-capture fallback. Normalize the
   PostgreSQL capture column and its mapping as described above; replace SQLite's
   instant-based parsing with direct local parsing, and bind new asset import
   times explicitly in UTC for both engines. Add the two date expression indexes
   for each engine in this storage unit. SQLite keeps its table and existing text
   values; its migration adds the indexes only. Cover fresh and upgraded schemas,
   including preservation of existing represented values. Use the next migration
   version, both engine migration files, both `all.sql` files, and the
   `schemaVersion` bump. Rationale: query optimization cannot compensate for
   grouping under the wrong calendar date.

2. **Add the typed grouped ID-search service and count query.** Extend the search
   model with optional grouping/direction and a separate typed ID-search result.
   Extract shared folder-scope resolution and search predicates, then add the
   narrow page/count statement in the existing DAO hierarchy. Preserve all fields
   in `SearchQuery.add`, `withFolderIds`, and `add_metadata_filter`; the latter
   currently drops `params`, which must be corrected when making these copies
   preserve the full grouped query. Implement both `dao/sqlite/SearchDao.scala`
   and `dao/postgres/SearchDao.scala` using the indexes prepared in unit 1; retain
   their engine-specific text-search and date expressions. Prove unique IDs,
   filtered day/global totals, independent ordering, empty pages, and multi-page
   days with integration fixtures. Rationale: one
   shared search definition prevents filters/counts from drifting between paths.

3. **Add cursor continuation to the grouped service.** Add the typed cursor,
   normalized-scope validation, comparison builder, and lookahead handling while
   retaining offset requests. Cover equal sort values, both group directions,
   every supported secondary sort direction, mixed directions, deletion of the
   anchor, insertion before the anchor, scope/order changes, and nullable legacy
   values. In an unchanged fixture, cursor traversal must exactly match the
   complete deterministic order without repeats/omissions. Rationale: continuous
   scrolling should not repeatedly skip growing prefixes of a large result set.

4. **Expose the grouped JSON contract.** Add controller parameters and validation,
   dispatch grouped JSON into the ID-search service before full-asset loading, and
   serialize the response above. Add controller coverage for both modes, headers,
   error responses, group ranges/counts, continuation, and end-of-results. Preserve
   existing ungrouped HTML/JSON behavior and authentication/repository isolation.
   Keep grouped HTML and all frontend work out of this unit. Rationale: deliver a
   usable backend contract without changing the current UI.

5. **Validate both engines and document the implemented contract.** Run the
   functional integration/controller suites with the filter, pagination, and
   migration cases below. Verify that both fresh and upgraded SQLite databases
   have the two expression indexes and preserve stored dates, and that PostgreSQL
   satisfies its corresponding date semantics. No benchmarks, timed performance
   gates, or large-scale fixture generation are required. Update
   `altitude/AGENTS.md` with the implemented ID search path, paging, date storage,
   and JSON contract; update other existing
   `AGENTS.md`, `CLAUDE.md`, or `ARCHITECTURE.md` files only where affected. Keep
   `CONTEXT.md` a domain glossary. Rationale: lock down both engine behavior and
   the design's operational limits without treating prototype timings as guarantees.

## Validation and completion criteria

Use `IntegrationTestCore` fixtures and the shared SQLite/PostgreSQL suite bundles.
Add controller tests alongside the existing search coverage in
`AlbumControllerTests`; do not rely only on SQL string assertions. Counts must
respect repository isolation, visibility/triage/trash, pipeline completion, text,
metadata, albums, people, root folders, and descendant folders. Multiple matching
metadata values must never duplicate an ID or inflate counts.

Count page/count SQL executions as part of integration coverage: the grouped DAO
uses one statement regardless of the number of days returned; existing folder
scope lookups remain separate. Confirm no full-asset metadata conversion occurs in
the grouped JSON path. Verify the range invariants and a page crossing a date
boundary, including a group much larger than `rpp`.

SQLite-specific checks must cover direct parsing of a timestamp in a server DST
gap, unchanged day keys after switching the JVM timezone, explicitly written UTC
imports, the retained capture fallback, and legacy null import dates. Assert that
the index-only migration leaves existing timestamp strings, asset IDs, and album
and folder relationships intact. Exercise `body MATCH ?` and metadata-filter
deduplication together with day totals and cursor continuation. These are
correctness checks on bounded fixtures, not benchmarks.

Use INFO for important search/migration events and DEBUG for bounded query timing,
returned-image count, and returned-group count. Include effective grouping/order
in diagnostics. Avoid logging every image or dumping full metadata/cursor contents.

After implementation run `make compile`, `make test-sqlite`, and
`make test-controllers`; use `make test-focused-sqlite` while iterating. Do not use
`make test`, which requires a live PostgreSQL container. PostgreSQL integration
checks require an explicitly configured isolated test database;
report them as outstanding if unavailable, never as passed.

This planning session changed documentation only. No application code, migrations,
frontend files, or live data were modified. Remaining work consists of the numbered
implementation units and their checks; there are no pending interview questions.
