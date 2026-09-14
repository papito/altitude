# Explorer rows: counts after names, group spacing, and expandable categories

## Original goals

- Move the asset count after the folder or Location name, so a row with no assets leaves no blank space and rows
  line up on the left. A top-level Location and a category (for example "Location" and "NYC") share the same left
  edge.
- Give an expanded folder with child folders extra space above and below its group, so it does not blend with the
  folders around it. The space exists only while the folder is expanded.
- Make Location categories expandable and collapsible like folders. Viewing a category turns it and its Locations
  green, like viewing a folder.
- Viewing the root folder does not turn the whole tree green.

Use the grill-me interview to settle behavior and produce a plan before changing application code.

## Status

Design interview complete on 2026-09-13. Not implemented.

## Confirmed decisions

### Counts

- Folders, Albums, and Locations show the count after the name: `NYC (47)`, dimmed, empty for zero. The count sits in
  its own cell directly after the name, is not a search trigger, and a long name wraps instead of pushing the count
  out of the panel. The root folder row still shows no count.
- With the count after the name, rows no longer reserve a uniform count column. The column sizing
  (`sizeCountColumn` and the `--folder-count-column`, `--album-count-column`, and `--location-count-column` variables)
  is removed.
- A category shows a count: the number of distinct assets in any of its Locations. An asset in two Locations of the
  same category counts once, so the number matches the category's search results. The server computes it with the
  list on every fetch; nothing is stored. It follows the same rules as Location counts and is patched in place with
  them.

### Clicks and green

- In both tabs, a name only searches. It never expands or collapses. An icon on a row with children (a folder branch
  or a category with Locations) only expands and collapses.
- A category name searches the assets in any of its Locations. The category and every one of its Locations turn
  green, the same rule a non-root folder follows for its subtree. Viewing a Location inside a category turns only that
  Location green.
- A category with no Locations has no expand control. Its icon searches like its name, as a leaf folder's icon does,
  and the search returns no assets.
- A category icon has no double-click action, because categories are one level deep. Folder branch icons keep their
  existing single- and double-click gestures.
- Viewing the root folder turns nothing green, the root icon included. A non-root folder still turns itself and its
  whole subtree green.
- Category search is not available in triage and trash, the same as Location search: the trash view hides the
  explorer, and triage hides the tab list and always shows Folders.
- While a category is viewed, the batch footer does not offer "Remove from location". It appears only while a single
  Location is viewed.
- A category row is not a drop target.

### Category expansion

- Categories start collapsed when the Locations tab loads. Expansion survives list reloads (add, rename, move, delete,
  count refresh fallback) and is not persisted across page reloads.
- The category icon stays `fa-layer-group` and carries a small +/− badge drawn in CSS with the Font Awesome font
  (collapsed: plus, expanded: minus). The badge adds no width, so a category's icon lines up with top-level Location
  icons.
- Adding a Location expands its category. Moving a Location into a collapsed category leaves that category collapsed,
  the same as moving a folder into a collapsed parent.
- Deleting the viewed category moves its Locations to the top level (the existing server behavior) and runs a search
  back to the whole repository, as deleting the viewed Location does. Deleting the category that holds the viewed
  Location keeps that Location viewed and green.
- Moving a Location into or out of the viewed category updates its color by its new position. The displayed results
  are not re-run, as with folders.

### Group spacing

- An expanded group is a non-root folder branch or a category that is expanded, together with its visible
  descendants.
- Between any two consecutive visible rows the space is either the normal row gap (8px) or twice that (16px) where an
  expanded group starts or ends. It is never more, whether groups end together, an expanded group directly follows
  another, or an expanded first child directly follows its parent's row. The top and bottom of a list get no extra
  space. Root is always expanded and is not a group.

For folders A (expanded) → B (expanded) → B1, then C (expanded) → C1 under A, and a sibling zeta:

| Between | Space |
|---|---|
| alpha and A | 16px (A starts) |
| A and B | 16px (B starts) |
| B1 and C | 16px (B ends, C starts) |
| C1 and zeta | 16px (C and A end) |
| Two rows inside one group | 8px |

### Search scope

- Search gains a `categoryId` parameter, carried in the browser URL so a category search can be bookmarked. The
  server resolves the category's Locations on every search, so a bookmark follows later moves.
- Choosing a category clears the folder, person, album, and Location, and each of those clears the category. A view
  change clears it, and a new category clears the map area. The map layout plots the same scope.

## Existing implementation

