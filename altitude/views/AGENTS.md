# Frontend – Agent Guide

## Do

The rules of [altitude/AGENTS.md](../AGENTS.md) **Do** apply here too. For frontend work:

- For UI work, if localhost:8080 is unreachable or the repo is not initialized (setup form shown, or no assets), prompt
  the developer to run `make db` and `make watch`.
- A browser check needs the login out of the way: every page redirects to `/login` unless `dev.user` and `dev.password`
  are set in `application-dev.conf` (project root, gitignored). A form login is lost on each dev server restart (every
  CSS or Scala change under `make watch`).
  - If the two lines are commented out, uncomment them for the check without asking, and comment them out after.
  - They are read once at startup and `make watch` does not watch the file, so the server must restart: make a real
    edit to a watched source (CSS or Scala; touching a timestamp does not count). The gate is gone once `/` redirects to
    `/r/<repoId>`.
  - Never repeat the two values anywhere: doc, log, commit or reply. If the file or the lines are missing, ask the
    developer. See **Config & Environments** in `altitude/AGENTS.md`.
- Use the fewest properties, tags and CSS/HTML features that do the job. Always prefer CSS Grid and Flexbox.
- Do not assume aesthetic preferences (color, spacing, padding) unless specified. The style guide
  (`views/style_guide.scala.html`, dev only, `/style-guide/r/:repoId`) records the design conventions: read its usage
  notes before choosing a color, spacing or component, and keep it current in the same change (see **Style guide**).
- CSS is one bundle: `static/css/app.css` `@import`s, in cascade order, `core.css`, `tabs.css`, then one file per
  template under `pages/`, `includes/` and `htmx/`, named after it. The build inlines it into one content-hashed file
  served from `/assets/`, so CSS URLs are absolute or `data:`.
  - A template's styles go in its own file (the folder tree's in `htmx/folders.css`), never in a `<style>` block.
  - The bundle applies on every page: scope a rule by an element only its template renders (usually an ID), and a
    page's own rule by its body class at zero specificity (`:where(body.setup) main`).
  - A Scala value reaches CSS as a custom property the template sets on the container (`style="--preview-box: ...px"`).
  - A nested rule extends its parent only by a pseudo-class or pseudo-element (`&:focus-visible`, `&::picker(select)`);
    a descendant gets its own rule, because the design tweaks panel's element picker matches with `closest()`, which
    cannot match a nested descendant selector (`& option`).
- Reuse `core.css`'s `:root` variables. Strongly prefer CSS variables over hard-coded values, especially repeated ones.
- Colors (detail under **Themes**):
  - No color literal in a stylesheet, named colors included. A rule writes a theme color (`--theme-*`: primary,
    secondary, accent, a neutral step, an on-color), a semantic color (`--success-color`, `--warning-color`,
    `--error-color`, `--info-color`) or `--shadow-color` by its token; a shadow by its token; an edge by a border token
    (width and style, no color) plus the color (`border: var(--border-hairline) var(--theme-neutral-3)`). Never restate
    a value a token holds.
  - Every theme and semantic color is a literal `light-dark(light, dark)` pair of two hex values (a swatch per line in
    an editor). The setup page's `--setup-*` palette is its own: literals with no `light-dark()` pair, so the page keeps
    its look in either theme, on `body:where(.setup)` in `pages/setup.css`, which the style guide's scan skips (see
    **Setup background**).
  - `core.css`'s only other color tokens: the Histogram channels (`--histogram-red`, `-green`, `-blue`, literal pairs);
    the veils (`--scrim-color`, `--popover-background-color`, `--panel-border-color`, …), each a theme color or
    `--shadow-color` at an alpha in relative color syntax; shadow tokens, whose color is a theme color or one of those
    at an alpha; the `--edit-track-*` gradients, whose stops are theme and semantic colors and `--shadow-color`; and the
    four semantic fills (`--success-background-color` etc.), each a `light-dark()` pair of HSL relative-color recipes
    over its semantic color, the only derived colors. No other token aliases a color or mixes one.
  - A color scoped to the elements it marks is declared by their rule from a theme or semantic color: `--view-tint` and
    `--view-stripe` on a view's panes, `--account-type-color` on `[data-account-type]` (Admin `--error-color`, User
    `--success-color`, Guest `--info-color`), `--edit-slider-track` on `.edit-slider`.
  - A new color is a theme color written in the rule; a new `--theme-*` hex pair when no role fits; a new veil token
    when it must be translucent.
  - Keyboard focus is one ring, `--border-heavy` in `--theme-primary`, drawn on every control by `core.css`'s
    `:focus-visible` rule. No rule removes an outline, except fields that draw their own focus indicator instead: a
    text-like field's accent edge and ring, the nav quick search field's glow, and an advanced search panel field, whose
    box draws the edge and ring around its chips and input.
- Spacing and sizes:
  - Paddings, margins, gaps and insets use a `--space-*` step of `core.css` or a role token over one (`--panel-padding`,
    `--viewport-gap`, `--list-row-gap`, …). Steps and size tokens (widths, heights, radii) are in rem; border widths,
    shadow offsets and server-set pixel sizes (`--face-box`, `--preview-box`) stay in px. A token JS reads as pixels is
    registered with `@property` as a `<length>`, so it computes to px (`--viewport-gap`).
  - Radii: `--radius` for a rectangular box or control; `--radius-large` for a button and a toggle group's track (a
    segment inside it `calc(var(--radius-large) - var(--space-1))`); `--radius-small` for a badge; the pill
    `--chip-border-radius` for a chip and a switch's track, except a chosen filter's chip (advanced search panel,
    filtered results title), a rectangular box with `--radius`; `--radius-xl` for the advanced search panel. Only a
    circle, a switch's knob or an avatar writes a radius in a rule, as a percentage.
  - The only literals: 0 and auto; centering offsets (`50%`, a negative half size); offsets beyond the scale (the 100 px
    of the general modal host and the unanchored popover dialog, the snackbar's top); `.visually-hidden`'s 1 px; and
    the em offsets and padding of a Locations category's +/− badge, which belong to its glyph.
- Type:
  - A font size is a step of `core.css`'s type scale (`--font-size-xs`, `-small`, `-base`, `-medium`, `-large`, `-xl`,
    `-display`, in rem), never a px or em literal, `smaller` or a parent-relative size. `--font-size-base` is the
    body's; a control that does not inherit it (a button, a menu action) writes it.
  - Weight: `--font-weight-bold`, `--font-weight-semibold` (buttons) or `--font-weight-medium`; `normal` resets one.
    Tracking: `--letter-spacing-wide`, `--letter-spacing-medium` (the brand) or `--letter-spacing-band` (the view band).
    Wrapping text's leading: `--line-height-text`.
  - Font: `--font`, set by `body`, or the monospace `--font-nav`, written only by the brand (nav and login page), the
    nav's entries and quick search field, and text that reads as an identifier (a path, a username, an account type, an
    Edit panel number field).
  - A glyph's own size and leading stay in its rule: an em size for an icon glyph (spinner, merge arrow, account info
    avatar, a category's +/− badge), `line-height: 1` for an icon-only control (menu trigger, close button, remove and
    clear controls, toolbar toggles, …) and the vertical view band. Font Awesome's solid weight 900 selects its face.
- Menus and dialogs (detail under **Modals**, **Context menus**, **Explorer action dialogs**):
  - Folder, Album and Location actions, Add controls and category actions included, open popover dialogs through
    `static/js/common/modal.js`, anchored below and centered on the row's ⋯ trigger or the Add button. Add location
    instead opens a centered modal, with room for its map editor. Their context menus stay action lists.
  - Two popover panels hold an inline dialog: View settings, in its menu's panel, and the advanced search panel under
    the nav's quick search (**Advanced search panel**).
  - A control that picks one of several values (the grid toolbar's Group and Sort, any dropdown added later) is a
    **choice menu**: the same native popover panel, built by `buildChoiceMenuCtrl`
    (`static/js/common/context-menu-markup.js`) from a server-rendered host of option buttons. Never a `<select>`,
    which stays a form field.
  - Every menu panel wears the popover dialog's frosted surface.

## Stack

- **HTMX 4** — server-driven HTML fragments, no SPA routing
- **Alpine.js** — reactive state and UI behavior
- **No bundler** — JS is native ES modules (`<script type="module">`, `import`/`export`), served as written
- Libraries are vendored in `static/js/lib/`; its `README.md` lists versions, sources and patches, and how to add one
  (**Adding a library**). They are htmx, Alpine and its focus plugin, axios, interact.js (drags, the Crop tool),
  Split.js (People tab), Viselect (box selection), Leaflet (Location pin editor, map), supercluster (map clustering),
  media-chrome (video player), Flatpickr (advanced search date ranges) and tokentweak (the dev-only design tweaks
  panel, loaded on no page: **Design tweaks**)
- Only htmx blocks the first render. Other plain library scripts are `defer`; htmx is not, so it starts on
  DOMContentLoaded after its deferred extensions and the modules (deferred, it would start before them). Libraries
  only some views need (Leaflet, supercluster, media-chrome, Flatpickr, with their stylesheets) are never linked by a
  page: the first view that needs one loads it through `js/common/vendor-loader.js`

## Template Layout

```
views/
  *.scala.html          ← full pages
  includes/             ← fragments included into a page or another template
  htmx/                 ← HTMX partials returned by routes/web/partial/**
```

Every page includes `@includes.html.header_common()` (HTMX, Alpine, the stylesheet bundle). The pages with a nav also
include `@includes.html.html_common()`: the snackbar's stacks and announcers, the tooltip's host and the four dialog
hosts. Login and setup have neither the nav nor `html_common`, and write their own `<title>`; every other page titles its
tab with `@includes.html.page_title(page)`, which names the request's Library, so each tab names its own.

Import (`pipeline.scala.html`), Settings and Users carry the nav outside the main app. Each loads `lib/json-enc.js`, since
the nav's dialogs post JSON, and a page module that starts with `startNavPage()` of `js/nav-page.js`
(**JS Directory ↔ Template Mapping**). How the nav's Library menu survives a nav reload there: **Nav refresh**.

HTMX partials (`views/htmx/`):

- Their controllers return them through `BaseController.htmlResponse` (`"<!doctype html>" + template(...)`).
- No `<style>`: a partial's styles are `static/css/htmx/<template>.css` (an include's in `css/includes/`, a page's in
  `css/pages/`), part of the one bundle (**Do**), so a swap carries markup only.
- No module scripts. Prefer declarative `data-app-*` attributes, hydrated centrally from `js/frontend-app.js`, over
  inline `<script type="module">`. What a partial would script the page does once: `index.scala.html` sets the
  repository ID, and `js/listeners/htmx-requests.js` marks the explorer tab that issued a load selected
  (`htmx:after:request`).
- The explorer tabs' partials are shells. `htmx/folders`, `albums` and `locations` hold action hosts and an empty list
  host (`data-app-fragment="folder-tree"`, `"album-list"`, `"location-list"`) that `js/fragments/explorer.js` renders
  client-side once the tab has settled; rows and their actions are in `js/common/folder-tree.js`, `album-list.js` and
  `location-list.js`. `htmx/people`'s `peopleTab` component loads each pane's list itself. See
  **Folder tree expansion and viewed scope**, **Albums**, **Locations** and **People tab**; Curation's partials are
  under **Curation**, the Triage explorer's under **Triage summary** and **Scope dialog and Quick view**, and every
  template under **Key Files**.
- The styles the explorer's menus and rows share (`.menu-ctrl`, `.context-menu`, `.asset-count`) live in `core.css`.

**Advanced search panel fragments** — `SearchPanelController` renders the panel's form and its comboboxes' options
(`../AGENTS.md`, **Advanced search panel**); the browser side is **Advanced search panel** below, the styles
`static/css/htmx/search_panel.css`.

- `htmx/search_panel` (`GET /htmx/search/r/:repoId/panel`) is the form, filled in from a search's parameters. Every
  filter is a field named after its search parameter (`Api.Field.Search`) holding the value a search takes, so the
  form's entries are the search. Every ID opens with `idPrefix` (`searchPanel` in the nav); the style guide's specimen
  passes its own, so both can be on one page. The "Search in" row and its `view` and `triaged` inputs are a member's
  alone.
- `includes/filter_combobox` is the one combobox field (`filterCombobox`): the panel's Folder, People and Locations, and
  the Import page's destination folders (**Import page**). The typed input has no `name`; only the hidden input of
  comma-separated IDs submits. Attributes configure the component: `data-options-url`; on a field of several
  `data-multiple`, `data-max-chosen` (`SearchRequestParser.MAX_IDS`) and `data-max-chosen-message`; and
  `data-options-params`, the form inputs an options request carries (a member's People field names `view` and
  `triaged`, so its counts follow the pick). The People label's row is as tall as the people match switch, so nothing
  moves when the switch appears.
- `includes/search_chip` is the one chip, the panel's and a filtered search's results title's (**Results title**);
  scripts find its parts by `data-option-name` and `data-chip-remove`. `includes/search_filter_icon` is the one place a
  filter kind's icon is named. The server names the chips: an ID that names nothing in the Library has no chip and no
  place in its input.
- `htmx/search_options_folders`, `_people` and `_locations` (`…/options/folders|people|locations?q=`) are bare
  `li[role="option"]` elements, at most `Const.Search.MAX_FILTER_OPTIONS`, matched and ordered by the server; the
  combobox marks the matched word starts. An option has no element ID: the combobox gives it `<listbox ID>-<option ID>`,
  so two comboboxes on a page offering the same folder never share one. A folder's and a Location's option is
  `includes/search_path_option`. A Person's count is `ListedPerson.visibleCount` under the request's `view` and
  `triaged`, and nobody a search finds nothing for is offered.

The explorer tabs (`index.scala.html`, in `#explorerHeader`) are the links themselves (`<a role="tab">`, selected by
`aria-selected="true"`). The initial tab is loaded through `htmx.ajax` with the tab as `source`, so it is selected as a
click would select it. The URL fragment names the tab to open; none, or one naming no tab of the view (`#albums` in
Triage), opens Folders. The server leaves the Albums tab out of Triage and puts the Triage summary's host above the tab
list there (**Triage summary**). The Trash hides the explorer through CSS. Placement against the results:
**Explorer dock**.

**Triage's and the Trash's look** — Triage is marked by the accent (`--theme-accent`), the Trash by `--warning-color`,
each at an alpha in relative color syntax, so it holds in either theme:

- The results pane (`#content:is(.triage, .trashbin)`, `core.css`) is diagonal stripes of `--view-tint` and
  `--view-stripe`, painted by the scroll container so they stay put while the results scroll. Bars that stick inside the
  pane (results title, toolbar, group headers, the map's surfaces) paint `--view-tint` alone over `--theme-neutral-2`.
- Triage tints its explorer flat (`pages/index.css`).
- The **view band** (`main::after`, `pages/index.css`) names the view down the left edge of the results. It sits in the
  `content` grid area, so it follows the results on every Dock side, and its top is anchored to the toolbar
  (`#searchControl`'s `--results-toolbar`), so it follows a results title and a wrapping toolbar with no script. Its
  text has an empty alternative (`"Triage" / ""`), since the nav already names the view. The striped pane pads its left
  by `--view-band-width` so nothing scrolls under the band, and the results title and toolbar take the same negative
  margin to span above it. It has no `z-index`: Asset detail, Curation and the dialogs paint over it.
- The nav's Triage entry stays `--success-color`, and its Trash entry `--warning-color`.

**The explorer in Triage** — In Triage the explorer still manages what it lists, but a search finds only triaged assets
(the glossary's **Triage**). Its tabs differ from Browse's in four ways, none of them in the tabs' markup or renderers:

- `routeOf` (`js/search-results/search-triggers.js`) routes a search trigger naming a folder, a Person or a Location to
  its scope dialog instead, unless the Person or Location has triaged assets; a folder (its name, a leaf's icon, a
  Results title crumb) holds none, and the root folder does nothing (the rule: **Triggers** under **Search
  parameters**; the dialog: **Scope dialog and Quick view**). What never searched acts as in Browse: a branch's expand
  control, a Category's row, the ⋯ menus, the add buttons, a Person's rename pencil, every drag and drop. A Person
  dropped on another merges and searches only if the results show one of the two (**Event Flow**).
- No count is displayed: one rule of `pages/index.css` hides `.asset-count` in the explorer and a People cell's
  `.count`. The cells stay and the code that patches them runs as in Browse; the Triage summary says what Triage holds.
- Everything keeps its Browse cursor except the root folder's name and icon, which do nothing: `pages/index.css` sets
  `cursor: default` over `folders.css`'s pointer.
- The **Triage summary** sits above the tabs on every tab (the header's layout: **Explorer dock**).

## JS Directory ↔ Template Mapping

Paths are under `static/js/`. File names say what each module does; this list gives the owner of each concern, the
templates it serves, and the contracts a directory listing does not show.

- `app.js`, `frontend-app.js` — the main app. `index.scala.html` and `style_guide.scala.html` call `initApp()`
  (`app.js`), which registers `alpine/components/index.js` and starts `FrontendApp`, the composition root that wires
  the asset, dragdrop, fragment, listener and search modules. The style guide imports `style-guide.js` first, so its
  `styleGuide` component exists before Alpine starts.
- `global.js` — document-level keys and the nav's theme switch, on every page (`includes/header_common`).
- `fragments/` — hydration of what a swap inserts, by `data-app-fragment="..."`, all from `hydrateAppFragments`
  (`fragments/index.js`). Its order matters: `location-editor` after `modal` (the map is sized once its host shows),
  `map-view` (`map/map-view.js`) after `search-results` (which disposes of the previous map). `dialog-operations.js`
  owns the operation lifecycle every dialog shares.
- `listeners/` — `document.body` event registration, one module per domain (`listeners/index.js`). Two are generic:
  `htmx-requests.js` is the one rule for every htmx request's outcome (failures to the snackbar, declared success
  events, tab selection, fragment hydration), and `dialogs.js` is the `document`-level dialog wiring (open requests, a
  204 to an open handed back to its search trigger, the popover dialog host's dismissals). `account.js` runs on every
  page with the nav.
- `search-results/` — the search, the grid and Asset detail, one behaviour per module. `search.js` is the single entry
  point for every search request: a caller passes only what it knows and the `searchParams` store supplies the rest.
- `edit/` — Image editing in Asset detail (`htmx/view_image_detail_modal`, `includes/edit_panel`,
  `includes/edit_stage_tools`); see **Edit panel**. `recipe.js`, `geometry.js`, `tone.js`, `adjust.js` and
  `edit-session.js` are DOM-free and tested under Node (`scripts/test-js/`, `make test-js`), so they stay free of DOM
  access. `edit-panel.js` is the Edit panel's coordinator.
- `import/` — the Import page's upload (no DOM of its own) and the Watch folders' cards; see **Import page**.
- `map/` — the map layout of `includes/search_results` and `htmx/map_view`; see **Map view**.
- `dragdrop/` — interact.js bindings, one module per drag source (`dragdrop/index.js`), with shared `helpers.js`.
- `stores/` — the Alpine stores (**Alpine.js Stores**). `html-common.js`'s
  `registerHtmlCommonBindings()` registers everything `html_common` binds, the focus plugin included.
- `common/` — modules shared across features. The ones with a contract:
  - `modal.js` owns the four dialog hosts: the general modal, the popover dialog, Asset detail and Curation.
  - `edit-keys.js` is the Edit panel's part in `global.js`'s keys, registered by `edit/edit-panel.js`
    (`registerEditKeys`), so `global.js`, which every page loads, imports no edit module.
  - `text-editing.js` decides whether focus is in a field that takes typed keys, for `global.js` and Curation's keys.
    A slider, checkbox, radio, color or file input or a button-like input is not one, so the shortcuts keep working
    on the Edit panel's sliders.
  - `stored-state.js` is a remembered object under one localStorage key that several modules share:
    `writeStoredFields` sets some fields and keeps the rest (`detail-panel.js` and `dock.js` both write Asset detail's
    view).
  - `dock.js` is the factory of every docked panel (the explorer's, the Detail panel's, the Edit panel's);
    `explorer-dock.js` is the explorer's configuration of it.
  - `clipping.js` classifies pixels in the browser the way the server's measurement does; `name-key.js` mirrors the
    server's name fold (`nameKey`, `partialMatchKey`).
  - `request-failure.js` is how a failed htmx request reads in the snackbar (a 401 nowhere); `session.js`'s
    `goToLogin` handles the 401.
- `models/folder.js` — DOM wrapper for folder tree elements and the owner of their expansion state.
- `alpine/components/` — Alpine components, referenced by name from templates' `x-data`. `index.js` registers the
  main app's; `nav-page.js` registers those the nav needs on other pages (`contextMenu`, `localTime`, `searchPanel`,
  `filterCombobox`, `dateRange`); the Users and Import pages' own are registered by their page modules.
- `upload-form-page.js`, `settings-page.js`, `users-page.js` — the page modules of `pipeline.scala.html`,
  `settings.scala.html` and `users.scala.html`, pages with the nav outside the main app. Each calls `startNavPage()`
  (`nav-page.js`): the `html_common` bindings, the nav's components, the htmx request rule, the dialog wiring and the
  account listeners on a minimal app that hydrates only `modal` fragments and choice menus, then `Alpine.start()`. A
  page module registers its own Alpine components before `startNavPage()` and its listeners after. The Import page
  also starts `import-status.js` (the Import history's status feed) and `import/watch-folders.js`. See **Settings
  screen** and **Users page**.
- `setup-page.js`, `setup-background/` — `setup.scala.html` only starts the background scene; its form submits
  through htmx and `json-enc.js`. The scene draws in a module Worker on an OffscreenCanvas; see **Setup background**.
- `theme-boot.js`, `explorer-dock-boot.js` — plain blocking head scripts (`includes/header_common` on themed pages;
  `index.scala.html`) that set the remembered theme, and the Dock side and explorer share, on `<html>` before the
  first paint. Each reads its storage key from its `data-key`, which matches `Const.localStore`; `common/theme.js` and
  `common/explorer-dock.js` own the key after that. See **Themes** and **Explorer dock**.
- `design-tweaks.js` — the adapter of the dev-only design tweaks panel over the vendored `lib/tokentweak.js`, loaded on
  no page (**Design tweaks**).

## Alpine.js Stores (initialized in `js/stores/app-stores.js` and `js/stores/html-common.js`)

`initializeFrontendStores()` registers every store below on the main page and the style guide (`js/frontend-app.js`).
What `html_common` binds (`modal`, `imageDetailLoading`, `snackbar`, the `$tooltip` magic, and the focus plugin for the
modal hosts' `x-trap`) comes from `registerHtmlCommonBindings()`, which the nav pages (Import, Settings, Users) call
alone through `startNavPage()` (`js/nav-page.js`). Each page registers exactly once, before `Alpine.start()`.

| Store key | Purpose |
|---|---|
| `Const.state.selectedAssets` | Reactive `Set` of selected asset IDs; the one place a selection changes (see **Selection**) |
| `Const.state.searchParams` | The complete search parameter set, the single source of truth for what is searched (see **Search parameters**) |
| `Const.state.currentView` | `repository`, `triage` or `trashbin`. Derived from `searchParams.view` and written once at page load: the view changes only by navigating |
| `Const.state.resultsTotal` | The toolbar total, set from the results fragment's `data-results-total` / `data-results-total-capped`. A capped total reads "10000+" and `decrement` leaves it alone, since it says only that there are more than the cap |
| `Const.state.sequencesToCurate` | Sequences in Curation's queue (`js/stores/sequences-to-curate.js`). `count` is null until the first answer; only the count endpoint sets it (`refresh`), never client arithmetic, and an older answer never replaces a newer one (see **Curation**) |
| `Const.state.modal` | The active dialog host, its title and the identity of the displayed open (`js/common/modal.js`, see **Modals**) |
| `Const.state.imageDetailLoading` | `value`: whether asset detail is loading, which shows its spinner (`js/search-results/detail-navigator.js`) |
| `Const.state.snackbar` | The snackbar's messages (`js/common/snackbar.js`). Producers call the module's three `show*SnackBar` functions, never the store (see **Snackbar**) |
| `Const.context.repoId` | Active repo ID, set once from Twirl via `window.ctx.setRepoId(...)` |
| `Const.context.gridMetadataFields` | Reactive `Set` of metadata field names shown in the grid, persisted in `localStorage` as a JSON array |

A JS module reaches a store only through `Alpine.store(Const.state.*)` / `Alpine.store(Const.context.*)` or the
`window.ctx` helpers, never by its key string; only a `$watch` expression names one (`"$store.modal.activeHost"`), as a
template binding does. Read the repo ID through `getRepoId()` of `js/context.js` (`window.ctx` on the page), never from
the store directly.

Some view state is deliberately **not** a store: one component or module owns it as its own data and remembers it in
`localStorage` under a key nothing else reads.

- The People tab (`peopleTab` component): each pane's buckets, Split view and the Split.js sizes, under
  `Const.localStore.peopleView` (see **People tab**).
- Curation (`curation` component, see **Curation**): the Loupe level (`Const.localStore.loupeLevel`), the Bundle kinds
  whose details show (`curationDetails`), the Histogram strips (`curationHistogram`) and the Sequence rail
  (`curationSequenceRail`).
- Asset detail: `Const.localStore.assetDetailView` is shared by two writers, each writing only its own fields.
  `js/search-results/detail-panel.js` writes the Detail panel's open state, Face highlights and dock (see **Detail
  panel**); `js/edit/edit-panel.js` writes `editOpen` and `editDock` (see **Edit panel**).

## DOM Addressing Convention

JS reads an element's identity from `data-*` attributes, **never** from positional or class-based selectors.
`Const.attributes` (`constants.js`) names the identity and state attributes that templates, the client-side renderers
and modules share, with a comment on each saying where it sits and what reads it; a new shared attribute goes there. JS
reads one with `getAttribute(Const.attributes.x)` or through `dataset` by its camel-cased key. A module's own hooks and a
fragment's configuration (`data-app-dialog-*`, `data-app-success-event`, `data-app-image-detail-*`) are read straight
from `dataset`.

Traps:

- A key need not match its attribute: `Const.attributes.loupeLevel` is `data-level`, and a `data-app-*` name reads as
  `dataset.app…` (`Const.attributes.sequenceId` is `data-app-sequence-id`, `dataset.appSequenceId`).
- `data-asset-id` belongs to the grid's thumbnail boxes: lookups by it find every cell of an asset
  (`js/search-results/cells.js`, see **Group headers**). Anything else that names an asset uses its own attribute, so
  those lookups never match it: a Curation frame in the rail carries `data-frame-id` (the grid sits beneath Curation),
  and the Detail panel's fragment and its People section carry `data-app-asset-id`.
- Near-twins stay distinct for the same reason: `data-rail-sequence-id` (a row of Curation's Sequence rail) against
  `data-app-sequence-id` (a group header's Curate), and `data-app-detail-dock-side` against `data-app-dock-side`, since
  both docks listen on the body.
- The one attribute without the `data-` prefix is `alt-has-no-date` on a result cell; the controller tests assert on it,
  so it keeps its name.

Curation's stage elements (each pane's image, lens, Histogram strip and Clipping canvas) are addressed by ID; see
**Curation**. Asset detail's fragment (`#imageDetailView`) carries the asset's Edit as JSON (`data-app-image-detail-edit`,
absent for an asset never edited), `data-app-image-detail-editable`, for a holder of Edit images the refusal
(`data-app-image-detail-edit-refusal`), and the measurement's `data-clipped-level`, `data-crushed-level` and
`data-measurement-box`, which the Edit panel's Clipping blink reads as Curation's reads them from a set's root (the
header comment of `htmx/view_image_detail_modal`; see **Edit panel**). The folder tree's node IDs and `Folder` model are
under **Folder tree expansion and viewed scope**.

## Event Flow

Two event layers, both on `document.body`. There is no router keyed on request URLs.

1. **Custom DOM events**: business-level actions (e.g. `FOLDER_MOVED_EVENT`), named in `Const.events` and handled by
   the domain modules in `js/listeners/`. A request element or a dialog announces its outcome declaratively
   (`readSuccessEvent` in `js/fragments/helpers.js`): `data-app-success-event="FOLDER_ADDED_EVENT"` (the event's string
   verbatim, not its `Const.events` key), `data-app-success-detail` (JSON), and
   `data-app-success-detail-target-attr-<key>="<attribute>"` for a value read from the element that issued the request.
2. **HTMX lifecycle events**: `js/listeners/htmx-requests.js` applies one rule to every request: a 401 goes to the
   login (**Requests and a refused session**); 400 and above goes to the snackbar (`reportRequestFailure`,
   `js/common/request-failure.js`) with its path and status, except a plain-text 403 or 409 (`BaseController.answering`),
   which shows its text, a 409 as a warning; a validation replacement (a 200 with `HX-Retarget: this`) is swapped in and
   announces nothing; any other success dispatches the declared success event and selects a `role="tab"` source; every
   swap hydrates the `data-app-fragment` roots it inserted. Modal opens and dialog operations are skipped there and
   settled by `js/listeners/dialogs.js` on `document`, so a failure is reported once.

Domain event chains:

- `assetMoved` / `assetTrashed` (`js/listeners/assets.js`) escalate to `batchAssetsMoved` / `batchAssetsRecycled` when
  the asset is among the selected ones. A recycle bin purge announces `trashPurged`, which refreshes the counts.
- `peopleChanged` means a person's count or bucket may have changed. A merge, a hide, a show, a discard, a rename,
  every Detail panel Face correction and every asset mutation through `refreshCounts()` (`js/assets/asset-actions.js`)
  announce it, since a person's number follows their assets. Its listeners read no detail: the People tab reloads its
  panes (**People tab**) and `js/listeners/triage.js` the Triage summary (**Triage summary**).
- Person events live in `js/listeners/people.js`. A rename (`personNameEdited`, from the inline editor or a People
  cell's rename dialog) patches no cell text: it can move the person from Unmatched to Matched, so it announces
  `peopleChanged` and the server's list puts them in the right bucket; it also reads the results title again
  (`reloadPersonTitle`) unless `titleShowsName` says the editor's own response shows the name. A cover-face change is
  the only one that patches cells, every cell of the person under `#people`. A merge (`personMerged`) runs no search of
  its own, in Browse and Triage alike, and results naming neither person stay. A search naming the source gets the
  destination in its place (`replaceId`, every other filter kept) and runs again; one naming the destination runs again.
- `confirmPersonMerge` sends its whole detail as the Merge people modal's request values. `keepDirection: true` (a
  name-conflict offer) keeps the requested direction; without it (a drop) the person with fewer faces is the source.
  The rename dialog's offer also sends `pane`, and the modal returns focus to `#personRenameCtrl-<pane>-<personId>`: it
  replaces the rename dialog, whose button goes with it, so a cancelled merge would otherwise leave focus on the body.
- A name or a merge of a Face's Person from the Detail panel's Change is a Face correction, neither `personNameEdited`
  nor `personMerged` (**Detail panel**).

### HTMX 4 conventions

- Lifecycle events carry the request context under `event.detail.ctx`. Read it through the helpers in
  `js/common/htmx-events.js`, not the detail directly.
- `htmx:after:request` fires when the response has arrived but **before** it is swapped in; `preventDefault()` drops
  the swap. Once the issuing element has left the DOM, htmx dispatches lifecycle events on `document`, so listeners
  that must see such late responses (dialog operations) go on `document`, not `document.body`. `htmx:after:settle`
  fires on the swap target once per swap, with the inserted nodes in `detail.newContent`.
- No swap settles (`defaultSettleDelay: 0` in the `htmx-config` meta). htmx's settle copies each old element's
  attributes onto the new one with the same ID and restores them after Alpine has initialized it, undoing what Alpine
  set (an `x-show` element back to the server's `display: none`; the Edit panel's tabs, whose IDs repeat across
  assets), and, skipping the focused element's `value`, shows the old value in a self-swapping form's focused field. An
  explicit `settle:0` in a swap is redundant.
- Attribute inheritance is explicit: every element that issues a request declares its own `hx-target` / `hx-swap`. Do
  not rely on a parent's (add `:inherited` if you ever must).
- Extensions activate by script inclusion; there is no `hx-ext`. JSON forms carry the boolean `hx-json-enc`.
- No Alpine compatibility shim is loaded: Alpine's mutation observer initializes swapped-in markup; nothing calls
  `Alpine.initTree` on it.
- A trigger's filter goes right after the event's name, with no space: `every[document.visibilityState === 'visible'
  && …] 5s` (the import page's nav poll, `views/pipeline.scala.html`; **Nav refresh**), `click[!event.shiftKey]`. The
  htmx 2 spelling `every 5s […]` parses, and its filter is silently dropped.
- The boolean `data-app-silent-failure` on an issuing element suppresses the snackbar for its 4xx/5xx. Only the nav
  poll declares it: the next tick retries, and a restarting server would otherwise raise an error every 5 s. A request
  with no response at all fires `htmx:error`, never `htmx:after:request`, so it is not reported either way.
- **Requests and a refused session**: any 401 sends the window to the login, to come back to the page (`goToLogin`,
  `js/common/session.js`): first thing in `htmx-requests.js` for every htmx request (a silent poll and a dialog's submit
  included), and in the axios interceptor of `js/http/client.js`. Nothing is reported and no success event fires. The
  server answers an htmx request with no login with a 401 plus `HX-Redirect` (`decorators.requireLogin`), never a
  redirect, which htmx's fetch would follow and swap in.
- A refusal the server words reaches the user as its words: an htmx plain-text 403 or 409 as **Event Flow** says
  (modal opens too), an axios JSON `{"error": ...}` through `getHttpErrorMessage`.
- Global config is the `htmx-config` meta in `views/includes/header_common.scala.html`. `noSwap` holds 204, 304, 4xx
  and 5xx: error bodies are never swapped, listeners report them.
- The Import page's status feed is `hx-ws:connect` to `/import/r/:repoId/status`, with no `hx-target`: each message is
  JSON naming the element it replaces (`target`, `swap`, `content`); one with no `content` (`{"connected": true}`,
  `{"watchFolders": true}`) swaps nothing (**Import page**; the handshake's refusals and the server side: **Request
  Lifecycle** and **Import history and the status feed** in `altitude/AGENTS.md`).
  - The server closes an account's sockets with 1013 when the account changes (`hx-ws` reconnects, and the handshake
    checks the login and Permission again) and with 1008 when its sessions are revoked (no reconnect). After its own
    password change the page reconnects itself: `js/listeners/account.js` swaps each `hx-ws:connect` element for a
    copy, which htmx processes again.
  - `hx-ws` retries a refused handshake forever, so `js/import-status.js` cancels `htmx:ws:before:connection` after
    `MAX_RECONNECTS` failed reconnects in a row and shows `#importStatusLost` (the vendored library stays unpatched).
  - `hx-ws` closes the socket while the tab is hidden and reopens it on show; every reopening reads the history again.
- **Not** HTMX requests: these go through the axios client because the caller must see or track the answer first, and
  each reports its own failure: an upload (`js/import/upload-run.js`, for `onUploadProgress`; **Import page**); a
  Curation set's fragment and the Sequence rail's rows (`curation`; a 204 passes over a Sequence; **Curation**); the
  advanced search panel's form and its comboboxes' options (`filterCombobox`; only the latest opening's or latest
  text's answer may show; **Advanced search panel**); the Change combobox's options (`faceCombobox`), the Detail panel's
  sections and the Face corrections (only the latest opening's, or the shown asset's, answer may show; **Detail
  panel**); the Watch
  folder dialog's deletion notice (`watchFolderForm`: an htmx request inside a dialog is a dialog operation, whose
  success closes it; **Import page**). Those are the only Alpine components that read through axios; `peopleTab` loads
  its panes with `htmx.ajax` (**People tab**).

### Full action flow (drag-and-drop example)

```
interact.js drag end / ondrop
  → dispatch CustomEvent on document.body
    → js/listeners/<domain>.js listener
        → shared axios client (`js/http/client.js`) or htmx.ajax() to server
          → on success: direct DOM mutation + snackbar + optional nav reload
```

- The person name editor's response is the one the client inspects: a saved name and the re-rendered editor both
  arrive as a 200 with no header, so `js/listeners/people.js` parses it and dispatches `personNameEdited` only when it
  holds no editor. The rename dialog (**People tab**) needs none: it is a dialog operation declaring
  `PERSON_NAME_EDITED_EVENT`, whose refusals are validation replacements.
- The Triage scope route is the one modal open a 204 answers: an open of nothing, whose `onNoContent` runs the
  trigger's search (**Modals**, **Scope dialog and Quick view**).
- A name another live person holds re-renders the editor or the dialog's form with `conflictingPerson`: the error row
  (`includes/person_name_taken`) offers `Merge into X…`, dispatching `confirmPersonMerge` with `keepDirection: true`, so
  the renamed person merges into the holder, whose ID survives. The button is Alpine's, so the row needs an `x-data`
  scope (`#person`'s for the editor; a bare `x-data` on the dialog's form, the popover host having none). The editor
  stays open behind the modal and keeps its error after the modal closes.
- The editor's field is `#editPersonNameField`, not the dialog's `#field-name`: the dialog can be open over the editor,
  and two fields would share the ID.

## Alpine.js Integration

Alpine serves four distinct roles in this codebase:

### 1. Global shared state (stores)
The stores and how a module reaches them: **Alpine.js Stores**.

### 2. Selection
An asset cell has no Alpine component. The `selectedAssets` store (`js/search-results/selection.js`) is the one place
a selection changes: its methods update the ID set and paint every cell of the asset, found through
`js/search-results/cells.js` (a Location grouping holds an asset once per Location). The grid's delegated click
listener, box selection and drags all go through the store. The DOM is not reactive, so code that adds or removes
cells calls `noteGridChange()`, the counter the group headers read.

### 3. Coordination around native UI
The `contextMenu` component (`alpine/components/context-menu.js`) wraps the explorer rows' ⋯ menus, the ⚙ View panel
and the choice menus. The browser owns visibility (`popovertarget`, light dismiss); the component only places, focuses,
closes and cleans up. Nothing binds `x-show` or an `open` flag to a menu. See **Context menus**.

### 4. Rendering a page's own data
A page that renders data it already holds may use `x-for` over one component, so its markup stays in Twirl: the style
guide (`styleGuide`, `js/style-guide.js`, registered by the page's module import before `initApp()`) and the snackbar's
stacks in `html_common`.

- Keep template expressions to property reads and single method calls: nothing lints them.
- Alpine has no template reuse: a repeated card or row is a Twirl block (`@tokenCards` in `style_guide.scala.html`).
- Twirl owns `@`, so write `@@click`.

The explorer lists are rendered by their JS modules. `peopleTab` and `curation` render nothing: the server renders the
markup, and the component holds the state its bindings read.

Alpine is **not** used for routing or HTMX trigger logic; those stay with HTMX and the event bus. A component that must
check an answer before showing it reads through the axios client; which ones do is listed under **HTMX 4
conventions**.

## UI Patterns

**Modals** — `js/common/modal.js` is the single owner of the four dialog hosts in `includes/html_common.scala.html`,
named by `ModalHost`: the general modal (`#modalContent`), the popover dialog (`#popoverContent`), Asset detail
(`#imageDetailModalContent`) and Curation (`#curationModalContent`). A request opens a host by targeting its content
element. `modal.js` owns:

- One active host at a time: a new open replaces the active dialog, whatever its host. Switching hosts releases the
  old one in its own reactive flush first, so the new host's `x-trap.inert` marks are not undone; during that tick the
  new open already counts as displayed (`activatingOpenId`), so a cached image that loads in it is still its own.
  Opening any dialog closes an open context menu first.
- Close guards: `setCloseGuard(host, guard)` lets a host hold a `closeModal()` and close later; `takeHeldClose()`
  hands the caller the promise of whether it closed. Asset detail's guard holds while the Edit panel has a change
  pending (**Edit panel**).
- Initial focus: the fragment's autofocus selector, else the host's `.close-modal`, else the host; never a destructive
  action.
- Focus on close: the fragment's `data-app-dialog-return-focus` selector, else the control focused when the open was
  requested. When the open takes that control away, `openerOf` substitutes the one that stands for it: a menu action's
  visible ⋯ trigger, or, for a control inside the open popover dialog that the new dialog replaces, the control that
  popover would have returned to (Quick view opened from a Triage scope dialog returns to the explorer's trigger).
- Identity: each displayed open has an `openId`, and asynchronous work checks `isModalOpenActive(openId)` before
  touching the modal. The latest open request (any htmx request targeting a host) is tracked, and a slower response
  for an earlier open is cancelled before it swaps. An open answered 204 opens nothing: `settleModalOpenRequest` drops
  it and calls `onNoContent`, which `js/listeners/dialogs.js` uses to let a search trigger search instead (**Scope
  dialog and Quick view**).
- One dialog fragment: a host keeps its last fragment after it closes, so opening the general modal or the popover
  dialog empties the other once it has faded out; two dialogs never share a field ID (a `<label for>`, an autofocus
  selector). A validation replacement empties nothing and keeps the original return-focus control. Curation's
  component empties its own host as it closes.
- Every close (`releaseActiveHost`) unloads the host's videos, clears the Media Session and leaves fullscreen.

The modal hosts bind visibility to the Alpine `modal` store (`x-show`) and trap focus with
`x-trap.inert.noscroll.noreturn.noautofocus` (vendored `@alpinejs/focus`, registered by `registerHtmlCommonBindings()`
in `js/stores/html-common.js`, local patches in `js/lib/README.md`); `noreturn` and `noautofocus` leave focus to
`modal.js`. A general dialog that needs more room overrides `--modal-content-width` in its own CSS.

Escape (`global.js`, whose header documents the order) closes one thing per press and consumes the key, so the
browser's own Escape handling of the popover and menus never runs a second time and a background inline edit
survives. What sits on top closes first: a combobox listbox or a context menu before the dialog holding it (focus back
on the menu's trigger), and in Asset detail the Edit panel's warning, its tools and the selected Face before the
dialog (**Edit panel**, **Detail panel**). In fullscreen Escape is the browser's (**Detail navigation**). With nothing
to close it broadcasts `Const.events.escapeKeyPressed` for an inline edit to cancel. An open select picker keeps its
Escape and closes only itself.

The popover dialog (`#popoverDialog`) is a native `<dialog popover="auto">`: no scrim, no focus trap, the page stays
live. The browser owns its visibility (`:popover-open`); `modal.js` changes it only through `showPopover()` and
`hidePopover()`, never `x-show`. The store still names it as the active host, for the shared title and the
one-active-host rule.

- CSS anchor positioning places it (`#popoverDialog` in `core.css`); no JS measures. A popover request names its
  trigger's ID in `data-app-dialog-anchor`, the trigger carries `anchor-name: --<its id>`, and `openModal` sets the
  host's `position-anchor` to `--<that id>`. `js/common/context-menu-markup.js` sets the anchor name on the triggers it
  builds; server-rendered triggers set it inline (**People tab**, **Scope dialog and Quick view**). Trigger IDs are
  stable, so a list rebuild re-anchors an open popover with no observer.
- `openModal` also passes the trigger as `showPopover({ source })`, so Tab out of the popover continues from the
  trigger. That implicit anchor references one element, which a rebuild leaves behind, so the named
  `position-anchor` overrides it. With the trigger gone or hidden, the host is `data-unanchored`, placed like the
  general modal.
- An outside click light-dismisses it, dropping what was typed. The `beforetoggle` listener (`bindPopoverHost()`)
  does the close bookkeeping synchronously, before that click can request another dialog, so it never cancels the
  click's open; focus is restored only if it was still inside. The `focusout` listener closes it when Tab moves focus
  to a control outside, leaving focus there; a focusout with no related target (a click, a validation swap removing
  the focused field) is left to light dismiss. Escape, the × and a completed operation close it through
  `closeModal()`, restoring focus unless it already sits on a control outside.
- The host has `tabindex="-1"`, as a context menu panel does, so a click on its title, a label or its padding focuses
  the host rather than the body and never reads as focus leaving.
- A form inside fills `--popover-form-width`, with its action `--space-5` below its fields (`core.css`).

A **dialog** is a server-rendered form that completes one user action, hydrated from its `data-app-fragment` kind; a
**modal dialog** is shown in a modal host, a **popover dialog** in the popover host. What describes the dialog is
`data-app-dialog-*` on the fragment root, whatever the presentation: autofocus selector and select-on-focus,
return-focus selector (a control that survives the page update the dialog triggers), and `kind`, for wiring beyond
the form (Add to album, Add to location). The host's one attribute is `data-app-modal-title`. The success event is
declared like any request element's (`data-app-success-event`, `-detail`, `-detail-target-attr-*`); an outcome only
the server knows arrives as JSON in the `App-Success-Detail` header (`BaseController.dialogSuccessResponse`;
`Const.http.successDetailHeader` is the client's copy of `Api.Field.SUCCESS_DETAIL_HEADER`), merged over the declared
detail. Single-field actions and small confirmations tied to a control (folder, album, Location
and category actions, People rename, password and delete dialogs) are popover dialogs; dialogs that need room (Add
location's map, Add to album, merge, Quick view, the user and Watch folder forms) are modal. View settings is the one
**inline dialog** fragment (**Inline dialogs**); the advanced search panel is no fragment kind (**Advanced search
panel**).

A dialog the page already holds needs no request: a button with `data-app-open-dialog="#id"` names a `<template>`
holding a `modal` fragment, which `js/fragments/dialog-openers.js` copies into the general modal and hydrates like a
fetched dialog. The purge confirmation (`htmx/trashbin_header.scala.html`) is the app's one; `hx-confirm` is not used.

`js/fragments/modal.js` hydrates a `data-app-fragment="modal"` fragment by opening the host it was swapped into, so
presentation follows the request's target and the template does not say which. `js/fragments/dialog-operations.js`
runs the operation lifecycle for every dialog kind (`registerDialogKind`):

- `htmx:before:request` captures the dialog's handle (for a modal or popover dialog, its open) and success metadata
  while the fragment is still in the DOM; a repeated submission while one is pending is dropped.
- The response dispatches the success event exactly once and lets page updates through even if the dialog was closed
  or replaced, but closes the initiating dialog only if it is still active.
- A validation response (`HX-Retarget: this`, `HX-Reswap: outerHTML settle:0`,
  `BaseController.dialogFormValidationResponse`) replaces the active form in place, values and errors kept, no success
  event; once the dialog is gone it goes to the snackbar. Its `settle:0` restates the no-settle config (**HTMX 4
  conventions**), which keeps the submitted value in the focused field.
- `htmx:finally:request` clears an operation left pending by a network failure, reports it, and allows resubmission.
- `js/listeners/dialogs.js` registers all of this on `document` (why: **HTMX 4 conventions**).

Asset detail (`js/fragments/image-detail.js`) opens its host at once and hands the load to the detail coordinator.
Its navigation starts from the cell whose thumbnail issued the request (`getModalOpenSource()`), and an open from no
cell (a map pin, an asset's URL) has no previous or next (**Detail navigation**). Its keys are `global.js`'s.

**Context menus** — A folder's, album's or Location row's ⋯ button (its **trigger**, `.menu-trigger`) is a real
`button` with `popovertarget` pointing at a `popover="auto"` panel (`.context-menu`), both built client-side by
`buildContextMenuCtrl` in `js/common/context-menu-markup.js`, so opening a menu sends no request. Trigger and panel IDs
(`folderMenuCtrl-<id>`, `menu-<id>`, and the album and Location equivalents) must stay stable across rebuilds:
rebuilds restore focus by ID, and dialogs name the trigger as their return-focus control.

- An action requests its dialog into `#popoverContent`, naming the trigger in `data-app-dialog-anchor`; `openModal`
  closes the menu. An action may `run` a script instead, and `{ separator: true }` is an `hr` between groups. Panels
  hold only action buttons, never an inline form or dialog host.
- The one script action is a non-root folder's **Search here**, first and set apart by a separator: it opens the
  advanced search panel with the folder chosen (`openSearchPanel({ folderId })`) and searches nothing until the
  panel's Search. Anyone who sees the Library can search, so it follows no Permission (a Guest's folder menu is Search
  here alone). The root folder has none, so without Organize its cell is empty (`buildNoMenuCell`).
- `buildModalTriggerCtrl` builds the Add buttons, empty-state ones included: into the popover host anchored to the
  button (`ModalHost.popover`, the default), or into the general modal unanchored (`ModalHost.general`, Add
  location); any other host throws.

The ⚙ View button (`js/search-results/view-settings-control.js`) uses `buildDialogTriggerCtrl`: a `dialog-only` panel
holding an inline dialog host, cleared on close. It stays invisible until the settings arrive and Alpine has bound
their switches (the reveal waits a microtask behind the mutation observer's, so no switch slides into its state). The
component drops a load response that arrives after the panel closed or reopened, and listens on its root because htmx
dispatches request events on the issuing button, not the panel. The host is emptied as a load starts and on close
(`clearDialog`), so a load never swaps over a leftover fragment.

A **choice menu** picks one of several values: the toolbar's Group and Sort (`includes/search_results.scala.html`).
The server renders a `data-choice-menu="<name>"` host holding one button per option, each a literal search trigger,
the effective one `aria-checked="true"`. Sort lists Similarity first for a described search and Relevance for one
with Search text, and neither otherwise, since the server refuses them. `ensureChoiceMenus`
(`js/common/choice-menus.js`, run on every hydrated root) hands each unbuilt host to `buildChoiceMenuCtrl`, which
turns the options into the `menuitemradio` items of a `role="menu"` panel under a trigger showing the checked choice.
Picking an option closes the menu (focus on the trigger) and runs its search; the results come back with a new
control showing the choice, so the client never updates the label, a failed search leaves the old choice shown, and
the search funnel knows nothing of the control. Host variants:

- **Compact choice menu** (`data-choice-menu-compact`): the trigger shows the checked option's `.glyph` alone, with
  the name and choice in its `aria-label` and `title`. A `.glyph` carries `width`/`height="16"` so an unstyled one
  never draws at 300×150. The one is the explorer's Dock side menu, whose options are dock-side picks rather than
  search triggers, so `js/common/explorer-dock.js` re-checks the options and updates the trigger itself (**Explorer
  dock**).
- Links and actions (the nav's Library menu, **Nav**): options may be links (`a[href]`). An `hr` ends the options;
  each element after it is a never-checked `menuitem` with its own `hx-get` into `#popoverContent`, anchored to the
  trigger, so focus returns there as from a ⋯ action. `data-choice-menu-trigger-class` replaces the trigger's class,
  and `data-choice-menu-choice-only` drops the icon and name, leaving the choice and caret.

Until a host is built, `core.css` hides its children (`[data-choice-menu] > :is(button, a, hr)`, which only an
unbuilt host has). The page's hosts are built by the first hydration, before `initExplorerDock` checks the current
side, so the compact trigger is corrected in the same task, before any paint.

The `contextMenu` Alpine component (`js/alpine/components/context-menu.js`) runs every menu, View settings and the
choice menus included:

- The browser owns visibility: it toggles the panel from its trigger, closes it on any outside click, and keeps one
  open, because the component root is the small menu cell and no panel is a descendant of another entity's panel.
- A menu opens focused on its checked action, else its first; Down/Up wrap, Home/End jump. No type-ahead.
- Unlike the popover dialog, a menu is placed by JS (`placeAnchoredPanel`, `js/common/anchored-panel.js`) and never
  tracks a moving trigger: it closes when focus leaves and on scroll outside the panel, window resize or explorer
  resize.
- Placement briefly sets inline `display: grid` on the hidden panel to measure it. CSS sets `display` only under
  `:popover-open`, because the hidden state relies on the browser's `display: none`; keep the `.context-menu`
  `margin: 0` and `inset: auto` resets so the viewport coordinates apply.
- `closeContextMenu` / `closeOpenContextMenu` close a menu from outside: Escape in `global.js`, the folder model when
  an ancestor collapses, the Location list when a category collapses, and `openModal`. The trigger is found as the
  `[popovertarget]` button in the panel's cell (`getContextMenuTrigger`), not by ID.
- `.menu-ctrl` and `.expand-ctrl` are excluded from folder dragging (`ignoreFrom` in `js/dragdrop/folders.js`).
- A panel lives inside whatever container holds its trigger, so no stylesheet may reach into one with a descendant
  selector: an ID selector outranks `.context-menu:popover-open` and would leave a closed panel displayed. That is why
  `#searchControl .controls > div:not(.toggle-group)` in `includes/search_results.css` targets direct children only.

**Folder tree expansion and viewed scope** — `js/common/folder-tree.js` renders the tree from one JSON fetch that holds
every folder, so expansion sends no request; `js/models/folder.js` is the single owner of expansion state. A **branch**
is a non-root folder with child folders, and only branches carry expansion state. Root is always expanded, has no
gestures, and its name and icon do nothing in Triage. In a non-root row the name and the icon have separate jobs:

- The name (`#folderName-{id}`) is a search trigger for the folder; Triage and the trash reroute its click
  (**Search parameters**, **The explorer in Triage**). A folder drag arms `suppressClickAfterRelease()` at its start,
  so the click its release produces on the name never searches.
- The icon sits in a native `button.expand-ctrl` (`#expand-folder-children-{id}`). A branch's button only toggles. A
  leaf keeps a plain `fa-folder` icon even when it holds assets, and its button is a search trigger like the name.
- The name and a leaf's button anchor the scope dialog their click opens in Triage (`_anchorDialogsTo`, the **Modals**
  anchor convention).

| Target and starting state | Single-click | Double-click |
|---|---|---|
| Collapsed branch icon (`fa-folder-plus`) | Reveal direct children only | Expand all levels in this branch |
| Partly or fully expanded branch icon (`fa-folder-minus`) | Close the branch and reset descendants | Close the branch and reset descendants |
| Folder name | Navigate to the folder (in Triage: its scope dialog, nothing for the root) | No expansion action |
| Plain root or leaf icon (`fa-folder`) | Navigate to the folder (in Triage: its scope dialog, nothing for the root) | No expansion action |

Enter and Space are single-click activations. `collapse()` also collapses every descendant branch (the
**reset invariant**: a reopened branch shows one level, and hidden descendant expansion never lingers), and moves focus
that was inside to the branch's button. `_bindBranchGestures` ignores the later clicks of a pointer multi-click and
applies the double-click from the state captured before the first click; its comment explains why. Branches start
collapsed, and nothing is persisted.

`Folder` (`js/models/folder.js`) is the one DOM abstraction for a folder-tree node (`#folder-{id}`, with
`#children-{id}` and `#folderName-{id}`). `Folder.find(id)` returns `null` when a rebuild removed the node
mid-interaction, so a caller handles `null`. The renderer reads the tree JSON's `numOfChildren` and `numOfAssets`
(**Folder asset counts**) and builds each folder's ⋯ menu (`#folderMenuCtrl-{id}` opening `#menu-{id}`). The IDs are
stable, so a rebuild restores focus and re-anchors an open dialog, and a dialog returns focus to its trigger.
`reloadFolderTree(repoId)`:

- Drops a superseded response, and snapshots the expanded folder IDs and the focused control only immediately before
  replacing the DOM, so a gesture made, or focus a dialog returned, during the fetch is not undone.
- Re-expands a surviving branch only under an expanded parent, so a folder moved beneath a collapsed parent comes back
  collapsed. It reapplies the latest viewed scope independently of the snapshot.
- Calls `htmx.process(container)`; Alpine's mutation observer initializes the new subtree. Never also call
  `Alpine.initTree`: it initializes every component a second time and duplicates their listeners.

An expanded branch and its visible descendants form a **group**: the space between two visible rows is the row gap
(`--list-row-gap`, shared with the albums and the Locations), or twice that where a group starts or ends, never more.
Root is not a group. `static/css/htmx/folders.css` implements it in CSS alone, from `data-expanded`. Indentation: each
non-root node's `--depth` times `--list-indent` (`core.css`, shared with the Location list) sizes the `.trace` grid cell
(`folders.css`), which indents the icon and name while `.menu-ctrl` stays flush left; the root row has no trace cell.
Keep the renderer's markup and these grid rules in sync.

Green marks the **viewed scope**: the folder whose results are displayed and all its descendants, because the results
cover that subtree. It has nothing to do with expansion. `js/common/viewed-folder-scope.js` marks that one node with
`data-viewed-scope`, and one `folders.css` rule colors every `.folder-icon` beneath it. Root is never marked: its scope
is the whole repository. The scope follows what is displayed, never the `searchParams` store: the results fragment
carries the scope the server resolved (`data-results-repo-id`, `data-results-view`, `data-results-folder-id`), and
`js/fragments/search-results.js` (`syncViewedScope`) applies it on hydration. So a superseded or failed navigation never
moves the highlight, it is absent in Triage and the trash, and every tree rebuild reapplies it. Deleting the viewed
folder removes it without selecting the parent.

**Folder asset counts** — Every non-root row shows its folder's recursive asset count in an `.asset-count` cell
(`#folder-count-{id}`, `js/common/asset-count.js`, shared with the albums and the Locations) directly after the name:
`NYC (47)`. It counts the folder's own sorted assets plus those of every folder beneath it, without triaged or recycled
assets. A zero is an empty cell, never `(0)`. The root row shows no count (the nav carries the repository total),
though the tree JSON reports `numOfAssets` for it. The count is not a search trigger, and Triage hides it
(**The explorer in Triage**). `FolderService.getTree` computes the counts on every fetch; nothing is stored.
`refreshFolderCounts` re-fetches the tree JSON after an asset mutation (`refreshCounts`, **Nav refresh**) and patches
each `#folder-count-{id}` in place, keeping expansion, focus and open menus; it falls back to a full render when the
response holds a folder the DOM lacks, as after a restore un-recycles one.

**Albums** — The Albums tab (`views/htmx/albums.scala.html`) is a flat list rendered by `js/common/album-list.js` from
the JSON list endpoint: one `.album` row (`#album-{id}`) per album, menu | icon | name | `.asset-count`. Icon and name
are `data-app-search-album-id` triggers; the row's `.controls` is a drop zone (`js/dragdrop/albums.js`) for a single
asset or the batch mover.

- Albums hold pointers only (**Albums** in `altitude/AGENTS.md`). A drop adds memberships
  (`assetActions.addAssetsToAlbum`) and the grid does not change. The counts are patched in place by
  `refreshAlbumCounts`, which `refreshCounts` also calls after every asset mutation, because recycling drops an asset
  from its albums.
- The viewed album follows `data-results-album-id` on the results fragment, as the folder scope does, and colors its
  row's icon green. Remove from album is in **Batch ops**.
- Add to album (`batchAddToAlbumRequested` → `add_to_album_dialog` in the modal host): `js/fragments/add-to-album.js`
  fills the hidden `assetIds` field (`fillSelectionField`, shared with Add to location) and keeps the success detail
  naming the chosen album, whose row may not be rendered. "New album…", the last option, has an empty value: the server
  then creates the album and adds the selection in one transaction (`AlbumService.addAssetsToNewAlbum`).
- The server's detail adds `added` and `created`. The `assetsAddedToAlbum` listener reports through
  `assetActions.reportAddedToAlbum`, the report a drop makes too ("Already in album" when nothing was new), and reloads
  the list for a created album (it needs a row) or refreshes the counts otherwise.
- Add album opens from `#addAlbumBtn`, or from `#addFirstAlbumBtn` while there are no albums; the renderer builds both
  and shows one. Deleting the viewed album searches back to the whole repository. `albumAdded` / `albumRenamed` /
  `albumDeleted` reload the list, which restores focus by ID, or focuses the visible add button when the change hid the
  dialog's return control (`focusAddAlbumControlIfFocusLost`).

**Locations** — The Locations tab (`views/htmx/locations.scala.html`) is rendered by `js/common/location-list.js` from
the JSON list endpoint, in path order (**Locations** in `altitude/AGENTS.md`). It works like **Albums** (pointers only,
drops, count patching by `refreshLocationCounts`, the viewed row, focus recovery by
`focusAddLocationControlIfFocusLost`), with one level of nesting:

- A category node (`.location.category`, `#location-{id}`) holds its row and its Locations' `.child` rows, indented by a
  `.trace` cell like a folder. A category holds no assets: no count, no drop target, no search trigger, never green.
- A category with Locations wraps its icon and name in one native button (`#location-expand-{id}`) that only expands or
  collapses. A double-click toggles once (later clicks of a pointer multi-click are ignored), and there is no recursive
  action. A category with no Locations is plain.
- Categories start collapsed. Expansion survives every rebuild (snapshotted immediately before it) but not a page load.
  Collapsing moves focus that was inside to the category's button. Expanded categories get the folder tree's
  **group** spacing.
- A Location's icon and name are search triggers, each the anchor of the scope dialog its click may open in Triage
  (**The explorer in Triage**).
- The viewed Location is `searchParams.viewedLocationId()`, the store's `locationId` when it holds exactly one ID, so an
  advanced search of several marks no row. Only its own icon turns green, never its category's.
- Add to location works like Add to album without creating. Its options read `Category › Location`, so the success
  detail takes the bare name from the option's `data-name`.
- Add location is a modal dialog in the general modal host, sized for the **Location pin editor**; the other Location
  dialogs are popovers. Delete returns focus to `#addLocationBtn`, because the row goes away.
- Every dialog event reloads the list; `locationDeleted` also reloads the Triage summary, since the Location's assets
  may now be in no Location. After an add the listener expands the category the new Location went into
  (`expandLocationCategory`, from the success detail's `categoryId`); a move into a collapsed category leaves it
  collapsed, as moving a folder under a collapsed parent does.
- Deleting a Location the search names drops it from the search (`replaceId("locationId", id, null)`) and searches
  again, keeping the other filters. Deleting a category leaves its Locations at the top level.

**Location pin editor** — A Location's pin is placed on a map, never typed. The Add location form nests a
`data-app-fragment="location-editor"` root, and `js/fragments/location-editor.js` builds a Leaflet map in it.

- The form's hidden `latitude` / `longitude` inputs are all the server sees. A validation replacement re-renders them
  with the submitted values, so the re-hydrated editor opens on that pin; otherwise it opens on the displayed results
  map's view (`getDisplayedMapView()`) or the world.
- `#locationEditorMap` has `tabindex="-1"` so that Alpine's focus trap leaves Leaflet's keyboard handling alone.
- One editor exists at a time: hydrating disposes of the previous map. It hydrates after the modal hydrator. Leaflet
  measures its container on creation, while the modal host is still hidden, so the map is sized again once the
  container is displayed and on every resize.
- The pin is an `L.divIcon`, so Leaflet's marker images are not vendored. Leaflet is a plain script (`window.L`), loaded
  with its stylesheet on first use (`loadLeaflet` in `js/common/vendor-loader.js`); the hydrator waits for it and skips
  a fragment removed meanwhile.
- The optional place search (geocoder flag) goes through `/api/map/r/:repoId/geocode`, and the newest search wins.

**People tab** — The People tab (`views/htmx/people.scala.html`, `static/css/htmx/people.css`) is a shell the browser
fills. `#people` (`x-data="peopleTab"`, `js/alpine/components/people-tab.js`) holds the **Main pane** and, in
**Split view**, the **Duplicate pane** (glossary terms), each a `section.pane[data-app-people-pane]` with bucket
switches over an empty `.list` host. Nothing of the tab is server state.

- Each pane's switches are the three disjoint **People buckets** (Matched, Hidden, Unmatched; `CONTEXT.md`), in the
  View dialog's switch pattern with the pane name in their IDs. A pane lists the union of those switched on; a first
  visit shows Matched alone.
- `loadPane` asks `GET /htmx/people/r/:repoId/list` (`PeopleActionController.listPeople`) with `pane` and `buckets`,
  through `htmx.ajax` into the pane's `.list`: on init, on a switch flip, on `peopleChanged`, and when "Show triaged
  items" flips (a `$watch` on the store's `triaged`). The host's `hx-sync="replace"` aborts the pane's load still in
  flight, so the latest bucket set lands.
- `triaged=true` is added only when `searchParams.showsTriaged()` or the view is Triage. Never forward the store's value
  as is: the `null` it holds for a Guest and outside Browse would go out as `triaged=null`, a 400.
- In `htmx/people_list` a cell is found by `data-person-id` and has no id, since a person may be in both panes. Its
  number is `ListedPerson.visibleCount`; a person with nothing to find is left off (**People** in `docs/faces.md`).
- The face link and the name link are both search triggers, and both name the name link
  (`#personLink-<pane>-<personId>`) in `data-app-dialog-anchor`, so the scope dialog a click opens in Triage sits under
  the name whichever was clicked.
- The rename pencil (Edit metadata's) is a sibling after the name link, not inside it, so it is no search trigger.
  `people.css` shows it on hover and on `.cell:has(:focus-visible)`, not `:focus-within`, so focus a click left in a
  cell shows no second pencil.
- The pencil anchors its dialog (**Modals**), and its ID carries the pane (`personRenameCtrl-<pane>-<personId>`)
  because a person can be in both panes and two pencils with one ID would be one anchor. That is why `loadPane` sends
  `pane`. The dialog's return focus is a selector, so it finds the pencil the list renders again, and its hidden `pane`
  field lets a refusal's replacement name the same pencil. A name another person holds offers the merge
  (**Event Flow**).
- In Triage the buckets and Split view are Browse's (**The explorer in Triage** for the hidden numbers and drops).
- The component listens on `document.body` for `peopleChanged` (each displayed pane reloads with its own buckets) and
  `dockSideChanged`, from `init()` to `destroy()`: the events are dispatched on the body, an ancestor of the shell, so a
  listener on the shell never sees them. Lookups start from `this.$root`, because in a `@click` handler `$el` is the
  button.
- `localStorage` `Const.localStore.peopleView` holds the split, each pane's buckets and each axis's pane sizes, written
  on every change and read once in `init()` (`rememberedView` validates it).

**Split view** renders the Duplicate pane under `<template x-if="split">`, and a Split.js instance made in `$nextTick`
divides the panes along the axis the **Dock side** calls for (`effectiveSide()`, `js/common/explorer-dock.js`): stacked
under a left or right dock, side by side under a top or bottom one, each axis with its own sizes. `dockSideChanged`
remakes the instance on the new axis. Turning Split view off destroys the instance, removing its gutter and inline sizes,
before `split` drops the pane, and `destroy()` does the same when another tab replaces the fragment. The Duplicate pane
copies the Main pane's buckets the first time only; from then on the two are remembered apart.

`js/dragdrop/people.js` binds cells in both panes, so a drop across panes merges like any drop. Drag start arms
`suppressClickAfterRelease()` (`click-suppression.js` under **Key Files**), so the release's click on the link under the
pointer does nothing, and points `autoScroll` at the cell's `.pane .list` (the pane is `overflow: hidden`).

The lists scroll only through a height chain across files: `#explorerViews` (`static/css/pages/index.css`) is an
`auto 1fr` grid at full height, `#explorerPanelContent` scrolls every tab, `#people` is a full-height flex column or row
by `html[data-dock-side]`, and a pane is an `auto 1fr` grid with both minimums reset whose `.list` scrolls.

**Triage summary** — `#triageSummary` in `#explorerHeader` (`index.scala.html`) hosts the Triage summary. The server
renders the host in Triage alone; it loads once with the page and survives tab loads, since it belongs to no tab. The
fragment's wording and counts are the server's (**Triage summary, scope dialogs and Quick view** in
`altitude/AGENTS.md`). Each count above zero, other than the total, is a search trigger for its Triage subset
(`data-app-search-subset`). Its `aria-label` names what it shows, since its text is a bare number, and it opens under its
own Results title (**Results title**). The paragraph is deliberately not a live region: the snackbar has already
reported each change it counts, and a region would read the whole sentence again on every reload.

Every reload goes through `app.reloadTriageSummary()` (`js/listeners/triage.js`), which does nothing outside Triage. It
runs on `peopleChanged`, every `FrontendApp.reloadNav()` and `reloadLocationCounts()`, and `locationDeleted`. One change
asks several times in one task, so the asks join one request after a microtask. The request is issued from the host, so
htmx's default `hx-sync` queue orders the reloads and a response never lands after a newer one.

**Scope dialog and Quick view** — In Triage, a trigger that names a folder, a Person or a Location asks the Triage scope
route instead of searching (`requestScopeDialog` in `js/search-results/search-triggers.js`; which triggers, and the root
folder's exception: **Triggers** under **Search parameters**). The request is an ordinary open of the popover host,
anchored to the trigger's `data-app-dialog-anchor` or `data-unanchored` when the trigger names none (a Results title
crumb, **Modals**). An open that the user replaced or dismissed in the meantime shows no dialog and runs no search. A
204 means the Person or Location has triaged assets, and `js/listeners/dialogs.js` runs the trigger's own search
(`runTriggerSearch`). Anything else is `htmx/triage_scope_dialog`. Its Close needs the bare `x-data`, because the
popover host has no Alpine scope.

Quick view (`htmx/quick_view_modal`) loads into `#modalContent`, so the general modal takes the popover's place, and
closing it returns focus to the explorer trigger (`openerOf`, **Modals**). It is read-only by its markup: no `.cell`,
`.drag-drop`, `data-asset-id` or search trigger, so selection, drag, Asset detail and search never bind to it. Its images
use the browser's `loading="lazy"`, because `js/search-results/lazy-images.js` watches only the results grid.

**Map view** — `layout` (`grid` | `map`) is a search parameter (**Search parameters**), set by `#layoutToggle`. A search
with a description is laid out only on the grid, so its toggle has no Map segment. In map layout the server disables
the Group choice menu (`data-choice-menu-disabled`) and renders `htmx/map_view` in place of `#assets`. `#map`'s
`isolation: isolate` keeps Leaflet's panes under the modals. While the results carry a `bbox`, the `#bboxScope` "Map
area ×" chip clears it, in either layout.

`js/map/map-view.js` hydrates `#map`. Only one map exists at a time: the results hydrator calls `disposeMapView()` for
every main results fragment (not for the panel's). The browser never receives a result set. Each settled move requests
`/api/map/r/:repoId/cells` with the store's query string plus `viewport` (in the server's form) and `zoom`, and a
response older than the latest request is dropped. Supercluster merges the cells again on screen. Pins are drawn in the
world copy nearest the map's center, so a view across the antimeridian shows both sides. A **crowded pin** (a cluster,
or a cell of several points) flies in while zooming can still split it. Otherwise it opens the panel for the union of
its cells' extents, which uses the server's cell rule (**Map** in `altitude/AGENTS.md`). Tiles that fail to load leave a
blank canvas with the pins, and no notice.

**Crowded-pin panel** — `openMapPanel({ bbox })` (`js/map/map-panel.js`) shows `#mapPanel` and runs `runSearch` with
`params: { bbox }`, a transient plain grid (`layout: "grid"`, no grouping) and `#mapPanelContent` as the target, with the
panel as `source`. `bbox` is a real store parameter, so the panel's grid is an ordinary search: infinite scroll, the page
window, selection, the detail modal and drag all work in it unchanged. The URL is bookmarkable, because the map hydrator
reopens the panel for the fragment's `data-results-bbox`. The map ignores `bbox` (**Map** in `altitude/AGENTS.md`), so
the map behind the panel keeps plotting the whole search.

- `js/fragments/search-results.js` hydrates a fragment inside `#mapPanel` as the panel's grid. It writes the panel's
  count and leaves the displayed map, the total store, the viewed scope and box selection alone.
- The panel's continuations stay a plain grid (**Infinite scroll + lazy load**).
- The server's `HX-Replace-Url` for a panel request says `layout=grid`. `js/listeners/map.js` puts `layout=map` back
  in `htmx:before:history:update` for requests issued from the panel.
- The panel's ×, Escape and the chip clear `bbox` through the funnel, which re-renders the map without a panel. "Show
  only these in the grid" keeps the area, so the grid opens scoped to it, with the chip to clear it.

`js/map/map-state.js` keeps the center and zoom in `sessionStorage`, keyed by a scope fingerprint (`SCOPE_PARAMS`: what
is plotted, not the sort, layout, grouping, paging or `bbox`). So a re-render, a panel, a trip to the grid or a reload
keeps the view, while a new scope, tab or window fits the server's bounds. Moves are written to storage only once they
pause, or on `pagehide`, because Leaflet reports every frame of a resize as a move. The module also holds the displayed
map's live view, where the pin editor opens.

**Explorer action dialogs** — The folder, album, Location and category forms are `data-app-fragment="modal"` popover
dialogs (Add location uses the general modal host). Add and Rename submit with Return, with `hx-target="this"`,
`hx-swap="none"` and `hx-json-enc`, so a validation response replaces the form with the submitted values and errors.
Each dialog holds one unlabelled field, since the title names it, with an `aria-label` (and a placeholder on Add's and
Rename's input).

- Initial focus: the name field (selected for Rename), or the category select for Move. Delete leaves focus on the
  close control (never a destructive action, **Modals**).
- Return focus (`data-app-dialog-return-focus`): Rename and Move return to the row's ⋯ button. Delete names a
  control that survives the mutation (the parent folder's ⋯, or the list's Add button). Add returns to its opener; for
  a menu action the modal owner remembers the visible ⋯ trigger. A validation re-hydration keeps the original return
  control and leaves the popover where it is. Dismissal follows the popover's rules (**Modals**).
- A folder's Delete (`htmx/delete_folder_dialog`) warns when Watch folders sort files into the folder or one below it.
  They pause until the folder is restored or an Admin gives them another destination (**Import page**).

**New library dialog** — `htmx/new_library_dialog` is the Library menu's "New library…" action, for an Admin or an
account with the Create Libraries flag, opened in the popover host below the nav's Browse trigger. It is a one-field
`modal` fragment like the explorer's Add dialogs, posting JSON to the create route. The route, why the field sets no
`minlength` or `maxlength` (every error is the server's), and the `HX-Redirect` a created Library answers are under
**Libraries** in `altitude/AGENTS.md`. `postUrl` is the create route everywhere except the style guide's sample
(**Style guide**).

**Inline dialogs** — View settings is the only user of `js/fragments/inline-dialog.js`, which only places focus. The
context menu component owns placement and dismissal (**Context menus**). The left column is the grid metadata switches
(`js/alpine/components/view-settings.js`). The right column, "Show triaged items", is the `triaged` search parameter
(where it shows and how it is remembered: **Search parameters**); it is not rendered for a Guest, and a flip runs
`runSearch`. `#viewSettingsActions` is `hx-preserve`, so the results swap moves the control and its open panel into the
new fragment (how a move keeps a popover open: **Nav refresh**), and `ensureViewSettingsControl` builds nothing. The
dialog has no operation lifecycle.

**Forms** — The FORMS section of `core.css` gives every text-like field (inputs, textareas, selects) one field style on
every page except the setup screen. On focus, the field shows the accent edge and ring instead of the focus ring
(**Themes**).

- The selector is inside `:where()`, so any page's own rule outranks it. Checkboxes, radios, ranges, hidden and submit
  inputs are excluded, and so is every field in the nav (`:not(nav *)`). The advanced search panel's fields are `.box`es
  that wear the style around an icon, chips and a bare input (**Advanced search panel**).
- The field and error rules exclude `body.setup`, so `htmx/setup_form.css` alone styles the setup form.
- A field keeps the width its page gives it, except in a popover dialog (**Modals**).
- A field in error carries the `error` class, and its message is a `div.error` under the field. An error with a way out
  puts an `.action-button.small` after the message, as the person name editor's merge offer does.
- A label in either dialog host or on the login form is small caps
  (`:is(#popoverContent, #modalContent, #loginForm) label`). A one-field dialog whose title names the field shows no
  label, and its field carries an `aria-label`.
- A select is a customizable select (`appearance: base-select` behind `@supports`), with the native select as the
  fallback. The toolbar's Group and Sort are choice menus, not selects (**Context menus**).
- A setting that applies as it is flipped is a `.switch` (SWITCH section): a `button[role="switch"]` before its
  `<label for>`. A button is labelable, so a click on the label flips it with no script, and Alpine keeps
  `aria-checked` in step. The exception is the Change combobox's "This face only" switch: focus must stay in the
  combobox's input, so `mousedown` is prevented on the switch and its label, and the label's click is prevented too and
  calls `toggleFaceOnly` (**Detail panel**).

**Snackbar** — Always use `showSuccessSnackBar` / `showWarningSnackBar` / `showErrorSnackBar` from
`js/common/snackbar.js`. Messages render as plain text (`x-text`). Pass raw text, including user-supplied names and
server errors, with no HTML markup or pre-escaping. The module comment covers the stacks, timers and de-duplication.

- Each stack is a `popover="manual"` element in the top layer: above the modal hosts and the map, and above a popover
  dialog or menu opened before it. `raiseSnackbarStacks()` re-raises the stacks above a fullscreen element. A click on a
  message while the popover dialog is open is an outside click, so it light-dismisses the dialog.
- Screen readers hear messages through hidden live regions (`data-app-snackbar-announcer`), never through the stacks.
  Each place holds a `role="status"` and a `role="alert"` region as siblings, never nested. `x-trap.inert` hides
  everything outside the active trapped `.modal-box`, so each trapped host holds its own pair. `modal.js` names that
  host (`getActiveTrapContainer`, `isInModalHost`).
- The fade-out length is read from `--snackbar-exit-duration`. Removal uses a timer, not `transitionend`.

**Tooltip** — A hint about a control, shown on a click; an outcome belongs to the snackbar. `$tooltip('…')` in a click
handler is `js/common/tooltip.js`, the Alpine UI tooltip's magic without tippy or Popper
(`docs/reference/alpine-components/tooltip.md`), and it writes into `html_common`'s `#tooltip` host. The magic sets an
inline `anchor-name` on the clicked element, so any element can call it with no ID or rule of its own. It hides at the
next `pointerdown` or `keydown`, caught at the capture phase so it hides a tick before the anchor can leave the tree. It
is announced through the snackbar's polite region (`announcePolitely`). Click only: a hover hint stays a native `title`.
The batch ops' Move button is a drag handle, so its drag (`js/dragdrop/batch-ops.js`) arms `suppressClickAfterRelease()`
(`click-suppression.js` under **Key Files**) and the release never shows the hint.

**Infinite scroll + lazy load** — Three modules under `js/search-results/` cooperate, and their module comments hold the
mechanics: `infinite-scroll.js`, `page-window.js` and `lazy-images.js`, bound by the results hydrator in that order.

- The last `.cell` by position gets `last-cell` and the cursor in `data-app-search-after` (by position, because a
  Location page can hold its last asset in an earlier cell too). It is observed only while nothing follows it in the
  grid. `loadNextPage(lastCellEl)` keeps the request in flight per cell (`pendingCursorOf`), so the detail modal,
  stepping past the last loaded cell, shares it.
- A continuation sends its fragment's `data-results-layout`, `data-results-group-by` and `data-results-group-direction`
  as transient parameters, so the crowded-pin panel's grid stays a plain grid while the store says map.
- A rejected page shows in the snackbar like any failed request (`js/listeners/htmx-requests.js`). This matters here
  because no visible control is behind the request.
- Only a first page's thumbnails (`isContinuousScroll` false in `htmx/result_cell`) carry `src` as well as `data-src`,
  so the first viewport paints before any script runs. Lazy images release an image more than a viewport away. Coming
  back is a cache hit, because the content route sends `immutable` caching.
- The page window folds the top page into a `.page-spacer` past `MAX_LOADED_CELLS`, reloads a spacer as a continuation,
  and drops the bottom page. Its scroll pane is `#mapPanelContent` for the panel's grid, else `#content`.
  `page-window.js` imports `infinite-scroll.js`, never the reverse (eslint forbids import cycles). A scrollbar jump can
  leave spacers between loaded pages, and since cursors are keyset positions a reloaded page can overlap the one below;
  both are allowed. `pagesOf`, `foldPagesIfNeeded` and `isSpacer` are exported for tooling.
- A spacer is not a `.cell` or `.result-group` and has no `data-asset-id`, `img` or `.metadata`, so selection, drag,
  lazy loading and metadata visibility never see it. The detail modal's walk reloads it (**Detail navigation**).

**Results title** — The glossary's Results title: one block every scope renders the same way, `includes/results_title`
around `includes/results_title_inner` (the kind's icon, the title, the way back, then a subtitle or a filtered search's
filters; `static/css/includes/results_title.css`). The total stays in the toolbar. `includes/search_results` renders the
filtered search's title when `filters` is set, otherwise the first of person, folder, album, Location, subset and text;
the store's scope rules keep these exclusive except for the advanced search panel, which combines them. The scopes:
- a **person** through `htmx/person`: `#person` is the block itself, the face merge's drop zone (`js/dragdrop/people.js`)
  and the swap target of Hide / Show / Discard, which answer with `htmx/person_inner`. Their `data-app-success-event`
  (`PEOPLE_CHANGED_EVENT`, `PERSON_MARKED_AS_BAD_MATCH_EVENT`) lets the People tab update. Which link shows depends on the
  person's face count, so a Face correction or a rename from a People cell re-reads the inside (`reloadPersonTitle`,
  `js/search-results/person-title.js`, `GET /htmx/people/r/:repoId/p/:personId/title`; a no-op unless `#person` shows that
  person). `#personName` hosts the inline name editor; only its error row wraps
- a **folder** as its breadcrumb (`.crumbs` of `core.css`): each ancestor short of the root is a search trigger for its
  folder (routed in Triage and the trash like any trigger, **Search parameters**), then the folder as `.leaf`. The path
  wraps instead of truncating. Built from `FolderService.getAncestors` plus the folder; the root folder renders no block
- an **album** (subtitle "Created *day*"), a **Location** (subtitle its category, if any)
- a **Triage subset**: "Unknown people" (`fa-user-secret`: Font Awesome 5 Free has no person with a question mark) or "No
  Location" (`fa-map-marker-alt slashed`: no slashed marker in the free set, so `.kind-icon.slashed::after` lays
  `fa-slash`'s glyph over it)
- the **Search text**, subtitle "in this library", or "in Triage" in the Triage view
- a **filtered search** (glossary; `SearchResultsController.isFiltered`, the root folder counting as no folder) as "Search
  results" over `includes/search_filter_chips`: the panel's chips (**Advanced search panel**) in its field order, then
  Edit. Names come from `SearchService.filterNames`, so an ID that names nothing in the Library has no chip and
  matches nothing. A date chip's label is `DateRange.label`, the server's copy of `rangeLabel`
  (`js/alpine/components/date-range.js`); both use fixed English month abbreviations and must agree.
  - The chips are the state. Each is `data-app-filter` with its parameters as `data-app-filter-<param>` literals; its
    × removes the chip at once and searches by what the remaining chips name, keeping the people match
    (`js/search-results/filtered-title.js`, through the panel's `searchByFilters`, in the search's view with its triage
    filter). So a second × before the first search answers drops both, an ID with no chip goes with the first removal,
    and a lone remaining scope chip (text, folder, person, Location) turns it into that scope's search and title. While
    a description remains, the search stays filtered, down to its chip alone. Removing the description leaves the other
    filters' search with the sort, grouping and layout the store kept (**Search parameters**), or the whole view when it
    was the only chip, the triage filter back to the remembered choice.
  - Edit (`data-app-edit-filters`) opens the panel on the search in force (`openSearchPanel()`).
  - A description that found nothing (`foundNothing`) is said after Edit: "Nothing within these filters looks like that."
    when another filter has a chip (`SearchFilters.narrowsDescription`), else "Nothing in this Library…" or, in Triage,
    "Nothing in Triage…" (`inTriage`). A search with no description says nothing of an empty result.
  - No `#person` block, so a chip shows a Person's name and cover as the search found them: renaming a Person with a chip
    re-runs the search (`searchAgainForPersonChip` on `personNameEdited`; after a Face correction named them, on closing
    Asset detail, **Detail panel**)

The way back is the `.clear-scope` ×: a search trigger with an empty `personId`, which the store's scope rules widen to
clearing every place, the panel's filters, the map area and the Search text, keeping view, sort, grouping and layout. An
empty value names nothing, so `routeOf` (`search-triggers.js`) searches it in Triage too, back to all of Triage.

The block and the toolbar (`#searchControl`) both stick at the top of `#content` (`z-index: 2`, above the group headers),
the toolbar under the title. Their heights vary (the path wraps, the name editor swaps in, the toolbar wraps), so
`js/search-results/sticky-offsets.js` writes them through one `ResizeObserver`: `--results-title-height` (the toolbar's
`top`, 0 without a title) and `--results-sticky-height` (both together, the group headers' `top`). Both paint the pane's
background (`--view-tint` over `--theme-neutral-2`) and use padding, not margin, so the grid never shows through.

The toolbar is the lead (the total, then **Batch ops**), the controls (a search with a description has no Map segment and
offers "Similarity" first, **Context menus**) and the trail (the trash's Purge, `htmx/trashbin_header`); the layout is in
`static/css/includes/search_results.css`. The crowded-pin panel hides the title, the toolbar and `#curateQueue`
(`static/css/htmx/map_view.css`).

In Triage, for a Curate Permission holder, `#curateQueue` follows the toolbar: `#curateSequences` opens Curation on the
whole queue whatever the search shows (`.curate.lit`, **Style guide**), labelled by the `sequencesToCurate` store. It is
outside `#searchControl`, so it scrolls away and the sticky offsets ignore it; it stays hidden until the count arrives and
while it is zero.

**Batch ops** — What the selection can do (`includes/batch_ops`): Move (a drag handle, **Tooltip**), Recycle, Add to
album, Remove from album while an album is shown, Add to location, Remove from location while one Location is
(`searchParams.viewedLocationId()`, none for a search of several), Curate Selected for two assets or more, and Deselect
all; in the trash, Purge, Restore and Deselect all. Each button shows only where the account holds its Permission in the
page's view (the template comment; **Controls follow Permissions**). The count shows once, before Deselect all.

The include renders twice, in the toolbar's lead and in the footer. The footer copy sits below its twin by CSS anchor
positioning alone (`static/css/includes/batch_ops.css`: the toolbar copy is `anchor-name: --batch-ops`, the footer copy is
fixed at its left edge), which is why both copies set their own sizes rather than inherit them. The crowded-pin panel's
toolbar is `display: none`, so it is never the anchor.

Move drags from either copy (`js/dragdrop/batch-ops.js`): `setFixedPositionWhileDragging` lifts it out of the scrolling
pane, and drop zones recognize it by `batch-mover` (`isBatchMoverElement`, `js/dragdrop/helpers.js`). Curate dispatches
`Const.events.batchCurateRequested`; `js/listeners/curation.js` opens **Curation** on the selected IDs, or, above
`data-max-selection` (`Const.Curation.MAX_SELECTION`), reports it in the snackbar. The server refuses too, but only the
client says why.

**Group headers** — A grouped grid (`htmx/results_grid_grouped.scala.html`) opens each group (a day, a Location or a
Sequence) with a `.result-group` header: a checkbox, the name, `data-group-key`, and the group's match count across
every page in `.count`, the number in `data-count`, which the client reads and decrements. A Sequence has no name, so
its header is its first capture's day and time (`Util.humanReadableDayAndTime`; seconds tell bursts of one minute
apart). With two frames or more it has a Curate button (`data-app-sequence-id`) opening **Curation** on all the
Sequence's frames out of the trash, even under a search showing some. Headers stick at `--results-sticky-height`
(**Results title**); `#assets` is an isolation root so the drag stand-in paints above them.

A continued group repeats no header. When the page window folds a page, the header of the group still open at its end
stays before the spacer as `keptHeader` (**Infinite scroll + lazy load**), still sticking and counting the loaded cells:
a spacer is not a header, so the walks below pass it.

`js/search-results/date-groups.js` is the one place a header changes on the client (only its count and Curate button).
`removeAssetsFromGrid` (`js/assets/asset-actions.js`) calls `decrementDateGroupOf(cellEl)` before a cell leaves: it
walks back to the nearest header, takes one off, drops the Curate button under `data-min-frames`, and removes the header
at zero. A header with no loaded cells but a positive count stays: the group has matches on unloaded pages. The client's
count text (`itemCountText`) must match `Util.humanReadableItemCount`. `removeCurateButtons(sequenceIds)` removes the
buttons of Sequences Curation cleared, every copy, since an overtaken Sequence has a second header (**Group by
Sequence** in `altitude/AGENTS.md`). Under Group by Location an asset has a cell per Location
(`asset-<id>-in-<locationId>`, else `asset-<id>`), so every per-asset change goes through `js/search-results/cells.js`
(by `data-asset-id`): each cell that leaves decrements its header, while the footer total, which counts assets, drops
once per asset (`resultsTotal`, **Alpine.js Stores**).

A Describe search's weaker matches open under their own header (`includes/weaker_matches_header`, both grid partials;
**Weaker matches** and **Sorted or grouped** in `altitude/AGENTS.md`). It is a `.result-group` marked `weaker-matches`, so
it sticks, the page window keeps it and the detail walk passes it with no code of its own; it has no checkbox and no
`data-group-key`. On the ungrouped grid it counts every cell after it, and cells above it have no header. On a grouped
grid the weaker groups follow it, each under its own header (a day or Location with matches above the line gets a second
group), so a weaker cell leaves two counts: `decrementDateGroupOf` also walks back from the group's header to the nearest
`.weaker-matches`.

The checkbox (`initDateGroupSelectable()`, `js/alpine/components/date-group-selectable.js`):
- Its set is the group's loaded cells, read off the DOM as the header's following siblings up to the next header. A group
  owns no element, which is what lets a continued group append cells without a header.
- It selects through the `selectedAssets` store and never fetches unloaded pages. It is checked only when the whole group
  is loaded and selected, indeterminate otherwise, so a group still scrolling in never reads as fully selected.
- `paint()` is the box's `x-effect`, and `change` calls it again after `toggle()`, since a click that changes neither
  count re-runs no effect. Do not cancel the click: the browser restores a cancelled checkbox's state after Alpine's
  effect microtask, so a `@click.prevent` box would show its pre-click state.
- Its counts read the store's set and `gridVersion`, which every cell arrival or removal bumps (`noteGridChange()`), so it
  repaints with no listener; Alpine batches effects, so a box selection repaints each header once.

Headers are never selectable (box selection targets `[data-asset-id]`) or draggable and carry no `img` or `.metadata`, so
lazy loading and metadata visibility skip them. The results hydrator (`js/fragments/search-results.js`) binds every grid
behaviour. A thumbnail lazy loading released keeps its rendered size as inline `width`/`height`, also once it is back, so
box selection hit-tests where the image was. A thumbnail drag (`js/dragdrop/assets.js`) therefore sets both dimensions on
the ghost and restores the inline size after: with the height left standing the ghost is too tall for a folder row to
cover a drop zone's share of it.

**Box selection** — Dragging a rectangle over the grid selects the thumbnails it touches
(`js/search-results/box-selection.js`, one controller per grid, replaced by the results hydrator; none for the crowded-pin
panel). The vendored Viselect (`lib/viselect.esm.js`) owns the gesture: the rectangle (`.selection-area`), autoscrolling
`#content` near its edges, and the list of thumbnails inside. It changes no state, and nothing visible changes during the
drag. On release the box goes through the `selectedAssets` store only: `reset()` for a replacing box, then `select()` per
thumbnail. The hit target is the `.drag-drop` div, and any overlap counts. Rules:
- A plain drag from empty grid space (padding, gaps, a cell's metadata) replaces the selection; over a thumbnail it is
  an asset drag. A Shift-drag from anywhere adds; Shift is read on button down, and `dragdrop/assets.js` declines an
  asset drag while Shift is held (interact's `actionChecker`). A press under 10px of movement is a click.
- Escape (consumed, so `global.js` closes nothing), window blur, a hidden page or a replaced grid discard the box
  without touching the selection. The click after release or cancellation is swallowed (`click-suppression.js`, as asset
  drags do).
- Candidates are snapshotted on release, so a page arriving afterwards changes nothing.

Viselect 3.9.0 re-evaluates the rectangle, and starts its scroll loop, only on `mousemove`, so the controller replays
the last pointer position as a synthetic `mousemove` when a page is appended, an image loads, the panel resizes, or the
pointer rests in an edge band with room to scroll. The position comes from the document's own `mousemove`, never
Viselect's `move` event, which during its scroll loop repeats the loop's starting event every frame and would keep the
pane scrolling with the mouse mid-pane. This adapter is version-bound and commented in place; the library file stays
unchanged. Viselect decides at drag start whether the container can scroll, so a box begun while the first page has no
scrollbar does not autoscroll.

**Detail navigation** — `js/search-results/detail-navigator.js` (`createSearchDetailCoordinator`) runs next/previous and
loading in Asset detail. The fragment (`htmx/view_image_detail_modal.scala.html`) holds an `<img>` and a media-chrome
player. Loading (`showMedia`):
- Each load gets a request token; only the latest for the still-active open may change anything, so a superseded
  completion never shows a stale asset or reopens a modal.
- The player is hidden while any asset loads, so an emptied player never shows in the previous frame.
- The box is fitted from the shown element's size (`naturalWidth`, `videoWidth`), not the stored one, which is the file
  header's: a browser turns an image upright by its EXIF orientation.
- A still image is shown by the Edit's stage (`js/edit/edit-stage.js`, **Edit panel**): `showMedia` awaits
  `showImage`, which picks the source by the asset's Edit and hands the Detail panel the size. The Edit, `editable` and
  the refusal come with the fragment on opening and with the asset's JSON on a step. A step first awaits the Edit
  panel's `beforeLeave()`, which may keep the asset (**The warning** under **Edit panel**); the panel is told of each
  asset as its load starts and again once a still image is shown.
- A Video plays at once, falling back to muted where sound is refused, by setting `muted` on the element so media-chrome
  does not store it as the user's mute preference (volume and mute are media-chrome's `localStorage` preferences);
  `AbortError` is ignored, and a clip under `LOOP_MAX_DURATION_SECONDS` loops. It is described to the Media Session
  (`js/common/video-playback.js`). Every close unloads the host's videos rather than pausing them (**Modals**), so no OS
  media control can resume one behind a closed modal.

The results grid is the source of truth: the coordinator remembers the *cell* it shows, not the asset, so under Group by
Location stepping stays in the group the user was looking at. `step({ direction, loadBeyond })` moves to the nearest
`.cell` sibling in document order, past headers (`siblingStopOf` stops at a cell or a page spacer). A spacer in either
direction is reloaded (`reloadSpacer`, `page-window.js`) and the walk looks again, since a 204 brings no cell. Past the
last loaded cell, next calls `loadNextPage` (shared with the scroll observer if in flight) and continues into it. A
detail opened from no cell (a map pin, an asset's URL) cannot step. There is no shadow list or JSON: a cell removed from
the grid leaves navigation with it, and the search route stays HTML only.

Asset detail's URL (`js/search-results/detail-url.js`, `app.detailUrl`) is `/r/:repoId/asset/:assetId`, plus `?panel=open`
while the Detail panel is open; it carries no search.
- `IndexController.assetView` answers with the main page, or a 404 for an asset outside the repository or invisible to the
  account (`decorators.visibleOnly`). `index.scala.html` calls `openAssetInLocation` after starting the first search, so
  the asset shows over the default results with no previous or next.
- The Detail panel follows the URL for an open that starts on one, else what this browser remembers (`attach`); only a
  toggle writes the remembered state. The panel drives the URL: `showAsset` and `toggle` call `show`.
- Opening from the results pushes one entry; stepping and the toggle replace it after `URL_DEBOUNCE_MS` (browsers
  limit history calls). Back closes Asset detail, any other close goes back over the entry, Forward reopens the asset.
  If the Edit panel holds Back's close for its unsaved-changes warning and the user stays, the entry is pushed again.
- A page loaded on an asset's URL owns no entry: closing replaces the URL with the results', and Back to an earlier load's
  entry reloads it for real, since the results on screen are not its search. A failed open restores the results' URL
  (`openFailed`).
- While the URL names an asset, a search's `HX-Replace-Url` is held (`htmx:before:history:update` cancelled) and
  applied on closing, or the first search of a page loaded on an asset's URL would overwrite it.
  `htmx:before:history:restore` is always cancelled: nothing pushes history through htmx, so every in-page traversal is
  this module's.

Fullscreen (`js/search-results/detail-fullscreen.js`, `app.detailFullscreen`) is the whole host,
`#imageDetailModalContainer`, image or Video, so the Detail panel, Edit panel, Face highlights and player controls come
along. `f` (`global.js`), the toolbar's toggle (`data-app-fullscreen-toggle`, removed where `document.fullscreenEnabled`
is false) and the player's button all go through it.
- `attach` sets each `<media-controller>`'s `fullscreenElement` to the host only after `customElements.whenDefined`: a
  property set before upgrade would shadow the class's accessor. The player's `hotkeys` turn off its own arrows, `f` and
  `m`.
- Fullscreen is never remembered and survives stepping. Escape is the browser's and leaves fullscreen (`global.js` ignores
  it while `document.fullscreenElement` is set; the rest of the order: **Modals**); a second Escape closes Asset detail.
  Every close path leaves fullscreen first (`releaseActiveHost`).
- The top layer stacks in order of entry, so a popover shown before fullscreen sits under the host: the snackbar's stacks,
  outside the host, are re-shown on entering (`raiseSnackbarStacks`); Asset detail's own popovers live inside the host.

**Detail panel** — Asset detail's docked panel holding the asset's People and its Details. The fragment
`htmx/view_image_detail_modal` (`#imageDetailView`, `static/css/htmx/view_image_detail_modal.css`) is a grid like the
main page: the media stage (`#imageDetailStage`, a size container, so the media resize with the panel), the divider and
`aside#detailPanel` (header with the Dock side menu, the scrolling `#detailPanelContent`, and the key legend
`footer.panel-keys`, which stays in view). The legend's `\` entry shows on an edited asset alone, its fullscreen entry
(`data-app-fullscreen-key`) goes with the toolbar's toggle where `document.fullscreenEnabled` is false, and Escape is
not listed, since what it does depends on what is open.

- Dock: the **Dock side** and share are written on the fragment (`data-detail-dock-side`, `--detail-panel-share`), from
  which the stylesheet lays out the grid; closed (no `data-app-detail-panel-open`) the stage is the whole grid.
  `js/common/dock.js` (the explorer's factory too, **Explorer dock**) owns side, share, divider and remembered state;
  `js/search-results/detail-panel.js` (`app.detailPanel`) configures it. The panel docks right by default and, unlike
  the explorer, never follows the viewport's orientation. Its menu's picks carry `data-app-detail-dock-side`, the
  panel's own attribute, because the explorer's dock listens for its picks on the same body. `fragments/image-detail.js`
  calls `attach` per fragment, which builds the menu's control first so the dock finds its trigger.
- For a holder of Edit images the Edit panel docks opposite (how it follows: **The dock** under **Edit panel**), and
  `view_image_detail_modal.css` has a rule per side for each panel alone and both open. The Detail panel lays out
  again (`layOut`) when the Edit panel opens or closes, and each panel's bounds leave room for the other and its
  divider (`panelMinimums`).

`m` toggles the panel and `p` the Face highlights (`global.js`, `Const.events.toggleDetailPanel` and
`toggleFaceHighlights`, wherever focus is except in a text field, ignoring a held key or any modifier), as do the
toolbar's toggles (`data-app-detail-panel-toggle`, `data-app-face-highlights-toggle`), whose `aria-pressed` the module
keeps. The player leaves `f` and `m` to Asset detail (its `hotkeys`, **Detail navigation**).

Loading: the sections (`htmx/detail_panel`, `GET /htmx/asset/r/:repoId/detail-panel/:assetId`) load only while the panel
is open or the highlights are on (`wantsFragment`, the one place that says so), on open, on each step, and when either
is switched on with the details not loaded. The coordinator (`detail-navigator.js`) names the asset through `showAsset`
with its request's `isCurrent`, so a late answer after a step or close is dropped, and the host empties as another asset
starts loading, so no asset's details read as another's. A step calls `showAsset` twice: at its start with `load: false`
(empties, loads nothing), and once the asset's metadata is in, which loads; a failed asset fetch is one error snackbar
and no panel request.

Sections (`static/css/htmx/detail_panel.css`, styled by class so the style guide shows them):

- People (`htmx/detail_panel_people`): one row per Face, its button `aria-pressed` while selected; the row's data
  attributes are what the highlights and the corrections read. Read-only (`data-app-read-only`) for an asset in the
  trash and for an account without Edit metadata.
- Details: every link (Folder, album, Location) opens its search in a new tab, so Asset detail stays open. The Curation
  row (Curate Permission, a set to open by `CurationService.setSizeOf`) is the exception: a plain click opens Curation
  over Asset detail, which comes back when it closes (**Curation**).
- The dimensions row is the shown element's size, which the coordinator hands over, not the stored one, so a rotated
  phone photo reads as shown. For a still image the Edit's stage hands over the upright size and the Edit it shows, if
  that changes a pixel (`setShownSize`), and the row adds "· edited w × h".

Remembered per browser under `Const.localStore.assetDetailView` as `{ panelOpen, highlightsOn, dock }`: closed and off
by default, written only by a toggle (an asset's URL overrides for its open, **Detail navigation**). The selection is
never remembered.

The **Face highlights** (`js/search-results/face-highlights.js`, which `detail-panel.js` tells what to draw) are placed
in `#faceHighlights`, a layer sharing the media's cell and box, by fractions of the shown element's natural size. That
works because the detector decodes an image upright by its EXIF orientation as a browser does, and a Video's frames are
turned as its player turns them: box space and shown space are one. While the stage shows an Edit, boxes go through
`geometry.mapBox`, and a Face whose center the crop leaves out is not drawn; under the Crop tool's whole frame none is
(`setShownSize`'s `highlights` false). The layer and the badges take no pointer events, so the player's control bar
under them stays usable; a badge never leaves the media (`@position-try`). With `p` on every Face shows, the selected
one emphasised; off, the selected one alone. On a Video a Face shows only while the player is paused within
`FRAME_TIME_TOLERANCE_MS` of its Frame time, so the layer redraws on `pause`, `play` and `seeked`.

The selection is one Face at most, held in `detail-panel.js` and never exported: the state the corrections act on. A
click on a row's button or a highlight's box selects its Face, a click on the selected one clears it, and a step, each
open and `p` clear it. On a Video a row's click seeks to the Frame time and pauses. A highlight's click also opens a
closed panel and scrolls the row into view.

The **Face corrections** (`docs/faces.md` **Corrections**) sit under the selected row (`.face-actions`, shown by the
row's `aria-pressed` in CSS, no script): Search, a new-tab link to the Person's results (with `triaged=true` for an
image in triage, so it is among them), then Change and Remove, which the server refuses too where the section is
read-only. `js/search-results/face-corrections.js` owns the clicks and all requests; `detail-panel.js` owns the
selection and what the answer changes on screen. Remove takes two clicks on the same Face, and a selection change
disarms it.

Change is the **Change combobox** (`x-data="faceCombobox"` on `.face-actions`, `js/alpine/components/face-combobox.js`,
core Alpine on the pattern of `docs/reference/alpine-components/headless-combobox.md`). Its listbox is a manual popover
in the top layer, so the panel's scrolling never clips it, anchored under the input by an anchor name per Face.

- Options load once per opening from `GET /htmx/people/r/:repoId/assignable?faceId=…`, built only by
  `assignablePeopleUrl`. The server (`PersonService.assignablePeople`) is their one source, leaving the Face's own
  Person out; the component is their one renderer and filters them on the client.
- Focus waits for the draw (`untilShown`): `x-show` shows the combobox a frame later (a timeout in a hidden tab), and
  focusing the still-hidden input fails, the hidden Change button loses focus to the body, and that `focusout` closes
  the listbox.
- Matching is by substring under `partialMatchKey` (the server's fold, `nameKey` mirroring `NameKey.of`, with ς read as
  σ) on both sides: the fold ends a word with ς, so "σοφοσ", typed on the way to "Σοφοσάκης", would otherwise miss.
  Exact-name equality is `nameKey`'s.
- The **Suggested** group (up to 3 people nearest the Face's vector) shows as a labelled ARIA group above the list while
  nothing is typed, its people absent from the list; from the first character they are filtered with the rest, ahead of
  them. Non-option lines are `role="presentation"`, so the listbox holds options alone.
- A name no option has, within `Const.people.nameLength`, is the Create row; an existing name in any case or Unicode
  form picks that Person. The Face's own Person is no option, so their name is offered as Create too and the server
  refuses it (400).

Two modes, by the row's Person (`rowPerson`). A named Person's Face: every pick moves this Face only. A nameless
Person's Face (Hidden or not): a pick acts on the whole Person (`wholePerson`) unless the "This face only" switch is on
(`faceOnly`, off at every opening, never remembered). In that mode a named option merges the Person into it, the Create
row names the Person (no confirmation: another name undoes it), and a nameless option still moves this Face only, since
two nameless people are never merged from here; each option's suffix says which. Merging takes Organize (`mergesInto`):
without it a named option moves this Face only. The switch sits inside the component's root, so Tab reaches it with the
listbox open, and a pointer press on it or its label keeps focus in the input so Enter still picks (the `.switch`
exception under **Forms**).

A merge cannot be undone from the panel, so it takes two picks, as Remove takes two clicks: the first arms the option
(`armedKey`, its label the `armedLabel` question), the second merges. Another active option, typing, the switch, the
listbox closing and `reset` disarm it. A text change under `aria-activedescendant` is not announced reliably, so a
visually hidden `role="status"` says `armedLabel`.

A pick is `Const.events.facePersonPicked` with `faceId` and an `action` (`move`, `name` or `merge`), which the
corrections module sends to `PUT /htmx/asset/r/:repoId/face/:faceId/person`, `…/person/name` or `…/person/merge`. The
last two carry the row's Person as `personId`, which the server checks, since the Face may have moved since the section
was rendered. All go through one `send`: one request at a time per Face. The X announces
`Const.events.faceChangeCancelled`, and the panel clears the selection, focus to the row.

Escape (the order: **Modals**): an open listbox closes alone (`closeOpenFaceCombobox`), and a later Escape clears the
selected Face (`clearSelectedFaceRow`). `m` and `p` in the input are the field's.

The answer: a correction returns the People section (200), which `afterEdit` swaps in whole: the selection clears, the
highlights redraw, the root's result message is a success snackbar, `peopleChanged` reloads the People tab and the
Triage summary, and focus goes to the first row. A merge's root names both Persons (`parsePeopleSection`), while who was
named comes from the request (`send`'s `outcome`), since the section does not say. A 400 is the server's message and
changes nothing; a 404 or 409 (the Face gone or moved, the destination not live, the asset recycled, the Person named
meanwhile) means the section is stale, and `reloadSection` loads it again. An answer for an asset no longer shown leaves
the panel alone but still acts on the grid.

The viewed Person (`searchParams.viewedPersonId()`: the one Person the search names; a search of several people has
none, and its grid waits for the next search). The grid follows a correction only when it concerns them, and never under
the open asset: `noteViewedPerson` marks the asset leaving when they have no Face left on it (a later correction giving
one back keeps it, unless they were deleted), and `flushLeaving` acts once a step has picked another asset or on closing
(an `Alpine.effect` over the active modal host). Then the asset's cells leave through
`assetActions.removeAssetsFromGrid` and the title is read again (`reloadPersonTitle`, **Results title**); a deleted
viewed Person drops out of the search instead (`replaceId`, other filters kept). The viewed
Person named has the title read at once; a Person named whose chip is in a filtered search's title (`showsPersonChip`)
has the search run again on closing. The viewed Person merged away leaves every cell the destination's, so the cells
stay, the destination (`mergedInto`) stands in for them in later corrections, and on closing alone (not on a step) it
takes their place in the search; a merge into the viewed Person takes the same path, so the grid gains the merged
Person's assets. Naming or merging any other Person changes nothing in the grid.

The style guide's Panels & layout section holds specimens to keep in sync: a highlight with its badge (selected,
Hidden), the actions under a selected row, the combobox with its listbox, and a nameless Person's combobox with the
switch, an armed option, a merge option and a "· this face" one.

**Edit panel** — Asset detail's second docked panel, where a holder of Edit images changes a still image's Edit
(glossary: Edit panel, Edit, Crop tool, Level tool, Display image, Show original). The server's side is **Image edits**
in `altitude/AGENTS.md`; the Edit's geometry and math, the two renderers, their parity and the crop's rules are
[docs/image-edits.md](../../docs/image-edits.md). The panel (`includes/edit_panel`), its divider, the tools over the
stage (`includes/edit_stage_tools`) and `#editClipping` are in the fragment for a holder of Edit images alone;
`#editCanvas` is there for every viewer, since the stage draws an edited asset whose render is pending.
`js/edit/edit-panel.js` (`createEditPanel`, `app.editPanel`) is the coordinator, the single owner of the Edit shown,
each asset's session, the panel's state and dock, the stage while the panel draws, the Crop and Level tools, the stage's
bar and the keys' handlers. The `editPanel` Alpine component (`js/alpine/components/edit-panel.js`) holds the controls
alone: it connects as it starts (`connectEditPanelView`), the coordinator pushes a snapshot into it on every change
(`sync`), and it asks the coordinator for every change, computed from the Edit shown by `js/edit/adjust.js` (pure
functions of an Edit and the upright size). The Edit and the sessions stay in the coordinator's closure, out of the
reactive object, as Curation keeps its heavy data; the tools over the stage, outside the component, are the
coordinator's and `crop-tool.js`'s.

- **Open or closed.** `e` and the toolbar's toggle (`data-app-edit-panel-toggle`, `includes/html_common`) open and close
  it; the state is remembered per browser (`editOpen` in `Const.localStore.assetDetailView`) and written as
  `data-app-edit-panel-open`, from which the stylesheet lays out the grid. On an asset no Edit may change (the server's
  `editRefusal`, an original the canvas cannot draw, no WebGL2) the toggle is `aria-disabled`, not `disabled`, so a
  keyboard or a screen reader still reaches the reason in its title; `toggle` ignores its press, and `e` shows the
  reason in the snackbar. Open on such an asset, the panel says why and draws nothing, and the toggle still closes it.
  Closing with a change pending asks first (**The warning**); closing with focus inside moves focus to the toggle.
- **The dock.** A third `common/dock.js` dock (**Explorer dock**) with no Dock side of its own: `followSide` puts it
  opposite the Detail panel on every layout, and the Detail panel's `onLayoutChange` lays it out again when that panel
  moves, opens or closes. It remembers its shares as `editDock`. Its bounds (`panelMinimums`) are
  `--edit-panel-min-width` and `-height` plus what the stage, the Detail panel and its divider need; both tokens are
  `@property` lengths in `core.css`, so their computed value reads as px.
- **Layout.** Header, a scrolling body (the Histogram, the tabs, the shown section), the commit row and the key legend;
  docked top or bottom, `edit_panel.css` rearranges it into columns and drops the legend.
- **Tabs and sections.** `recipe.js`'s `SECTIONS`, an ARIA tablist. The tab shown is module state kept for the page's
  life, so each asset opens on the last one.
- **Sliders.** `.edit-slider` (**Forms**), rendered from `recipe.js`'s `SLIDERS`; `formatSlider` is the one formatter of
  a slider's value, and the component writes the fill and a gradient track inline (`sliderStyle`). When a change counts:
  - a drag is shown at once and is one change on release; each of its values applies to the Edit the drag started from
    (`adjust.sliderDrag`), so a crop that shrank on the way grows back as Straighten returns;
  - the range's keys and a click on its track are one change once they rest (`commitSoon`: 400 ms for keys, so a held
    key is one change; 300 ms for a click, so a double-click's reset makes no step between);
  - Enter in the number field is one change, and Escape or leaving the field puts its value back; a double-click on the
    label or the track resets the slider.
- **The crop's rules** (`adjust.js` over `geometry.js`) are under **The crop's rules** in
  [docs/image-edits.md](../../docs/image-edits.md). The aspect chips (`.toggle-group.edit-aspects`) are labelled in the
  lock's orientation (2:3 while it is portrait).
- **The tone curve.** A canvas (`.edit-curve`, `role="application"`) with the luminance Histogram behind it. It takes
  its colors from its CSS rule (`color`, `border-color`, `outline-color`), so the script writes none. A pointer gesture
  is one change on release, the keys one once they rest; the gestures and keys are the component's, their distances
  `adjust.js`'s.
- **The Histogram and the Clipping blink.** Curation's markup and drawing (`includes/histogram`, `common/histogram.js`'s
  `channelPaths`, see **Curation**) over the bins `renderer.readHistogram` reads from each drawing. The whole Histogram
  is the Clipping blink's switch (`aria-pressed`, off on each new fragment). 150 ms after the drawings rest,
  `common/clipping.js`'s `readClipping` reads the canvas at the import measurement's levels and box, which the fragment
  carries as `data-*`: the crushed and blown fractions always, and while the blink is on the mask on `#editClipping`,
  colored from its CSS rule (`color` blown, `border-color` crushed), blinking at 1 Hz, steady under reduced motion.
  Nothing is read while Before/after or the Crop tool puts more than the Edit's result on the canvas.
- **The header.** Undo, Redo, Before/after and Reset all (one change). Before/after (`#editSplit`: Before, the geometry
  alone, `renderer.renderBefore`, left of `#editSplitHandle`) is off while the Crop or Level tool is up.
- **The session** (`edit-session.js`, one per asset, DOM-free: the coordinator supplies the requests; its header is the
  full contract).
  - **Every change is held**: shown at once and saved by nothing (`commit`), the Crop tool's gestures and the Level
    tool's line included. Changes are held while the Edit shown is not the one saved (`pending`) or a change waits out
    its delay (the coordinator's `held`).
  - **Done** (the stage's bar, Enter) saves the Edit shown as one change (`PUT /api/edit/r/:repo/asset/:id`), queued
    behind the save on its way, its `base` the edit version the previous write answered; Dones waiting behind are one
    save, of the latest. **Cancel** (the bar, Escape through **The warning**) shows the saved Edit again.
  - **Undo and Redo** step through the changes held, each step itself held, so Revert to original is undone the same
    way. Done folds what it saves into one step; Cancel puts Undo back where it was at the last Done. The stacks are
    forgotten (`clearUndo`) when the panel closes, Asset detail closes or steps away, and on a conflict or a failure.
  - The last write's answer is adopted whole, its Edit shown unless changes are held over it. A 409 drops the save
    waiting and the changes held and adopts the state it carries; a failed save drops them too and reads the state
    again; a failed read changes nothing shown. The commit row's status is `SaveStatus`, replaced by the
    `.notice.edit-held` notice while changes are held.
  - A session still saving when Asset detail steps away finishes in the background, and stepping back picks it up. An
    idle one is dropped (`forgetWhenIdle`) unless it still follows a render, so the next visit reads the Edit afresh.
  - Every state a session adopts (`onAdopted`, the coordinator's `adopted`) becomes the asset's `latestEdit` unless a
    newer one is known, and the stage takes it at once (the badge, Show original's toggle, the source while the panel
    does not draw the asset), so a closed panel never leaves the stage on an Edit the server has moved on from. An
    unrendered state's render is followed once per edit version (`render-status.js`); then the grid's cells get the new
    Preview and, with the panel closed, the stage the Display image. An Edit Asset detail brings (its fragment, a step's
    JSON) replaces `latestEdit` when as new, and an older one gives way to it once shown (`isNewerEditState`).
- **The stage's bar** (`#editBar`, `syncBar`). Shown while changes are held or the Crop tool is open: the Crop tool's
  guide and crop size while it is open, Cancel, the count of edits pending and Done. The count is the session's
  `heldCount` (the steps of Undo since the last Done, an Undo past the Done counting as one) plus one for a change on
  its way (a drag, a key waiting out its delay).
- **The warning** (`js/edit/leave-dialog.js`, `dialog#editLeaveDialog`). A change is pending while changes are held or a
  save is waiting or on its way (`hasPending`). Then closing Asset detail, stepping to another asset (`beforeLeave`,
  which `detail-navigator.js` awaits), closing the panel, and Escape with changes held ask first.
  - Asset detail's close goes through the guard the coordinator registers with `modal.js`'s `setCloseGuard`, which every
    `closeModal` asks (Escape, the ×, the backdrop, Back). The guard answers a promise of whether Asset detail closed
    (`takeHeldClose`; a Back answered by staying: **Detail navigation**).
  - A save on its way alone is waited for up to `SAVE_SETTLE_MS` and asks nothing once answered.
  - It is a native modal `<dialog>` inside Asset detail's fragment, so it stacks over Asset detail, the one active host,
    rather than replacing it as an open of another host would.
  - Save (the initial focus: never the destructive choice) is Done. Discard is Cancel when asked about changes held (a
    save on its way, unmentioned, still finishes), and otherwise the session's `discard`, which takes back a save on its
    way. Keep editing and Escape stay. A change waiting out its delay stays undecided while the warning is open. A leave
    asked for while one is asked about stays (`beforeLeave`, `decideLeave`).
  - A close nothing asks about (another dialog's open replacing Asset detail) saves the changes held as Done.
  - Leaving the page with a change pending, or a save another asset's session has on its way, has the browser ask
    (`beforeunload`); leaving anyway saves nothing: Done is the only way changes are saved. A page shown again from the
    browser's cache (`pageshow`, `persisted`) has every session read the Edit again.
- **The stage** (`js/edit/edit-stage.js`, `app.editStage`; its header lists the sources: the original, the Display
  image, or the canvas drawn by `renderer.js`, which every asset gets while the panel is open). The `<img>`, both
  canvases, `#faceHighlights` and the Edit's tools share the media box's one grid cell.
  - The canvas behind the `<img>`: the canvas, like the server's renderer, takes the original's values as sRGB, while
    the browser color-manages an `<img>`, so a wide-gamut photo looks less saturated on the canvas. So while the panel's
    Edit is the identity and no change is on its way, outside the Crop tool and Before/after, the canvas draws hidden
    behind the original's `<img>` (`isBehindImage`, `data-app-edit-canvas-behind`, `visibility: hidden` so it keeps its
    box): an unchanged asset looks as it does with the panel closed, and the Histogram and the Clipping blink still read
    the canvas.
  - A decoded original a quarter turn from the server's upright size (`uprightWidth`, `uprightHeight`: the browser
    applied, or skipped, an EXIF turn the server did not) falls back to the original with a console warning, and the
    Edit panel refuses it: every Edit's geometry is in the server's upright original. It is checked before every draw,
    and again when the first Edit carrying that size arrives (the panel's read of an asset never edited).
  - Whatever is shown, the box is fitted to it (`setAssetDetailSize`) and the Detail panel is handed the upright size
    with the Edit shown (`setShownSize`, **Detail panel**): the decoded original's, or, for the Display image, the
    server's upright size, since the stored size is the file header's. Every presentation has a token, so one overtaken
    changes nothing, and `settled` waits for the latest.
- **Show original** (`edit-stage.js`'s header), on an edited asset, for every viewer, Guests included: `\` held, or the
  toolbar's toggle. `global.js` announces the key's press and release (`Const.events.showOriginalHeld`); the release
  counts wherever focus has gone and whatever modifier is held by then, and the window's `blur` releases it too, so it
  never sticks. The press is whichever key types `\` on the layout (with Shift, Option or AltGr where needed), never
  with Cmd or a plain Ctrl.
- **The Crop tool** (`js/edit/crop-tool.js`; its header covers the gestures), a view of the changes held: `c` or the
  Crop button opens and closes it, saving and ending nothing, and the fragment carries `data-app-crop-tool-open`
  meanwhile. The canvas draws the whole warped frame, with the effects and the Histogram sized from and read over the
  crop (`geometry.effectRegion`), so nothing jumps as it closes. The gestures are vendored interact.js, which restricts
  to upright rectangles alone, so each listener turns the rectangle it proposes into frame fractions and, while the Edit
  keeps the crop inside, stops it at the outline (`geometry.limitTransition`) before the Edit takes it. Each gesture is
  one change at its end, the arrows' nudges one once they rest. Undo and Redo work in it.
- **The Level tool** (the Level (draw line) button). A drag on `#cropLayer` draws `#levelLine`; the release sets
  Straighten (`adjust.levelStraighten`) as one change and turns the tool off, and a line under `LEVEL_MIN_LENGTH` is a
  click. With the Crop tool open it works in it; with it closed the layer covers the stage alone (`data-level-only`).
  Escape, the button, Done, Cancel or closing the Crop tool turn it off.
- **Auto-level** asks the server (`GET …/auto-straighten?recipe=` with the Edit shown) and sets Straighten to its angle
  as one change; a null `angle` leaves Straighten and says so in the snackbar, and a 503 (every auto-straighten slot
  taken) or a 400 (an original it cannot read) shows the server's error.
- **Keys.** In Asset detail and never in a text field (`isTextEditingTarget`, which counts no slider, checkbox or
  button-like input, so the keys work on the panel's controls). `global.js` reads them (its header lists them) and
  dispatches a `Const.events` event that `listeners/edit.js` hands to the coordinator. It reaches the warning, the Crop
  tool and the changes held only through `common/edit-keys.js`, whose hooks the coordinator registers as it starts, so a
  page without Asset detail loads no edit module. Enter is Done while changes are held, unless focus is on a button, a
  link or a `summary`, which Enter presses.
  - Escape in Asset detail closes the warning alone (Keep editing), then the panel's own (`escapeEdit`): the Level tool
    off, else the warning about the changes held, else the Crop tool closed; all of it ahead of a selected Face and of
    closing Asset detail.
  - The arrows and Space are left to focus in `#editPanel`, `#editPanelDivider`, `#editSplitHandle`, `#cropLayer` and
    `#editBar` (`isInEditTools`), as in the Detail panel, so a slider steps and the crop nudges where Asset detail would
    step.

The style guide's specimens of the Edit panel are listed under **Style guide**.

**Curation** — The modal where the user keeps the best frames of a Curation set and recycles the rest. Part 2 of
`docs/curation-batch-detection.md` is the UX narrative (layout, copy, the key table, the walk's moves, Undo, the grid
after closing). What sorts a set and what its outcome writes are under **Sequences and Image quality** in
`altitude/AGENTS.md`; the terms are the glossary's. This topic holds the client's contracts and traps. Curation has no
page or nav entry, so the nav keeps marking the view it opened from, and a reload shows the grid.

Every way in dispatches `Const.events.curateRequested` with its entry. `js/listeners/curation.js` names the entry
(`requestCuration`) and loads a new shell through `htmx.ajax` on every open, so each open starts with every row open and
no undo entry. `js/fragments/curation.js` opens `ModalHost.curation` with focus on `#curation`, whose `tabindex="-1"`
keeps the keys from pressing a control by accident.

- The queue: Triage's `#curateSequences`, with no detail (see **Results title**).
- One Sequence, `{ sequenceId }`: a Sequence's group header (see **Group headers**) or the Detail panel's Curation row
  (see **Detail panel**). A plain click on the row's link (`data-app-curate-sequence`) adds `resumeDetail`, Asset
  detail's asset and cell. On close, unless another modal has taken over, Asset detail reopens through that cell, or,
  when Curation recycled the asset, through the nearest cell left (`detailAfterCuration`), so stepping goes on from
  there. A modifier or middle click opens the link's new tab, Triage with `?curate=<sequenceId>`:
  `openCurationInLocation` answers it on load, after `runSearch()` starts, only for a user with a Curate Permission, and
  the first search's replaced URL drops the parameter.
- A selection, `{ assetIds }`: the batch ops' Curate, through `Const.events.batchCurateRequested` and its cap (see
  **Batch ops**). The component posts the IDs, so they never sit in a URL.

- **The shell** (`htmx/curation.scala.html`) is a header over `#curationMain` and the Sequence rail. `#curationMain`
  holds `#curationSet`, which each set's fragment goes in, and the four states with no set (`.curation-no-set`): too few
  frames left (`curation_set(None)`'s words, for a selection's answer and a Sequence's 204 alike), the report of a
  resolve, the unread state, and the queue's end.
- **The Sequence rail** (`aside#curationSequenceRail`, the queue alone) is outside `#curationSet`, so it outlives each
  set's fragment and stays through the states with no set.
  - `loadQueueRows` reads `htmx/curation_queue.scala.html` through the axios client into `#curationQueueHost` and takes
    `queueIds` from each row's `data-rail-sequence-id` (`Const.attributes.railSequenceId`) in document order. Never key
    the rail on `data-app-sequence-id`: that marks a group header's Curate button, which `removeCurateButtons` removes.
  - A row has no Alpine binding. `paintRail` (a `$watch` on `position`, and after the rows load) writes `.current` with
    `aria-current` and `.done` (from `doneIds`) directly, so a long queue costs nothing per move. One delegated listener
    on the host (`onQueueRowClick`) moves the walk to an open row.
  - `toggleSequenceRail` (Q and the header's button, remembered) does nothing off the queue, and hiding the rail
    changes nothing about the walk. Showing it repaints on `$nextTick`, since a hidden row cannot be scrolled into view.
- **The state** is `x-data="curation"` on the shell's root (`alpine/components/curation.js`), so it outlives each
  fragment.
  - The component renders nothing: `showSet` reads a set's frames from the rail items' `data-*`, and the fragment's
    bindings read the state back. What no binding reads (the Clipping reads, the Histogram paths, the Loupe, the
    remembered views, the move token) is closure state, not component data.
  - `replaceSetHost` destroys the old fragment's Alpine trees (`Alpine.destroyTree`) before it swaps or empties the
    host, so no loop of the old fragment runs on the new set's state. Keep that order.
  - Exit and Esc close Curation through `closeModal` (Esc is `global.js`'s); a backdrop click does not. The component
    watches `$store.modal.activeHost`, announces `Const.events.curationClosed` and empties the host, so the originals
    stop loading. An action still running announces when it finishes (`exclusively`'s `finally`).
  - Actions run one at a time (`exclusively`), so a key pressed during one does nothing. A failed write goes to the
    snackbar and leaves the set on show, since nothing was saved. Every action is saved as it happens, so closing loses
    only the undo entry.
- **A failed read** of a set or of the queue (`reading`), on open or after an outcome, goes to the snackbar and shows
  the unread state, whose Try again runs that read again (`retryRead`). No set stays on show past a failed read, so a
  set whose outcome was saved cannot be acted on twice. The walk's position and the undo entry stay, and on the queue
  the rail and the moves still work, so a Sequence that will not read cannot block the rest.
- **The Bundle rail** (`#curationRail`, `htmx/curation_set.scala.html`) lists the notices, the Contenders, then each
  Bundle in the rule's order. A frame's `data-problems` is what the stage's tag shows, its Bundle first and then its
  chips; a Contender has neither `data-bundle-kind` nor `data-problems`. A Bundle's Recycle (`recycleBundle`, R) leaves
  its Picks in place. The details switch is per Bundle kind, not per set (`detailKinds`, remembered; the keys are under
  **Alpine.js Stores**), so a kind opened in one set is open in the next.
- **The stage** (`#curationStage`) is the pinned frame on the left and the focused frame on the right (`leftFrameId`,
  `rightFrameId`). With nothing pinned, or the pinned frame focused, it is one pane, the focused frame, which counts as
  the left frame. Pinning keeps nothing: Resolve keeps the Picks, or with none the one frame on the stage.
  - The server chooses the opening pair (`CurationSet.openingFrames`, on the set root's `data-opening-pinned-id` and
    `data-opening-focused-id`), so every set opens on two frames and Enter keeps nothing until there is a Pick. A set
    read again after a recycling or its Undo gets a `view` (`currentView`: pinned, focused, Picks, zoom), which
    `showSet` keeps as far as its frames are still in the set.
  - A pane reuses its `<img>`, and the browser paints the old picture until the new one is in. So from a frame change
    until `load` or `error` (`isPaneLoading`, `settledSrcs`) the image is hidden behind `.media-spinner`, and a pane
    never shows a frame under another's name. The zoom is applied on `load`, before the image shows. `preloadOnly`
    preloads the focused frame's neighbours and stops stale preloads, so a held arrow does not queue originals ahead of
    the frame on show.
  - The pane's tag (`.pane-tag`) is in `.pane`, not on the image, so a zoom's transform leaves it in place. Both panes
    carry the bottom hairline, transparent on the unfocused one, so the images keep the same height as focus moves.
  - Pinning (`pinFocused`) replaces the pinned frame and moves focus to the next frame in the rail's order, or the
    previous at the end, so two frames stay on the stage. There is no unpinning by design: it would lose the comparison
    with no way back. Focusing the pinned frame shows it alone.
  - A pane's Recycle (`recycleFrame`) goes through `recycleFrames`, as a Bundle's does: the grid's own endpoint, then a
    read of the set again in `viewWithout(frameId)`. Focus moves to the next frame, never to the pinned one, and of two
    frames on the stage the one left becomes the pinned frame, so the frame coming in is on the right. Undo puts the
    frame back beside the one it was recycled from (the undo entry's `stage`).
  - The Loupe is `js/common/loupe.js` (`attachLoupe`, answering `{ refresh, detach }`), which knows nothing of Curation.
    It maps the pointer through the image's bounding rect, which includes the zoom's transform, so it works on a zoomed
    stage, and draws with the image's `currentSrc` as the lens's background, so nothing is fetched. It writes only the
    lens's `data-level`, inline styles and `hidden`; `curation_set.css` has the rest. `watchStage` attaches it with the
    stage's `ResizeObserver`, `applyZoom` and `onImageSettled` (on `$nextTick`) refresh it, and `detachLoupe` detaches
    it on the next set and on close. The level is one value for every frame (`loupeLevel`, a rung of `LOUPE_LEVELS`, 0.5
    to 4 by halves, the first rung by default), remembered; the wheel over a pane is its only control.
  - The Histogram strip is `includes/histogram` (styled by `static/css/includes/histogram.css`), the one markup the Edit
    panel and the style guide's specimen render too. Its parameters are Alpine expressions of the host component. The
    include binds `clippingBlink` and `toggleClippingBlink()` itself, so every component that renders it must provide
    both. No color is in the script: the strip draws from `--histogram-*` tokens.
  - The strip's data is the frame's, measured at import (`ImageMeasurement`), so it shows while the image loads. The
    rail item carries `data-histogram`, the Base64 of `Histogram.relativeBytes`: a byte per bin, 64 bins a channel in
    `HISTOGRAM_CHANNELS` order (luminance, red, green, blue), each bin over the tallest of all four channels
    (`HISTOGRAM_TALLEST`, 255), so they share one scale. It also carries `data-crushed-shadows` and
    `data-clipped-highlights`, the corners' stored fractions. A frame not measured carries none of the three and gets
    `NO_PATHS`. `histogramOf` decodes a frame's paths through `common/histogram.js`'s `channelPaths` (shared with the
    Edit panel) the first time it is on the stage, cached by frame ID and cleared with the set.
  - H (`toggleHistogram`, `showsHistogram`, remembered) does nothing with no set on show. Hiding the strips turns the
    Clipping blink off, since the strip is its only switch, and hands focus to the shell when a strip held it.
  - The Clipping blink (`clippingBlink`) is one switch for the stage, off on every open and whenever the strips hide,
    and never stored. Its mask is read in the browser only while it is on, by `js/common/clipping.js`'s `readClipping`
    (shared with the Edit panel), after `$nextTick` so the decode never holds up the pane's paint. The image is resized
    to the measurement box and grayed as OpenCV's `BGR2GRAY` does, so the marks are close to what the stored fractions
    counted, not the same to the pixel. The levels and the box come from the set root's `data-clipped-level`,
    `data-crushed-level` and `data-measurement-box` (`ImageMeasurement`'s constants), so the client restates none.
  - Reads are cached by `currentSrc` in `clippingReads` (the promise while in flight, so a second draw awaits it), a new
    map for each set, so a read that finishes after its set has gone fills only a dead cache. `drawClipping` paints only
    if the set, the pane's source and the blink still hold when the read is in. The canvas is laid out as the image is,
    and `applyZoom` writes the image's transform to both. Its colors are the canvas's computed `color` (blown) and
    `border-color` (crushed), read at each draw (`clippingColors`), so they follow the theme.
- **The face strip** (`#curationFaces`) is the main decision aid. A click on a crop zooms both panes to that Person's
  box, or back out (`zoomToPerson`, `applyZoom`, which runs again when an image loads or a pane resizes). A Face's box
  is in upright pixels while `asset.width` and `height` come from the file's header, so `zoomTransform` uses the
  displayed image's `naturalWidth` and `naturalHeight`.
- **The footer** and the pane captions are grids of `minmax(0, 1fr) auto minmax(max-content, 1fr)`, so the center stays
  at the true center and the right column never shrinks below its unwrapped buttons.
  - The center holds what `keepIds` answers: the Picks; with none and one frame on the stage, that frame; with none and
    two on the stage, nothing, so a frame on the stage is never recycled on a guess. With nothing to keep, the keep hint
    replaces Resolve, and Enter does nothing (`settle` refuses an empty list).
  - The album-or-Location note (`recycledInAlbumOrLocationNote`) is empty while nothing is to be kept, or it would count
    every frame. Its row shows whenever any frame of the set is in one (`hasFrameInAlbumOrLocation`) and holds one line
    while empty (`min-height: 1lh`), so a Pick does not shrink the panes mid-comparison; `contain: inline-size` keeps
    its text from widening the right column and moving the center.
- **The keys** are a `document` listener (`handleKey`) while Curation is the active host, so they keep working when the
  focused control leaves with a fragment. The key table is in Part 2 of `docs/curation-batch-detection.md` and in
  `handleKey`'s comment.
  - They are ignored in a text field (`isTextEditingTarget`) or with Ctrl, Cmd or Alt held.
  - Shift+1 and Shift+2 are told by `event.code` (`recycleKeyOf`), since the symbol Shift types differs by layout. On
    AZERTY, where the digits are on the shifted level, that press types the digit and stays the Pick's key; only the
    button recycles there.
  - Only the arrows repeat when held, so a held key cannot resolve set after set or run through the queue. `handleKey`
    calls `preventDefault` on the arrows even while a set is resolved or read, so ↑ and ↓ never scroll the rail.
  - Enter or Space on a focused button or link presses that control. A pointer click hands focus back to the shell
    (`refocusAfterPointer`), so Enter resolves next.

- **The walk.** On the queue, opening reads the rows into the Sequence rail (`startQueue`) and requests one set's
  fragment at a time. A row is **open** or **done** (code and guide words, never shown). A row is done (`markDone`) once
  its set is resolved or kept, a Keep all that changed nothing included, or once its Sequence answers 204 when reached.
  A done row stays in place and the walk passes over it. Undo of an outcome opens its row again, and Start again opens
  every row (`doneIds` cleared), so a Sequence over the window, done for this walk, is offered again.
  - Every move goes to an open row (`openRowFrom`, `readQueueFrom`). A row that answers 204 becomes done and the move
    goes on in its direction. Only the move after an outcome (`nextSet`, `readSet`) wraps to the top; an N, P or click
    that finds nothing further stays on the set on show and its row.
  - The end state comes only when every row is done: the count is read again and no row is current (`position` -1).
    `preloadNextSet` preloads the opening frames of the next open row in the direction of the last move.

  **Latest move wins.** N, P and a row click are moves (`move`): the walk stands on the target row at once, so the
  rail's highlight moves before the read answers. Each queue read takes a token (`moveToken`), and `nextMoveToken`
  aborts the previous read (`AbortController`). An overtaken read drops its answer, shows nothing and reports nothing,
  so the last move is the set that opens, and no dead read holds a write up. The writes (Resolve, Keep all, Recycle,
  Undo, Try again, Start again) run through `exclusively`, which refuses to start while a move reads (`movesInFlight`);
  no move starts while a write runs (`isBusy`).

  **Remembered views.** Leaving a set by a move, or by an Undo that reads another set, saves `currentView()` under its
  Sequence (`rememberShownView`, `rememberedViews`): once as the move starts, so a failed read loses nothing, and again
  as the new set replaces it. The walk restores it on return, while Curation stays open. A done row's set is never
  remembered, a row going done forgets its view, and Start again forgets them all once the rows are in.

  A selection or a Sequence opened alone (`!isQueue`) has no rows and no next set: a Keep all that changes nothing
  closes Curation, a Resolve or a Keep all that changes something stays open on the report, and a recycling that leaves
  too few frames shows the too-few state.
- **The outcomes.** Resolve and Keep all post to `/api/curation/r/:repoId/resolve` (`settle`). Recycling a Bundle or a
  frame is the grid's own `DELETE /api/asset/r/:repoId/move`, then a read of the set again.
  - Undo holds one entry: the last mutation, with the Sequence of the set it was made on (`shownSequenceId`). It reads
    that set again by its own ID wherever the walk has gone (`returnToSequence`), and the walk goes back to its row.
    When Start again has replaced the rows and the Sequence is in none, `loadQueueRows` reads them afresh, keeping the
    done marks of the rows still listed. The return and the read are one `reading`, so a failure in either shows the
    unread state and Try again runs both.
  - A Resolve or a Keep all is undone through `/api/curation/r/:repoId/reopen`, with what `resolve` answered
    (`sequenceOf`), and its set opens as a new one. A recycled Bundle or frame is undone through the grid's own `PUT
    /api/asset/r/:repoId/restore`, and its set keeps the view. `reportRestore` (`assets/asset-actions.js`) reports
    either answer. A Keep all that changed nothing leaves the undo entry as it was.
- **The grid beneath** keeps its scroll position, and its selection is reset on every close, resolved or not, since a
  selection left standing would make the next one additive. `Const.events.curationClosed` carries what the session left
  changed, net of its undos (`recycledIds`, `clearedSequenceIds`); with nothing changed, nothing else moves. Otherwise
  `js/listeners/curation.js` takes the frames still recycled out through `removeAssetsFromGrid`, as the grid's own
  Recycle does, and calls `removeCurateButtons` on the cleared Sequences. The map layout has no cells, so closing there
  runs the search again (the store's `showsMap()`, false for any search with a description, whatever layout the store
  keeps).
- **The counts.** `Const.events.curationChanged` follows each Resolve, Keep all, recycling and Undo, and runs
  `refreshCounts` (see **Nav refresh**), which includes the `sequencesToCurate` store. A move of the walk announces
  nothing. The store is also refreshed when the Triage results render (`js/fragments/search-results.js`), when Curation
  opens and when its queue runs out.
- **The styles** are `static/css/htmx/curation.css` (the host, which fills the window, the shell's columns and the
  Sequence rail's frame), `curation_set.css` (the set) and `curation_queue.css` (the rail's rows); their size tokens
  (`--curation-*`, `--histogram-height`) are in `core.css`. The style guide's Badges & states section holds the notice,
  the chip, a Bundle's row with its marks and the rail's rows, and its usage note covers the pane's tag and the keep
  hint, whose rules apply only inside the set (see **Style guide**).

**Metadata field visibility** — Fields default to `display:none`. Use `window.ctx.addGridMetadataField(name)` /
`removeGridMetadataField(name)`, which change the reactive `gridMetadataFields` set and persist it to `localStorage`. One
reactive effect per displayed grid (`js/search-results/metadata-visibility.js`) keeps one `show-<field>` class per
visible field on `#assets`, and `static/css/includes/search_results.css` shows the matching `.metadata > .<field>` rows:
no per-cell work, nothing to re-apply for appended pages. A new field needs its class added to both lists of rules there.

**Login page** — `login.scala.html` (`GET /login` and a refused `POST /login`, `SessionController`) is a plain form
post: no htmx, no page module, no nav; `header_common` gives it the remembered theme, `static/css/pages/login.css` its
look. "Email or username" takes either. A refused login answers 401 with the page again: one error, `LOGIN_FAILED`,
whatever refused it, so it never reveals whether the account exists or is deactivated; the login as typed, focus on the
password, the `redirect` kept, never the password. Server side: altitude/AGENTS.md **Login and the token**.

**Settings screen** — `settings.scala.html` (`/settings/r/:repoId`, Admin only) is a nav page as **Template Layout**
describes (`current` is `Const.NavEntry.SETTINGS`). Its module `js/settings-page.js` calls `startNavPage()`, registers
the save listener (`js/listeners/settings.js`: "Settings saved" on `SETTINGS_SAVED_EVENT`) and owns Reset. The routes,
the JSON parsing, validation, what a save writes, the restart notice and the `import.parallelism` note are the server's:
altitude/AGENTS.md **Settings**.

- `main` holds one form, `htmx/settings_form.scala.html` (`#settingsForm`), the page's and its save's answer alike. It
  swaps itself (`hx-target="this"`, `hx-swap="outerHTML settle:0"`), posts JSON (`hx-json-enc`) and declares
  `SETTINGS_SAVED_EVENT`. A failed validation is a validation replacement (**Event Flow**), so no success event fires.
- One section per Setting group. An input's `name` is the Setting's name, its `id` is `field-<name>`; a Flag is a
  checkbox with `value="true"`, anything else a text input.
- Reset (`data-app-reset-setting`) is one delegated click listener, so it survives the form's swap. It fills the field
  from the row's `data-setting-default` (`Const.attributes.settingDefault`), checks or unchecks a checkbox, and clears a
  text input's `error` class. Nothing is written until Save.
- CSS is `static/css/htmx/settings_form.css` alone (`.setting` is `display: contents`, so a section's labels align).

**Import page** — `pipeline.scala.html` (`/pipeline/r/:repoId`, for a holder of Import) is a nav page as **Template
Layout** describes. Its module `js/upload-form-page.js` registers `importUploads` and `watchFolderForm` before
`startNavPage()`, then the history's live updates and the Watch folders' re-reads. Server side: altitude/AGENTS.md
**Uploads**, **Import history and the status feed** and **Watch folders**.

Layout (`pages/pipeline.css`, scoped to `body.pipeline`): two columns, 5 : 7, `#importSources` (the upload sections,
`includes/upload_sections`, then `#watchFolders`) and `#importHistory`, wrapping sources first. `main` is
`min(100%, 100vw)` wide, so the columns stack by the window's width even under a nav wider than the window.

The upload sections are the `importUploads` component (`js/alpine/components/import-uploads.js`) on their root: File
upload and Folder upload, each a drop zone holding its picker button (the folder picker is `webkitdirectory`).

- For a holder of Organize, each section has a two-way `.toggle-group`: "To Triage", or "Skip Triage" / "Keep folder
  structure", which shows a destination folder field. That field is the advanced search panel's Folder field
  (`filterCombobox`, **Advanced search panel**) inside `.search-panel.destination` (`display: contents`); the component
  reads the folder from the field's `change`. The option on with no folder chosen shows an error under the
  field and uploads nothing. Without Organize there is no option and every upload goes to Triage.
- What is sent, under which path, is `js/import/chosen-files.js`. File upload sends every file under its bare name, the
  files of a dropped folder too. Folder upload sends each file under its path: a single folder, chosen or dropped, is
  the top, so its name is the Import's and no part of the paths; a drop of several items is its own top (each folder a
  folder of its name under the destination, the Import named "N folders"). Dropped directories are walked with
  `webkitGetAsEntry`, their entries read in batches until a batch is empty.
- The ignored names are the server's list (`IgnoredFiles`, on the root as `data-app-ignored-files`). A file or a
  directory under such a name, and an empty file, are left out before anything is sent. The one folder chosen, or
  dropped alone, is taken whatever its name: it is the folder the user named, as a Watch folder's directory is on the
  server. Of several dropped items each is held to the list.
- The root takes every `dragover` and `drop` of the window, so a file dropped beside a zone does not open in the tab.

One upload runs at a time (`js/import/upload-run.js`, the axios client, no htmx). It opens the Import
(`data-app-imports-url`) with `expectedFiles` (the ignored files already left out; the history counts these as
remaining), sends one request per file, three at a time, and closes the Import.

- No answer or a 5xx: two retries, after 1 s and 3 s, then the page has the server record the file as failed and goes
  on. A file answered as failed, or refused with a 400, is not sent again. Any other refusal ends the upload.
- Cancel stops the sending and closes the Import; leaving the page closes it by a `pagehide` beacon.
- While it runs, both sections take nothing (controls disabled, destination fields inert, zones `.waiting`). Its
  outcome is a `.notice` in its section (`.warning` when files failed or were not sent); a failure to start, and a
  refusal that ended it, go to the snackbar in the server's words.

The **Import history** (`htmx/import_history`, `static/css/htmx/import_history.css`; read again from
`GET /htmx/import/r/:repoId/history?shown=`) is a timeline of `li.import[data-import-id]`, "In progress" above
"Earlier". An entry is its header (title `includes/import_title`), its status (`includes/import_status`,
`static/css/includes/import_status.css`) and a `<details>` ("Files") hosting its files. Both includes take the Import as
a parameter and read no request context, since the status feed's actor renders them too.

- The status bar has one segment per outcome, its flex factor (`--files`) the outcome's file count; the remaining files
  are the bare track, striped while in progress. The remaining count (`Import.remaining`) includes the files an upload
  or a scan said it would deliver and has not yet, so the bar fills as files settle, not as they arrive.
- An outcome's color is declared once, by its class (`pages/pipeline.css`, under `#importHistory`: `--outcome-color` and
  its tint `--outcome-fill`), on whatever it marks; the bar's imported segment alone is the accent. `duplicate` reads
  "already in the Library" (`ImportOutcome.label`).
- "Show earlier Imports" and the files' "Show more" step up to `Const.ImportHistory`'s limits.
- An Import's files (`htmx/import_files`, `GET /htmx/import/r/:repoId/imports/:importId/files?outcome=&shown=`): an
  imported file links to its asset in a new tab, since leaving the page ends an upload.

`js/import-status.js` keeps the history live with no Alpine component (its module header has the detail):

- A feed message replaces an Import's status, and its title out of band. A status of an Import the page does not show
  is dropped and the history read again; so is the history after a status that says the Import closed (it moves to
  "Earlier") and when the feed opens again after it was lost. An Import whose files are shown has them read again with
  each of its statuses.
- Every re-readable part (the history's list, each Import's files, the Watch folders' cards) carries its route in
  `data-app-view-url` and is read through one request sequence per part (`readAgain` and `request`, `htmx.ajax`
  replacing the part): one request at a time. A control's `data-app-view` (filter chip, "Show more", "Show earlier
  Imports") names the view it asks for. What is asked for meanwhile is read once the request on its way has answered,
  so a view the user picked is never undone by a read that began before the click. A new reader of these parts goes
  through `readAgain`.
- The `<details>` are `hx-preserve`, so a re-read leaves an expanded Import open with its filter and files (moved, not
  rebuilt: its scroll state survives where the browser has `moveBefore`). The script scrolls the page by as much as the
  expanded Import in view moved, so it stays put on screen.
- The upload sections announce nothing to the history: the uploader's page learns of its own Import from the feed, as
  every other page does.

The **Watch folders** (`#watchFolders`; the cards are `htmx/watch_folders`, `static/css/htmx/watch_folders.css`, read
again from `GET /htmx/watch-folders/r/:repoId/list`) are a grid of cards, one per Watch folder. An Admin gets "Add Watch
folder" (`#addWatchFolderBtn`) and each card's actions; anyone else reads the cards alone.

- An Admin's card has "Scan now" (disabled when paused) and a ⋯ menu (`includes/menu_ctrl`, as on the Users page's
  account cards): Pause or Resume, Retry failed, Edit…, Remove…. Scan now, Pause, Resume and Retry failed are direct
  `PUT`s naming the Watch folder in JSON, `hx-swap="none"`, declaring `WATCH_FOLDERS_CHANGED_EVENT` with the snackbar's
  message. A refused scan (paused, destination in the Trash) is a 409, shown in the server's words.
- `js/import/watch-folders.js` reads the cards again, through `readAgain`, on `Const.events.watchFoldersChanged`, and
  shows its `detail.message` when there is one. An Admin's action on the page announces it with a message; the status
  feed's `{"watchFolders": true}` announces it with none, for any change the server tells of. The read replaces the
  cards, and with them an open ⋯ menu and the trigger an open popover dialog is anchored to, so it waits while either
  is open and looks again every second.

The **Watch folder dialog** (`htmx/watch_folder_form`, `static/css/htmx/watch_folder_form.css`; Add Watch folder and
Edit…, in the general modal host) has three fieldsets: Source, Destination (the "Keep folder structure" switch, then the
same destination field as the upload sections) and After import (the "Delete imported files" switch, then a red
`.notice.deletion`, `includes/watch_folder_deletion_notice`, naming the files in the folder now). The Watch folder
imports as the Admin who saves it.

- `watchFolderForm` (`js/alpine/components/watch-folder-form.js`) holds the two switches (`button[role="switch"]`
  beside a hidden input), starting from `data-app-keeps-structure` and `data-app-deletes-imported`. It asks
  `data-app-notice-url` for the notice when deletion is switched on and when the path changes while it is on, through
  the axios client, not htmx: an htmx request from inside a dialog is a dialog operation, whose success closes it. Only
  the latest asking's answer is shown. No switch saves.
- The form posts JSON (`POST …/add`, or `PUT …/edit` with the ID in a hidden input). A refused field comes back as the
  form with the values submitted; an edit's title names the Watch folder as stored. A save declares
  `WATCH_FOLDERS_CHANGED_EVENT`.
- Remove… is a popover dialog below the card's ⋯ (`htmx/remove_watch_folder_dialog`) saying what stays (the files on
  the server, what was imported); initial focus on the close control (**Modals**).
- Both dialogs return focus to `#addWatchFolderBtn`, which is outside the cards and survives their re-read.

**Users page** — `users.scala.html` (`/users/r/:repoId`, `UsersController`, `requireAccountCreator`, so it opens from
every Library the account sees) is a nav page as **Template Layout** describes (`current` is `Const.NavEntry.USERS`).
Its module `js/users-page.js` registers `usersPage`, `accountForm` and `passwordField` before `startNavPage()`. Styles:
`static/css/pages/users.css`. Routes, the ceiling, the start tab and every server rule: altitude/AGENTS.md
**The Users page**.

- `main#users` is the `usersPage` component (`js/alpine/components/users-page.js`): the tab and the filter text. Both
  live above `#accounts`, so a reload of the cards keeps them. Add User (`#addUserBtn`) requests the Add User modal into
  the general modal host.
- `htmx/user_accounts` (`#accounts`, `GET /htmx/users/r/:repoId/accounts`) is a `.toggle-group` of Admins, Users and
  Guests, then one section per tab (`x-show`). The first tab comes from `#accounts`' `data-app-start-tab`.
- The filter makes no request. Each card carries its name, username and email in `data-app-filter-text`, and
  `shows($el)` compares it under the server's name fold (`partialMatchKey`, `common/name-key.js`).
- After every change the page makes, the dialogs and menu entries declare `ACCOUNTS_CHANGED_EVENT`.
  `listeners/account.js` shows the server's message and swaps `#accounts` from `main`'s `data-app-accounts-url`.
- A card's Libraries are `includes/account_libraries`, shared with the account info dialog.
- An Admin sees a ⋯ menu per card (`includes/menu_ctrl`, `#accountMenuBtn-<id>` opening `#accountMenu-<id>`): Edit…
  (the general modal host); Set password… (a popover dialog, `htmx/set_password_dialog`); Deactivate or Reactivate, a
  direct `PUT` (`hx-json-enc`, the ID in `hx-vals`; a refusal is a 409 with the server's reason) that declares the
  success event itself and closes the menu (`handleActionClick`); Delete… (a popover dialog,
  `htmx/delete_account_dialog`, naming how many assets move to the Admin; a destructive `.action-button.error`, initial
  focus on the close control, **Modals**). The Admin's own card has neither Deactivate nor Delete. Anyone else's cards
  have no menu.

The **Add User modal** (`htmx/account_form`, `#accountForm`, also the edit form) is a `modal` fragment of the general
host (`#modalContent:has(#accountForm)` sets its width). Its body (`.account-form-body`) scrolls while the actions stay
in view.

- The edit form is titled by the name as stored, whatever a refused save typed, carries the account's ID and the state
  it was opened over in hidden inputs, and has no password. A save over a change made since it opened comes back with
  `ACCOUNT_CHANGED` above the actions.
- To add, Password (with Generate, 20 characters from `crypto.getRandomValues`, and Show; `includes/password_field`)
  and Confirm password sit in one `passwordField` element (`.password-fields`, `display: contents`, so each stays a row
  of the form's grid): Generate fills both and Show shows both. The Set password dialog's form is that component's
  element itself. A mismatch comes back under Confirm (`PASSWORDS_DO_NOT_MATCH`, `Api.Field.User.PASSWORD2`), refused by
  the route before the service, beside every other field's error, with both passwords kept.
- The account type is a row of `.type-card` radios, only the types the creator may create. For an Admin creator, the
  Create Libraries switch (a User's) and the new-Library grant's switch and Permission chips (a User's; a Guest's grant
  is access alone) follow, each a `.switch` with a bound hidden input, disabled where the type may not have it.

The **Permission matrix** is a table under a sticky header: one row per Library the creator may give access to (the
access checkbox, `libraryIds`, its name, an All button), one column per Permission in `Permission`'s order (its header
a button). A cell is a checkbox (`permissions`, "<Library ID>:<Permission>") where the creator holds that Permission
there, and empty elsewhere; a Library or Permission above the creator's ceiling is not rendered at all.

- `accountForm` (`js/alpine/components/account-form.js`) starts from the JSON in `data-app-account-form` (the values,
  the ceiling, and `preset`, `AccountForm.PRESET`: Import and Curate own, which a User given a Library starts with, and
  which the new-Library chips start from while that switch is off). It runs the matrix's toggles with no request; its
  module header lists them.
- The inputs follow the state through bindings, and what may not be sent is disabled, so the browser leaves it out of
  the form: a Guest's Permissions (the `.guest` class hides the columns), an Admin's whole matrix (a notice in its
  place), a row the type may not be given (also hidden), and an unchecked Library once `maxLibraries` are checked (a
  note names the bound). The server holds every rule again.
- The form posts JSON to `POST /htmx/users/r/:repoId/add` or `PUT …/edit`. A refused field is a validation replacement
  with the values kept (the type's error under the cards, the Libraries' under the matrix); a save answers the success
  detail's message.

**Setup background** — `setup.scala.html` (`/setup`, `SetupController`; the index redirects here until the repository
exists) runs a full-screen animated scene, a top-down jet over clouds and farmland, behind the setup form.

Page and CSS (`static/css/pages/setup.css`):

- `<canvas id="setupBackground">` is the body's first element, fixed over the viewport at `z-index: -1` (below every
  element, so the rest of the markup is untouched), `pointer-events: none`, and `opacity: 0` fading in on `.ready`
  (`transition: none` under reduced motion), so the form never sits on a blank canvas.
- The form's container (`:where(body.setup) main > div`) is a frosted card: a radial gradient of `--setup-form-base`,
  opaque at the center, transparent at the corners, over `backdrop-filter`. Its 6px radius and the blur are literals (a
  radius and a blur, not spacings). No top bar; the footer drops its background, so the scenery runs under it.
- The palette is the page's own (the carve-out in **Do**): the scene's `--setup-background-*` and the form's
  `--setup-form-*` tokens, declared at the top of `pages/setup.css`. `static/css/htmx/setup_form.css` styles the form
  from the `--setup-form-*` tokens alone. Left out of the style guide's scan, the palette is on no card and in no audit.
  Its only core tokens are the spacing steps, `--text-shadow`, and the error box's `--theme-neutral-2`, `--error-color`
  and `--theme-neutral-4`.

Page side: `js/setup-page.js` only calls `startSetupBackground(canvas)` of `js/setup-background/setup-background.js`;
the form submits through htmx and `json-enc.js`, loaded as plain scripts.

- Without `canvas.transferControlToOffscreen` it returns at once. There is no fallback: the canvas stays empty.
- It resolves the palette on the page, since a Worker has no stylesheet (`resolvePalette`: the `--setup-background-*`
  tokens, and `--error-color`, `--success-color` and `--theme-neutral-4` for the lights). Each token is set as the
  canvas's inline `color`, read back computed, painted into a 1 × 1 scratch context and read with `getImageData`, so it
  reaches the Worker as sRGB `[r, g, b]` (0–255) whatever form the browser computes; the Worker applies its own alphas.
- It starts the module Worker (`new URL("./setup-background-worker.js", import.meta.url)`, `{ type: "module" }`) and
  posts `init` with the canvas transferred. A Worker that does not load (file missing, no module Workers) or throws
  fires `error`: the page logs one line naming the feature and does nothing else, so the canvas stays transparent.

Every message is a plain object with a `type`:

| Direction | Message | Fields | When |
|---|---|---|---|
| page → Worker | `init` | `canvas` (the transferred OffscreenCanvas), `width`, `height`, `dpr`, `palette`, `reduced`, `hidden` (`document.hidden`: a page opened in a background tab draws its first frame and waits to be shown) | once, on start |
| page → Worker | `resize` | `width`, `height`, `dpr` | a `ResizeObserver` on the canvas (the entry's `contentRect`, CSS px; its first notification repeats `init`'s size), and a change of `devicePixelRatio`, caught by a `matchMedia("(resolution: …dppx)")` query for the current ratio, re-armed for the new one each time it fires |
| page → Worker | `visible` | `hidden` (`document.hidden`) | `document`'s `visibilitychange` |
| page → Worker | `motion` | `reduced` | the `(prefers-reduced-motion: reduce)` query's `change` |
| Worker → page | `ready` | none | after the Worker's first frame is drawn; the page adds `.ready` to the canvas, which fades it in |

The scene is the Worker's, `js/setup-background/setup-background-worker.js` (the fields' textures are
`setup-background-textures.js`; sizes, speeds and timings are the constants at the Worker's top). What keeps it cheap
and correct:

- It draws in CSS px. The bitmap is `width × dpr` by `height × dpr` with `dpr` capped at 2, so a 3× phone does not
  triple the fill rate. The context is opaque (`alpha: false`), as the field-edge fill covers it every frame.
- The clock is `requestAnimationFrame` (a Worker owning an OffscreenCanvas has it). A frame advances the scene by
  `min(timestamp − last, 100 ms)`, so a resumed tab or a stalled frame moves it one short step. At most one frame
  handle is pending: a start requests one only when none is, a stop cancels it.
- On `init` it sizes the bitmap, generates the scene, draws the first frame synchronously (the fade-in waits on
  `ready`, and a hidden document gets no frame callbacks), posts `ready`, then starts the loop unless `reduced` or
  `hidden`.
- `visible { hidden: true }` cancels the pending frame; `hidden: false` resets the clock, so nothing jumps, and starts
  the loop unless `reduced`. `motion` does the same for `reduced`; `reduced: true` leaves the last frame as the still.
- A `resize` equal to the current size and ratio is a no-op (the observer's first notification is one). A new ratio
  alone resizes the bitmap; a new size also refits the scene. Either draws one frame at once, since sizing the bitmap
  clears it and the blank would reach the page before the next frame.
- The scene is random and never repeats: ground blocks and clouds that pass the bottom edge are dropped, and new ones
  are generated above the top. On resize the ground is regenerated only when the width grows (its roads and cells are
  cut for the width); otherwise blocks are dropped or added as the height asks. A resize leaves the clouds in place.
- A vehicle belongs to a block, its position relative to the block, so the ground's scroll moves it for nothing.
  Vehicles are stepped after the blocks are recycled, and one that changed block moves to its new block once every
  block has been stepped, so none is stepped twice in a frame.
- No frame builds a gradient: a cloud circle's gradient and a lamp's glow are built once at the origin and drawn under
  a translate. No `ctx.filter` (slow in Safari). The jet's `Path2D`s are built once in units of its length and drawn
  under a scale, so one builder serves both its sizes.
- Draw order: field-edge fill → each block's cells, roads, vehicles → cloud shadows → clouds → jet → lights.

**Explorer dock** — The main page (`index.scala.html`, `static/css/pages/index.css`) is a grid of three areas,
`explorer`, `gutter` and `content`. Its shape is the **Dock side**: where the explorer sits against the results
(`left`, `right`, `top`, `bottom`), written on `<html>` as `data-dock-side`, with the explorer's share of the axis in
`--explorer-share`. `index.css` has one grid rule per side.

- **Minimums.** `--explorer-min-width`, `--explorer-min-height`, `--content-min-width` and `--content-min-height`
  (`core.css`) are registered with `@property` as lengths, so the script reads them in px. Across the width the
  explorer's minimum is its header's `min-content` (the tabs and the Dock side menu in full), floored by
  `--explorer-min-width`. This holds only because `#explorerPanelContent` has `contain: inline-size` and the Triage
  summary's sentences wrap, so neither adds width. The script measures the same width for the divider's bounds
  (`headerMinContentWidth`).
- **Trash bin.** The results alone: `main:has(> #content.trashbin)` hides the explorer and the divider, and
  `initExplorerDock(view)` returns at once.
- **Default and state.** Until this browser has a pick, the side follows the viewport (portrait: bottom, else left) and
  a rotation re-docks it; a pick holds from then on. The state is per browser under `Const.localStore.explorerDock`,
  `{ side?, widthShare, heightShare }`: one share per axis (defaults 25 % and 35 %), so a switch within an axis keeps
  the size.
- **No layout shift.** `static/js/explorer-dock-boot.js`, a plain blocking script in the head (key in `data-key`),
  writes the side and the share on `<html>` before the first paint. It cannot import, so it repeats the sides and the
  default shares of `js/common/explorer-dock.js`: change them in both.
- **Ownership.** `js/common/explorer-dock.js` (`initExplorerDock(view)`, called by the page script) owns the side and
  the share after the first paint. The mechanics are `js/common/dock.js`'s `createDock`, whose header documents every
  option, the divider's drag and keys included. Three docks are made from it: the explorer's, the Detail panel's
  (**Detail panel**) and the Edit panel's, which passes `followSide` in place of a pick attribute, so it remembers no
  side and has no Dock side menu (**Edit panel**).
- **Side changes.** A click on any `[data-app-dock-side]` on the page is a pick. Every side change, by a pick or by the
  viewport, dispatches `Const.events.dockSideChanged` on `document.body` with `detail.side`. Whatever lays itself out
  by the side listens to it: the People tab's Split view rebuilds its Split.js instance on the new axis
  (**People tab**).

The pick is the **Dock side menu**, `#dockSideMenuHost`, at the end of `#explorerHeader` (a grid: the tab list in the
`1fr` column, the menu in the `auto` one; in Triage the Triage summary spans both above them, **Triage summary**). It is
a compact choice menu (**Context menus**) whose options carry `data-app-dock-side` and a glyph from the sprite
`includes/dock_glyphs` (included by `index.scala.html` and the style guide). `dock.js` (`syncDockControl`) checks the
effective side's option and gives the trigger its glyph and label on init and on every change.

The divider, `#explorerDivider` (`role="separator"`), is a `.divider` of `core.css`, the one drawing of a draggable
divider, which the People panes' Split.js gutter wears too. Its orientation is its class, `.vertical` or `.horizontal`;
a divider with neither (the explorer's) follows `html[data-dock-side]`, so it is laid out before any script runs.

- The remembered share is the user's own, bounded only when the user sets it. The laid-out share is bounded again on
  every write and on `resize`, so a visit in a small window never narrows what a wide one remembered.
- The dock fires no resize event. Whatever reacts to the explorer's size observes it itself: the context menus close
  through their `ResizeObserver` on `#explorer`, and the map and the sticky offsets have their own.

The style guide's Panels & layout section shows the divider in both orientations.

**Advanced search panel** — The panel that combines the filters of one search: a description, the Search text, one
folder, people, Locations, and the Date Taken and Date Imported ranges. For the routes and what a search takes see
`../AGENTS.md`, **Advanced search panel**, and **Describe search** for a description. It is the second inline dialog in
a popover panel after View settings: a form that stays in its panel until Search or a dismissal. Its fields, in order:
Describe, Keywords, Folder, People, Locations, Date Taken, Date Imported. **Keywords** labels the Search text's field
here and the remove control of the Search text's chip in a filtered search's title (**Results title**), and nothing
else: the parameter is `q`, and the code, its comments, the guides and the server's messages say Search text
(`CONTEXT.md`).

- **Host.** `includes/nav` renders, in the `.search` cell, `#searchPanel` (a native `<dialog popover="auto">`, empty
  until it opens) and the filter button in the quick search form (`.filters`, `popovertarget="searchPanel"`). The
  browser owns its visibility; CSS anchor positioning places it under the form (`--search-form`, scoped to the cell by
  `anchor-scope` because the style guide holds a second nav). Both are `hx-preserve`, so a nav reload keeps the open
  panel (**Nav refresh**). `bindSearchPanel()` (`js/search-results/search-panel.js`) binds the host once, from
  `FrontendApp.start()` and from `startNavPage()`.
- **Load on open.** Every opening empties the panel and reads its form from `data-panel-url`
  (`GET /htmx/search/r/:repoId/panel`). It listens to `beforetoggle`, which fires for each opening, where `toggle`
  coalesces a fast close and reopen. The request carries the filters in force (`searchParams.filters()`; none without a
  store), so the server names the chips, the quick search input's text as `q`, and the `view` and `triaged` of the
  search the panel will run (`pickOfPanelSearch`): in Triage the Triage view; in Browse the repository view with the
  triage filter in force; anywhere else the repository view with the choice remembered in the browser
  (`rememberedTriaged`), for a member alone (`context.seesTriaged()`), since a Guest is refused the filter. A folder's
  Search here is Browse's even from Triage, since a folder holds no triaged asset.

  The read goes through the axios client, not htmx, so only the latest opening's answer is put in; a failure is an
  error snackbar and closes the panel. The panel is invisible while empty (`.search-panel:empty`). Focus goes to
  Describe with the caret after its text (`focusAtEnd`), so typing adds to a description in force. Closing empties the
  panel, which removes its components with their listeners and calendars, and drops what was changed and not searched.

  `openSearchPanel(filters)` opens it from a script with `filters` laid over the ones in force, or reloads an open
  panel: a folder's Search here passes `{ folderId }` (**Context menus**), a filtered search's Edit nothing (**Results
  title**). A closing panel returns focus to what had it at opening. A menu entry is gone by then (the opening closed
  its menu), so with focus inside another popover `openSearchPanel` first moves it to the filter button.
- **Search in.** The form's first row, a member's alone: a toggle group picking the assets the search covers. It adds no
  parameter: a pick is the search's `view` and `triaged`, in the row's hidden inputs. **Library** is the repository
  view with `triaged=false` (the sorted assets), **All** the repository view with `triaged=true`, **Triage** the Triage
  view, which holds no filter. Library here means the sorted assets alone; the glossary's Library is the whole
  (`CONTEXT.md`). While Triage is picked the Folder field is hidden (`searchesTriage`) and its folder is in neither the
  search nor the badge's count (`searchOfForm`). The People field requests its options with the two inputs as they
  stand (`data-options-params`), so its counts match the search. The badge does not count the pick, Clear leaves it,
  and it lasts for the search it is submitted with (**Search parameters**).
- **Describe.** A `textarea` with `maxlength` from `Const.Search.MAX_DESCRIPTION_LENGTH`, the server's bound (which
  counts the description trimmed). Enter searches and Shift+Enter breaks the line (`searchOnEnter`). It opens with the
  store's `describe`. A quick search from the nav searches by its text alone and drops the description and the other
  filters (**Search parameters**).
- **Text.** The panel's Search text field and the nav's quick search input hold one value: the input's text, searched
  or only typed, is the `q` the form is requested with, and a search from the panel writes its text back into the input.
- **Search and Clear** are the form's (`x-data="searchPanel"`, `js/alpine/components/search-panel.js`). Search reads the
  form with `searchOfForm` over `FormData`: every filter is a field under its parameter's name, so the form's entries
  are the search. It trims the text, sends `peopleMatch` only as `any` with two people or more, and calls
  `searchByFilters`. Where the page shows the view the search is of (Browse or Triage), the store takes the filters
  (`searchParams.replaceFilters`) and `runSearch()` reloads in place. Otherwise (the other of the two views, whose page
  is laid out for its own, and any other page or the Trash) the window goes to the quick search form's `action` with
  the search as its query string. Clear empties every field without searching, each component through a
  `search-panel-clear` event on its root, and leaves the pick.
- **Combobox** (`filterCombobox`, `js/alpine/components/filter-combobox.js`, markup `includes/filter_combobox`): one
  component for the folder, the people and the Locations, on the ARIA combobox pattern of
  `docs/reference/alpine-components/headless-combobox.md`; `faceCombobox` stays the Face correction's own. The Import
  page's upload sections and the Watch folder form use it for a destination folder. Its rules are scoped by
  `.search-panel`, so any host wears that class. The fields differ only in markup: the options route, single or
  multiple, the most chosen (`data-max-chosen`, `SearchRequestParser.MAX_IDS`) and the chip's `<template>`
  (`includes/search_chip`). Its header documents the keys and the loading states. Across files:
  - The server renders the `role="option"` elements; the client only drops the chosen ones and marks matched word
    starts by the server's rule (`SearchWords`: lower case, no diacritics, letter and digit runs, camelCase humps).
  - The chips are the state: their IDs, comma-separated, are the hidden input's value, and writing it fires `change`
    (`syncChosen`), which the filter button counts.
  - Escape with the listbox open, or options loading, is consumed by the input. `expanded` is written as the listbox is
    shown and hidden, not on the popover's later `toggle`, so a second Escape reaches `global.js` and closes the panel.
  - The listbox is a manual popover in the top layer, anchored under the field's `.box`; a click in it counts as inside
    the panel, so the panel stays open.
- **People match.** The `.switch` beside the People label is the form's state (`matchesAll`, on for all), shown once
  the field holds two people (`hasSeveral`); its value is the hidden `peopleMatch` input's.
- **Date range** (`dateRange`, `js/alpine/components/date-range.js`): Flatpickr in range mode, loaded with its
  stylesheet by the first field (`loadFlatpickr`, `js/common/vendor-loader.js`). The ISO days are the two hidden
  inputs; the read-only input shows `rangeLabel`. Its header documents the rest. Across files:
  - `rangeLabel` uses its own English `MONTHS` table, matching the server's `DateRange.label`, so the field and a
    filtered search's chip read one range alike in every browser.
  - The calendar is appended to the field (`appendTo`), inside the panel: appended to `body` it would be drawn beneath
    the panel, which is in the top layer, and a click on it would light-dismiss the panel. It is a manual popover,
    placed by CSS anchor positioning (`htmx/search_panel.css`); Flatpickr's `position` is switched off.
  - Escape with the calendar open closes it alone: the field's root takes the key on the way down
    (`keydown.escape.capture`), before Flatpickr or `global.js`.
- **Flatpickr's look.** `static/css/flatpickr.css` is the release's file, loaded as `@import … layer(flatpickr)`, so
  every rule of the bundle, which is in no layer, outranks it whatever the selectors and the load order.
  `htmx/search_panel.css` restates its colors, radii and spacing in theme tokens under `.search-panel`, so the calendar
  follows both themes.
- **Filter button.** Its badge (`data-app-filter-count`) and accessible name ("Advanced search: 3 filters") count the
  filled fields (`countSearchFilters`: each date range counts once), from one up. With the panel closed it counts the
  search in force (`searchParams.filterCount()` in an `Alpine.effect`; none without a store). With it open it counts
  the form (`showFormFilterCount`, on load and on every `input` and `change` reaching the form). This is why
  `filterCombobox` and `dateRange` fire `change` on their hidden inputs as they write them: a removed chip, a cleared
  range or Clear shows on the button before anything is searched. Closing without a search returns to the count in
  force.
- **Look.** `static/css/htmx/search_panel.css`, scoped by `.search-panel`. A `.box` wears the field style around its
  icon, chips and input (or the Describe textarea, which shares the input's rules through `.box :is(input, textarea)`),
  so the input itself sets `outline: none` (**Themes**). A chip is drawn by `core.css` (CHIPS,
  `:is(.search-panel, .results-title) .chip`) for the panel and a filtered search's title alike. The style guide's
  Menus & dialogs section holds the form with a static listbox, and its navigation specimen the filter button with a
  badge.

**Nav** — `includes/nav` is the bar of every page behind a login, inside the page's `<nav>`, styled by the NAVIGATION
block of `core.css`. In order: the `.brand`, the Browse cell (below), the `.search` cell (the quick search form,
**Search input**, then the advanced search panel's host), one `.menu` per entry (Triage, Import, Users, Settings, Trash,
Style Guide), the account cell, and the theme switch, always last. An entry with nothing behind it is `.empty`, "(0)" in
its label and not a link. Which entries show follows the Permissions (**Controls follow Permissions**); Users shows for
an Admin and the holder of Create Users or Create Guests in any Library (`RequestContext.createsAccounts`, **Users
page**).

- **Current entry.** `aria-current="page"` is rendered by the server from the include's `current` parameter, a
  `Const.NavEntry` key: the main page's view (`IndexController` passes the `view` query parameter, `repository` by
  default), or `import`, `settings`, `users`, `style-guide`, passed as constants by those templates. Nothing marks an
  entry client-side, so a nav reload passes `current` again (**Nav refresh**).
- **Quick search form.** Its rules are `nav .search > form`, never `nav form`, since the panel beside it holds a form
  too. The form's accent glow on focus is the field's focus indicator, one of the places a rule sets `outline: none`
  (**Themes**). Focus on the filter button, where a closing panel returns it, is not focus in the field
  (`:focus-within:not(:has(.filters:focus))`), and the button draws the focus ring.
- **Theme switch.** `button.menu.theme-switch` shows the theme a press goes to. CSS picks the icon from `data-theme` on
  `<html>`, so a nav reload needs no client step, and one delegated click in `global.js` (`[data-app-theme-switch]`)
  serves every page, those without Alpine included (**Themes**).

The **account cell** (`.menu.account`, `includes/account_menu`) shows who is logged in. `data-account-type` sets
`--account-type-color` (`core.css`). The trigger, `#accountMenuBtn`, is named and titled "Account: <username>
(<type>)", since the color alone does not say the type. It requests the **account info dialog**
(`htmx/account_info_dialog`, `AccountInfoController`, `GET /htmx/account/r/<the Library>/dialogs/info`) into the
popover host, right-aligned below the trigger (`htmx/account_info_dialog.css`). The dialog shows the type's
`AccountType.description` (shared with the Add User form's type cards) and the account's Libraries through
`includes/account_libraries` (shared with the Users page's cards).

- Each time is a `<time datetime>` holding UTC text (`Util.utcText`), shown in the viewer's zone and locale by the
  `localTime` component (`js/alpine/components/local-time.js`), registered in both `alpine/components/index.js` (the
  main app) and `nav-page.js` (the nav pages).
- "Change password…" requests `htmx/own_password_dialog` (`GET /htmx/account/dialogs/password`) into the same host;
  it sends JSON to `PUT /htmx/account/password` (`OwnPasswordController`). A refusal returns the form with the error
  under its field and nothing typed kept. A change answers with the session's new cookie and `PASSWORD_CHANGED_EVENT`,
  which `listeners/account.js` confirms in the snackbar.
- "Log out" submits the empty `#logoutForm` (`POST /logout`) beside the trigger, hidden by `nav .menu.account form`.

The Browse cell (`.menu.repository`, `includes/library_menu`) holds two targets: the **Browse** link to the current
Library's main page with no search (`/r/<id>`), and the trigger of the **Library menu**, a choice menu of the Libraries
the account can see, opened by the current Library's name. Its host, `#libraryMenuHost`, carries
`data-choice-menu="Library"`, `data-choice-menu-choice-only`, `data-choice-menu-trigger-class="nav-menu-trigger"` and
`hx-preserve`.

- The options are `RepositoryService.getAccessibleTo` (every Library for an Admin, those with a grant for anyone else;
  natural order by name), each a link to `/r/<id>`, the current one `aria-checked`. For an Admin or the holder of the
  Create Libraries flag (`decorators.mayCreateLibraries`), "New library…" follows a separator and requests the **New
  library dialog**.
- The trigger and the panel cut a name with an ellipsis at `--library-name-max-width`; the full name is in the title
  and the trigger's accessible name ("Library: <name>"). A panel in the nav takes the page's text, not its entry's
  (`nav .context-menu`).
- The main page builds the host with its first hydration; Import, Settings and Users with `startNavPage()`
  (**Template Layout**). A nav reload keeps the built host (**Nav refresh**), so a Library created in another tab
  appears at the next full page load.
- A name is a flex item in the trigger and a grid item in its entry, a bidi paragraph of its own, so a right-to-left
  name never reorders the dot or the caret. The browser tab title holds no markup, so `includes/page_title` isolates
  the name with U+2068 … U+2069.

**Controls follow Permissions** — An action control is rendered, or bound, only where the account holds what its request
needs, so no control answers 403 (`altitude/AGENTS.md`, **Permissions on routes**). Templates and scripts read the same
values the route decorators and the services read:

- Templates: `RequestContext` over the token's bitmask in the request's Library: `holds(p, ...)` (any one of them),
  `isMember` (not a Guest), `recyclesIn(view)` (Recycle in Browse, a Curate Permission in Triage, nothing in the Trash),
  `restores` (Recycle or a Curate Permission) and `createsAccounts` (Create Users or Create Guests in any Library).
- Scripts (`js/context.js`, `Const.permission` naming the Permissions): `context.holds(...)` reads the main page's
  `data-app-permissions` on `<body>` (`index`); `context.recyclesHere()` its `data-app-recycles`;
  `context.seesTriaged()` the `data-app-sees-triaged` that `#searchPanel` carries for a member on every page with the
  nav, so the scripts send the triage filter for a member alone (**Search parameters**).

Where each control follows (each template's comment carries the detail):

- Nav (`includes/nav`): Triage and Trash for a member, Import for Import, Users for `createsAccounts`, New library for an
  Admin or the Create Libraries flag, Settings for an Admin.
- Batch ops (`includes/batch_ops`, given the page's `view`): Move and the album and Location buttons for Organize;
  Recycle by `recyclesIn(view)`; Restore by `restores`, since the Trash holds assets recycled from a folder and from
  Triage alike and the server checks each; Curate Selected for a Curate Permission, and outside Triage for Recycle too,
  since the frames not picked are recycled; Purge and Purge recycle bin (`htmx/trashbin_header`) for Purge.
- Explorer (`js/common/folder-tree.js`, `album-list.js`, `location-list.js`): the ⋯ menus' actions and the add buttons
  for Organize, but a folder's Search here, every account's (**Context menus**). A menu with no action left is
  `buildNoMenuCell` (`js/common/context-menu-markup.js`), so the row keeps its grid columns; without Organize each tab
  opens with `includes/cannot_organize` instead of its add controls. A folder's Delete needs Recycle too unless the
  folder holds no asset (its recursive `numOfAssets` as the tree read it), since a delete recycles what it holds; the
  server refuses a delete whose count went stale.
- People: the rename pencil (`htmx/people_list`) and the results title's name editor (`htmx/view_person_name`) for Edit
  metadata; the cover choice, Hide / Show / Discard (`includes/results_title_inner`) and "Merge into …"
  (`includes/person_name_taken`) for Organize. The Detail panel's People section (`htmx/detail_panel_people`) is
  read-only without Edit metadata; its Change combobox (`js/alpine/components/face-combobox.js`) merges into a named
  Person only for Organize and otherwise moves this Face alone ("· this face").
- The View settings dialog's "Show triaged items" for a member.
- Drag and drop (`js/dragdrop/index.js`): every move, membership, folder and People drag for Organize; the thumbnails
  drag, and `#trash` takes a drop, where `context.recyclesHere()` holds too.
- Asset detail: the Edit toggle (`includes/html_common`), the Edit panel and its stage tools
  (`htmx/view_image_detail_modal`) for Edit images; Show original and the "Edited" badge are every viewer's.
- Curation is a Curate Permission's, as its routes are: every way in (Triage's Curate button, a Sequence header's in
  `htmx/results_grid_grouped`, which with Curate own alone needs two frames the account owns,
  `CurationService.curatableFrameCounts`; Curate Selected; the Detail panel's row; the `?curate=` address, which
  `js/listeners/curation.js` ignores without one) and the queue's count (`js/stores/sequences-to-curate.js`). Inside,
  every control is offered, since a set holds only the frames the account may curate (`AssetRules.curatedOwner`).
- Import page (**Import page**): each upload section's destination option for Organize; without it the sections upload
  to Triage, and the server refuses a destination all the same. The Watch folder controls are an Admin's.
- Users page (**Users page**): Add User as the page itself (`createsAccounts`); each card's ⋯ menu an Admin's; the Add
  User form renders nothing above the creator's ceiling.

**Nav refresh** — After any successful asset mutation, reload the nav for fresh counts with `FrontendApp.reloadNav()`
(`app.reloadNav()` from a listener). It loads `/htmx/nav/r/<repoId>?current=<view>` into `nav`, the view read from the
search parameter store, since nothing marks the current entry client-side, and reloads the Triage summary
(**Triage summary**). A folder delete recycles its whole subtree, so the `folderDeleted`
listener (`js/listeners/folders.js`) reloads the nav before awaiting the folder-tree refresh.

A reload must keep three `hx-preserve` elements: the quick search form (its listeners and the text being typed), the
Library menu's host (`includes/library_menu`) and the advanced search panel (`#searchPanel`). The reload (`NavController`,
`includes/nav`'s `reload`) renders the host and the panel empty, by ID, because htmx 4 keeps the page's element: it
parks each page element a response's `hx-preserve` element names by ID, swaps, and moves the parked one into place. So
the Library list is the page load's. The move is `Element.moveBefore` where the browser has it, which keeps an open
popover open, and `insertBefore` elsewhere, which closes it; so the Import page's nav poll (`pipeline.scala.html`,
`current=import`) skips a tick while the nav's menu panel, `#searchPanel` or `#popoverDialog` is open, in any browser.

`refreshCounts` in `js/assets/asset-actions.js` runs after every asset mutation and makes every count reload: the nav;
`app.reloadFolderCounts()` (**Folder asset counts**); `app.reloadAlbumCounts()` and
`app.reloadLocationCounts()` (the same for their lists, fetching nothing while another explorer tab is active); the
Sequences to curate (`Const.state.sequencesToCurate`, **Curation**); and `peopleChanged`. Folder operations (add,
rename, move, delete) use `reloadFolderTree`, which rebuilds with fresh counts.

`js/assets/asset-actions.js` owns the move, recycle, purge, restore and membership flows, and `js/dragdrop/` the
interact.js bindings; listeners reach them through `app.assetActions` / `app.searchDetailCoordinator`, and `FrontendApp`
is the composition root that wires them. A restore answers `{restored, duplicates}`: an asset whose content was imported
again after it was recycled stays in the Trash, so only the `restored` cells leave the grid and the snackbar warns about
the rest. A move answers the same, since it takes an asset out of the Trash too: every cell but the `duplicates`' is
moved (and leaves the grid in Triage, the Trash or a folder the destination is outside of), the duplicates stay
selected while the moved ones are deselected, and `reportLeftInTrash` words both snackbars.

**Control states** — The action buttons, a toggle group's segments, the filter chip, the menu trigger, a context menu's
actions and the switch share one transition rule in the DEFAULT CORE section of `core.css`. The hover lift and the press
scale live in one `@media (prefers-reduced-motion: no-preference)` block, so nothing moves under reduced motion while
colors and shadows still change. A disabled button takes neither the hover nor the press look
(`:hover:not(:disabled)`, `:active:not(:disabled)`) and is dimmed whole, whatever its variant
(`.action-button:disabled`). A button inside a container that already dims is not dimmed twice (the upload sections'
Choose buttons, `includes/upload_sections.css`). `.action-button.primary`, the accent call to action, is a dialog's or a
form's main action (Add, Move, Merge, Save, the upload sections' Choose buttons, the empty lists' "Add your first …"); a
destructive confirmation stays `.error`.

**Themes** — The app has a light and a dark theme and ships in the dark one (`color-scheme: dark` on `:root` in
`core.css` and in the meta tag of `includes/header_common`); with nothing remembered it stays dark.

- `chooseTheme` of `js/common/theme.js` owns the switch, for the nav's theme switch (**Nav**) and the style guide's
  switcher: it declares `color-scheme` and `data-theme` on `<html>`, remembers the theme (`Const.localStore.theme`) and
  announces `Const.events.themeChanged`. `js/theme-boot.js`, a blocking script of `includes/header_common` keyed by its
  `data-key`, declares both before the first paint; the setup page passes `themed = false` (no nav, a palette of its
  own). The design tweaks panel sets the scheme by itself (**Design tweaks**).
- An element over a pane that is dark in either theme declares `color-scheme: dark` on itself, so its tokens take their
  dark side: Curation's `.pane-tag`, `.loupe` and clipping overlay (`htmx/curation_set.css`) and the Histogram strip
  (`includes/histogram.css`).
- The color rules are in **Do**; what each token is for is documented here, not in `core.css`.

The theme colors:

- `--theme-primary`, the one hue (indigo): the selected tab, the focus ring, selection, drop targets, chips and pins, the
  brand gradient's start, the nav bar's tint. `--theme-primary-text`: the primary as text or a thin edge where the
  primary misses 4.5:1 (the filter chip). `--theme-on-primary`: text and glyphs on the primary fill, dark in the dark
  theme.
- `--theme-secondary`: the supporting surface lifted off the page (a button, a hovered tab, row or menu action, a count
  pill; at 0.5 a toggle group's track).
- `--theme-accent` (teal): where you are and what to do next: the current nav entry, Triage's stripes, tint and view
  band, the brand's mark, the fill of `.action-button.primary`. `--theme-on-accent`: text on that fill.
- The neutral scale, `-1` lightest to `-4` darkest in the light theme: `-1` is never a surface, only a sheen or hover veil
  at a low alpha; `-2` the page surface, a small button's fill, the pressed toggle segment and, at 0.84, a floating
  surface; `-3` lines and secondary text (4.5:1 on the page and the secondary surface); `-4` text. The dark side is out
  of lightness order and `-1` and `-4` hold one value there by design; the style guide flags neither.
- `--theme-magenta`: the Tint track's magenta end, its one use.
- Text on a semantic fill or over an image reads `--theme-neutral-4`; on the primary fill `--theme-on-primary`; on the
  accent fill `--theme-on-accent`.

The other colors:

- The channel colors (`--histogram-red`, `-green`, `-blue`): drawn as light on a strip dark in either theme, so each pair
  holds one color.
- The semantic colors are a status's text, icon and edge, each at least 4.5:1 on the page surface; `--info-color` is a
  blue cooler than the primary so a notice does not read as a selection. A fill (`--*-background-color`, one HSL recipe
  per scheme, so the four sit at one lightness) carries `--theme-neutral-4` text and its color only as an edge, since the
  color on its own fill reads about 3:1. A success, warning or error button takes its color at 0.16 as its fill and the
  color itself as text and edge.
- `--shadow-color` is the ink of shadows and scrims. A veil (listed in **Do**) follows the pair of the color it reads;
  `--popover-backdrop-filter` and the more blurred `--search-panel-backdrop-filter` are the frosts.

Borders and shadows:

- A border token holds a width and a style (`--border-hairline`, `--border-heavy` with its width `--border-heavy-width`,
  `--border-double`, `--border-band`, `--border-dashed`); the rule writes the color after it, by convention:
  `--theme-neutral-3` around a cell, a menu, a quiet control and on a modal's heavy edge; at 0.3 between a context menu's
  actions; at 0.45 around a button (a semantic or accent button's edge is its own color); at 0.25 around a field
  (`--theme-accent` focused, `--error-color` in error); `--popover-border-color` around a floating surface;
  `--theme-primary-text` at 0.4 around a chip; `--panel-border-color` around or inside a chrome panel;
  `--theme-secondary` on a list's double trace.
- The shadow tokens: `--drop-shadow` (a modal, a panel, a pin, the checkmark), `--float-shadow` (a floating surface),
  `--field-focus-shadow`, `--crop-dim-shadow` (dims the stage outside the crop), `--text-shadow` (transparent on the light
  side), `--box-shadow` (the light line under a panel's edge), and for buttons `--sheen-shadow` (every button's top
  line), `--soft-shadow` (at rest) and `--lift-shadow` (hovered). A rule lists the ones it draws
  (`box-shadow: var(--sheen-shadow), var(--soft-shadow)`).
- No two border or shadow tokens hold one value: a rule writes the token that has the look, and a new value is a new
  look, described here.

Colors derived in a rule:

- A color taken from a token at an alpha is written in relative color syntax, so it follows the pair: the view tints
  (**Triage's and the Trash's look**), the nav bar (primary at 0.1), the current nav entry (accent at 0.16), hover glows
  and tints over `currentColor`.
- A hovered button lightens its fill with one rule for every variant,
  `oklch(from var(--button-fill) calc(l + 0.04) c h)`: each variant declares `--button-fill` and `background` reads it.
- The filter chip and a chosen name's chip in the advanced search panel share `--chip-fill`, declared by one rule.
- The Edit panel's slider tracks are `:root` gradient tokens (`--edit-track-*`): each end shows what it does to the
  picture, `--theme-neutral-3` at 0; the component sets `--edit-slider-track` on a slider to one.
- Literals stay only in the SVG data URI of `htmx/view_image_detail_modal.css`, since a data URI cannot read a token;
  the check of `includes/search_results.css` is a mask, colored by its element's background.

Keyboard focus is the `:focus-visible` ring of `core.css`, inset (`outline-offset: -2px`) where a panel clips its rows.
The browser withholds it after a pointer, including the focus a closing dialog returns, so no rule removes an outline
to hide it after a click. The three fields that draw their own indicator (**Do**) are a text-like field (**Forms**),
which matches `:focus-visible` after a pointer too and keeps its `--error-color` edge with no ring in error; the nav's
quick search (**Nav**); and an advanced search panel input, whose `.box` draws the edge on `:focus-within`
(**Advanced search panel**). The setup page recolors the ring to `--setup-form-accent` (**Setup background**).

**Style guide** — `views/style_guide.scala.html` is a dev-only page (`/style-guide/r/:repoId`, `StyleGuideController`,
whose routes `App` registers in dev alone; the nav shows its button only in dev) with a section per foundation and per
shared primitive of `core.css` and `tabs.css`, plus a token audit. It starts the whole app (`initApp()`), so it loads
`lib/interact.min.js` and `lib/json-enc.js` itself.

- **Discovered, nothing to maintain:** the tokens and the icons. The server scans the sources on every request
  (`StyleGuideScan`) and embeds the result as JSON (`#styleGuideScan`); the `styleGuide` Alpine component
  (`js/style-guide.js`) resolves each `:root` token against the loaded stylesheets, and `x-for` templates render the
  cards (**Rendering a page's own data**), each section asking for its own with `@tokenCards(kinds, group)`.
  A card's note is the comment directly above the token's declaration. The token audit lists tokens declared outside
  `:root`, tokens set only from JavaScript, and the color and length literals with their locations, each color naming
  the token that already holds it. The kinds and groups are `js/common/token-kinds.js`'s (`TOKEN_KINDS`, `kindOf`,
  `groupOf`); the design tweaks adapter configures the same sections.
- **Token cards:** a token's group follows from its name (a length's also from what it references): the colors
  **Theme colors** (`--theme-*`), **Status, shadow and channels** and **Derived** (the veils); the lengths **Scale**
  (`--space-*`), **Roles** (a length referencing a step) and **Sizes**. The typography tokens are a kind by name, since a
  font size is a length and a weight a bare number. "Off palette" marks a color, border or shadow token outside the
  first two color groups whose declaration writes a color literal (a relative-color recipe is none), which keeps the
  palette in the theme and semantic tokens. "Same value as" flags tokens of one kind that resolve alike (a color in both
  themes), except two that read their color from the same tokens, which share it by design; two recipes computing one
  color are flagged, since they should be one token.
- **Hand-written, keep in sync:** the specimens. **A new or changed shared primitive in `core.css` or `tabs.css` (a
  class meant for reuse) adds or updates its specimen in `style_guide.scala.html` in the same change.** A feature's own
  conventions can have one too (Curation, the Detail panel, the Edit panel, the People tab, the Results title, Quick view,
  the advanced search panel and more). Specimens use the real classes; `static/css/pages/style_guide.css` (`sg-`
  classes, bare elements under `body.style-guide`) only lays the page out, from the `:root` variables. A specimen of a
  scoped feature stays inert:
  - It is the template's markup, hand-written inside an element carrying the ID or class the feature's rules are scoped
    by (`#editPanel`, `#people` with `--face-box` set, `#quickView`, `.search-panel`), with no `x-data`,
    `data-app-fragment`, `drag-drop` or `dropzone` class or search trigger, so nothing binds.
  - A real include is bound to the `styleGuide` component or to nothing: the Edit panel's Histogram draws a sample with
    the real `channelPaths`; the advanced search panel's real form has its submit stopped by the wrapper; the Results
    title's real filter chips are bound only by the results hydrator, which the page does not run.
  - The Edit panel's warning (`dialog#editLeaveDialog`) is hand-written to `includes/edit_panel`'s markup and shown
    open in place (`open`), not modal.
  - The gradient tracks are seen only in the `.edit-slider` specimen, since a token card draws no gradient.

  Three more things keep the page current:
  - A changed size, font, or icon set updates the values the specimens state in text.
  - `core.css` comments its boxed section headings (`/* NAVIGATION */`) and, in a line or two, a rule whose selector or
    values need a reason, pointing to this guide for the detail. A `:root` token may carry a short note in the comment
    directly above it, which its card shows; what a token is for in full, and what a rule draws, is documented in this
    guide and in the style guide's prose.
  - A new section also needs its `#styleGuideToc` link and its ID in `StyleGuideControllerTests` (`sectionIds`).
- **Live specimens:** `initStyleGuide()` builds an Add button and a folder-shaped ⋯ menu with `buildModalTriggerCtrl` /
  `buildContextMenuCtrl`, the explorer's own markup. Their dialogs open `htmx/style_guide_sample_dialog`, whose dev-only
  route validates the name and persists nothing (`Const.events.styleGuideSampleSubmitted` on success). The Forms
  section's Labelled fields button opens a page-held `<template>` dialog (`data-app-open-dialog`, **Modals**).
- **Library menu and New library:** the navigation section renders the real `includes/library_menu`
  (`#styleGuideLibraryMenuHost`); its New library opens the real `htmx/new_library_dialog` in its taken-name error state
  (`GET /htmx/style-guide/r/:repoId/dialogs/new-library`), and its form posts to
  `POST /htmx/style-guide/r/:repoId/new-library`, which applies the Library name rule alone and creates nothing.
- **Theme switcher:** `#styleGuideTheme`. `setTheme` calls `chooseTheme` (**Themes**), so the choice is the whole app's;
  on `Const.events.themeChanged` the component resolves the tokens again, cards showing the theme shown while "Same
  value as" compares both themes.
- **Font tester:** in Typography, the typed value becomes the specimen's `font-family`, and `isFontAvailable`
  (`js/common/font-availability.js`) reports whether the browser has the font, by measuring text against each generic
  family alone.
- **Alpha slider:** on the `--scrim-color` card (`ALPHA_SLIDER_TOKEN`), it replaces the alpha of the token's relative
  color and declares the value inline on the root element until reload, so Copy value gives a value to paste into
  `core.css`; of a `light-dark()` pair only the theme shown's side changes. A card is a `div`, not a button, because it
  holds the slider's input.

The scan (`StyleGuideController.excludedFiles`) leaves out the guide's own files, so a specimen never counts as a use of
its token; the vendored stylesheets (`font-awesome.min.css`, `leaflet.css`, `flatpickr.css`); `pages/setup.css`, whose
`--setup-*` palette is the setup page's own (**Do**); and `js/design-tweaks.js`, which names tokens to configure the
panel, not to use them. It skips every `lib/` directory. `GET /style-guide/scan.json` (dev only) serves the same scan to
the panel.

**Design tweaks** — A dev-only panel that changes any `:root` token of the bundle live, on the real markup and data of
the running app, so the look of every page, dialog and htmx fragment can be explored without touching a rule. It is
**currently disabled**: both of its script tags in `includes/header_common` (`lib/tokentweak-boot.js` and
`js/design-tweaks.js`, inside `@if(Environment.isDev)`) are Twirl-commented (`@* … *@`), so it loads on no page, dev
included. To
enable it, uncomment both tags; `Ctrl+Alt+T` then toggles it (there is no nav entry). The rest describes the panel once
enabled.

The panel is the vendored **tokentweak** package (`lib/tokentweak.js`, `lib/tokentweak-boot.js`, 0.3.0, refreshed by
`make vendor-tokentweak`; `lib/README.md`), whose README documents every option. `js/design-tweaks.js`, the adapter, is
the only Altitude code of the panel and passes only what the package cannot infer.

- **What the adapter configures:** the storage key `Const.localStore.designTweaks`, the bundle (`"/assets/"`), the
  shortcut, the server's scan as `sources`, the style guide's sections and the spacing and type scales. The two tokens
  the server sets inline on their containers (`--preview-box` on `#assets`, `--face-box` on `#people`) are scoped tokens,
  and the explorer size is an extra control over `js/common/explorer-dock.js`, which owns that share. It passes no
  `seed`: every theme and semantic color is a literal pair, edited with the full sliders.
- **How an override applies:** one adopted `CSSStyleSheet`, rewritten on every change: a marker rule
  (`:root { --tokentweak: designTweaks }`), one `:root` rule with every tweaked token, and one `!important` rule per
  scoped token on its container, since an inline value beats any normal rule. Adopted sheets cascade after the document's,
  so the root tokens win at equal specificity, with no `!important` and no `@layer`.
- **No flash on load:** the package stores the sheet's text and the theme under its key; `lib/tokentweak-boot.js`, a
  blocking head script naming the key in `data-key="designTweaks"`, adopts them before the first paint, and the
  adapter's deferred module finds that sheet by its marker rule and takes it over. No `window` global is involved.
- **The panel itself:** a `popover="manual"` host (`#designTweaks`) in the top layer, with a shadow root holding the
  package's own sheet, so tweaking the app's tokens never restyles the panel. A modal's focus trap marks the host inert;
  the package's `inertGuard` (a `MutationObserver`, on by default) removes the attribute, so the panel stays usable under
  a dialog, though the trap still pulls focus back from the panel's text fields.
- **Themes:** the panel's Light / Dark toggle sets `document.documentElement.style.colorScheme` alone, remembered with
  the tweaks; it bypasses `js/common/theme.js` (the two keep separate copies of the pair logic) and sets no
  `data-theme`. Editing a color edits the side of its pair for the theme shown; the export writes the full pair.
- **Dev only:** nothing of the panel ships outside dev: its tags sit inside `@if(Environment.isDev)`, its stylesheet is
  built in the shadow root rather than the bundle, and nothing else references the vendored files.
  `StyleGuideControllerTests` asserts the index and the nav of a non-dev app carry none of `design-tweaks`,
  `designTweaks` and `tokentweak`.

## Key Files

Only files whose name hides what they hold or a contract they share with another file. Paths are under `altitude/`.
A feature's files are named in its topic under **UI Patterns**, the image-edit modules in `docs/image-edits.md`, and
what each JS directory owns in **JS Directory ↔ Template Mapping**.

| File | Purpose |
|---|---|
| `static/js/context.js` | `context`, which `app.js` also exposes as `window.ctx` for the templates' inline scripts: the Library ID (set once by `index.scala.html`, read through `getRepoId()`), what the body says the account holds (`holds`, `recyclesHere`, `seesTriaged`; see **Controls follow Permissions**), the viewed folder, album and Location (read from the `searchParams` store), and the grid's metadata fields (**Metadata field visibility**) |
| `static/js/http/client.js` | The axios client (`http`) that every non-htmx request to a route goes through, never raw `fetch()` (the one `fetch()` reads an original's bytes in `edit/edit-stage.js`). A 401 sends the window to the login (`common/session.js`); `getHttpErrorMessage` gives a JSON refusal's reason. Override `validateStatus` with `allowHttpStatuses(...)` only on a call that handles a non-2xx answer itself (for example a `409`) |
| `static/js/common/vendor-loader.js` | Loads Leaflet, supercluster, media-chrome and Flatpickr, with their stylesheets, when a view first needs one: one request per page that every caller awaits, and a failed load is retried by the next caller. Flatpickr's stylesheet goes into a cascade layer of its own, so every rule of the app's bundle outranks it. A library with an ES module build is a dynamic `import()` instead |
| `static/js/common/anchored-panel.js` | `placeAnchoredPanel`: the viewport placement (flip, shrink, `--viewport-gap`) of the `contextMenu` component's panels, that is the ⋯ menus, the choice menus and View settings, which close on scroll instead of following their trigger. The popover dialog, the tooltip, the Face highlights' badges and the comboboxes' listboxes use CSS anchor positioning instead |
| `static/js/common/context-menu-markup.js`, `views/includes/menu_ctrl.scala.html` | The two builders of the same ⋯ menu markup, kept in step: `buildContextMenuCtrl` for the rows the client renders (folder, album, Location), `menu_ctrl` for the cards the server renders (an account card, a Watch folder card). The JS module also builds the Add and dialog triggers, and every choice menu from its server-rendered host (`buildChoiceMenuCtrl`); see **Context menus** |
| `static/js/search-results/click-suppression.js` | The one way a drag swallows the click its release fires: `suppressNextClick()` (an asset drag, a box gesture) or `suppressClickAfterRelease()`, armed at drag start (a Person, a folder, the batch mover), not in `end`, because with inertia a flick's `end` fires after the click. A new drag source uses it |
| `static/js/common/content-urls.js` | The one place the client builds an asset's content URLs (`previewUrl`, `displayUrl`, `originalUrl`; the server's twin is `Util.previewUrl`) and repoints an image at a new Preview (`repointImage`). An edited image's URL carries its revision (`?v=<rev>`) as its cache key (**Edited images and caching** in `altitude/AGENTS.md`) |
| `views/includes/html_common.scala.html` | Besides the snackbar, the tooltip host and the four dialog hosts (**Modals**), Asset detail's toolbar: the title with its "Edited" badge and the toggles (Edit for a holder of Edit images, Show original, Detail panel, Face highlights, fullscreen). The fragment swaps in below it |
| `views/htmx/view_image_detail_modal.scala.html` | Asset detail's fragment for every media type, a Video included (**Detail navigation**); its data attributes are under **DOM Addressing Convention** |

## Search parameters

Every search request goes through **one** function, `runSearch` in `js/search-results/search.js`: the initial load,
the explorer, the Search input, the Sort, Group and layout menus, continuous scroll, the detail modal's page at the
end of the grid. A caller passes only the parameter it knows; the `searchParams` store supplies the rest. Nothing else
builds a URL for `/htmx/search/r/:repoId`; other URLs built from the store (the map's cells in `map-view.js`, a
navigation to Browse) use its `toQueryString`, so they carry the same parameters.

`js/stores/search-params.js` owns the parameters (`DEFAULTS`, in serialization order) and every rule for combining
them; its comments are the detailed spec. The store holds no position: a search starts at its first page, and a later
page is one request's `after` cursor.

**Places and `CLEARS`.** The five places (`PLACES`) are a folder, a person, an album, a Location and a Triage subset.
Choosing one clears the other four, and a view clears all five. Search text (`q`) is one more place, the whole of the
view it is typed in, so it clears the five and keeps the view. The map area (`bbox`) belongs to the search it was drawn
on. "The panel's six" are `PANEL_FILTERS` (below).

| Changed | Cleared (back to its default) |
|---|---|
| `view` | `folderId`, `personId`, `albumId`, `locationId`, `subset`, the panel's six, `bbox`, `q`, `triaged` |
| `folderId`, `personId`, `albumId`, `locationId` or `subset` | the other four, the panel's six, `bbox`, `q` |
| `q` | `folderId`, `personId`, `albumId`, `locationId`, `subset`, the panel's six, `bbox` |

A place set to nothing still clears the others, so a trigger with one empty literal (the Results title's ×, the Triage
summary's total) returns to the whole view; an empty value is never sent. Layout, grouping, `rpp` and sort only
narrow or reorder, so they survive every change; with no sort chosen the server's default applies (Relevance with
text, newest import without).

**Combined filters.** The advanced search panel is the one widget that combines parameters (**Advanced search panel**).

- `personId` and `locationId` may hold several comma-separated IDs (`idsOf`, `ID_LISTS`), at most `Const.search.maxIds`,
  the client's copy of `SearchRequestParser.MAX_IDS`. `PANEL_FILTERS` (`describe`, `peopleMatch`, the two date ranges)
  are set by the panel alone; it sends `peopleMatch` only as `any`, since `all` is the server's default.
- A panel search bypasses `CLEARS`: `replaceFilters(filters)` (`applySearchFilters`) sets every filter as given (one
  left out is no filter) and resets `albumId`, `subset` and `bbox`. Filters that name a `view` carry the "Search in"
  pick, setting `view` and `triaged` together; filters without one (a Guest's panel, a chip's × in the
  **Results title**) keep both, except that a panel search never searches the trash.
- `replaceId(param, fromId, toId)` swaps or drops a searched Person or Location, keeping every other filter: a merge or
  deletion of a Person and a deletion of a Location use it (**Event Flow**, **Detail panel**, **Locations**).
  `viewedPersonId()` and `viewedLocationId()` are none when a search names several.

**A pick lasts one search.** `replaceFilters` writes nothing to `localStorage`, so a pick of Library or All, like a
`triaged` named by the URL, belongs to its search. A change that looks elsewhere (a name in `CLEARS`), or removing a
Filtered search's last filter, restores the remembered choice (`withRememberedTriaged`); sort, grouping, layout, `rpp`
and paging keep the pick. The "Show triaged items" switch reads the store, so it shows the search's value, and turning
it is remembered as always.

**The triage filter** (`triaged`) belongs to the repository view: the View settings switch is shown only there
(**Inline dialogs**), a change of view clears it, and the store drops it from any other view.

- With no `triaged` the server leaves triaged assets out, while the switch is on until turned off. So a member's store
  always holds `true` or `false` in the repository view, and every request and the bookmarkable URL say which.
- Seeding takes it from the URL when that names a valid value, else from `localStorage` (`rememberedTriaged()`, `true`
  when nothing valid is kept). `merge` writes it only when the change names `triaged`, which only the switch does.
- `seesTriaged` (`context.seesTriaged()`, passed by `index.scala.html`) is false for a Guest, who is refused
  `triaged=true` (a 403): its store is seeded none, reads nothing remembered, and keeps a `triaged=false` from its URL.
- A search that leaves the Trash for Browse is sent without the filter, which Browse then seeds; a panel search sent to
  Browse from Triage carries its pick.
- The People tab and the panel's form request read it, so their counts follow it (**People tab**,
  **Advanced search panel**).

**Refused combinations and hand-edited URLs.** The store settles what the server would refuse, so no stored state
produces a 400.

- `withoutRefusedCombinations` runs on seeding and after every change: `q` or a panel filter leaves the trash for the
  repository view; a reversed date range is dropped; a Filtered search (`combinesFilters`, the server's rule) drops an
  album and a subset; `sort=relevance` without `q` and `sort=similarity` without a description drop the sort, while a
  column sort stays.
- `normalize` turns any value the server would refuse into the default: `rpp` outside 1 to `Const.search.maxRpp`, an
  unknown `subset`, `peopleMatch` or `triaged` (`ALLOWED_VALUES`), a non-ISO day, an empty or overlong description,
  repeated or excess IDs. The `Const.search` limits are the client's copies of the server's (`Const.Search.MAX_RPP`,
  `MAX_DESCRIPTION_LENGTH`, `SearchRequestParser.MAX_IDS`); change them together.
- A parameter at its default is left out of the request, so a default is never spelled out on both sides. The
  exception is `view`, always sent, whose values match `Const.Search.View.*` verbatim.

**Search input.** `#searchForm` in `includes/nav` is in every page's nav (**Nav refresh**). `bindSearchInput()`
(`js/search-results/search-input.js`) binds it on the pages that run `FrontendApp.start()`; elsewhere (import,
settings) the form's native GET of `/r/:repoId` opens Browse with the text as `q`. The input holds no state: an
Alpine effect writes `searchParams.q` into it. An empty submit with no `q` in the store does nothing, so it never
leaves the folder or view being looked at. Text searches Browse or Triage in place; the Trash page is laid out for
its own view, so from there the store is merged and the browser navigates to `/r/<repoId>?<the store's query string>`.

**What the toolbar offers.** The toolbar offers nothing the server refuses. `includes/search_results` puts the matched
Search text on the fragment (`data-results-q`), and the Sort menu offers "Relevance" only then. A description
(**Describe search** in `altitude/AGENTS.md`) is not a template parameter: it rides on the first filter chip
(`data-app-filter-describe`), and the template reads `isDescribed` off the filters. A described search's Sort menu
offers "Similarity" first and its layout toggle has no Map segment (**Context menus**, **Map view**); sorted otherwise
or grouped, its grid shows the weaker matches last under their header (**Group headers**).

**A description in the store.** `describe` is an ordinary panel filter: seeded from the URL, sent with every request
built from the store (continuations included), and cleared by a place, a view or new quick search text.

- It drops an album, a subset, the map area and the trash from the store, and searches Browse or Triage.
- The layout stays in the store but is left out of the request (`DESCRIBED_UNSENT` in `serializeSearchParams`), as
  the server's URL leaves it out, and applies again once the description goes. So the store's `layout` may say map
  over a grid: ask `showsMap()` (as Curation's closing does), and read the fragment's `data-results-layout` for how
  the page was rendered.
- A panel search with a new or changed description takes the Similarity sort (`applySearchFilters`) whatever sort was
  in force, so it opens most alike first. An unchanged description keeps its sort, and a sort picked later stays. With
  no description left, the Similarity sort is dropped.

**Grouping.** Each Group menu option carries both `groupBy` and `groupDirection`; `location` and `sequence` have a
fixed order and an empty direction, and "No grouping" sets both empty (`null` in the store). The menu offers Sequence
only in Triage. Elsewhere the server groups nothing for `sequence`, while the store and URL keep it, so a return to
Triage groups by Sequence again. There is no page number, grouped or not: the grid continues by its last cell's
`after` cursor.

**What is remembered.** Every parameter lives in the store and the bookmarkable URL. Only the layout
(`Const.localStore.resultsLayout`) and the triage filter (`Const.localStore.showTriaged`) are also remembered in
`localStorage`: `merge` writes a change, and seeding takes the URL first and `localStorage` second, so a map user opens
on the map. Grouping is not remembered.

**The URL.** The browser URL is authoritative once, at page load: `index.scala.html` seeds the store from
`window.location.search`, so a bookmarked or shared search opens. After that the store is the source of truth, and
the URL the server pushes back (`HX-Replace-Url`) is a projection of it, never read back. The server reads only its own
query parameters: it takes nothing from `HX-Current-URL` but the `#tab` fragment (`UrlService.getBrowserViewUrl`), and
there is no "new search" flag. While Asset detail is open the pushed URL waits for the closing (**Detail navigation**).

**Triggers** are declarative and hydrated centrally by `bindSearchTriggers` (`search-triggers.js`), called from
`js/fragments/index.js` and from the explorer lists that build their rows after their tab has settled
(`reloadFolderTree`, `reloadAlbumList`, `reloadLocationList`):

```html
<a data-app-search="click" data-app-search-person-id="abc">          <!-- literal parameter -->
<button data-app-search="click" data-app-search-sort="filename0">     <!-- a choice menu's option: literals too -->
<button data-app-search="click" data-app-search-group-by="dateTaken" data-app-search-group-direction="desc">
<button data-app-search="click" data-app-search-bbox="">             <!-- an empty literal clears the parameter -->
```

The hydrator is the single owner of where a trigger's click goes in the current view (`routeOf`), so the rule is not
in markup and holds for every trigger:

- **Trash** has no explorer: a trigger carrying `folderId` silently does nothing.
- **Triage** finds only triaged assets: a trigger naming a folder, Person or Location asks the Triage scope route
  instead (`requestScopeDialog`). A folder always gets the dialog, except the root folder, which does nothing
  (`isRootFolder`). A Person or Location with triaged assets is a 204 and then searches (`runTriggerSearch`, from
  `js/listeners/dialogs.js`); one without them gets its dialog (**Scope dialog and Quick view**). An empty value
  searches, so the Results title's × and the Triage summary's total still clear a scope there.
- Everything else searches.

`runSearch` called directly is not routed, so the caller decides, as the `personMerged` listener does by searching only
when the search names one of the merged people (**Event Flow**). Its `transient` option holds per-request values that
must never reach the store (`isContinuousScroll`, a continuation's `after` with the layout and grouping its fragment was
rendered with, the crowded-pin panel's grid layout); `target`, `swap` and `source` are documented in `search.js`. A
server-side action that should change what is displayed reports a custom event and lets JS run the search, as the
people merge does with `personMerged`, rather than redirecting to a search URL of its own.
