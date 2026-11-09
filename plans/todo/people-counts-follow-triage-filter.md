# People counts follow the triage filter

## Context

The People tab shows each Person with a count, `person.num_of_faces`: the assets outside the trash with a Face of that Person,
triaged assets included. The repository view leaves triaged assets out unless the View dialog's "Show triaged items" is on (the
`triaged` search parameter). So a Person reading (10) can open 7 results.

The outcome: the number on the People tab is what clicking that Person finds, with "Show triaged items" on or off.

## Decisions

| Concern | Decision |
|---|---|
| Source of the number | `num_of_faces` stays as it is, with no schema or write-path change. With triaged items hidden, the list subtracts a live grouped count of each Person's triaged assets, read in the same snapshot |
| Triaged items shown | The list shows `num_of_faces`, and the triage read is skipped |
| A count of 0 or less | With triaged items hidden, the Person is left off the list, as a Person with nothing to find already is |
| People buckets | Matched and Unmatched keep reading the whole `num_of_faces`, so the switch never moves anyone between buckets |
| Whole-count readers | The Discard link, `Person.isMatched`, the merge modal's "instances" and the merge direction keep `num_of_faces`: a merge moves every Face |
| Results title | A Person's title has no "N faces" subtitle; the toolbar's Total says it. `results_title_inner` keeps `subtitle` for the other scopes |
| Title route | `GET /htmx/people/r/:repoId/p/:personId/title` stays: the title's Hide and Discard links depend on the count. Its description says so instead of naming the count |
| Refresh | The tab reloads when the switch flips and after every asset mutation that goes through `refreshCounts()` |

## Known limitation

A merge recounts through a search, which skips assets still being imported, while the subtraction includes them. During an import
the difference can go negative: one sorted asset and two in-flight assets give 1 − 2. The visible count is clamped at 0, and such
a Person is left off the list until the next recount. `docs/faces.md` records this.

With "Show triaged items" on, the count runs ahead of the search by the assets still being imported, until they complete.

## Implementation

Server groups are red-green with integration tests on both engines, each test opening with the Setup / Assertions Scaladoc.

### A. The visible count on the server

1. **`PersonServiceTests`, red first.** `TestContext.addTestFacesAndAssets` creates sorted assets; give it a triaged option, or call
   `addFace` directly. Tests:
   - Each listed count equals `library.count` for the repository scope (not recycled, not triaged, that Person) across sorted,
     triaged, two-Faces-on-one-asset and recycled-from-triage assets, and an in-flight asset (flag set to false).
   - With triaged items shown, the count is `numOfFaces`.
   - A Person with only triaged assets is left off when hidden and listed when shown.
   - A Person's bucket and the order do not change with the switch.
   - After a merge during an import (one sorted, two in-flight), the Person is left off and no count is negative.
2. **Plan test.** `atScale` from `SearchPlans` with assets and Face rows seeded (`seedCopies` copies assets only, so the seed adds
   Faces; with few Face rows the planner starts from `face`), through `lookupPlanOf`, asserting an `asset_triage_` index on both
   engines.
3. **`PersonDao.getTriagedAssetCounts: Map[String, Int]`** in `dao/PersonDao.scala` and `dao/jdbc/PersonDao.scala`:

   ```sql
   SELECT f.person_id, COUNT(DISTINCT f.asset_id) AS ...
     FROM asset a
     JOIN face f ON f.asset_id = a.id
    WHERE a.repository_id = ?
      AND a.is_triaged = TRUE
      AND a.is_recycled = FALSE
    GROUP BY f.person_id
   ```

   - The flags are literals, with a comment saying why: SQLite uses the partial `asset_triage_*` indexes only for a predicate
     matching theirs, which a flag bound as text does not.
   - `is_pipeline_processed` is not filtered: an in-flight import's Faces are in `num_of_faces`, and its assets are triaged.
   - `COUNT` is a Long on PostgreSQL and an Int on SQLite, so it is read through `asInstanceOf[Number].intValue`.
4. **A result type in `models/`, a Person with their visible count,** and a `PersonService` method taking the buckets and
   `showTriaged`, in one `asReadOnly`, over the existing `getInBuckets`. The number never goes on `Person.numOfFaces`, because
   `isMatched` reads it. `getInBuckets` is unchanged.

### B. The route, the template and the title

1. **`PeopleActionControllerTests`, red first:** a Person with one sorted and two triaged assets reads (1) by default and (3) with
   `triaged=true`; a Person with only triaged assets is absent by default; `triaged=nope` is a 400.
2. **`PeopleActionController.listPeople`** takes `triaged: Option[Boolean] = None`, and only `true` shows triaged items, the
   repository view's rule in `SearchRequestParser`.
3. **`htmx/people_list`** takes the new type and uses the visible count in the text and in the hidden Person's `aria-label`.
4. **`htmx/person_inner`** loses `@defining` and the subtitle. `PeopleActionControllerTests`' title test, its name and Scaladoc
   follow. `style_guide.scala.html` loses the sample's subtitle.

### C. The client

1. **`alpine/components/people-tab.js`:** `loadPane` reads the `searchParams` store on every load and adds `triaged: "true"` only
   when the store says so, since a null value would be sent as `triaged=null`, a 400. `init()` adds a `$watch` on the store's
   `triaged` that calls `reloadPanes()`.
2. **`assets/asset-actions.js`:** `refreshCounts()` dispatches `Const.events.peopleChanged`. The People tab is its only listener, it
   ignores the detail, and nothing listens while the tab is not mounted.

### D. Docs and comments

- `docs/faces.md`: **People** (what the count is and what the list shows, the limitation above, its "what a search finds" wording)
  and the title route's purpose under **Corrections**.
- `altitude/views/AGENTS.md`: **People tab**, **Event flow**, **Results title**, the files table's `people-tab.js` row, and
  **Search parameters** (the tab reads `triaged`).
- `altitude/AGENTS.md`: the new reader of the `asset_triage_*` indexes.
- `docs/test-coverage.md`: the new tests and the title route's entry.
- Comments: `Person.scala` (`numOfFaces`), `people-tab.js` header, `listeners/people.js`, `people_list.scala.html`,
  `asset-actions.js` (`refreshCounts`), `view-settings.js`, and `PeopleActionController` (the list and title routes).
- `CONTEXT.md` needs no change.

## Critical files

- `altitude/src/altitude/core/dao/jdbc/PersonDao.scala`, `dao/PersonDao.scala`
- `altitude/src/altitude/core/service/PersonService.scala`
- `altitude/src/altitude/core/routes/web/partial/PeopleActionController.scala`
- `altitude/views/htmx/people_list.scala.html`, `person_inner.scala.html`
- `altitude/static/js/alpine/components/people-tab.js`, `assets/asset-actions.js`
- `altitude/test/src/altitude/core/integration/PersonServiceTests.scala`,
  `altitude/test/src/altitude/core/controller/PeopleActionControllerTests.scala`,
  `altitude/test/src/altitude/test/TestContext.scala`

## Verification

1. `make test-sqlite`, `make test-controllers` and `make test-psql`; the plan test passes on both engines. `make lint`.
2. In the app, on the dev server, with a Person who has sorted and triaged assets:
   - With the switch off, the tile's number equals the toolbar's Total after clicking the Person.
   - Flip "Show triaged items": every pane, Split view included, updates with no page reload, and the numbers again equal the Total.
   - Move a triaged asset of that Person to a folder, recycle an asset and restore it: the tile follows each time.
   - A Person with only triaged assets appears only with the switch on.
   - The results title has no subtitle, and Hide / Show still swap correctly.
