# Search in: Library, Triage or All

## Goals

- The Advanced search panel opens with a top row of three choices, **Library**, **Triage** and **All**, which say which
  assets the search covers: the sorted ones, the triaged ones, or both.
- In Browse the row opens on what Browse is showing: All while "Show triaged items" is on, Library while it is off. In
  Triage it opens on Triage.
- A pick lasts for the search it is submitted with. The panel opened on a search in force shows that search's pick.
- The quick search typed in Triage searches the triaged assets alone and stays in Triage, under the placeholder
  "search triage".
- A Describe search runs in Triage as it does in Browse.

Out of scope: a Describe search from the quick search (it stays a search by the Search text), a union of a description's
results with the Search text's, the trash, a Triage-only search rendered inside Browse, remembering a pick per browser.

## How it works

**The three choices are the two parameters a search already has.** No parameter is added.

| Choice | `view` | `triaged` | Results page |
|---|---|---|---|
| Library | `repository` | `false` | Browse |
| All | `repository` | `true` | Browse |
| Triage | `triage` | none | Triage |

**The row.** The form's first row, above Describe: a `.toggle-group` named "Search in" (`role="group"`, the current
segment `aria-pressed="true"`, as every toggle group of the app), its three segments of equal width across the form.
The pick is held in two hidden inputs, `view` and `triaged`, so the form's entries stay the search. A Guest sees no
triaged asset and is rendered no row and neither input: its searches are the repository view's.

**What the row opens on.** The panel asks for its form with the `view` and the `triaged` of the search it will run
(`search-panel.js`), and the server presses the segment they name:

- Browse: the store's `triaged`, All when it shows the triaged assets and Library when not. With no search in force that
  is the "Show triaged items" switch; with one in force it is that search's pick.
- Triage: Triage, with or without a search in force.
- The trash and the pages with no search (Import, Settings, Users, the style guide): the choice remembered for "Show
  triaged items", as Browse would be seeded.
- A folder's **Search here**, from Browse or Triage: the folder chosen, and All or Library by the remembered choice,
  since a folder holds no triaged asset.

**While Triage is picked** the Folder field is hidden and its folder left out of the form's search (`searchOfForm`), so
a folder chosen before the pick is neither counted on the filter button nor searched, and is in force again once
Library or All is picked. The People field's options are asked for with the form's `view` and `triaged` as they stand
(`data-options-params`), so each Person's number is what the search will find (`CountedAssets`): under Triage, their
triaged assets alone, and a Person with none is no option.

**Clear** empties the fields and leaves the pick.

**Running the search** (`searchByFilters`). The filters carry the pick's `view` and `triaged`. When the page shows the
view the search names, the store takes the filters and the results are read in place; otherwise the window goes to that
view with the search as its query string: Triage picked in Browse opens the Triage page, Library or All picked in Triage
opens Browse. A chip's × in a Filtered search's title and Edit carry no pick, so they keep the view and the triage
filter of the search in force.

