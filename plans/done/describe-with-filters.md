# Describe search with filters

## Goals

- The Advanced search panel has a **Describe** field: the user writes what a photo shows ("woman in a park") and
  gets the still images that look most like it, best first.
- A description is narrowed by the other Search filters: the Search text (the panel's **Keywords** field), one
  folder, people, Locations, a Date Taken range and a Date Imported range. A photo is in the results only when every
  filter holds for it **and** it looks like the description; the results are ordered by how well they look like it.
- The filters decide membership only. The Search text is one of them: an asset matches it or does not, and its
  Relevance plays no part in the order.
- The panel is one form: the Describe field above the Keywords field and the other filters.
- One design on both engines.

Out of scope: a union of a description's results with the Search text's (rank fusion), sorts other than similarity,
groupings, the map layout and a map area, an album or a Triage subset beside a description, the trash and the Triage
view, Videos and animated images (they carry no Scene embedding), similar-image search ("more like this").

## How it works

**What a described search matches.** The assets that the same search without the description matches in the
repository view (a folder with the folders below it, several people as all or any of them, Locations as any of them,
the Search text by its terms, `triaged=true` adding the triaged assets), that carry a SigLIP 2 Scene embedding, and
that are within the description's cutoff. They are ordered by distance to the description, then by ID, and end at
`search.describe.max_results`: the limit keeps the nearest of the assets the filters match.

**Request.** `describe` is accepted beside `q`, `folderId`, `personId`, `locationId`, `peopleMatch`, the four dates
and `triaged`, each read and refused as it is without a description. A 400: a description that is blank or longer
than 300 characters, the trash and the Triage view, and a description beside `albumId`, `subset`, `bbox`, a sort, a
grouping or the map layout ("A description takes no …"). The sort is similarity, implied, whether or not the search
has Search text. A search with a description is a Filtered search whatever else it holds; an ID that names nothing
matches nothing.

**The statement.** `SearchDao.describe(query, ranking)` reads one statement, `SearchQueries.described`, a shape over
`SearchQueries.matching` like every other search's, built through `statement` so the Search text's CTEs head it. The
ranked rows are the matching relation itself, ordered and limited by the engine's vector search
(`DescribeRanking`: the embedding, the least cosine, the limit); around them are the page after the cursor, its
assets, and the total on a first page. The cursor's fingerprint is the description with the filters and `triaged`.

- **SQLite** has one shape: sqlite-vector's exact scan joined to the matching relation.
- **PostgreSQL** counts, for each page, the matching assets that carry an embedding, up to one past
  `SearchQuery.describeExactLimit` (`Const.Search.DESCRIBE_EXACT_LIMIT`, 75,000), a description with no filter
  included. At most the limit are ranked **exactly**: the distance over the `vector` column, the cutoff, the order,
  the limit, with no index. More are ranked through the **index**: the matching relation ordered by the `halfvec`
  cast `asset_describe` covers, its filters applied as the HNSW scan walks, the exact distance and the cutoff taken
  outside. Both order by the exact distance, then the ID, so a cursor from one continues on the other.

**Results.** The title is "Search results" over its chips: `Describe: “…”` first, with the Describe icon, then the
other filters in the panel's order. A chip's × removes that filter and searches by the rest; the Describe chip's ×
leaves the search of the other filters, or Browse. **Edit** opens the panel on the whole search. The sort menu shows
"Similarity" and the grouping menu is disabled, both dimmed as every disabled action button is; the layout toggle
shows Grid alone. An empty result says, in the title's row after Edit, "Nothing within these filters looks like
that." when another filter is shown and "Nothing in this Library looks like that." when none is. The Search text
chip's remove control is named "Remove Keywords <text>".

**The panel.** One form: **Describe** (a multi-line field of up to 300 characters, as tall as its text, the
placeholder "What's in the photo? e.g. woman in a park", Enter searches, Shift+Enter is a newline), **Keywords**
(the Search text, `q`), Folder, People with the people match, Locations, Date Taken and Date Imported. Search reads
every field; Clear empties all of them. Opening the panel puts focus in the Describe field with the caret after its
text. The filter button's badge counts a description as one filter. The Keywords field and the nav's quick search
input hold one value, and a quick search typed in the nav searches by its text alone.

Keywords is a label of the panel and of the chip's remove control. The term stays Search text in the glossary, the
code, the guides and the server's messages.

**Search parameters.** In `js/stores/search-params.js`, `describe` is a parameter of the search, trimmed, none when
blank or over 300 characters, in `PANEL_FILTERS` and `FILTERS`: a search from the panel sets it with the other
filters, and a place, a view or new quick search text clears it with them. A description holds the search to the
repository view and drops the album, the subset and the map area. The sort, the grouping and the layout stay stored
and are left out of a described search's request, so they come back when the description goes. Infinite scroll and a
reload keep the description.

## Decisions

| Decision | Choice |
|---|---|
| Combining | An intersection: the assets the filters match that also clear the description's cutoff. |
| The Search text | A filter like the others when a description is set; it does not rank. |
| Ranking | Similarity to the description only. |
| Where results end | `search.describe.min_probability` and `search.describe.max_results`, whatever the filters. |
| The panel | One form, the Describe field first. No mode switch. |
| The Keywords label | The Search text field's label in the panel; a UI label only. |
| PostgreSQL | Adaptive: few matching assets are ranked exactly; many through the HNSW index with the filters applied as it walks. |
| The size that separates the two | 75,000, an estimate from the figures of a description alone, not measured. |
| SQLite | The exact scan, joined to the matching assets. |
| Describe icon | `fa-magic`. |
| The map with a description | The layout toggle shows no Map segment. |
| A disabled action button | Dimmed whole, at the opacity a chip with a disabled input has. |
| Schema | No change. |

## Changes

### A. Backend

1. `SearchQueries.described` over `matching`, `SearchDialect.ranked` per engine and PostgreSQL's `rankedByIndex`,
   `DescribeRanking`, `ScannedRow`; `SearchDao.describe(query, ranking)`.
2. PostgreSQL's choice of shape: `SearchQueries.embeddedCount`, `SearchQuery.describeExactLimit`,
   `Const.Search.DESCRIBE_EXACT_LIMIT`.
3. `SearchRequestParser`: `describe` beside the panel's filters and what it takes none of; the cursor's fingerprint;
   `SearchService.describe(description, query)` and `SearchService.withResolvedScope`.
4. `SearchResultsController`: `isFiltered` counts a description, `SearchFilters.description`; the panel route reads
   `describe`.
5. Tests: `DescribeSearchTests` (the intersection for each filter and for two together, the cutoff and the limit
   within filters, paging, the two shapes and a cursor across them, the plans), `SearchRequestParserTests`,
   `SearchQueryModelTests`, `SearchResultsControllerTests`, `SearchPanelControllerTests`.
6. Docs: `altitude/AGENTS.md`, `docs/test-coverage.md`.

### B. Panel, results and docs

1. `htmx/search_panel`: the Describe field, the Keywords label; `searchPanel` and `search-panel.js`: Enter and
   Shift+Enter, focus and the caret, Clear, the badge; `search_panel.css`; the style guide's specimens.
2. `searchParams`: `describe` and its rules; `scripts/test-js/search-params.test.mjs`.
3. `includes/search_filter_chips` and `includes/search_results`: the Describe chip, the empty-state lines, the
   menus and the toggle; `.action-button:disabled` in `core.css`.
4. Docs: `CONTEXT.md` (**Describe search**, **Description**, **Search filter**, **Filtered search**,
   **Advanced search**, **Search text**, **Relevance**), `altitude/views/AGENTS.md`, `docs/test-coverage.md`.

## Verification

- `make test-unit` (358), `make test-controllers` (331), `make test-sqlite` (806, one cancelled as PostgreSQL-only)
  and `make test-psql` (810) pass, `make test-js` passes (105), and `make lint` is clean.
- In the browser, on a scratch instance over the test fixtures, with trusted input: a description alone and with
  Keywords, a folder, one person, two people as any, a Location and a Date Imported range, from the panel by
  keyboard; the chips removed one at a time down to Browse; Edit; paging by scroll; a reload; a quick search over a
  described search; a folder's Search here; a Keywords search as before; Clear; the dimmed menus in both themes; the
  caret. A Date Taken range was checked only where it matches nothing.
- Not measured: a described search with filters on a large library, on either engine, and with it
  `DESCRIBE_EXACT_LIMIT` and the cost of the count on a description with no filter.
- The intersection through PostgreSQL's index shape is tested with a folder and with a folder, a Location, a Date
  Imported range and excluded text, as a subset of the exact ranking, not with every filter: the vector index can
  miss a row whose asset was rewritten many times, so a test that holds the index to the exact list fails now and
  then. `docs/test-coverage.md` records it.