- `altitude/static/js/common/asset-count.js` builds the `.asset-count` cell (`buildAssetCountEl`, `setAssetCount`) and
  sizes a list's count column (`sizeCountColumn`). `core.css` styles `.asset-count` dimmed and `justify-self: end`.
  `common/folder-tree.js`, `common/album-list.js`, and `common/location-list.js` place the count before the icon and
  call `sizeCountColumn` after each render and count patch.
- Row grids are declared in `views/htmx/folders.scala.html` (root: menu | icon | name; non-root: menu | trace | count |
  icon | name), `views/htmx/albums.scala.html` (menu | count | icon | name), and `views/htmx/locations.scala.html`
  (category: menu | icon | name; top-level Location: menu | count | icon | name; child: menu | trace | count | icon |
  name). The folder tree and the Location list use one uniform row gap (`--folder-row-gap`, `--location-row-gap`,
  both 8px); nothing adds space around expanded branches.
- `models/folder.js` owns folder branch expansion (`expand`, `expandAll`, `collapse` with descendant reset) and sets
  `data-expanded`, the plus/minus glyph, children visibility, and `aria-expanded` together. Root carries
  `data-expanded="true"` and `data-is-root="true"`. Collapsing closes an open descendant menu through
  `closeOpenContextMenu` and moves focus out of hidden content.
- `common/viewed-folder-scope.js` marks the viewed folder's node with `data-viewed-scope`, root included, and the CSS
  rule `#rootFolderList .folder[data-viewed-scope] .folder-icon` colors that node's whole subtree green.
- `common/location-list.js` renders the list endpoint's rows flat, in path order, as siblings of `#locationList`: a
  category row, then its Locations as `.child` rows (`data-category-id`, `--depth: 1`, a `.trace` cell), with
  top-level Locations interleaved by name. Categories and Locations share the `#location-{id}` ID space. A category
  row is not a search trigger and has no count; `_patchAssetCounts` skips categories. `setViewedLocation(id)` marks
  one row `data-viewed-scope`, and the CSS colors that row's `.location-icon`.
- `fragments/search-results.js` (`syncViewedScope`) reads `data-results-folder-id`, `data-results-album-id`, and
  `data-results-location-id` from the results fragment and sets each explorer's highlight, clearing all of them in
  triage and trash.
- `stores/search-params.js` lists every search parameter (`DEFAULTS`) and the mutual clearing rules (`CLEARS`).
  `map/map-state.js` keys remembered map views by `SCOPE_PARAMS`. `context.js` exposes `getCurrentLocationId()`.
  `search-results/search-triggers.js` maps `data-app-search-<param>` attributes to parameters and blocks only
  `folderId` in triage and trash.
- `views/includes/batch_ops.scala.html` shows "Remove from location" only when `$store.searchParams.locationId` is set.
- `listeners/locations.js` reloads the list after `LOCATION_ADDED_EVENT`, `LOCATION_MOVED_EVENT`, and the other
  dialog events. On `LOCATION_DELETED_EVENT` (detail `{ id }`, for both kinds) it runs a search back to the whole
  repository when the deleted row is the current Location.
- Server search: `SearchRequestParser.parse` builds a `Scope` from `view`, `q`, `folderId`, `personId`, `albumId`,
  `locationId`, and `bbox`. `SearchQuery` carries `locationIds`, and `SearchQueries.matching` applies
  `locationFilter` only when that set is non-empty. `SearchCursor.scopeFingerprint` includes each scope set.
  `SearchResultsController.htmxSearchResults` passes `locationId` to the template (`data-results-location-id`) and
  `browserViewUrl`. `MapController.cells` and `bounds` build their scope through `mapScope`, whose parameters mirror
  the grid's.
- `LocationDao.getAll` returns rows in path order with `numOfAssets` counted from memberships per row, so a category
  counts zero. `LocationController.toJson` serializes it. Recycling removes an asset's memberships.
- `LocationActionController.add` completes the Add location dialog with an empty response, so the dialog's success
  event carries no detail. `BaseController.dialogSuccessResponse` sends a detail in the `App-Success-Detail` header,
  which the dialog operation merges into the event.
- Font Awesome Free 5.13.0 (`static/css/font-awesome.min.css`) has no plus/minus variant of `fa-layer-group`.

## Language

`CONTEXT.md` needs no change. **Location** and **Category** already cover what these decisions refer to: a top-level
Location and a Category are separate entities, and a Category still holds no assets of its own. Expansion, group
spacing, and the green viewed scope are presentation terms and are documented in `altitude/views/AGENTS.md`.

## Implementation tasks

### Unit A: counts after names (frontend)

