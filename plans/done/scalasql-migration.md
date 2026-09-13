# Replace the custom SQL query builders with ScalaSql

## Original goals

Replace the hand-rolled query-builder layer with [ScalaSql](https://github.com/com-lihaoyi/scalasql):

- `altitude/core/dao/jdbc/querybuilder/SqlQueryBuilder.scala` (generic SELECT/UPDATE from a `Query`)
- `altitude/core/dao/jdbc/querybuilder/SearchQueryBuilder.scala` (search filters, sorted paging, the grouped-search CTE statement, cursor predicate)
- `altitude/core/dao/{postgres,sqlite}/querybuilder/AssetSearchQueryBuilder.scala` (engine hooks)
- `ClauseComponents.scala`, `SqlQuery.scala`

Behaviour must not change: both engines keep working, every existing integration suite stays green, search results, cursors and the benchmarked grouped-search plan shape are preserved.

## Status

Planned 2026-09-12. **Implemented 2026-09-12: steps 1-8 all done.**

ScalaSql owns the whole read side of the DAO layer: the generic `BaseDao` paths (`getOneByQuery`,
`getById`, `query`, `getByIds`, `updateByQuery`, `updateById`) for all eleven DAOs, and both search
paths (`SearchDao.search`, `SearchDao.searchGrouped`). The hand-rolled builder layer is gone:
`dao/jdbc/querybuilder/` and both `dao/{postgres,sqlite}/querybuilder/` directories are deleted,
along with `SqlQueryTests` and `SearchSqlQueryTests`.

Green after `make lint`: `make test-unit` 68, `make test-sqlite` 192, `make test-psql` 192,
`make test-controllers` 31. Plans captured on both engines against a 200,000-asset scratch database
(below); browser pass done on the dev server (see Verification).

### Deviations from the plan as written

- **No `Candidate` projection table.** Naming the columns on the CTE instead
  (`candidates (id, day, sort_value) AS MATERIALIZED (...)`, which both engines accept) lets each
  branch project a plain tuple and renames ScalaSql's `res_0`/`res_1`/`res_2` in one place. This was
  not only cosmetic: `object Candidate extends Table[Candidate]` would need a `TypeMapper[SortValue]`
  resolvable at macro-expansion time from an abstract `DialectTypeMappers`, and that mapper is
  per-engine. The mapper is now supplied as a local `given` where a `SortValue` is read or bound.
- **The candidate slices' `ORDER BY ... LIMIT` is attached with `Select.withExprSuffix`**, the same
  public hook ScalaSql's own `forUpdate` uses. It renders in the select's own context, so the terms
  come out as `date(asset0.original_created_at) DESC, +asset0.filename ASC, asset0.id ASC` -
  character for character what the builder emitted, SQLite's unary `+` planner hint included.
  `sortBy` would have rendered the select-list *aliases* (`day`, `sort_value`, `id`) instead, which
  both engines resolve but which would have silently dropped the `+` hint.
- **`SearchDocumentRow` declares only the three columns both engines have** (`repository_id`,
  `asset_id`, `body`). PostgreSQL keeps `metadata_values` and `tsv`; SQLite's is an fts4 virtual
  table with neither. `tsv` is therefore a raw fragment in `PostgresSearchDialect.textMatch`,
  unambiguous because the subquery has one table in scope. `RowColumnTests` now also reads
  `CREATE VIRTUAL TABLE` out of the SQLite schema so the shared columns are still pinned.
- **`DEFAULT_SQL_COLS_FOR_SELECT` was retired in step 6, not step 4**: `postgres/SearchDao` still fed
  it to the grouped builder until the grouped switch landed.
- **`UrlServiceTests` needed its own `RequestContext.repository`.** It had been relying on
  `SearchSqlQueryTests`' constructor to set one for the whole unit run - a latent isolation bug that
  deleting that suite exposed.

### Plan shape, measured

A scratch database per engine: 200,000 assets in one repository, 10,000 of them undated, the rest
spread over 3,650 days; 20,000 metadata parameter rows and 200,000 search documents, 4,000 of which
match the probe text. PostgreSQL 18 (pgvector image), SQLite 3.51 via sqlite-jdbc 3.51.2.0. The SQL
is what `SearchQueries` actually emits, bound through `DbApi`, not a transcription.

PostgreSQL, `EXPLAIN (ANALYZE, BUFFERS)`:

| Shape | Candidate slice | Day counts | Time |
| --- | --- | --- | ---: |
| first page, day DESC, sort capture | `Index Scan Backward using asset_search_date_taken` + Incremental Sort, presorted on the day | both probes `Index Only Scan using asset_search_date_taken` | 39.3 ms |
| first page, day ASC, sort capture | same, forward | same | 18.1 ms |
| dated cursor, day DESC | index, `((original_created_at)::date) < ?` | same | 0.76 ms |
| dated cursor, day ASC (needs the `undated` slice) | index | `undated` CTE **never executed** | 0.79 ms |
| undated cursor, day DESC | index | same | 12.8 ms |
| undated cursor, day ASC | index | same | 9.2 ms |
| first page, day DESC, sort filename | index + Incremental Sort | same | 23.5 ms |
| dated cursor, day DESC, sort filename | index | same | 0.70 ms |

SQLite, `EXPLAIN QUERY PLAN`: the slice is `SEARCH asset0 USING INDEX asset_search_date_taken
(repository_id=? AND is_recycled=? AND is_pipeline_processed=?)` with `USE TEMP B-TREE FOR LAST 2
TERMS OF ORDER BY` (one term when the grouping and sort directions agree) - the day itself is read in
index order, never a full sort. Both day-count probes are `SEARCH asset0 USING COVERING INDEX
asset_search_date_taken (... AND <expr>=?)`, and so is the first-page total.

Flat search on both engines renders one `SELECT` level with `COUNT(1) OVER ()`, `ORDER BY <sort>
<dir>, asset0.id ASC` and bound `LIMIT`/`OFFSET` - the old sorted search wrapped a subquery around a
comma join to do the same thing.

**The semi-join change, old shape against new**, the one item that alters plan shape (decision 5):

| Engine | Filter | Old (comma join) | New (semi-join) |
| --- | --- | ---: | ---: |
| PostgreSQL | text | 136.3 ms | 108.2 ms |
| PostgreSQL | metadata | 75.9 ms | 38.8 ms |
| SQLite | text | 18 ms | 18 ms |
| SQLite | metadata | 34 ms | 37 ms |
| SQLite | flat sorted, deep page | 311 ms | 306 ms |

PostgreSQL picks the *same* plan for the text filter either way (walk `asset_search_date_taken`,
probe `search_document_01`, filter on `tsv`), and a better one for metadata now that the outer
`GROUP BY` is gone. A flat text search uses the `search_document_02` GIN index. On SQLite the fts4
`MATCH` inside a scalar subquery does resolve through the full-text index - `SCAN search_document1
VIRTUAL TABLE INDEX 4:`, which is `FTS3_FULLTEXT_SEARCH` on the `body` column, not a scan - which was
the specific unknown this gate existed for.

## Background

### What the builders do today

- `SqlQueryBuilder` turns a stringly-typed `Query` (`Map[String, Any]` keyed by `FieldConst` column names, only `EQ` and `IN` params supported, `negate` silently ignored) into `SELECT <cols>, count(*) OVER() AS total FROM t WHERE ... ORDER BY ... LIMIT/OFFSET` and `UPDATE t SET ... WHERE ...`. **As of step 3 the generic `BaseDao` paths no longer use it**; it survives only because `SearchQueryBuilder` extends it, and `SqlQueryTests` still pins its output.
- `SearchQueryBuilder` adds repository scoping, the `is_pipeline_processed` filter, text search (comma-join to `search_document`), metadata filters (comma-join to `metadata_parameter` + `GROUP BY asset.id HAVING count >= n`), folder/person/album filters, a subquery wrapper for sorted searches, and `buildGroupedSearchSql`: a `WITH ... MATERIALIZED` chain (`candidates` / optional `dated`+`undated` / `page` / `day_counts` / optional `total`) whose bind values are concatenated by hand in textual appearance order (`SearchQueryBuilder.scala:182-187`). It also reads `RequestContext.getRepository` while building SQL (`:232`). **Untouched so far.**
- Execution of everything that is still hand-written SQL is Apache commons-dbutils (`QueryRunner` + `MapListHandler`) over the `java.sql.Connection` held in `RequestContext.conn` (a `DynamicVariable`), opened per transaction by `TransactionManager` (no pool). Rows arrive as `Map[String, AnyRef]` and each DAO's `makeModel` casts by column name.
- Engine differences live in `PostgresOverrides` / `SqliteOverrides` (bind helpers `nativeBool`, `nativeLocalDateTime`, `jsonFunc`; readers `getDateTimeField`, `getSortValueField`, `count`), now joined by `dialect` and `toLocalDateTime` for the typed paths.
- The only unit tests were two string-assertion suites (`SqlQueryTests`, `SearchSqlQueryTests`); real coverage is the integration suites run against both engines (`SearchServiceTests`, `SearchGroupingTests`, `SearchCursorTests`, `AssetQueryTests`, ...).

### ScalaSql facts this plan relies on (verified against the 0.3.2 sources)

- Release **0.3.2** (2026-09-03), Scala **>= 3.6.2** (project is on 3.6.3, mill 1.0.6: compatible). Dependency: `mvn"com.lihaoyi::scalasql:0.3.2"`. `scalasql-simple` (`SimpleTable`, named tuples) needs Scala >= 3.7.0: follow-up, see below.
- Dialects: `PostgresDialect`, `SqliteDialect`. Queries are written against a `dialect` value (`import dialect.*`), so DAO code stays engine-agnostic and the engine is still picked at runtime in `Altitude.DAO`. **`dialect` must be a `val`, not a `def`** - `import dialect.*` needs a stable path.
- `DbApi.Impl(connection, config, dialect, listeners, autoCommit = false)` is a public class (`DbApi.scala:239`). It wraps an existing `java.sql.Connection`, never touches `setAutoCommit` when `autoCommit = false` (`:562`), and only `close()` closes the connection (`:633`). This is the integration point with the existing `TransactionManager`.
- `sql"..."` interpolator: every interpolated Scala value becomes a `?` bound through its `TypeMapper`; `SqlStr` fragments and typed `Select`s can be interpolated too; `SqlStr.raw` splices trusted text. `db.runSql[R](sqlStr)` reads rows into `R` = primitives, `Option`, tuples (to 22), or `Row[Sc]` case classes, in any nesting.
- A typed `Select` is rendered to a `SqlStr` with `SqlStr.Renderable.renderSql(select)(ctx)` where `ctx = Context.Impl(Map(), Map(), valueMarker = false, config, dialect)` (the trait method is `private[scalasql]`, the `object Renderable` accessor is not). `Select.renderSql` renders with `LiveExprs.none`, so no columns are dropped, and `.withCompleteQuery(false)` stops the result being parenthesised when spliced.
- `sortBy` can be chained; the **last** `sortBy` call becomes the leading ORDER BY term - but only while `limit`/`offset` are still empty (`CompoundSelect.scala:47-52`), so every `sortBy` must precede `drop`/`take`. `.asc/.desc/.nullsFirst/.nullsLast` apply to the most recent `sortBy`, and no `NULLS` clause is emitted unless asked for. `take(n)`/`drop(n)` render `LIMIT ?`/`OFFSET ?` (bound, not inlined).
- Window functions: `select.mapAggregate((row, agg) => (row, agg.size.over))` renders `COUNT(1) OVER ()`.
- Table and column names render **unquoted** by default (`Table.escape = false`): `asset0.original_created_at`, not `"asset"."original_created_at"`. Every select-list item is aliased `AS <camelToSnake(label)>`.
- A sort term that is also in the select list renders as its **alias** (`ORDER BY res_0_filename DESC`), not as `table.column`. Both engines accept it.
- `IN (subquery)`: `otherSelect.map(_.assetId).contains(a.id)`. `EXISTS`: `.nonEmpty`.
- `Table.update(filter).set(assignments*)`, `Table.delete(filter)`; column names via `Config.columnNameMapper` (default camelToSnake) plus per-table `tableColumnNameOverride`; table names via `override def tableName`. `Update.Impl.Renderer` builds the SET list from `columnNameMapper(assign.column.name)`, so dynamically constructed `Column.Assignment`s work.
- `Select.single` raises a bare `AssertionError` on a row count other than one - not an `Exception`, so it slips through ordinary `catch` clauses.
- `withCte` exists but cannot emit `MATERIALIZED`, `LIMIT CASE WHEN ...`, or SQLite's unary `+` planner hint, which is why the grouped statement stays a raw shell (decision 2).
- `Config.logSql(sql, file, line)` gives central SQL logging (`DbApi.scala:570`).
- **A wart to route around: `SqliteDialect.TableOps` resolves its dialect from the library's own `SqliteDialect` object, not from the dialect in scope** (`dialects/SqliteDialect.scala:196-212`). A table reached through it reads and writes every column with the *stock* mappers, silently ignoring a custom dialect's. `PostgresDialect` does not override `TableOpsConv`, so only SQLite is affected. See decision 8.
- Dialect gaps that need custom `TypeMapper`s (defined once per engine, decision 8):
  - SQLite timestamps are stored as text `yyyy-MM-dd HH:mm:ss` (camera wall-clock / UTC). ScalaSql's SQLite mapper uses `getObject(idx, classOf[LocalDateTime])`/`setObject`, which is not that format.
  - SQLite `date()` returns ISO text; Postgres `::date` returns a `DATE`.
  - Postgres `timestamp` must be read as `LocalDateTime` and `timestamptz` as `OffsetDateTime` (today's `javaTimeRowProcessor`).
  - Postgres `jsonb` columns: `rs.getString` returns the JSON text (pgjdbc), so the `(col#>>'{}')::text` select-list casts are only needed by the raw paths that keep using dbutils.
  - SQLite `TINYINT` booleans work with the default `setBoolean/getBoolean` mapper.

## Confirmed decisions

1. **Scope: builders and their callers only.** Replaced: the four querybuilder files, both `AssetSearchQueryBuilder`s, `BaseDao.getOneByQuery/getById/query/getByIds/updateByQuery/updateById`, `SearchDao.search/searchGrouped`, the `UserService` leak. Unchanged in this phase: the ~54 hand-written SQL statements in the other DAO methods (inserts, recursive folder CTEs, album/person updates, vector search, `deleteByQuery`, `increment`), which keep running on commons-dbutils and `makeModel(Map)`. Porting them is a follow-up phase.
2. **Grouped search = hybrid.** The "matching assets" relation is one typed ScalaSql `Select` shared by every branch. It is rendered into a `sql"..."` shell that keeps today's tuned shape verbatim: `MATERIALIZED` CTEs, the `LIMIT CASE WHEN (SELECT count(*) FROM dated) > rpp THEN 0 ELSE rpp+1 END` guard, the SQLite unary `+` sort hint, native null placement (no `NULLS FIRST/LAST`), the first-page-only `total`. Bind values are carried by the fragments themselves, ending the hand-ordered bind list.
3. **Encoding: `Table[T[_]]` on Scala 3.6.3.** Row classes are separate from the domain models (`AssetRow[T[_]]` vs `Asset`) and convert through a per-DAO `toModel`. Two reasons, and only the first is about ScalaSql: `Table[V[_[_]]]` needs a higher-kinded row so the same class can be a projection of expressions, of assignable columns and of plain values, which a flat case class cannot be; and the models are not row shapes anyway - `Asset.assetType` flattens to three columns, the three metadata fields are *parsed* JSON, `isInFaceRecModel` is not a column, `Person` carries a mutable `TreeSet[Face]`, `Folder.children` is a tree and `numOfChildren`/`numOfAssets` are computed per query, `Face.features` is dropped on read, `User` deliberately omits `password_hash`, and several models omit columns that predicates filter on (`repository_id`, `is_purged`, `is_deleted`, `name_lc`). Moving to Scala 3.7 + `SimpleTable` (follow-up 2) removes the `T[_]` boilerplate but not the second reason.
4. **Connection: wrap the existing one.** `TransactionManager`, `RequestContext.conn`, `withTransaction/asReadOnly`, the SQLite PRAGMAs and the vector-extension loading stay exactly as they are. DAOs obtain a per-call `DbApi` over `RequestContext.getConn` with `autoCommit = false` and never call `close()` on it.
5. **Text and metadata filters become semi-joins on `asset.id`**, like the existing person and album filters: `asset.id IN (SELECT asset_id FROM metadata_parameter WHERE repository_id = ? AND (...) GROUP BY asset_id HAVING count(*) >= n)` and `asset.id IN (SELECT asset_id FROM search_document WHERE repository_id = ? AND tsv @@ to_tsquery(?) | body MATCH ?)`. Every search then reads `FROM asset` alone: no comma joins, no `GROUP BY asset.id / HAVING` on the outer query, the sorted flat search no longer needs its subquery wrapper, and the grouped CTE branches simplify. **This is the one change that alters plan shape**, so the `EXPLAIN` gate in step 5 is not optional. Not started.
6. **The `count(1) OVER ()` total stays** on flat queries (one round trip), added with `mapAggregate` **before** `sortBy/take/drop` so it counts the whole match, not the page.
7. **Null ordering stays native.** The engine hooks `nullsFirst(direction)` and `isNullableTimestamp(field)` survive; no `NULLS FIRST/LAST` is ever emitted (index-ordered reads on both engines depend on it).
8. **One ScalaSql dialect object per engine** (`AltitudePostgresDialect extends PostgresDialect`, `AltitudeSqliteDialect extends SqliteDialect`) carrying the project's `TypeMapper`s. As built:
   - SQLite `LocalDateTime` and `OffsetDateTime` as `yyyy-MM-dd HH:mm:ss` text (reusing `SqliteOverrides.DATETIME_FORMATTER/PARSER`), SQLite `LocalDate` as ISO text, Postgres `timestamptz -> OffsetDateTime` via `getObject`. The Postgres wall-clock and calendar-day mappers are the stock ones, which already read the `java.time` types.
   - `AltitudeSqliteDialect` also **overrides `TableOpsConv` back to the base `TableOps`**, which threads the dialect it is given. Without it none of the mappers above are used by a table query; the symptom was a wall-clock capture time shifted by the JVM zone (`AssetDateStorageTests`, the DST-gap case) - a failure a long way from its cause. `SqlDialectTests` pins it.
   - **Instant columns need an engine hook**, which the original decision missed. Row classes are shared between engines, so `created_at` / `updated_at` / `expires_at` are `T[Option[OffsetDateTime]]` on both, but the engines already mean different things by them: PostgreSQL stores a `timestamptz` and `getDateTimeField` shows it in the JVM zone, while SQLite stores UTC wall-clock text and hands it back verbatim. `BaseDao.toLocalDateTime(OffsetDateTime): LocalDateTime`, supplied by the two override traits, preserves each engine's existing behaviour. `original_created_at` is `T[Option[LocalDateTime]]` on both and needs no hook.
   - The `SortValue` mapper per engine is **step 5 work** and does not exist yet.
9. **`Query`/`SearchQuery` public API is unchanged**; services are not touched (except the `UserService` leak, which moved into `UserDao`). String-keyed params are resolved against the table's column map at build time; an unknown column or an unsupported `ParamType` (anything but `EQ`/`IN`) throws `IllegalArgumentException` as before, and `negate = true` now throws too instead of being ignored (nothing in the codebase uses it).
10. **Tests.** `SqlQueryTests` and `SearchSqlQueryTests` are deleted **in step 6**, not before - they pin the builders that search still uses. Their replacements assert structural invariants of `db.renderSql(...)` output (semi-join shape, ORDER BY term order, no `NULLS` clause, `MATERIALIZED`, `LIMIT CASE`, `?` count equals bind count). `SearchSqlQueryTests`' first test - the 24-case matrix over 2 engines x 2 group directions x 3 cursor positions x 2 sort fields - encodes the tuned plan invariants and should be **ported**, not dropped. Behaviour is covered by the integration suites on both engines.
11. **Logging** moves to `Config.logSql` at debug level (replacing the per-call `logger.debug(sql)` in the replaced methods). The `RequestContext` read/write query counters are preserved by routing every ScalaSql call through `Db.read(...)` / `Db.write(...)`, which bump them exactly once per call. This is load-bearing: `SearchGroupingTests` asserts a read-count delta of exactly 1 per grouped page.
12. **Plan document lives here** (`plans/scalasql-migration.md`) and moves to `plans/done/` when implemented.

## Design

### Package `altitude/src/altitude/core/dao/sql/` - **built**

- `Db.scala`:
  - `private def api(dialect): DbApi` = `new DbApi.Impl(RequestContext.getConn, Db.config, dialect, Nil, autoCommit = false)` (cheap, per call; never closed - closing it would close the transaction's connection).
  - `val config: scalasql.Config` overriding `logSql` (slf4j debug) and keeping the default camelToSnake name mapper.
  - `def read[R](dialect)(f: DbApi => R)` / `def write[R](dialect)(f: DbApi => R)` - the only entry points; each bumps one `RequestContext` counter.
  - `def rootContext(dialect): Context` and `def render(query, dialect): SqlStr` for splicing typed selects into raw shells; the grouped statement is their only caller.
- `dialects/AltitudePostgresDialect.scala`, `dialects/AltitudeSqliteDialect.scala`: the two dialect objects of decision 8. `BaseDao` has `protected val dialect: scalasql.dialects.Dialect`, supplied by `PostgresOverrides`/`SqliteOverrides`; DAO methods start with `import dialect.*`.
- `tables/*.scala`: one `XRow[T[_]]` case class + `object XRow extends Table[XRow]` for each of the eleven tables the generic paths touch: `asset`, `folder`, `person`, `face` (without `features`), `album`, `repository`, `account` (with `password_hash`), `user_token`, `metadata_field`, `stats`, `system`. Each object overrides `tableName`; **no `tableColumnNameOverride` is needed anywhere** - naming the asset field `filename` rather than `fileName` makes camelToSnake round-trip every column. Nullable columns are `T[Option[...]]`. Row classes declare **every** column of their table, not only the ones `makeModel` reads, so any `Query` parameter resolves; parity quirks are kept deliberately (`asset.folder_id` is `T[String]` because the model trims it, `size_bytes` is `Int` in the DB and widened in `toModel`).
  - Three more were added for the search side: `metadata_parameter`, `album_asset`, and `search_document` with only the columns both engines have (see the Status deviations). No `Candidate` projection exists - the CTEs name their own columns.
- `Columns.scala`: `byName(table, exprs)` zips `Table.labels` through the name mapper against `walkExprs`, with `of(table, row, dialect)` for a known table; `required(...)` raises on an unknown column; `literal(value, dialect)` dispatches an untyped value to a bound fragment, accepting exactly the types the hand-built SQL accepted; `equalTo` and `isIn` build the two bound predicates the string-keyed API needs, shared by `DynamicFilter` and the search filters.
- `DynamicFilter.scala`: `Query.params -> Expr[Boolean]`, per param `String/Boolean/Number` and `QueryParam(EQ)` -> `sql"$col = $v"`, `QueryParam(IN)` -> `sql"$col IN (...)"`, everything else throws. No params renders no WHERE clause at all.
- `DynamicAssignments.scala`: the `SET` half of an update, against the same column map with the same value dispatch. `Column.Assignment` is typed and the value is not, so one unchecked cast lives here, confined to a single line with a comment.

### Search package `dao/sql/search/` - **built**

- `SearchDialect.scala` (trait) + `PostgresSearchDialect.scala` + `SqliteSearchDialect.scala`: the engine hooks that replaced the two `AssetSearchQueryBuilder`s: `day(asset, groupBy): Expr[Option[LocalDate]]` (`${col}::date` / `date(${col})`, raw fragments so the index expression matches textually), `textMatch(document, text)`, `secondarySort(asset, sort, grouping)` (unary `+` on SQLite), `isNullableTimestamp`, `nullsFirst`, `sortValueMapper`, and the `dialect` the typed halves are written against.
  - `sortValueMapper` is a `TypeMapper[SortValue]`, the typed replacement for `getSortValueField`. PostgreSQL reads the column's own type name and pulls a `timestamp` as `LocalDateTime` and a `timestamptz` as `OffsetDateTime`, exactly as `javaTimeRowProcessor` did; SQLite reads text and numbers as stored. `put` binds a cursor value back the same way. A null column reads as `SortValue.Null`, which is never bound - it is compared with `IS NULL`.
- `SearchQueries.scala`: the typed search:
  - `matching(engine, query, repoId): Select[AssetRow[Expr], AssetRow[Sc]]`: `AssetRow.select` filtered by repository and `is_pipeline_processed`, then `DynamicFilter(query.params)`, then folder / text / metadata / person / album as `filterIf`. Pure function of its arguments; `RequestContext` is read by the caller.
  - `flat(engine, query, repoId)`: `matching(...).mapAggregate((a, agg) => (a, agg.size.over))`, then `sortBy(_._1.id).asc`, then `sortBy(sortColumn).asc|desc` (last call leads), then `drop((page-1)*rpp).take(rpp)` when `rpp > 0`. Runs with `db.run` into `Seq[(AssetRow[Sc], Int)]`.
  - `grouped(engine, query, repoId): SqlStr`: the hybrid statement (below).
  - `cursorPredicate(engine, cursor, grouping, sort, row): Expr[Boolean]`: the same logic as the builder's, written as `Expr[Boolean] { implicit ctx => sql"..." }` over typed columns, binding the `SortValue` and the `LocalDate` day through the engine mappers. The redundant inclusive day bound is kept - it is what lets the day index seek.

### The grouped statement (hybrid) - **built**

```scala
val base  = matching(engine, query, repositoryId)          // FROM asset asset0 WHERE ...
val asset = WithSqlExpr.get(base)                          // the row every fragment refers to

def candidates(extra, limit: SqlStr) =                     // one narrow, ordered, limited slice
  val projected = extra.fold(base)(base.filter).map(row => (row.id, day(row), sortValue(row)))
  Db.render(
    Select.withExprSuffix(Select.toSimpleFrom(projected), false, ctx =>
      sql" ORDER BY ${day(asset)} $groupDir, ${engine.secondarySort(asset, sort, grouping)} $sortDir, ${asset.id} ASC" + limit),
    engine.dialect).withCompleteQuery(false)

def matchingIds(extra) = Db.render(extra.fold(base)(base.filter).map(_.id), engine.dialect).withCompleteQuery(false)

sql"""WITH $candidatesCte, page (id, day, sort_value) AS MATERIALIZED (...),
      day_counts AS MATERIALIZED (
        SELECT p.day AS day, (SELECT count(*) FROM (${matchingIds(Some(isCursorDay))}) AS m) AS n
          FROM (SELECT DISTINCT day FROM page$datedDriver) AS p$nullCount)
      $totalCte
 SELECT $assetColumns, p.day AS day, p.sort_value AS sort_value, d.n AS day_total,
        (SELECT count(*) FROM candidates) AS candidate_count$totalColumn
   FROM page AS p JOIN asset ON asset.id = p.id
        LEFT JOIN day_counts AS d ON d.day = p.day$nullJoin $totalJoin
  ORDER BY ${order("p")}"""
```

- **The CTEs name their own columns** (`candidates (id, day, sort_value)`), which both engines accept, so each branch projects a plain tuple and ScalaSql's `res_0`/`res_1`/`res_2` aliases are renamed once. This replaces the planned `Candidate` projection table; see the Status deviations for why.
- **`Select.withExprSuffix` carries the `ORDER BY ... LIMIT`.** It is the public hook ScalaSql's own `forUpdate` uses, and it renders in the select's own context, so the terms are the builder's verbatim - SQLite's unary `+` included - rather than the select-list aliases `sortBy` would emit.
- The `day = p.day` correlation inside `matchingIds(...)` references the raw alias `p`, so that predicate is a raw fragment: `Expr[Boolean] { implicit ctx => sql"${day(row)} = p.day" }`.
- The optional branches (`undated`, `nullCount`, `total`) are `SqlStr.empty` when not applicable, exactly mirroring the builder's conditions (`needsUndatedSlice`, `ownGroup`, `isFirstPage`).
- `assetColumns` is `Table.labels(AssetRow)` through the name mapper, so the outer select names the asset columns in row order and reads back positionally.
- Because every branch renders from the same `base` select, the predicate cannot drift between rows and counts, and bind values travel with their fragment: the hand-ordered bind list is gone.

### `BaseDao` changes - **done**

- New abstract members `type Row[T[_]]`, `protected def table: Table[Row]`, `protected def toModel(row: Row[Sc]): Model`, `protected val dialect: Dialect` and `protected def toLocalDateTime(OffsetDateTime): LocalDateTime` (the last two from the engine override traits), plus `protected given rowQueryable` so the generic code can summon a `Queryable.Row` for an abstract row type.
- Reimplemented on ScalaSql: `getOneByQuery`, `getById`, `query`, `getByIds`, `updateByQuery`, `updateById`. `getOneByQuery` still raises `NotFoundException` when empty and `ConstraintException` on more than one row.
- **`query` needed a protected twin.** `dao.AssetDao` overrides the public `query` to throw - an asset is only ever queried through one of its view-scoped variants - and those used to reach the implementation through the old `query(q, builder)` overload. The implementation is now `protected def queryRecords(q)`, which the four variants call.
- `sqlQueryBuilder` is gone from `BaseDao` and from `jdbc/AssetDao`, and step 6 removed the rest: `totalRecsWindowFunction`, `count(recs)`, `getDateField`, `getSortValueField` and `postgres/AssetDao.DEFAULT_SQL_COLS_FOR_SELECT`. `columnsForSelect` survives for `RepositoryDao.getAll`, the one hand-written `SELECT` that still names its columns (PostgreSQL's `#>>'{}'` cast on `file_store_config`).
- `makeModel(Map)`, `executeAndGetOne/Many`, `manyBySqlQuery`, `addRecord`, `updateByBySql`, `deleteByQuery`, `increment` stay on dbutils for this phase.
- `UserDao` gained `getPasswordHashByEmail(email): String`; `UserService.getPasswordHashByEmail` is gone and the service calls the DAO. It does **not** use `Select.single`, which raises an `AssertionError` the login path would not catch - it reads the rows and raises `NotFoundException` itself.
- Two latent problems removed on the way through: nothing emits `count(*) OVER() AS total` twice on Postgres any more (`DEFAULT_SQL_COLS_FOR_SELECT` contained it and `SqlQueryBuilder.selectStr` appended it again, so the search path did until step 4); and `SystemMetadataDao`'s non-`final` `override val tableName` no longer leaves a query builder constructed with a `null` table name, because `table` is an abstract `def` supplied by the subclass.

### `SearchDao` changes - **done**

- `jdbc/SearchDao.search` -> `SearchQueries.flat`; `searchGrouped` -> `SearchQueries.grouped`. `assetSearchQueryBuilder` and the `getDateField/getSortValueField/getIntField` reads are gone.
- `searchGrouped` reads its rows with `db.runSql` into a tuple of `(AssetRow[Sc], Option[LocalDate], SortValue, Int, Int)`, with the first-page total as a sixth element. The two shapes are read separately rather than selecting a `NULL` total on continuation pages, so the statement stays exactly what the builder emitted.
- `postgres/SearchDao` and `sqlite/SearchDao` supply their `SearchDialect` instead of an `AssetSearchQueryBuilder`; their document-indexing SQL is untouched.

### Build - **done**

- `build.mill` `coreDeps`: `mvn"com.lihaoyi::scalasql:$scalaSqlVersion"` with `scalaSqlVersion = "0.3.2"` (transitively `scalasql-core`, `scalasql-query`, `scalasql-operations`, plus `geny` and `sourcecode`, already in the lihaoyi stack via cask/upickle). commons-dbutils stays until the follow-up phase.
- New code must satisfy `make lint`: scalafmt `maxColumn = 130`, scala3 dialect, import groups `[".*"] / ["scala\..*"] / ["altitude.core\..*"]`, and scalafix `ExplicitResultTypes` + `OrganizeImports` + `RemoveUnused`.

## Implementation steps

Each step compiles and keeps `make test-sqlite` green before the next; `make test-psql` at the checkpoints marked (PG).

1. ~~**Dependency and infra.**~~ **Done.** ScalaSql 0.3.2 added; `dao/sql/Db.scala` and the two dialect objects created; `protected val dialect` wired through `PostgresOverrides`/`SqliteOverrides`.
2. ~~**Row classes.**~~ **Done** for the eleven DAO tables. The three search side tables were deferred to step 4, since nothing in this phase reads them, and `Candidate` was never needed. Every DAO got its `type Row`, `table` and `toModel` (mirroring its `makeModel`, with `folderId.trim`, the `ujson` column parsing and `CaptureDateSource.fromDbValue` carried across unchanged), plus the `toLocalDateTime` hook on both override traits. `RowColumnTests` renders `table.select` for every row class and diffs the column list against both `all.sql` files, and checks that the names `DynamicFilter` resolves are the names the rendered SQL uses.
3. ~~**Generic reads and updates.**~~ **Done.** `Columns`, `DynamicFilter` and `DynamicAssignments` added with `DynamicFilterTests`; the six `BaseDao` methods reimplemented; the `UserService` leak moved into `UserDao`; `sqlQueryBuilder` removed from `BaseDao`/`AssetDao`. `SqlDialectTests` added for the dialects and the `TableOps` wart.
4. ~~**Flat search.**~~ **Done.** `MetadataParameterRow`, `SearchDocumentRow` and `AlbumAssetRow` added; `SearchDialect` + both engine implementations; `SearchQueries.matching/flat`; `SearchDao.search` switched. `DEFAULT_SQL_COLS_FOR_SELECT` had to wait for step 6, because `postgres/SearchDao` still fed it to the grouped builder. Green on both engines (PG).
5. ~~**Grouped search.**~~ **Done.** The per-engine `SortValue` mapper, `cursorPredicate` and `SearchQueries.grouped` added; `SearchDao.searchGrouped` switched. No `Candidate` table - the CTEs name their own columns. Green on both engines (PG); `EXPLAIN` captured against 200,000-asset scratch databases and recorded in the Status section.
6. ~~**Delete the old layer.**~~ **Done.** `dao/jdbc/querybuilder/*`, both `AssetSearchQueryBuilder`s, `SqlQueryTests` and `SearchSqlQueryTests` are gone, along with `totalRecsWindowFunction`, `count(recs)`, `getDateField`, `getSortValueField` and `DEFAULT_SQL_COLS_FOR_SELECT`. `columnsForSelect` stays for `RepositoryDao.getAll`. `SearchSqlTests` replaces the two deleted suites in `AllUnitTestSuites`, and `RowColumnTests` grew to cover the three new row classes.
7. ~~**Docs.**~~ **Done.** `altitude/AGENTS.md`, `docs/COVERAGE.md`, and `docs/Result-Grouping.md`, whose SQL section was rewritten from the statement the code actually emits: it had drifted to a single `candidates` CTE and predated the `dated`/`undated` guarded slices.
8. ~~**Lint and final runs.**~~ **Done.** See the Status section for the counts.

## Verification

### Done for steps 1-3

- `make compile` after every step.
- `make test-unit` 68 (the new `RowColumnTests`, `DynamicFilterTests` and `SqlDialectTests`, plus `SqlQueryTests` and `SearchSqlQueryTests` still passing **unchanged** - if either breaks, something was deleted too early).
- `make test-sqlite` 192 and `make test-psql` 192 after steps 2 and 3; `make test-controllers` 31 at the end of step 3; `make lint`, then the whole matrix again.
- The suites that carried the weight: `AssetQueryTests` (the plain `Query` path), `AssetDateStorageTests` (wall-clock preservation, the DST gap, JVM-zone independence - the suite that caught the `TableOps` dialect leak), `LibraryService*Tests` (`updateByQuery`), `UserServiceTests` (the `getPasswordHashByEmail` move), `SearchGroupingTests` (the read-count delta invariant).

### Done for steps 4-5

Results are in the Status section. Method: a scratch database per engine (200,000 assets, 10,000 undated, 3,650 days, 20,000 metadata parameter rows, 200,000 search documents) with the statement `SearchQueries` actually emits, bound through `DbApi` rather than transcribed, and the old comma-join shapes hand-written beside the new ones for comparison. The scratch databases were dropped afterwards.

- Plan shape, per engine, over the grouped statement in eight shapes (first page, dated cursor, undated cursor; both group directions; sorted by `original_created_at` and by `filename`), plus the metadata- and text-filtered variants and the three flat shapes:
  - Postgres `EXPLAIN (ANALYZE, BUFFERS)`: the candidate slice reads `asset_search_date_taken` under an Incremental Sort presorted on the day, both day-count probes and the first-page total are index-only scans of it, and the `undated` CTE is reported **never executed** when `dated` fills the page. ✅
  - SQLite `EXPLAIN QUERY PLAN`: `SEARCH asset0 USING INDEX asset_search_date_taken` for the slice, `USING COVERING INDEX` for both day counts, and `USE TEMP B-TREE FOR LAST 2 TERMS OF ORDER BY` (one term when the two directions agree) rather than a full sort. ✅
  - Flat sorted search: one `SELECT` level with `COUNT(1) OVER ()`, `ORDER BY <sort> <dir>, asset0.id ASC`, bound `LIMIT`/`OFFSET`, pinned by `SearchSqlTests`. The old sorted search wrapped a subquery around a comma join to do the same thing; timings are unchanged. ✅
  - Metadata and text filters render as `asset0.id IN (SELECT ...)`. Postgres uses `search_document_02` (gin) for a flat text search, and picks the *same* plan as the comma join for a grouped one. On SQLite the fts4 `MATCH` inside a scalar subquery resolves through the full-text index (`VIRTUAL TABLE INDEX 4:` = `FTS3_FULLTEXT_SEARCH` on `body`), which was the one genuine unknown. ✅
- Browser check on the dev server (`:8080`, 150 assets on PostgreSQL, 13 of them undated). Every grouped combination - both group directions crossed with a capture sort and a filename sort - walks its continuations to exhaustion: 150 images over 49 groups each time, no repeated day header, and the "No date" group appearing exactly once, leading on `desc` and trailing on `asc` (the `asc` case is the one that needs the guarded `undated` slice). Flat view pages 1, 2 and 6 of 25 return full pages, page 7 returns none, and both sort fields work. A folder filter reads `Total: 25` against the sidebar's own `(25)`, which `AssetDao.countByFolder` computes on a separate raw-SQL path - so the search predicate and the folder count still agree. An album filter reads `Total: 10` against its `(10)` badge, and a person filter returns its 94 assets grouped and counted. The dev library has no user metadata at all, so the text and metadata filters return an honest zero there; those two are covered by `SearchServiceTests` on both engines and by the `EXPLAIN` comparison above. ✅
- Cursor round trip: a cursor issued before the change is still accepted after it - neither `scopeFingerprint` nor the cursor format changed, and `SearchCursorTests` passes on both engines. ✅

## Risks and how the plan handles them

- **Alias changes (`asset` -> `asset0`) or `CAST(x AS DATE)` vs `x::date` could stop an expression index matching.** The day expression is emitted as a raw fragment with the builder's text, and the `EXPLAIN` gate confirmed both engines still match the index. Columns render unquoted, which `RowColumnTests` pins. The `ORDER BY` needed `Select.withExprSuffix` to stay verbatim - see the Status deviations.
- **The semi-join change (decision 5) alters plan shape.** Was the highest-risk item; measured on both engines against the old comma-join shapes and no worse anywhere (see Status). SQLite fts4 inside a scalar subquery does use the full-text index.
- **`Candidate` projection labels.** Sidestepped entirely: the CTEs name their own columns, so the projection's labels never matter.
- **sqlite-jdbc `getObject(idx, classOf[LocalDateTime])`.** Not relied on: the SQLite dialect overrides the temporal mappers with the text formats the schema uses - **but only because `TableOpsConv` is also overridden** (decision 8). *This one materialised in step 2 and cost a debugging session.*
- **`===` on `Option` columns renders `IS NOT DISTINCT FROM`.** Non-null columns use `` `=` ``/`===` on non-Option types; dynamic filters emit `=` directly. SQLite >= 3.39 (bundled in sqlite-jdbc 3.51) supports the form anyway.
- **`LIMIT ?` binds instead of inlined numbers.** Harmless on both engines; the `LIMIT CASE ...` guard stays raw.
- **Two mapping paths coexist** (`makeModel(Map)` for raw SQL, `toModel(Row)` for ScalaSql) until the follow-up phase. They are adjacent in each DAO with a comment saying which paths use which.

## Follow-ups (not in this change)

1. **Port the remaining hand-written SQL to ScalaSql and drop commons-dbutils.** Inserts via `Table.insert.columns`, correlated updates via `update.set`, recursive folder CTEs and vector queries stay `sql"..."` but run through `db.runSql[(FolderRow[Sc], Int)]`-style typed reads; delete `makeModel(Map)`, `nativeBool`, `nativeLocalDateTime`, `nativeUtcTimestamp`, `jsonFunc`, `rowProcessor`, `getBooleanField`, `getDateTimeField`, `columnsForSelect` and the `#>>'{}'` casts.
2. **Scala 3.7.x + `scalasql-simple`.** Bump `scalaVersion`, verify Twirl 1.6.8, mill-scalafix 0.6.0 and `-source 3.4-migration` still work, add `mvn"com.lihaoyi::scalasql-simple:0.3.2"`, then convert `Row[T[_]]` classes to plain case classes with `SimpleTable` and let queries return named tuples (`.map(a => (id = a.id, day = ...))`), which also removes the `Candidate` projection trick. Note this removes the `T[_]` boilerplate but not the reason the row classes are separate from the domain models (decision 3).
3. **Transactions through `DbClient.transaction` and a pooled `DataSource`** (HikariCP) once the DAO layer is fully on ScalaSql; needs pool-init hooks for the SQLite PRAGMAs and `load_extension`.
4. **Prune `Query.ParamType`** to the two variants actually supported, or implement the rest now that predicates are typed.
5. **Report the `SqliteDialect.TableOps` dialect leak upstream.** `dialects/SqliteDialect.scala:196-212` at 0.3.2 resolves `dialectSelf` and `t.containerQr` from the library's own `SqliteDialect` object instead of the dialect in scope, so any custom SQLite dialect's `TypeMapper`s are silently ignored for table queries. `MySqlDialect`, `H2Dialect` and `MsSqlDialect` override `TableOpsConv` the same way and look to have the same problem.
