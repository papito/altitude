# Search performance

## Goals

- A search of a library of a million assets meets the targets under **Verification** on both engines, and no search
  runs unbounded.
- A page reads full asset rows for its own assets only; which assets are on it is decided over narrow rows.
- Search text whose hits are few is answered from those hits. Text whose hits are many is matched in one pass over the
  library, with each of its sources built once per statement.
- Totals count up to 10,000 and read "10000+" past that. Day and Location group counts stay exact, and merging people
  counts exactly.
- Every column a search filters or sorts by, and every foreign key a purge cascades through, leads an index.
- Connections are pooled. A read transaction sees one snapshot on both engines, a SQLite write transaction holds the
  write lock from its start, and a PostgreSQL read statement has a time limit.
- The partial person indexes cover the rows their queries read.

Out of scope: cursor paging for the flat grid, moving the metadata JSON to its own table, `uuid` IDs, phrases that match
across the boundary between two values of a Search document, and a committed benchmark harness.

## Current behavior

Measured on synthetic libraries of one million assets (a 28k-folder tree, one person on 62k assets, 2k Locations,
about 7 KB of extracted metadata per asset), warm cache:

| Request | PostgreSQL | SQLite |
|---|---|---|
| Browse, first page (flat, Date Imported) | 2.5 s | 161 s |
| Text `alice`, Relevance | 0.6–1.1 s | 7–14 s |
| Text `img` or `-img`, Relevance | over 60 s (cancelled) | 48 s |
| Grouped by Location, with text | 9.1 s | 89 s |
| Map Location panel, with text | over 60 s | 37 s |
| Purge one asset | 16 ms at 250k metadata values, growing with them | 0.9–1.9 s |

Pages and counts:

- `SearchQueries.flat` puts `COUNT(1) OVER ()` beside every asset column, the three metadata JSON columns included, so
  every match is materialized as a full row and sorted before `OFFSET`/`LIMIT` picks the page; each infinite-scroll
  page repeats it. `SearchResult.hasMoreResults` and the controller's empty-continuation check derive from that total,
  and a page past the last carries a total of 0.
- The grouped first page counts every match (`totalFragments`). The map layout's total and `PersonService.merge` call
  `LibraryService.count`.
- `groupedByLocation`'s `group_counts` evaluates `matching` once per distinct Location on the page, and `mapLocations`
  evaluates it twice per Location in the viewport (in its filter and its count). Each evaluation is a pass over the
  library.

Search text (`SearchQueries.textFilter`, `relevance`, `termMatches`):

- Each source of a term is `asset.id IN (SELECT …)`, OR-ed with the term's other sources, so the asset table drives
  every text search whatever the number of hits: on SQLite a text with no hit takes 2.5 s.
- `relevance` renders every source again in the select list and the `ORDER BY`, and `afterBySort` again in a cursor's
  comparison: a grouped continuation for one word renders 10 document matches and 40 ID sets.
- PostgreSQL plans an `IN` under `OR` or `CASE` as a SubPlan, hashed only while the set fits in
  `work_mem × hash_mem_multiplier` (4 MB × 2 by default, about 130k IDs). A larger set is re-scanned for every asset row,
  which is why `img` and `-img` never finish. An excluded term is `NOT (… IN …)`, never an anti-join.
- `personFilter` joins `person`, though the face's `person_id` is all it reads. On SQLite the document source's
  `search_document.repository_id = ?` steers the planner into scanning `search_document_01`.
- `SearchService.resolveText` reads every name of the repository and computes the `SearchWords.variants` of each on every
  request: 25 ms of reads and 130 ms of CPU at 28k folders.

Schema:

- Neither engine indexes `asset.folder_id` or `asset.created_at` (the default sort), and `metadata_parameter` has no
  index at all. `face_01` and `search_document_01` lead with `repository_id`, so SQLite scans `face`, `search_document`
  and `metadata_parameter` for every purged asset (PostgreSQL 18 skip-scans the first two). `face_02` does not carry
  `asset_id`.