1. **Move the count after the name in all three tabs.**
   In `common/folder-tree.js`, `common/album-list.js`, and `common/location-list.js`, append the `.asset-count` cell
   after the name. Update the grid templates in the three tab partials: the name track sizes to its content and
   wraps, and the count track takes the remaining width, so the count follows the name directly. Change the
   `.asset-count` rule in `core.css` to start-align and update its comment. Remove `sizeCountColumn`, its callers, and
   the three `--*-count-column` variables and their comments. Update the renderer header comments that describe the
   count column. This removes the blank count slot, so rows without a count line up with rows that have one.

### Unit B: folder tree root highlight and group spacing (frontend)

2. **Leave root uncolored when it is the viewed folder.**
   In `common/viewed-folder-scope.js`, keep recording the scope but do not mark the node when it is root. Update the
   module comment and the CSS comment in `folders.scala.html`. Descendants of root are then never green through the
   root, while a non-root viewed folder still colors its subtree.

3. **Add group spacing to the folder tree.**
   In `folders.scala.html`, give an expanded non-root `.folder` a block margin equal to `--folder-row-gap`, and cancel
   the doubled cases: the top margin of an expanded folder directly after an expanded sibling, and the bottom margin
   of an expanded folder that is the last child of its children container (its parent's margin, or the end of the
   tree, takes its place). Grid items do not collapse margins, so these rules implement the "8 or 16, never more" rule. Comment
   each rule with the case it covers. Expansion already toggles `data-expanded` through the folder model, so no JS
   changes.

### Unit C: category search and counts (server, test-first)

4. **Search by category.**
   Add `Api.Field.Search.CATEGORY_ID`. Add `categoryIds` to `SearchQuery` (constructor, `toString`, copy) and to
   `SearchRequestParser.Scope` and `parse`. In `SearchQueries.matching`, add a category filter: assets with a
   membership in any Location whose `category_id` is in the set. Resolve it in SQL rather than expanding the
   category into `locationIds`, since an empty set of Locations would drop the filter and return every asset. Include
   `categoryIds` in `SearchCursor.scopeFingerprint`. Accept `categoryId` in
   `SearchResultsController.htmxSearchResults` (every layout branch, `browserViewUrl`) and pass it to
   `includes/search_results.scala.html`, which renders `data-results-category-id`. Accept it in `MapController.cells`
   and `bounds` through `mapScope`.
   Tests first (both database bundles): a category search returns assets of each of its Locations once and nothing
   outside it; an empty category returns no assets; category and folder scopes combine; Group by Location under a
   category search groups by every Location of the matching assets; a cursor from one category scope is rejected
   for another; the results fragment carries `data-results-category-id` and the replaced URL carries `categoryId`;
   the map cells and bounds honor `categoryId`.

5. **Count a category's distinct assets.**
   In `LocationDao.getAll`, count distinct asset IDs over the memberships of the row itself or of any Location whose
   category is the row. A Location's count is unchanged; a category's count is its distinct assets.
   Tests first: `LocationServiceTests` (an asset in two Locations of one category counts once for the category; an
   asset moved out by recycling is not counted) and `LocationControllerTests` (the list's `numOfAssets` for a
   category).

6. **Report the new Location's category when it is added.**
   Complete `LocationActionController.add` with `dialogSuccessResponse` carrying `categoryId` (null at the top level),
   so the client can expand that category. Test first in `LocationActionControllerTests`: the success detail header
   names the category, and a top-level add names none.

### Unit D: expandable categories and category search (frontend)

Tasks 7–10 depend on unit C.

7. **Search parameters and scope plumbing.**
   Add `categoryId` to `DEFAULTS` in `stores/search-params.js`, to every `CLEARS` entry that clears a scope, and as its
   own entry clearing folder, person, album, Location, and map area; update the table's comment. Add it to
   `SCOPE_PARAMS` in `map/map-state.js` and add `getCurrentCategoryId()` to `context.js`. In
   `fragments/search-results.js`, pass the viewed category to the Location list along with the viewed Location (they
   share the `#location-{id}` ID space, and at most one is set); update the comment. The batch footer condition needs
   no change.

8. **Nest Locations under their category and make the category expandable.**
   In `common/location-list.js`, render each category as a node containing its row and a children container
   (`#location-children-{id}`) that holds its Locations, collapsed on first render. Keep top-level Locations and
   categories as siblings in path order. A category with Locations gets a native button around its icon
   (`#location-expand-{id}`) with an accessible name, `aria-controls` for the children container, `aria-expanded`,
   and a tooltip. Clicking it toggles `data-expanded`, children visibility, the badge state, and `aria-expanded`
   together, in one function. Enter and Space activate it; double-click has no extra action. Collapsing closes an
   open menu inside the children through `closeOpenContextMenu` and moves focus to the button if focus was inside.
   A category with no Locations keeps a plain icon that is a search trigger. The category name becomes a search
   trigger (`data-app-search-category-id`). Snapshot expanded category IDs and focus immediately before `_render`
   replaces the DOM and restore them afterwards, dropping categories that no longer exist or have no Locations.
   Include categories in `_patchAssetCounts`. Export a function that expands a category by ID for the add listener.
   Update the module comment. Nesting lets one descendant CSS rule color a viewed category's Locations and lets the
   children container carry the group spacing.

