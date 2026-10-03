# Text search

## Goals

- A search text input in the UI runs a search and shows its results, by the Enter key and by a Search button.
- Text search matches, in order of importance: the names of the people attached to the asset, the Locations attached
  to it, its folder (the folder it is in and every folder above it), and its file name.
- Recycling an asset keeps its Search document and removes it from results. Searching the trash is out of scope.
- Purging an asset purges its Search document.
- Merging people, hiding a person, renaming or deleting a folder, album or Location, moving assets, and adding or
  removing assets to or from Locations are all reflected in search results, and none of them can leave the search
  stale after a crash or a lost database connection.

Out of scope: searching the trash, suggestions while typing, parentheses in the Search text, and a rebuild operation
for Search documents (a database is created from scratch).

## Current behavior

- No template has a search text input. The `q` parameter is plumbed end to end: the `searchParams` store
  (`static/js/stores/search-params.js`) serializes it, `SearchRequestParser.parse` puts it in the scope, and
  `SearchQueries.textFilter` turns it into a semi-join on `search_document`. Nothing sets it.
- The Search document holds user metadata values only. On Postgres `SearchDao.addSearchDocument` writes them to
  `metadata_values` and always writes `""` to `body`; a trigger builds `tsv` with the `english` configuration. On
  SQLite `search_document` is an fts4 table whose `body` is the metadata values. File names, people, Locations and
  folders are not searchable.
- `PostgresSearchDialect.textMatch` passes the text to `to_tsquery(?)` unparsed, so two words are a syntax error.
  `SqliteSearchDialect.textMatch` passes it to `MATCH` unparsed.
- SQLite updates a document with `UPDATE search_document ... WHERE asset_id = ?`, a scan of the whole fts4 table,
  and has no foreign key from it to `asset`: a purged asset leaves its document behind. Postgres cascades.
- Sorting is by an `asset` column only (`Const.Search.SORT_FIELDS`); there is no ordering by match quality.
- `AssetService.rename` changes `asset.file_name` and nothing else.

## Design

### What is stored, and what is read live

The Search document of an asset holds only what belongs to the asset itself: the words of its file name and of its
user metadata values. It is written in the same transaction as the asset row it describes (import, rename, metadata
edit, purge), so the two commit or roll back together.

Names of people, Locations, Categories, folders and albums are never copied into a document. Each search reads them
from their own tables and reaches the assets through the relations a search already uses (`face`, `location_asset`,
`album_asset`, `asset.folder_id`). A merge, a hide, a rename, a delete, a move or a membership change therefore
changes what the next search returns with no reindexing, and there is no pipeline, work log or startup recovery for
search.

If a source is ever denormalized into the document, the write side to add is a transactional outbox: a task row
written in the mutating transaction, drained by a stream with at-least-once delivery into idempotent upserts, claimed
with `FOR UPDATE SKIP LOCKED` on Postgres, and swept on startup.

### Words: one rule everywhere

`util/SearchWords` turns any text into its words, and is the only word-splitting rule in the application. It is used
for the stored body of a document, for every name read at query time, and for the typed Search text:

- lower-case, and strip diacritics (NFD, combining marks removed);
- split on every character that is not a letter or a digit;
- split on camelCase humps and on letter/digit boundaries.

`IMG_1234-beachSunset.final.jpg` is `img 1234 beach sunset final jpg`.

### Search text grammar

`util/SearchText.parse` turns the typed text into a `SearchExpression`, and never fails:

- Terms separated by spaces are AND-ed.
- `OR` (upper case) between two terms makes them alternatives, and binds tighter than AND:
  `alice OR bob paris` is `(alice OR bob) AND paris`.
- A leading `-` excludes: the asset must not match that term in any source. Text made only of exclusions is valid.
- A bare term matches the start of a word. A bare term that splits into several words (`IMG_1234`) is those words in
  sequence, the last one as a prefix.
- A `"quoted phrase"` is whole consecutive words within one name or one document.
- An unbalanced quote is closed at the end of the text; a dangling `OR` or a lone `-` is dropped; text with no usable
  term is no text. Terms past the sixteenth are dropped, and that is logged at INFO.

### Sources and Relevance