- `asset_02` is the first three columns of `asset_search_date_taken`. Once SQLite has statistics, it picks skip-scan
  plans through it.
- SQLite's `asset` declares its three metadata JSON columns before `folder_id`, the flags and the timestamps, so reading
  any of those walks the row's overflow pages. PostgreSQL keeps the `jsonb` columns in the heap row: 1.3 GB at a million
  assets.
- PostgreSQL ID columns are `CHAR(36)` under the database's default collation (`en_US.utf8`), which sorts and groups
  them at about half the speed of `C`.
- `person_01` on SQLite, and `person_02` and `person_03` on both engines, are partial on `is_deleted = TRUE` (`= 1` on
  SQLite), while every person query reads `is_deleted = FALSE`. On SQLite two live people can share a name, and two
  merged-away people whose cover is the same face violate `person_02` on both engines.
- SQLite's `search_document` has no declared key: `search_document_fts` follows its implicit `rowid`, which `VACUUM` may
  renumber. Its `metadata_parameter.field_value_dt` is declared `DATEN`.
- The document upsert rewrites `body` even when it is unchanged, and on SQLite the update trigger rewrites the FTS entry
  with it.

Connections (`transactions/TransactionManager.scala`):

- Every transaction opens a connection through `DriverManager` and closes it.
- A PostgreSQL read-only connection stays in autocommit, where pgJDBC ignores `setReadOnly`, so each statement of
  `resolveText` or `mapCells` takes its own snapshot. A SQLite read connection is in autocommit too, and gets none of
  the write connection's PRAGMAs.
- The SQLite write connection runs `PRAGMA isolation_level=IMMEDIATE`, which is not a SQLite pragma: write transactions
  begin DEFERRED, and a busy lock upgrade fails without waiting for `busy_timeout`.
- SQLite never runs `ANALYZE` or `PRAGMA optimize`, so its planner has no statistics.
- PostgreSQL runs with JIT on (3.1 s of the 9.1 s Location grouping is compilation) and no statement timeout: an `img`
  search held three backends for over ten minutes until it was cancelled.

## Design

### Schema

Both `all.sql` files, as original definitions.

PostgreSQL:

```sql
-- Every ID column (id and every *_id, the foreign keys included) is CHAR(36) COLLATE "C": IDs compare byte-wise
CREATE TABLE asset (
  ...
) INHERITS (_core) WITH (toast_tuple_target = 128);  -- the jsonb columns live out of line, the heap row stays narrow

-- asset_02 goes: it is the first three columns of asset_search_date_taken
CREATE INDEX asset_search_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, (original_created_at::date), original_created_at
) INCLUDE (id, folder_id);
CREATE INDEX asset_search_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at);
CREATE INDEX asset_folder ON asset (folder_id);
CREATE UNIQUE INDEX face_01 ON face (asset_id, repository_id, checksum);
CREATE INDEX face_02 ON face (person_id, detection_score) INCLUDE (asset_id);
CREATE UNIQUE INDEX search_document_01 ON search_document (asset_id, repository_id);
CREATE INDEX metadata_parameter_01 ON metadata_parameter (asset_id);
-- person_02 and person_03 partial on is_deleted = FALSE
```

SQLite:

```sql
-- asset: extracted_metadata, public_metadata and user_metadata are declared last, after updated_at
-- asset_02 goes, as on PostgreSQL
CREATE INDEX asset_search_date_taken ON asset (
  repository_id, is_recycled, is_pipeline_processed, date(original_created_at), original_created_at, id, folder_id
);
CREATE INDEX asset_search_created ON asset (repository_id, is_recycled, is_pipeline_processed, created_at);
CREATE INDEX asset_folder ON asset (folder_id);
CREATE UNIQUE INDEX face_01 ON face (asset_id, repository_id, checksum);
CREATE INDEX face_02 ON face (person_id, detection_score, asset_id);
CREATE TABLE search_document (id INTEGER PRIMARY KEY, repository_id ..., asset_id ..., body ..., <foreign keys>);
CREATE UNIQUE INDEX search_document_01 ON search_document (asset_id, repository_id);
-- search_document_fts adds content_rowid='id', and its triggers write new.id / old.id
CREATE INDEX metadata_parameter_01 ON metadata_parameter (asset_id);
-- metadata_parameter.field_value_dt is DATETIME; person_01, person_02 and person_03 partial on is_deleted = 0
```