9. **Style categories: badge, green scope, and group spacing.**
   In `locations.scala.html`, reset the button to the icon's look and position with a visible focus ring, as the
   folder `.expand-ctrl` does. Draw the +/− badge as the icon's `::after` (Font Awesome 5 Free, weight 900), placed
   at a corner of the icon without adding width, so it inherits the icon's color, including green. Keep
   `.location[data-viewed-scope] .location-icon`, which now covers a viewed category's Locations through nesting.
   Indent child rows by their trace as today. Apply the task 3 spacing rules to expanded categories. The Location
   list has no root row, so an expanded category that is the list's first row also drops its top margin.

10. **Listeners for add and delete.**
    In `listeners/locations.js`, after `LOCATION_ADDED_EVENT` reloads the list, expand the category named in the
    success detail. After `LOCATION_DELETED_EVENT`, run a search back to the whole repository when the deleted row is
    the current category, in addition to the existing current-Location check. Move leaves expansion to the list
    reload's restore.

### Unit E: documentation and verification

11. **Update documentation and nearby comments.**
    In `altitude/views/AGENTS.md`, update **Folder asset counts** (count after the name, no column sizing), **Folder
    tree expansion and viewed scope** (root is never marked; group spacing rule), **Albums** (row order), and
    **Locations** (nesting, category expand control and badge, category search and count, viewed category, add and
    delete behavior, the `categoryId` parameter); the JS directory and Key Files tables; and the attribute list if a
    constant is added. Update **Search parameters** for `categoryId`. In `altitude/AGENTS.md`, update the
    `common/album-list.js` and Location entries that mention the count cell. Review root `AGENTS.md` for drift.

12. **Compile, test, and verify in the browser.**
    Run the new and affected server suites on both bundles (`make test-psql`, `make test-sqlite`) and
    `make compile`, then `mill altitude.resources` so the dev server serves the changed static files. Run ESLint and
    Prettier on the changed JS. Walk through the scenarios below in Chrome against the dev server and record the
    results in this plan.

## Browser acceptance scenarios

- Folders, Albums, and Locations show `Name (n)` with no blank space before the icon. A zero shows nothing. A long
  name wraps and its count stays visible. A top-level Location's icon and a category's icon share a left edge.
- Asset moves, recycling, and membership changes patch counts in place in every tab, categories included. An asset
  in two Locations of one category adds one to the category.
- View root: nothing is green. View a folder: it and its subtree are green; ancestors are not. Switch back to root:
  the green clears.
- Expand and collapse folder branches and categories in the combinations from the spacing table: every gap is 8px or
  16px, measured with `getBoundingClientRect`, and the top and bottom of each list have no extra space. A collapsed
  branch has no extra space.
- On a fresh page load every category is collapsed with a plus badge. Clicking the icon reveals its Locations and
  shows minus; clicking again hides them. Enter and Space do the same. The icon never searches. `aria-expanded` tracks
  the state. No network request is made.
- Click a category name: the grid shows assets in any of its Locations, once each. The category and its Locations are
  green, visible or not. The URL carries `categoryId`; a direct load of that URL restores the search and the green
  category with categories collapsed. Sorting, grouping, and the map layout keep the scope. "Remove from location"
  is not shown.
- Click a Location inside the viewed category: only that Location is green. Click a folder, album, or person: the
  category green clears.
- An empty category has no badge; its icon and name search and return no assets.
- Add a Location to a collapsed category: the category expands and shows it. Move a Location into a collapsed
  category: it stays collapsed and the Location is inside when expanded. Rename and count refreshes keep expansion.
- Move a Location out of the viewed category: its icon loses the green. Delete the viewed category: its Locations
  appear at the top level and the grid returns to the whole repository. Delete a category holding the viewed Location:
  that Location stays green at the top level.
- Collapse a category with an open Location menu: the menu closes and focus moves to the category's button.
- Dragging assets onto a Location row inside an expanded category adds them; a category row accepts no drop.
- Triage shows only Folders and trash hides the explorer, so neither offers category search.
