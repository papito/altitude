# Replace the custom SQL query builders with ScalaSql

## Original goals

Replace the hand-rolled query-builder layer with [ScalaSql](https://github.com/com-lihaoyi/scalasql):

- `altitude/core/dao/jdbc/querybuilder/SqlQueryBuilder.scala` (generic SELECT/UPDATE from a `Query`)
- `altitude/core/dao/jdbc/querybuilder/SearchQueryBuilder.scala` (search filters, sorted paging, the grouped-search CTE statement, cursor predicate)
- `altitude/core/dao/{postgres,sqlite}/querybuilder/AssetSearchQueryBuilder.scala` (engine hooks)
- `ClauseComponents.scala`, `SqlQuery.scala`

Behaviour must not change: both engines keep working, every existing integration suite stays green, search results, cursors and the benchmarked grouped-search plan shape are preserved.

## Status

Planned 2026-09-12. **Steps 1-3 done 2026-09-12. Steps 4-8 not started.**

ScalaSql owns the generic `BaseDao` read and update paths for all eleven DAOs (`getOneByQuery`,
`getById`, `query`, `getByIds`, `updateByQuery`, `updateById`), and the `UserService` leak now
lives in `UserDao.getPasswordHashByEmail`. **Search still runs on the old builders**, so nothing in
`dao/jdbc/querybuilder/` could be deleted yet - `SearchQueryBuilder extends SqlQueryBuilder`, and
`SqlQueryTests` / `SearchSqlQueryTests` still pass unchanged.

Added (476 lines): `dao/sql/{Db,Columns,DynamicFilter,DynamicAssignments}.scala`,
`dao/sql/dialects/Altitude{Postgres,Sqlite}Dialect.scala`, `dao/sql/tables/*.scala` (eleven row
classes). New unit suites: `RowColumnTests`, `DynamicFilterTests`, `SqlDialectTests`.

Green after `make lint`: `make test-unit` 68, `make test-sqlite` 192, `make test-psql` 192,
`make test-controllers` 31. No `EXPLAIN` capture and no browser pass - both belong to step 5, and
nothing in steps 1-3 touches the grouped statement or the tuned index plan.

Everything below is updated to describe the code as it now stands; the sections for steps 4-8 are
still forward-looking.

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
  - `def rootContext(dialect): Context` and `def render(query, dialect): SqlStr` for splicing typed selects into raw shells (unused until step 5).
- `dialects/AltitudePostgresDialect.scala`, `dialects/AltitudeSqliteDialect.scala`: the two dialect objects of decision 8. `BaseDao` has `protected val dialect: scalasql.dialects.Dialect`, supplied by `PostgresOverrides`/`SqliteOverrides`; DAO methods start with `import dialect.*`.
- `tables/*.scala`: one `XRow[T[_]]` case class + `object XRow extends Table[XRow]` for each of the eleven tables the generic paths touch: `asset`, `folder`, `person`, `face` (without `features`), `album`, `repository`, `account` (with `password_hash`), `user_token`, `metadata_field`, `stats`, `system`. Each object overrides `tableName`; **no `tableColumnNameOverride` is needed anywhere** - naming the asset field `filename` rather than `fileName` makes camelToSnake round-trip every column. Nullable columns are `T[Option[...]]`. Row classes declare **every** column of their table, not only the ones `makeModel` reads, so any `Query` parameter resolves; parity quirks are kept deliberately (`asset.folder_id` is `T[String]` because the model trims it, `size_bytes` is `Int` in the DB and widened in `toModel`).
  - The search side tables (`metadata_parameter`, `search_document`, `album_asset`) and the `Candidate` projection are **step 4-5 work** and do not exist yet.
- `Columns.scala`: `byName(table, exprs)` zips `Table.labels` through the name mapper against `walkExprs`; `required(...)` raises on an unknown column; `literal(value, dialect)` dispatches an untyped value to a bound fragment, accepting exactly the types the hand-built SQL accepted.
- `DynamicFilter.scala`: `Query.params -> Expr[Boolean]`, per param `String/Boolean/Number` and `QueryParam(EQ)` -> `sql"$col = $v"`, `QueryParam(IN)` -> `sql"$col IN (...)"`, everything else throws. No params renders no WHERE clause at all.
- `DynamicAssignments.scala`: the `SET` half of an update, against the same column map with the same value dispatch. `Column.Assignment` is typed and the value is not, so one unchecked cast lives here, confined to a single line with a comment.

### Search package - **not built** (steps 4-5)

- `search/SearchDialect.scala` (trait) + `search/PostgresSearchDialect.scala` + `search/SqliteSearchDialect.scala`: the engine hooks that replace the two `AssetSearchQueryBuilder`s: `day(a: AssetRow[Expr]): Expr[Option[LocalDate]]` (`${col}::date` / `date(${col})`, written as raw fragments so the index expression matches textually), `textMatch(sd: SearchDocumentRow[Expr], text)`, `secondarySort(sort, grouping, a)` (unary `+` on SQLite), `isNullableTimestamp`, `nullsFirst`, `sortValueMapper`.
- `search/SearchQueries.scala`: the typed search:
  - `matching(query: SearchQuery, repoId: String): Select[AssetRow[Expr], AssetRow[Sc]]`: `AssetRow.select.filter(_.repositoryId === repoId).filter(_.isPipelineProcessed === true)` + `DynamicFilter(query.params)` + `filterIf(folderIds.nonEmpty)(folder IN)` + person/album semi-joins + text/metadata semi-joins (decision 5). Pure function of its arguments; `RequestContext` is read by the caller.
  - `flat(query, repoId)`: `matching(...).mapAggregate((a, as) => (a, as.size.over))` then `sortBy(_._1.id).asc`, then `sortBy(sortColumn).asc|desc` (last call leads), then `drop((page-1)*rpp).take(rpp)` when `rpp > 0`. Runs with `db.run` into `Seq[(AssetRow[Sc], Int)]`.
  - `grouped(query, repoId)`: the hybrid statement (below).
  - `cursorPredicate(cursor, grouping, sort, a): Expr[Boolean]`: same logic as today's `cursorPredicate`, written as `Expr { implicit ctx => sql"..." }` over typed columns, binding the typed `SortValue` and `LocalDate` day values through the dialect mappers. Keep the redundant inclusive day bound - it is what lets the day index seek.

### The grouped statement (hybrid) - **not built** (step 5)

```scala
val base      = SearchQueries.matching(query, repoId)                       // FROM asset asset0 WHERE ...
def ids(extra: AssetRow[Expr] => Expr[Boolean]) = Db.render(base.filter(extra).map(_.id))
val candidate = (a: AssetRow[Expr]) => Candidate[Expr](a.id, dialect.day(a), sortExpr(a))   // labels: id, day, sort_value
val dated     = base.filter(continuation).map(candidate)
                    .sortBy(_.id).asc.sortBy(_.sortValue).dir(sort).sortBy(_.day).dir(grouping).take(rpp + 1)
val undated   = Db.render(base.filter(a => dialect.day(a).isEmpty).map(candidate).sortBy(...)).withCompleteQuery(false)

sql"""WITH dated AS MATERIALIZED (${Db.render(dated)}),
      undated AS MATERIALIZED ($undated LIMIT CASE WHEN (SELECT count(*) FROM dated) > $rpp THEN 0 ELSE ${rpp + 1} END),
      candidates AS MATERIALIZED (SELECT id, day, sort_value FROM dated UNION ALL SELECT id, day, sort_value FROM undated),
      page AS MATERIALIZED (SELECT id, day, sort_value FROM candidates ORDER BY ${SqlStr.raw(orderBy)} LIMIT $rpp),
      day_counts AS MATERIALIZED (
        SELECT p.day AS day, (SELECT count(*) FROM (${ids(a => dialect.day(a) `=` p.day)}) AS m) AS n
          FROM (SELECT DISTINCT day FROM page WHERE day IS NOT NULL) AS p
        ${nullCountBranch} ),
      total AS MATERIALIZED (SELECT count(*) AS n FROM (${ids(_ => true)}) AS m)        -- first page only
 SELECT ${SqlStr.raw(assetColumns)}, p.day, p.sort_value, d.n AS day_total,
        (SELECT count(*) FROM candidates) AS candidate_count, t.n AS total
   FROM page AS p JOIN asset ON asset.id = p.id LEFT JOIN day_counts AS d ON ... CROSS JOIN total AS t
  ORDER BY ..."""
```

- `Candidate[T[_]](id: T[String], day: T[Option[LocalDate]], sortValue: T[SortValue])` with `object Candidate extends Table[Candidate]` used purely for its projection labels (`id`, `day`, `sort_value`) - ScalaSql aliases every select-list item as `AS <camelToSnake(label)>`, so these come out right. If label rendering turns out to differ, wrap each branch as `SELECT res_0 AS id, res_1 AS day, res_2 AS sort_value FROM (...) AS m` instead.
- The `day = p.day` correlation inside `ids(...)` references the raw alias `p`, so that predicate is a raw fragment: `Expr { _ => sql"${dialect.day(a)} = p.day" }`.
- The optional branches (`undated`, `nullCount`, `total`) are `SqlStr.empty` when not applicable, exactly mirroring today's conditions (`needsUndatedSlice`, `ownGroup`, `isFirstPage`).
- Result rows are read with `db.runSql[(AssetRow[Sc], Option[LocalDate], SortValue, Int, Int, Option[Int])]` (asset columns, day, sort_value, day_total, candidate_count, total) and folded into `GroupedSearchRow`/`GroupedSearchPage` as today.
- Because every branch renders from the same `base` select, the predicate cannot drift between rows and counts, and bind values travel with their fragment.

### `BaseDao` changes - **done**

- New abstract members `type Row[T[_]]`, `protected def table: Table[Row]`, `protected def toModel(row: Row[Sc]): Model`, `protected val dialect: Dialect` and `protected def toLocalDateTime(OffsetDateTime): LocalDateTime` (the last two from the engine override traits), plus `protected given rowQueryable` so the generic code can summon a `Queryable.Row` for an abstract row type.
- Reimplemented on ScalaSql: `getOneByQuery`, `getById`, `query`, `getByIds`, `updateByQuery`, `updateById`. `getOneByQuery` still raises `NotFoundException` when empty and `ConstraintException` on more than one row.
- **`query` needed a protected twin.** `dao.AssetDao` overrides the public `query` to throw - an asset is only ever queried through one of its view-scoped variants - and those used to reach the implementation through the old `query(q, builder)` overload. The implementation is now `protected def queryRecords(q)`, which the four variants call.
- `sqlQueryBuilder` is gone from `BaseDao` and from `jdbc/AssetDao`. **Still present, because search needs them:** `BaseDao.totalRecsWindowFunction`, `count(recs)`, `getDateField`, `getSortValueField`, `columnsForSelect`, and `postgres/AssetDao.DEFAULT_SQL_COLS_FOR_SELECT`. They go in step 6.
- `makeModel(Map)`, `executeAndGetOne/Many`, `manyBySqlQuery`, `addRecord`, `updateByBySql`, `deleteByQuery`, `increment` stay on dbutils for this phase.
- `UserDao` gained `getPasswordHashByEmail(email): String`; `UserService.getPasswordHashByEmail` is gone and the service calls the DAO. It does **not** use `Select.single`, which raises an `AssertionError` the login path would not catch - it reads the rows and raises `NotFoundException` itself.
- Two latent problems removed on the way through: the generic paths no longer go through `columnsForSelect`, so they no longer emit `count(*) OVER() AS total` twice on Postgres (**the search path still does** - `DEFAULT_SQL_COLS_FOR_SELECT` contains it and `SqlQueryBuilder.selectStr` appends it again; that ends in step 4); and `SystemMetadataDao`'s non-`final` `override val tableName` no longer leaves a query builder constructed with a `null` table name, because `table` is an abstract `def` supplied by the subclass.

### `SearchDao` changes - **not started** (steps 4-5)

- `jdbc/SearchDao.search` -> `SearchQueries.flat`; `searchGrouped` -> `SearchQueries.grouped`. `assetSearchQueryBuilder` and the `getDateField/getSortValueField/getIntField("total")` reads disappear.
- `postgres/SearchDao` and `sqlite/SearchDao` supply their `SearchDialect` instead of an `AssetSearchQueryBuilder`; their document-indexing SQL is untouched.

### Build - **done**

- `build.mill` `coreDeps`: `mvn"com.lihaoyi::scalasql:$scalaSqlVersion"` with `scalaSqlVersion = "0.3.2"` (transitively `scalasql-core`, `scalasql-query`, `scalasql-operations`, plus `geny` and `sourcecode`, already in the lihaoyi stack via cask/upickle). commons-dbutils stays until the follow-up phase.
- New code must satisfy `make lint`: scalafmt `maxColumn = 130`, scala3 dialect, import groups `[".*"] / ["scala\..*"] / ["altitude.core\..*"]`, and scalafix `ExplicitResultTypes` + `OrganizeImports` + `RemoveUnused`.

## Implementation steps

Each step compiles and keeps `make test-sqlite` green before the next; `make test-psql` at the checkpoints marked (PG).

1. ~~**Dependency and infra.**~~ **Done.** ScalaSql 0.3.2 added; `dao/sql/Db.scala` and the two dialect objects created; `protected val dialect` wired through `PostgresOverrides`/`SqliteOverrides`.
2. ~~**Row classes.**~~ **Done** for the eleven DAO tables. The three search side tables and `Candidate` were deferred to steps 4-5, since nothing in this phase reads them. Every DAO got its `type Row`, `table` and `toModel` (mirroring its `makeModel`, with `folderId.trim`, the `ujson` column parsing and `CaptureDateSource.fromDbValue` carried across unchanged), plus the `toLocalDateTime` hook on both override traits. `RowColumnTests` renders `table.select` for every row class and diffs the column list against both `all.sql` files, and checks that the names `DynamicFilter` resolves are the names the rendered SQL uses.
3. ~~**Generic reads and updates.**~~ **Done.** `Columns`, `DynamicFilter` and `DynamicAssignments` added with `DynamicFilterTests`; the six `BaseDao` methods reimplemented; the `UserService` leak moved into `UserDao`; `sqlQueryBuilder` removed from `BaseDao`/`AssetDao`. `SqlDialectTests` added for the dialects and the `TableOps` wart.
4. **Flat search.** Add the search side tables, `SearchDialect` + engine impls and `SearchQueries.matching/flat`. Switch `SearchDao.search`. Retire `postgres/AssetDao.DEFAULT_SQL_COLS_FOR_SELECT`. Run `SearchServiceTests`, `AlbumServiceTests`, `SearchResultsControllerTests` on both engines (PG).
5. **Grouped search.** Add `Candidate`, the per-engine `SortValue` mapper, `cursorPredicate` and `SearchQueries.grouped`. Switch `SearchDao.searchGrouped`. Run `SearchGroupingTests` and `SearchCursorTests` on both engines (PG). Capture `EXPLAIN` output (Verification) and compare with `plans/done/search-results-date-grouping-benchmark.md`.
6. **Delete the old layer.** Remove `dao/jdbc/querybuilder/*`, both `AssetSearchQueryBuilder`s, `SqlQueryTests`, `SearchSqlQueryTests`, and the now-unused `BaseDao`/override members (`totalRecsWindowFunction`, `count(recs)`, `getDateField`, `getSortValueField`, and `columnsForSelect` except for `RepositoryDao.getAll`'s raw select). Add the new render-based unit tests (decision 10) to `AllUnitTestSuites`.
7. **Docs.** Update `altitude/AGENTS.md` (lines 92 and 94 name `SearchQueryBuilder` and `buildGroupedSearchSql`), `docs/Result-Grouping.md` (lines 77-78 and the file table at 330-331), and `docs/COVERAGE.md` (line 279 cites `SearchSqlQueryTests`). Note that `docs/Result-Grouping.md` has **already drifted**: it documents a single `candidates` CTE and predates the `dated`/`undated` guarded slices the builder emits today, so its SQL section needs rewriting regardless. Record `EXPLAIN` results in this plan's Status section.
8. **Lint and final runs.** `make lint`, `make test-sqlite`, `make test-psql`, `make test-controllers`, `make test-unit`. Move this file to `plans/done/`.

## Verification

### Done for steps 1-3

- `make compile` after every step.
- `make test-unit` 68 (the new `RowColumnTests`, `DynamicFilterTests` and `SqlDialectTests`, plus `SqlQueryTests` and `SearchSqlQueryTests` still passing **unchanged** - if either breaks, something was deleted too early).
- `make test-sqlite` 192 and `make test-psql` 192 after steps 2 and 3; `make test-controllers` 31 at the end of step 3; `make lint`, then the whole matrix again.
- The suites that carried the weight: `AssetQueryTests` (the plain `Query` path), `AssetDateStorageTests` (wall-clock preservation, the DST gap, JVM-zone independence - the suite that caught the `TableOps` dialect leak), `LibraryService*Tests` (`updateByQuery`), `UserServiceTests` (the `getPasswordHashByEmail` move), `SearchGroupingTests` (the read-count delta invariant).

### Still required, for steps 4-5

- Plan shape, per engine, on a populated dev database with the SQL logged by `Config.logSql`:
  - Postgres `EXPLAIN (ANALYZE, BUFFERS)` on the grouped statement (first page, dated cursor, undated cursor; both directions; sort by `original_created_at` and by `filename`): the `dated` slice and both day-count probes must use `asset_search_date_taken`; the `undated` CTE must not execute when `dated` fills the page.
  - SQLite `EXPLAIN QUERY PLAN` on the same six shapes: `USING INDEX asset_search_date_taken` for the slice and the day counts, no `USE TEMP B-TREE FOR ORDER BY` on the slice.
  - Flat sorted search on both engines: one `SELECT` level, `COUNT(1) OVER ()` present, `ORDER BY <sort> <dir>, asset0.id ASC`, `LIMIT ? OFFSET ?`.
  - Metadata and text filters: `asset0.id IN (SELECT ...)` semi-joins; on Postgres the FTS subquery uses `search_document_02` (gin); on SQLite `search_document` is an **fts4 virtual table**, so confirm `body MATCH ?` inside a scalar subquery still resolves through the FTS index rather than scanning.
- Browser check on the dev server (`:8080`): grouped date view scrolls through dated pages into the "No date" group and back in both directions; flat view pages and sorts; metadata, text, person, album and folder filters return the same counts as before the change (compare footer totals against a pre-change run).
- Cursor round trip: a cursor issued before the change must still be accepted after it (the `scopeFingerprint` and the cursor format do not change).

## Risks and how the plan handles them

- **Alias changes (`asset` -> `asset0`) or `CAST(x AS DATE)` vs `x::date` could stop an expression index matching.** The day expression is emitted as a raw fragment with today's text, and the `EXPLAIN` checks are a hard gate in step 5. Columns already render unquoted, which `RowColumnTests` pins.
- **The semi-join change (decision 5) alters plan shape.** Highest-risk item left; SQLite fts4 inside a subquery is the specific unknown.
- **`Candidate` projection labels.** Fallback documented above (explicit `AS` wrapper).
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
