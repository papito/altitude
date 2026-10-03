# Search paging bounds

## Goals

- No search request can load an unbounded page of assets into memory: the flat grid's `rpp` and `p` are checked like
  the grouped grid's, and a value out of range is a plain-text 400, not a page of every match or a 500.
- Merging people recounts the destination's assets with a `COUNT`, without loading them.
- A hand-edited browser URL cannot turn every search into a 400: the `searchParams` store keeps `rpp` and `p` within
  what the server accepts.

Out of scope: cursor paging for the flat grid, the per-request loading of names and folder descendants, and the
browser grid's growth under infinite scroll.

## Current behavior

- `SearchResultsController.htmxSearchResults` passes `rpp` (default `Const.Search.DEFAULT_RPP`, 50) and `p` to the flat
  search unchecked. `SearchQueries.flat` adds no `LIMIT` when `rpp <= 0`, so `rpp=0` reads every matching asset as a
  full row (with its three metadata JSON columns), and any large `rpp` reads that many. `p < 1` or `rpp < 0` throws
  `IllegalArgumentException` in the `Query` constructor and surfaces as a 500. The offset `(p - 1) * rpp` is an `Int`
  (ScalaSql's `drop(n: Int)`), so a large `p` wraps it negative: `p=2147483647` at `rpp=50` is `OFFSET -100`, a 500 on
  PostgreSQL and page 1 on SQLite.
- The grouped path already checks its values in `parseGroupedQuery`: `rpp` 1 to `Const.Search.MAX_GROUPED_RPP` (500),
  and `p` refused.
- Map layout ignores `rpp` and `p` (a controller test sends `rpp=0` and `p=99` with `layout=map` and expects a 200).
- `PersonService.merge` sets the destination's `numOfFaces` from `app.service.library.search(recountQuery).total`, a
  `SearchQuery` with no page size: it reads every asset of the person to use only the total.
- The `searchParams` store (`static/js/stores/search-params.js`) seeds `rpp` and `p` from the browser URL when they
  are finite numbers (`normalize`), and `rpp` survives every later change. The server never writes either into the
  URL, so only a hand-edited one carries them.

## Design

- `Const.Search.MAX_GROUPED_RPP` becomes `MAX_RPP`: the bound applies to every grid page. Its comment says why pages
  are bounded: a flat page holds full asset rows, and a grouped page carries a count for each of its groups (day or
  Location).
- `htmxSearchResults` checks `rpp` once, after the map branch (map layout keeps ignoring paging) and before the
  grouped/flat split: 1 to `MAX_RPP`, else `rpp must be between 1 and 500`. Zero is refused with the rest, since to
  a flat search it means no limit. `parseGroupedQuery` relies on that check.
- `parsePage(p, rpp)` checks the flat page: 1 to `Int.MaxValue / rpp`, the last page whose every row an `Int`
  numbers (`p * rpp`), so its offset `(p - 1) * rpp` cannot wrap, else `p must be between 1 and <last>` (42949672
  at the default `rpp`, `Int.MaxValue` at one asset a page).
- `PersonService.merge` recounts with `app.service.library.count(recountQuery)`, the same matching relation as
  `search` rendered as one `COUNT`.
- The store holds each number to the server's bound: `rpp` an integer from 1 to `Const.search.maxRpp` (500, the
  client's copy of `MAX_RPP`), `p` an integer from 1 to 2³¹−1, since the server reads both as an `Int`. `normalize`
  turns anything else into the parameter's default, "no opinion", on seeding and on every change, so the server's
  default applies. The offset bound of `p` depends on the server's default `rpp` and stays the server's: an absurd
  seeded `p` costs one 400, and the next change returns to the first page anyway.

After this, no production path reads asset rows without a page size: the flat and grouped grids are bounded by the
controller, and the map's `count`, `mapBounds` and `mapCells` are aggregates. `SearchQuery` keeps `rpp = 0` as "no
limit" for callers inside the application and its tests.

## Tasks

1. **Flat paging bounds.** Controller tests first (`SearchResultsControllerTests`, SQLite, two assets): `rpp` of 0, −1
   and 501 is a plain-text 400 `rpp must be between 1 and 500` on a first page and on a continuation; `p` of 0, −1,
   42949673 and 2147483647 is a plain-text 400 `p must be between 1 and 42949672`, while `p=42949672`, and
   `p=2147483647` at `rpp=1`, are a 200;
   `rpp=1` renders one of the two cells and `rpp=500` both; the grouped rejections and the map-layout test still
   pass. Then `MAX_RPP`, the single `rpp` check and `parsePage`.

2. **Merge recount.** `PersonService.merge` calls `library.count`. This is a refactor with nothing new to observe: the
   merge tests in `PersonServiceTests` that assert `numOfFaces` after a merge ("Merging people results in correct
   persistence state", "Person merge B -> A", "Person merge C -> B, B -> A", "Merging of people in the same asset
   results in correct face counts") guard it on both engines.

3. **Documentation.** `../../altitude/AGENTS.md`, the search parameter table: `rpp` (1 to `MAX_RPP` on every grid page; a
   grouped continuation may use another, a flat one keeps its page's, since `p` is an offset; map layout ignores it)
   and `p` (1 up to the last page whose every row an `Int` numbers; a 400 with `groupBy`). `../../altitude/views/AGENTS.md`,
   **Search parameters**: the store keeps `rpp` and `p` within the server's bounds. `../../docs/test-coverage.md`: the flat
   bounds beside the pagination line of ordinary search.

4. **Store bounds.** `Const.search.maxRpp` in `static/js/constants.js`; the per-parameter bounds in `normalize` and
   the comments that list what the store settles, in `search-params.js`. Verified in the browser: `?rpp=1000`,
   `?rpp=0`, `?rpp=2.5` and `?p=0` load a grid with no 400, the store at `rpp: null` and `p: 1`; `?rpp=2` is kept.

## Verification

- `make test-controllers` for task 1 (red before the change: `rpp=0` and `rpp=501` return 200, `p=0` a 500, and
  `p=2147483647` a 200 of page 1 on SQLite).
- `make test-sqlite` and `make test-psql` for task 2.
- `make test-unit`, a scalafmt check of the changed Scala files, and eslint/prettier on the changed JS.
- The browser checks of task 4 on `localhost:8080` (`mill altitude.resources` after editing `static/`).