- The date index carries `id` and `folder_id`, so a pass over the library that tests text membership, a folder or a day
  reads the index and not the table.
- `asset_search_created` serves the default flat sort, Date Imported, as an ordered read.
- `asset_folder` serves folder browsing and the folder source of text, and lets SQLite plan an OR of sources as a
  multi-index OR.
- Leading with `asset_id`, `face_01`, `search_document_01` and `metadata_parameter_01` serve the purge cascades,
  `clearMetadata` and the per-asset probes of the selective text path. The upsert's `ON CONFLICT (repository_id,
  asset_id)` still finds `search_document_01`, since a conflict target names a column set.
- `SqliteSearchDialect.textMatch` matches through `id IN (SELECT rowid FROM search_document_fts WHERE … MATCH ?)`.
  `SearchDocumentRow` keeps mapping the three shared columns.
- The document upsert updates only a changed body, on both engines:
  `… DO UPDATE SET body = excluded.body WHERE search_document.body <> excluded.body`.

### Connections and transactions

`TransactionManager` draws connections from HikariCP pools that the `Altitude` instance creates and `Altitude.cleanup`
closes. `connection(readOnly)` borrows from them, so the suites' setup code is unchanged. Hikari resets autocommit,
read-only and isolation on return.

PostgreSQL has one pool (`db.postgres.pool_size`, default 10). Every connection opens with `db.postgres.options`,
default `-c jit=off -c plan_cache_mode=force_custom_plan -c work_mem=64MB`:

- JIT compilation costs more than it saves on these statements.
- Pooled connections reach pgJDBC's `prepareThreshold` and switch to server-side prepared statements. A generic plan
  cannot see the length of an ID array or the selectivity of a text term; custom plans keep both visible.
- 64 MB keeps the broad text path's ID sets hashed up to about a million IDs.

A PostgreSQL read transaction has autocommit off and is `READ ONLY` and `REPEATABLE READ`. It begins with
`SET LOCAL statement_timeout` (`db.postgres.read_statement_timeout`, default 30 s) and ends with a rollback. `Db.read`
turns a statement cancelled by the timeout (SQLState `57014`) into `QueryTimeoutException`. `SearchResultsController`
answers that with a plain-text 503, and `MapController` with a JSON 503.

SQLite has a read pool (`db.sqlite.read_pool_size`, default 4) and a write pool of one connection, over one file.

- Both pools open their connections through `SQLiteConfig` with `journal_mode=WAL`, `synchronous=NORMAL`,
  `foreign_keys=ON`, `busy_timeout=10000`, `temp_store=MEMORY`, `cache_size=-65536` and `mmap_size=1073741824`.
- Each connection, when opened, loads the vector extension, runs `vector_init` and runs `PRAGMA optimize=0x10002`.
  `withFaceVector` goes, and its callers use `withTransaction`.
- A write transaction begins IMMEDIATE (`SQLiteConfig.setTransactionMode`), so it takes the write lock when it starts.
  The one-connection pool queues writers inside the process instead of failing them with `SQLITE_BUSY`.
- A read transaction has autocommit off, so it reads one WAL snapshot, and it ends with a rollback.
- `PRAGMA optimize` runs hourly on the write connection, scheduled on the actor system, and once more at `cleanup`.

### Pages and totals

Every statement is a hand-written shell over the typed `matching` relation, as the grouped statements are already. One
entry point, `SearchQueries.statement`, prefixes the shell with the text's CTEs (see **Search text**), so no shell can
leave them out.

The flat page picks its rows over narrow ones, then reads the page in full:

```sql
WITH <text CTEs>, page AS MATERIALIZED (
  SELECT asset.id AS id, <sort value> AS sort_value, <capture time> AS second_sort_value
    FROM asset
   WHERE <matching>
   ORDER BY sort_value <dir>, [second_sort_value DESC NULLS LAST,] id ASC
   LIMIT <rpp + 1> OFFSET <(p - 1) * rpp>
)
SELECT <asset columns> FROM page JOIN asset ON asset.id = page.id ORDER BY <the same order>
```

- Under the Relevance sort, `sort_value` is the Relevance, computed once per row.
- The row past the page sets `SearchResult.hasMore`, which decides `data-app-search-next-page`. A continuation that
  finds no rows is a 204.
- `SearchResult.total` is an `Option`, present on a first page only.

A **capped count** is `SELECT count(*) FROM (SELECT asset.id FROM asset WHERE <matching> LIMIT <cap + 1>)`, where the
cap is `SearchQuery.totalCap` (default `Const.Search.TOTAL_CAP`, 10,000). A result above the cap means "more than the
cap".

- The flat first page, the grouped first page (`totalFragments`) and the map layout (`LibraryService.cappedCount`) report
  a capped count.
- `LibraryService.count` stays exact, for `PersonService.merge`.
- `includes/search_results` renders `data-results-total` (at most the cap) and `data-results-total-capped`.
- The `resultsTotal` store holds both values. The toolbar and the map panel's heading read the count followed by "+" when
  it is capped, and `decrement` leaves a capped total alone.

Location counts:

- `group_counts` counts each page Location's own members: `location_asset` for that Location, joined to `asset` under
  the `matching` predicates. The cost scales with the size of the page's Locations, not with the library.
- The "No location" count stays a count of the matches in no Location. It runs only on the pages that reach that group.
- `mapLocations` becomes one statement: `matched AS MATERIALIZED (SELECT asset.id … WHERE <matching>)`, then the
  Locations of the repository pinned in the box, joined to `location_asset`, with members in `matched`, grouped by
  Location with their counts and category names.

### Search text

A text is matched in one of two ways. Which one is decided per request by a probe that `SearchService.resolveText` runs
in the same read transaction, after resolving names.

**The probe.** `SearchDao.probeText` is one statement with one branch per positive group (a group none of whose
alternatives is excluded). A branch is the `UNION ALL` of the group's source relations, limited to `limit + 1` rows,
where the limit is `SearchQuery.textProbeLimit` (default `Const.Search.TEXT_PROBE_LIMIT`, 5,000). Each source relation
is scoped to the repository by its own `repository_id`:

| Source | Relation |
|---|---|
| Person | `face.asset_id` where `person_id` is in the set |
| Location, Category | `location_asset.asset_id` where `location_id` is in the set |
| Folder | `asset.id` where `folder_id` is in the set |
| Album | `album_asset.asset_id` where `album_id` is in the set |
| Document | `search_document.asset_id` of the engine's match (on SQLite driven from the FTS table) |

A group that returns at most `limit` rows is **complete**: its distinct IDs are exactly the assets it can match. The
candidates are the intersection of the complete groups' IDs, carried on the resolution as
`ResolvedSearchText.candidates`.

- `None` means broad: no positive group is complete, or the text has no positive group, in which case the probe does not
  run.
- Empty candidates answer the search with nothing, without reading the library.

**Selective path** (candidates present): `matching` filters `asset.id` by the candidate set (`Columns.isInSet`, one
bind). The complete groups need no other predicate. Every other group, excluded term and source membership tested for
the Relevance is a correlated probe on the asset's own row:

- `EXISTS (SELECT 1 FROM face WHERE face.asset_id = asset.id AND face.person_id = ANY(?))` and the like for Locations
  and albums;
- `asset.folder_id` in the folder set;
- `EXISTS` on `search_document` by `asset_id` with the engine's match.

Each reads an index that leads with `asset_id`, for no more than `limit` rows.

**Broad path** (no candidates): each source relation of each term is a `MATERIALIZED` CTE at the head of the statement,
named by group, alternative and source. Every predicate, the Relevance and the cursor comparison test membership in
those CTEs, so a source is built once per statement.

- A group made of one excluded term is a top-level anti-join: `NOT EXISTS` on PostgreSQL, which plans a hash anti-join,
  and `NOT IN` over the CTE on SQLite, which indexes the set once.
- An `OR` group with an excluded alternative keeps today's shape (OR and NOT over the memberships), over the CTEs.

**The Relevance once.** Under the Relevance sort, every grouped statement first materializes
`scored (id, day, sort_value, second_sort_value)` over the matches, with the Relevance as `sort_value`. The candidate
slices, `afterBySort` and the order read its columns; `groupedByLocation` joins its Locations to `scored`. The flat page
computes the Relevance once in its `page` select list.

Dead predicates go: `personFilter` reads `face.person_id` without joining `person`, and the document source carries no
`search_document.repository_id = ?` beyond the probe's scoping.

`SearchWords.variants` memoizes its result per text in a bounded LRU map, since it is a pure function of the text:
`resolveText` keeps reading names fresh on every request, and stops recomputing their words.

`totalCap` and `textProbeLimit` are tuning carried on the query so that tests can force each path. Neither is part of
the cursor fingerprint.

## Tasks

1. **Schema.** Both `all.sql` files as under **Schema**: the indexes, `asset_02` gone, SQLite's column order, PostgreSQL's
   `COLLATE "C"` IDs and `toast_tuple_target`, the person indexes, SQLite's `search_document.id` with
   `content_rowid`, `field_value_dt`, and the upsert's `WHERE`.

   The plan-reading helper of `SearchMapTests` moves to a trait shared by the search suites. Integration tests first,
   on both engines:
   - a second live person with a taken name is a `DuplicateException` (red on SQLite);
   - a person merged into another, who is then merged in turn after taking the first's cover face, leaves two
     merged-away people with one cover face (red on both);
   - rewriting an unchanged document updates no row;
   - folder browsing reads `asset_folder`, a Date Imported page reads `asset_search_created`, and a lookup of an
     asset's faces, document or metadata parameters reads `face_01`, `search_document_01` or `metadata_parameter_01`.

   `RowColumnTests` follows SQLite's `search_document`.

2. **Connections and transactions.** The HikariCP dependency in `../../build.mill`. The pools, PRAGMAs, options, read
   transactions and statement timeout in `TransactionManager`, with the config keys in `reference.conf` (the test
   `reference.conf` sets a 2 s timeout). `QueryTimeoutException` in `Exceptions.scala` and its 503s. Hourly
   `PRAGMA optimize`, and `withFaceVector` replaced by `withTransaction`.

   Integration tests first, in a new `TransactionManagerTests` registered in `AllIntegrationTestSuites`:
   - a read transaction counts the same assets before and after another thread commits one (red on both engines);
   - on SQLite, a write transaction that has only read holds the write lock: another connection's `BEGIN IMMEDIATE`
     with no busy timeout fails (red);
   - SQLite read and write connections report `journal_mode=wal`, `foreign_keys=1` and `temp_store=2`;
   - a PostgreSQL read transaction reports `jit=off` and `plan_cache_mode=force_custom_plan`, and `pg_sleep(3)` in it
     is a `QueryTimeoutException`.

3. **Flat pages and capped totals.** The flat shell, `SearchResult.hasMore` and its `Option` total. The capped count
   with `SearchQuery.totalCap`, used by the flat and grouped first pages and `LibraryService.cappedCount`. The
   controller's continuation check, the template's two data attributes, the `resultsTotal` store, the toolbar, and the
   map panel heading.

   Tests first:
   - `SearchServiceTests` (both engines): with a cap of 2, four matches report 3, capped, on a flat first page, a
     grouped first page and the map count; a continuation has no total; the last page has no next page;
     `PersonService.merge` still counts exactly (its existing tests);
   - `SearchResultsControllerTests`: the capped attributes, and a 204 past the last page;
   - `SearchSqlTests` pins the shell.

   Verified in the browser: "10000+" in the toolbar and the map panel, unchanged by recycling an asset.

4. **Location counts.** `group_counts` driven by `location_asset`, and `mapLocations` as one statement over `matched`.
   `SearchSqlTests` first, for the new SQL. The counts in `SearchGroupingTests` and `SearchMapTests` guard the
   behavior on both engines.

5. **Search text, broad path.** `SearchQueries.statement` with the text CTEs, memberships over them, the anti-joins,
   `scored` for the Relevance, and the dead predicates removed.

   `SearchSqlTests` first: each source is rendered once per statement, and the Relevance once. The existing text
   tests in `SearchServiceTests`, `SearchCursorTests` and `SearchGroupingTests` guard the results on both engines, and
   a plan test checks that an exclusion is an anti-join on PostgreSQL.

6. **Search text, selective path.** `SearchDao.probeText`, `ResolvedSearchText.candidates`, `SearchQuery.textProbeLimit`
   and the correlated forms.

   Tests first: every text test in `SearchServiceTests`, `SearchCursorTests` and `SearchGroupingTests` runs under a
   probe limit of 0 (always broad) and of 1,000,000 (always selective), and both give the same assets, order, groups
   and totals. Also:
   - a text with a positive group that has no hit returns nothing;
   - a selective page reads `asset` by its primary key (plan test, both engines).

7. **Word memo.** `SearchWords.variants` memoized. A refactor: `SearchWordsTests` and the text tests guard it.

8. **Documentation.**
   - `../../altitude/AGENTS.md`:
     - **Search results and date grouping**: the shells, the flat page, capped totals, the two text paths, `scored`,
       and the new indexes;
     - **Text search**: the probe and the paths;
     - **Map**: `mapLocations`;
     - **DAO / Service Layer** and **Key Files**: the pools, read transactions, timeouts and PRAGMAs.
   - `../../altitude/views/AGENTS.md`: the capped total in the toolbar and the map panel.
   - `../../docs/test-coverage.md`.
   - The comments beside `flat`, `grouped`, `groupedByLocation`, `mapLocations`, `textFilter`, `relevance`, both
     dialects' `textMatch`, `Columns.isInSet` and `TransactionManager`.
   - Root `../../CONTEXT.md`, if a defined term no longer holds.

## Verification

- `make test-unit`, `make test-sqlite`, `make test-controllers` and `make test-psql` (with `altitude-core-postgres-test`
  up), each task red before its change and green after. Then `make lint`.
- Browser checks on `localhost:8080` (`make db` and `make watch` first if it is not running):
  - the toolbar's capped total;
  - infinite scroll to the last flat page;
  - text with Relevance and with a column sort, grouped by day and by Location;
  - the map panel's count.
- A one-off check at a million assets on each engine, with a scratch database seeded like the table under **Current
  behavior** and analyzed (SQLite through `PRAGMA optimize`). Medians, warm cache:

  | Request | Target |
  |---|---|
  | Browse, first page (flat, Date Imported) | ≤ 100 ms |
  | Browse, page 200 | ≤ 300 ms |
  | Folder with a 19k-folder subtree, first page | ≤ 200 ms |
  | Text with at most 5,000 hits, Relevance, first page | ≤ 300 ms |
  | Text matching most of the library (`img`, `-img`), Relevance, first page | ≤ 5 s |
  | Grouped by Location, with text, first page | ≤ 1 s |
  | Map Location panel, with text | ≤ 1 s |
  | Purge one asset | ≤ 50 ms |

  Also check PostgreSQL's `asset` heap size (`pg_relation_size`) with `toast_tuple_target`, against 1.3 GB without it.
