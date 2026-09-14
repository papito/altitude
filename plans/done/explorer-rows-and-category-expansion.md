# Explorer rows: counts after names, group spacing, and expandable categories

## Original goals

- Move the asset count after the folder or Location name, so a row with no assets leaves no blank space and rows
  line up on the left. A top-level Location and a category (for example "Location" and "NYC") share the same left
  edge.
- Give an expanded folder with child folders extra space above and below its group, so it does not blend with the
  folders around it. The space exists only while the folder is expanded.
- Make Location categories expandable and collapsible: clicking a category shows its Locations, and clicking it again
  hides them. Categories get the same extra space while expanded.
- Viewing the root folder does not turn the whole tree green.

Use the grill-me interview to settle behavior and produce a plan before changing application code.

## Status

Implemented and verified on 2026-09-13. Every task below is done; the browser results are recorded under
**Verification results** at the end.

## Confirmed decisions

### Counts

- Folders, Albums, and Locations show the count after the name: `Acquirium (47)`, dimmed, empty for zero. The count
  sits in its own cell directly after the name, is not a search trigger, and a long name wraps instead of pushing the
  count out of the panel. The root folder row and category rows show no count.
- With the count after the name, rows no longer reserve a uniform count column. The column sizing
  (`sizeCountColumn` and the `--folder-count-column`, `--album-count-column`, and `--location-count-column` variables)
  is removed.

### Folder clicks and green

- Folder clicks are unchanged: a folder name searches, and a branch icon expands and collapses with its existing
  single- and double-click gestures.
- Viewing the root folder turns nothing green, the root icon included. A non-root folder still turns itself and its
  whole subtree green.

### Categories

- A category is not searchable and never turns green. Clicking its icon or its name expands or collapses its
  Locations; nothing else happens. Enter and Space do the same. There is no double-click action, because categories
  are one level deep: a double-click toggles once, like a single click.
- A category with no Locations has no expand control, and clicking it does nothing.
- Categories start collapsed when the Locations tab loads. Expansion survives list reloads (add, rename, move, delete,
  count-refresh fallback) and is not persisted across page reloads.
- The category icon stays `fa-layer-group` and carries a small +/− badge drawn in CSS with the Font Awesome font
  (collapsed: plus, expanded: minus). The badge adds no width, so a category's icon lines up with top-level Location
  icons. A category with no Locations has no badge.
- Viewing a Location turns only that Location green, whether it is top-level or inside a category. A viewed Location
  inside a collapsed category stays hidden until the category is expanded.
- Adding a Location expands its category. Moving a Location into a collapsed category leaves that category collapsed,
  the same as moving a folder into a collapsed parent.
- Deleting a category moves its Locations to the top level (the existing server behavior); a viewed Location among
  them stays green.
- A category row is not a drop target.

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

## Existing implementation

- `../../altitude/static/js/common/asset-count.js` builds the `.asset-count` cell (`buildAssetCountEl`, `setAssetCount`) and
  sizes a list's count column (`sizeCountColumn`). `core.css` styles `.asset-count` dimmed and `justify-self: end`.
  `common/folder-tree.js`, `common/album-list.js`, and `common/location-list.js` place the count before the icon and
  call `sizeCountColumn` after each render and count patch.
- Row grids are declared in `views/htmx/folders.scala.html` (root: menu | icon | name; non-root: menu | trace | count |
  icon | name), `views/htmx/albums.scala.html` (menu | count | icon | name), and `views/htmx/locations.scala.html`
  (category: menu | icon | name; top-level Location: menu | count | icon | name; child: menu | trace | count | icon |
  name). The folder tree and the Location list use one uniform row gap (`--folder-row-gap`, `--location-row-gap`,
  both 8px); nothing adds space around expanded branches.
- `models/folder.js` owns folder branch expansion and sets `data-expanded`, the plus/minus glyph, children visibility,
  and `aria-expanded` together. Root carries `data-expanded="true"` and `data-is-root="true"`. Collapsing closes an
  open descendant menu through `closeOpenContextMenu` and moves focus out of hidden content.
  `#rootFolderList .folder .expand-ctrl` resets the branch button to the icon's look while keeping a focus ring.
- `common/viewed-folder-scope.js` marks the viewed folder's node with `data-viewed-scope`, root included, and the CSS
  rule `#rootFolderList .folder[data-viewed-scope] .folder-icon` colors that node's whole subtree green.
- `common/location-list.js` renders the list endpoint's rows flat, in path order, as siblings of `#locationList`: a
  category row, then its Locations as `.child` rows (`data-category-id`, `--depth: 1`, a `.trace` cell), with
  top-level Locations interleaved by name. A category row is menu | icon | name with no search trigger and no count;
  `_patchAssetCounts` skips categories. `setViewedLocation(id)` marks one Location row `data-viewed-scope`, which
  `_render` reapplies after every rebuild, and the CSS colors that row's `.location-icon`. `_render` restores focus
  by ID but keeps no other state across a rebuild.