| Source | Matches assets | Relevance |
|---|---|---|
| Person: named, not hidden, not merged away, not a bad match | with a Face of that Person | 5 |
| Location | in that Location | 4 |
| Category | in any Location of that Category | 3 |
| Folder, not recycled, not the root | in that folder or any folder below it | 2 |
| Album | in that album | 2 |
| Search document: file name and user metadata values | whose document has the term | 1 |

A term matches an asset when any source does. The Relevance of an asset is the sum, over the terms that are not
exclusions, of the highest-scoring source the term matched; alternatives joined by `OR` count as the best of them.

`SearchService.resolveText(expression)` reads, in one read-only transaction, the ID and name of every candidate of
each name source (people filtered as in the table), matches each term against their `SearchWords` in memory, and
returns a `ResolvedSearchText`: the expression with, per term and per source, the set of matching IDs. Category hits
become the IDs of their Locations; folder hits are expanded to their descendants from the same in-memory folder
list. `SearchQuery` carries the `ResolvedSearchText` in place of the raw text for the DAO; `LibraryService.search`,
`searchGrouped`, `count`, `mapCells` and `mapBounds` resolve it next to the folder scope. The cursor fingerprint uses
the text as typed, and names are resolved afresh for every page, as folder descendants are.

`SearchQueries.textFilter` renders a term as the OR of its non-empty sources: the existing person, album and Location
semi-joins and `folder_id IN (...)` over the resolved IDs, and the engine's document match. An ID set is bound as one
parameter (`= ANY(?)` on Postgres, `IN (SELECT value FROM json_each(?))` on SQLite) through a new `Columns.isInSet`,
so a folder subtree of thousands is one bind. `SearchDialect.textMatch` takes a term, not text: Postgres renders
`tsv @@ to_tsquery('simple', ?)` with `word:*` or `w1 <-> w2`, SQLite renders an FTS5 `MATCH` with `word*` or
`"w1 w2"`. The query strings are built from `SearchWords` output only, so they hold letters and digits.

`SearchQueries.relevance` renders the score as a sum of per-term `CASE` expressions over the same predicates, and is
rendered only when the sort is Relevance.

### Relevance sort

`Const.Search.SORT_RELEVANCE` (`relevance`) is a sort that is not a column:

- `SearchResultsController` defaults `sort` to Relevance when `q` is present and to Date Imported, newest first,
  otherwise. `sort=relevance` without `q` is a plain-text 400.
- A flat grid orders by Relevance descending, Date Taken descending, ID.
- A grouped grid orders by its group, then the same three terms. `SearchCursor` (version 5) carries a second,
  optional sort value, the capture time, read and compared only under the Relevance sort; `SearchQueries.afterBySort`
  compares the triple.
- The Sort dropdown offers "Relevance" only while the results have text.
- The map layout has no sort and plots the matches as it does for any other filter.

### Schema

Both `all.sql` files, as original definitions.

Postgres:

```sql
CREATE TABLE search_document (
  repository_id CHAR(36) NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
  asset_id CHAR(36) NOT NULL REFERENCES asset (id) ON DELETE CASCADE,
  body TEXT NOT NULL,
  tsv TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', body)) STORED
);
```

with the two existing indexes. The `metadata_values` column, `update_search_document_rank` and its trigger go.

SQLite: `search_document` becomes an ordinary table with the same three columns, a unique index on
`(repository_id, asset_id)` and the foreign keys, so a document is found by asset ID and cascades on purge.
`search_document_fts` is an FTS5 external-content table over its `body` (`content='search_document'`,
`tokenize='unicode61 remove_diacritics 0'`, `prefix='2 3 4'`), kept in step by the three standard insert, update and
delete triggers. `SqliteSearchDialect.textMatch` matches through `rowid IN (SELECT rowid FROM search_document_fts
WHERE search_document_fts MATCH ?)`. `SearchDocumentRow` maps the ordinary table on both engines.

### Writing the document

`SearchDao.writeDocument(asset)` is one upsert of `body` on both engines: the `SearchWords` of the file name followed
by those of every user metadata value. `SearchDao.indexAsset` and `reindexAsset` call it after the
`metadata_parameter` rows, and `addMetadataValues` after its inserts, as today. `AssetService.rename` calls
`app.service.search.reindexAsset` in its transaction. Purge deletes the document through the cascade on both engines.
Recycling and restoring do not touch it: the views' `is_recycled` filter keeps a recycled asset out of results.

### Scope and views

Text searches the whole repository outside the trash:

- In the `CLEARS` table of `search-params.js`, `q` clears `folderId`, `personId`, `albumId`, `locationId`, `bbox` and
  `sort`, and resets `view` to the repository view; `folderId`, `personId`, `albumId`, `locationId` and `view` each
  clear `q` and `sort`. Layout and grouping survive.
- `SearchResultsController` and the map endpoints answer `q` with `view=trashbin` with a 400 (plain text and JSON
  respectively), through `SearchRequestParser.parse`.

### The input

`includes/nav.scala.html` gets a `<form role="search">` between the menu links and Import: an
`<input type="search">` and a Search button. `static/js/search-results/search-input.js` binds it: submit (Enter or
the button) runs `runSearch({ params: { q } })`; the input's native clear and an empty submit run it with an empty
`q`; the input's value follows `searchParams.q`, so a URL with `q` fills it on load and navigating away empties it.
`frontend-app.js` wires it at startup. The form is in the nav of the main page only.

`includes/search_results` gets `data-results-q` on its fragment root and the Relevance option.

## Tasks

1. **Shared word rule and Search text parser.** `util/SearchWords` and `util/SearchText` (`SearchExpression`, terms,
   phrases, `OR`, exclusion, the forgiving rules, the sixteen-term cap), with unit tests in `SearchWordsTests` and
   `SearchTextTests`. Pure code with no callers yet; everything below builds on it.

2. **Search document schema and writes.** Both `all.sql` files as under **Schema**; `SearchDocumentRow`;
   `SearchDao.writeDocument` replacing `addSearchDocument` / `replaceSearchDocument` in the JDBC, Postgres and SQLite
   DAOs; `AssetService.rename` reindexing. Integration tests first (`SearchServiceTests`, both engines): an imported
   asset has a document of its file name words; a rename and a metadata edit rewrite it; recycling keeps it; purging
   removes it. `RowColumnTests` follows the SQLite table change. Rationale: the document becomes transactional, found
   by key, and cascades on both engines.

3. **Document matching by term.** `SearchDialect.textMatch` over a term on both dialects; `SearchQueries.textFilter`
   renders the parsed expression against the document alone (AND, `OR`, exclusion, prefix, phrase). Integration tests
   for multi-word text, prefixes, phrases, `OR` and exclusion over file names and metadata values on both engines;
   `SearchSqlTests` updated for the new SQL. Rationale: fixes multi-word text on Postgres and unparsed `MATCH` on
   SQLite before any name source is added.

4. **Name sources.** `SearchService.resolveText`, `ResolvedSearchText` on `SearchQuery`, `Columns.isInSet`, the
   per-source predicates in `textFilter`, resolution in `LibraryService` beside `withResolvedFolderScope`. Integration
   tests per source and per rule: named visible person only (hidden, merged away, bad match, unnamed do not match);
   Location; Category through its Locations; folder and ancestor folders, recycled folders excluded; album; a phrase
   confined to one name; and that each of these is reflected by the next search with no reindex: person merge, hide
   and rename; folder rename, move and delete; album rename and delete; Location rename, move and delete; asset move;
   Location and album membership changes. Map counts and bounds follow the text (`SearchMapTests`).

5. **Relevance sort.** `Const.Search.SORT_RELEVANCE`, `SearchQueries.relevance`, flat and grouped ordering,
   `SearchCursor` version 5 with the second sort value, the controller's default and 400s. Tests: ordering across
   sources and summed terms, ties by Date Taken, cursor continuation under both groupings (`SearchCursorTests`,
   `SearchGroupingTests`), `sort=relevance` without `q`, and `q` with `view=trashbin`, in the controller tests.

6. **Search input and scope rules.** The nav form, `search-input.js`, the `CLEARS` changes, `data-results-q` and the
   Relevance option in `includes/search_results`. Verified in the browser: Enter and the button, clear, a bookmarked
   `?q=` URL, text from Triage and Trash landing in Browse, a folder click emptying the input, grouping and map
   layout with text.

7. **Documentation.** `../../altitude/AGENTS.md` (**Search results and date grouping**: text search, sources, Relevance,
   cursor version 5, the document and its schema), `../../altitude/views/AGENTS.md` (**Search parameters**: the input and
   the `q` scope rules), and the comments beside `textFilter`, `SearchDocumentRow` and both dialects.