**A pick lasts one search.** A search from the panel sets `triaged` in the store without remembering it; only the "Show
triaged items" switch writes the remembered choice. The store puts the remembered choice back when the search is
replaced: by a place (a folder, a Person, an album, a Location, a subset, or the title's ×), by new quick search text,
or by the removal of the search's last filter. The sort, the grouping, the layout and paging keep it. The switch shows
the search's value while the search is in force, and turning it changes the search and is remembered, as any turn of it
is. A URL that names `triaged` wins for its page and is not remembered, so a search opened in Browse from Triage with
Library or All behaves the same way.

**The quick search.** In Triage it searches by the Search text within the Triage view, in place, and its placeholder is
"search triage"; in Browse it searches the repository view by the triage filter in force, under "quick search". New
text clears the places and the panel's filters and keeps the view; the trash is left by the rule that text searches no
trash. From the trash and from a page with no search it opens Browse.

**A description in Triage.** `describe` with `view=triage` ranks the triaged still images that carry a SigLIP 2 Scene
embedding, within the cutoff and the limit, narrowed by the Search text, people, Locations and the date ranges. It
takes no album, subset, map area, sort, grouping or map layout, and the trash refuses it. The Sequence grouping
the store keeps for Triage is left out of a described request, as every grouping is.

**Results in Triage.** A Filtered search's title, chips and Edit are Browse's. The title of a search by the Search text
alone reads "in Triage" where Browse reads "in this library". A description that finds nothing says "Nothing in Triage
looks like that." when it stands alone, and "Nothing within these filters looks like that." beside another filter.
Triage's Curate row and its batch operations stay.

## Decisions

| Decision | Choice |
|---|---|
| Quick search | A search by the Search text, as now. Describe stays a field of the panel. |
| Quick search in Triage | The triaged assets alone, in the Triage view, placeholder "search triage". |
| The control | The app's toggle group, three equal segments in the panel's first row. |
| Default in Browse | Follows "Show triaged items": All when on, Library when off. |
| Default in Triage | Triage. |
| How long a pick lasts | One search. The panel opened on a search in force shows its pick. |
| Where results show | The pick names the page: Triage in the Triage view, Library and All in Browse. |
| Folder with Triage | The Folder field is hidden while Triage is picked. |
| Parameters | None new: a pick is `view` and `triaged`. |
| Schema | No change. |
| The row's name | "Search in", its accessible name; the row has no visible label. |
| The Library label | A label of this row alone, for the sorted assets. The glossary's Library stays the whole of it, Triage and Trash included. |
| Semantics | `role="group"` with `aria-pressed`, as the other toggle groups, not `radiogroup`. |
| Clear | Leaves the pick. |
| Search here from Triage | Opens the panel on All or Library by the remembered choice. |
| A Guest | No row; Library always. |
| The filter button's badge | Does not count the pick. |
| Results title | Names no pick: the page (Browse or Triage) and the "Show triaged items" switch show it. |

## Changes

### A. Server

1. `SearchRequestParser.descriptionOf` takes a description in the Triage view; the trash and the rest of what it
   refuses stay. `SearchRequestParserTests`.
2. `DescribeSearchTests`: the Triage view ranks triaged assets alone, within a filter, by cursor, on both engines and
   through PostgreSQL's index shape.
3. `includes/search_results` and `includes/search_filter_chips` (`inTriage`): "in Triage" under a Search text title and
   "Nothing in Triage looks like that." in the Triage view. `SearchResultsControllerTests`.
4. `CountedAssets` (Sorted, Triaged, All) beside `ListedPerson`; `PersonService.getListedSearchable(counted)` and
   `SearchService.peopleOptions(typed, counted)`. `SearchPanelController`: `panel` and `peopleOptions` read `view`
   (`viewOf`: the Triage view, or the repository view for anything else) beside `triaged`, and refuse a Guest as a
   search does. `SearchPanelControllerTests`, `SearchServiceTests`, `PermissionControllerTests`.
5. `includes/nav`: the quick search's placeholder by the page shown. `IndexControllerTests`.

### B. The store and the search

1. `js/stores/search-params.js`: `CLEARS.q` without `view`; a description leaves the view alone;
   `applySearchFilters` sets `view` and `triaged` from filters that name a view and keeps them otherwise;
   `withRememberedTriaged` when a search is replaced; `merge` remembers a `triaged` the change names.
   `scripts/test-js/search-in.test.mjs`, `search-params.test.mjs`, `triage-filter.test.mjs`.
2. `js/search-results/search-panel.js`: `pickOfPanelSearch`, `searchOfForm`, and `searchByFilters` searching in place
   when the page shows the search's view and navigating otherwise.
3. `js/search-results/search-input.js`: a quick search in place in Browse and in Triage.

### C. The panel

1. `htmx/search_panel`: the row, its two hidden inputs, the Folder field's `x-show`, the People field's
   `data-options-params`; `includes/filter_combobox`: `optionsParams`, `shownBy`, `hidden`.
2. `js/alpine/components/search-panel.js`: `view`, `triaged`, `searchIn`, `searchesIn`, `searchesTriage`.
   `filter-combobox.js`: `optionsParams()`.
3. `css/htmx/search_panel.css`: the segments' equal width.
4. `style_guide`: the panel specimen's note on the row; the toggle group's note, a pick of one among a few that all
   stay in view.

### D. Docs

1. `CONTEXT.md`: **Search in**, **Search text**, **Advanced search**, **Describe search**, **Triage**.
2. `altitude/AGENTS.md` and `altitude/views/AGENTS.md`: the parser's rules, the panel's routes, the **Advanced search
   panel**, the **Search parameters**, the nav's quick search, the Results title.
3. `docs/test-coverage.md`.

## Verification

- `make test` passes: unit 359, controllers 333, SQLite 807 (one cancelled as PostgreSQL-only), PostgreSQL 811.
  `make test-js` passes (128) and `make lint` is clean; the Scala bundles ran before the lint pass, after which the
  tests compile.
- In the browser, on the dev instance, as a member, driven through the page's own controls by script:
  - Browse with "Show triaged items" on: the row opens on All; a Library search carries `triaged=false`, is not
    remembered, and the panel reopened on it shows Library; All from there; the last chip's × and new quick search
    text put the remembered choice back; the switch shows off during a Library search, and turning it changes the
    search and is remembered.
  - Triage picked in Browse opens the Triage page on the search, where the panel shows Triage, the Folder field is
    hidden, the placeholder reads "search triage", a chip's × and a quick search stay in Triage in place, and the
    Search text's title says "in Triage". The People options request carries `view=triage`, and `triaged=true` once
    All is picked. Library picked in Triage opens Browse with `triaged=false`.
  - The Import page and the Trash: the panel opens on All, the remembered choice; a search from the Import page with
    Triage picked opens Triage, and a quick search in the Trash opens Browse.
  - The row in both themes, its three segments equal at the panel's width and at 240px, each a focusable button
    before the Describe field.
- Not checked in the browser: a Guest's panel (held by `PermissionControllerTests`), "Show triaged items" turned off
  as the starting state, a folder's Search here with a real folder (the dev Library has none but the root; the pick
  it opens on was checked), and a batch operation over Triage search results.