- `listeners/locations.js` reloads the list after `LOCATION_ADDED_EVENT`, `LOCATION_MOVED_EVENT`, and the other
  dialog events.
- `LocationActionController.add` completes the Add location dialog with an empty response, so the dialog's success
  event carries no detail. `BaseController.dialogSuccessResponse` sends a detail in the `App-Success-Detail` header,
  which the dialog operation merges into the event, as `addAssets` does.
- `dragdrop/locations.js` binds drops to `#locationList .dropzone`, which only Location rows' `.controls` carry.
- Font Awesome Free 5.13.0 (`static/css/font-awesome.min.css`) has no plus/minus variant of `fa-layer-group`.

## Language

`../../CONTEXT.md` needs no change. **Location** and **Category** already cover what these decisions refer to: a top-level
Location and a Category are separate entities, and a Category holds no assets of its own. Expansion and group spacing
are presentation terms and are documented in `../../altitude/views/AGENTS.md`.

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
   tree, takes its place). Grid items do not collapse margins, so these rules implement the "8 or 16, never more"
   rule. Comment each rule with the case it covers. Expansion already toggles `data-expanded` through the folder
   model, so no JS changes.

### Unit C: expandable categories

4. **Report the new Location's category when it is added (server, test-first).**
   Complete `LocationActionController.add` with `dialogSuccessResponse` carrying `categoryId` (null at the top level),
   so the client can expand that category. Test first in `LocationActionControllerTests`: the success detail header
   names the category, and a top-level add names none.

5. **Nest Locations under their category and make the category expandable.**
   In `common/location-list.js`, render each category as a node containing its row and a children container
   (`#location-children-{id}`) that holds its Locations, collapsed on first render. Keep top-level Locations and
   categories as siblings in path order. A category with Locations gets one native button
   (`#location-expand-{id}`) wrapping its icon and name, with an accessible name, `aria-controls` for the children
   container, `aria-expanded`, and a tooltip. Clicking it toggles `data-expanded`, children visibility, the badge
   state, and `aria-expanded` together, in one function. Ignore the later clicks of a pointer multi-click sequence
   (`event.detail` above 1), as the folder branch control does, so a double-click toggles once; keyboard activation
   (`detail` 0) is always a single toggle. Collapsing closes an open menu inside the children through
   `closeOpenContextMenu` and moves focus to the button if focus was inside. A category with no Locations keeps a
   plain, non-interactive icon and name. Snapshot expanded category IDs immediately before `_render` replaces the DOM
   and restore them afterwards, alongside focus, dropping categories that no longer exist or have no Locations.
   Export a function that expands a category by ID for the add listener. Update the module comment. Nesting lets the
   children container carry the group spacing, and hiding it hides every Location of the category at once.

6. **Style categories: button, badge, and group spacing.**
   In `locations.scala.html`, reset the category button to plain row content with a visible focus ring, as the folder
   `.expand-ctrl` does, laying out its icon and name with the row's column gap so the icon stays in line with
   top-level Location icons. Give it a pointer cursor; give a category with no Locations none. Draw the +/− badge as
   the icon's `::after` (Font Awesome 5 Free, weight 900), placed at a corner of the icon without adding width. Keep
   indenting child rows by their trace and coloring only the viewed Location's icon green. Apply the task 3 spacing
   rules to expanded categories. The Location list has no root row, so an expanded category that is the list's first
   row also drops its top margin.

7. **Expand the category of an added Location.**
   In `listeners/locations.js`, after `LOCATION_ADDED_EVENT` reloads the list, expand the category named in the
   success detail. Moves leave expansion to the list reload's restore.

### Unit D: documentation and verification

8. **Update documentation and nearby comments.**
   In `../../altitude/views/AGENTS.md`, update **Folder asset counts** (count after the name, no column sizing), **Folder
   tree expansion and viewed scope** (root is never marked; group spacing rule), **Albums** (row order), and
   **Locations** (nesting, the category button and badge, collapsed start and restored expansion, add behavior,
   spacing), plus the Key Files entries for the three renderers and `asset-count.js`. In `../../altitude/AGENTS.md`, update
   the `common/album-list.js` and Location entries that mention the count cell. Review root `../../AGENTS.md` for drift.

9. **Compile, test, and verify in the browser.**
   Run `make test-controllers` for task 4 and `make compile`, then `mill altitude.resources` so the dev server serves
   the changed static files. Run ESLint and Prettier on the changed JS. Walk through the scenarios below in Chrome
   against the dev server and record the results in this plan.

## Browser acceptance scenarios

- Folders, Albums, and Locations show `Name (n)` with no blank space before the icon. A zero shows nothing. A long
  name wraps and its count stays visible. A top-level Location's icon and a category's icon share a left edge.
  Category and root rows show no count.
- Asset moves, recycling, and membership changes still patch counts in place in every tab.
- View root: nothing is green. View a folder: it and its subtree are green; ancestors are not. Switch back to root:
  the green clears.
- Expand and collapse folder branches and categories in the combinations from the spacing table: every gap is 8px or
  16px, measured with `getBoundingClientRect`, and the top and bottom of each list have no extra space. A collapsed
  branch or category has no extra space.
- On a fresh page load every category with Locations is collapsed with a plus badge. Clicking its icon or name
  reveals its Locations and shows minus; clicking either again hides them. Enter and Space do the same. The grid does
  not change, no network request is made, and `aria-expanded` tracks the state. A double-click or triple-click
  toggles once.
- A category with no Locations has no badge, and clicking it does nothing.
- View a Location inside a category: only that Location is green; the category is not. Collapse and expand the
  category: the Location is still green.
- Add a Location to a collapsed category: the category expands and shows it. Move a Location into a collapsed
  category: it stays collapsed, and the Location is inside when expanded. Rename and count refreshes keep expansion.
- Delete a category holding the viewed Location: that Location appears at the top level and stays green.
- Collapse a category with an open Location menu: the menu closes and focus moves to the category's button.
- Dragging assets onto a Location row inside an expanded category adds them; a category row accepts no drop.

## Verification results

Verified on 2026-09-13 against the dev server (`make test-controllers`: 46 passed, including the new success-detail
assertions; ESLint and Prettier clean; `mill altitude.resources` refreshed). Chrome ran in a hidden tab, so pointer
and keyboard input was driven through DOM APIs; two checks are left for a hand test, listed last.

- Counts follow names in all three tabs (`NYC (47)` with the row's 6px gap between them); zero counts render empty
  and rows end at their names. Folder, top-level Location and category icons share the same left edge. Root and
  category rows show no count. A long Location name wrapped to two lines with its count still inside the panel and
  no horizontal scrolling. `refreshAlbumCounts` and `refreshLocationCounts` patched counts in place.
- Viewing a folder marked it and its two children green and nothing else; viewing root marked nothing and root's
  icon kept its own color.
- Folder spacing, measured with `getBoundingClientRect` on temporary folders A → B → B1, A → C → C1 beside NYC and
  zzzz: all collapsed gave 8px everywhere; A, B and C expanded gave NYC→A 16, A→B 16, B→B1 8, B1→C 16, C→C1 8,
  C1→zzzz 16; NYC expanded as well gave NYC's children 8 apart and 2→A 16; collapsing A then gave A→zzzz 8. The
  top and bottom of the tree stayed at 0 throughout.
- On a fresh page load the category with Locations was collapsed with the plus badge (`\f067`), `aria-expanded`
  false, and its Locations hidden. `button.click()` (keyboard-style, `detail` 0), a click on the name, and a click
  on the icon each toggled; a click/click/dblclick sequence and a triple-click each toggled once; `aria-expanded`,
  the tooltip, the badge (`\f068` when expanded) and `data-expanded` moved together; no network request was
  made. The badge added no width: the expandable category's icon measured the same as an empty category's.
- A category with no Locations rendered as plain cells with no button, no badge and the default cursor, and clicks
  on its icon and name changed nothing.
- Location spacing: one expanded category gave 16 above and below and 8 inside; two consecutive expanded
  categories gave 16 between them; an expanded category moved to the list's first position gave a top of 0.
- Adding a Location into the collapsed category through the Add location dialog expanded it and showed the row;
  the success event's detail carried `categoryId`. Renaming (list reload) and a count refresh kept the category
  expanded. Moving a Location into a collapsed category through the Move dialog left it collapsed, and expanding
  it showed the Location.
- Viewing a Location inside a category marked only that row and colored only its icon green; the category and
  the other rows stayed plain, and collapsing and re-expanding the category kept it marked and green.
- With a Location's menu open and focused inside the category, collapsing the category closed the menu and moved
  focus to the category's button.
- Deleting the category holding the viewed Location through the Delete dialog left that Location at the top
  level, visible and green.
- Drop targets: every Location row's `.controls` carries `.dropzone` (inside an expanded category too) and no
  category row does, so `dragdrop/locations.js` binds only Location rows.

Left for a hand test (the hidden tab delivers no real input): pressing Enter and Space on a focused category
button, and dragging assets onto a Location inside an expanded category.
