# Frontend – Agent Guide

## Do

The rules of [altitude/AGENTS.md](../AGENTS.md) **Do** apply here too. For frontend work:

* For UI work, prompt the developer to run "make db" and "make watch" if localhost:8080 is not accessible or if the repo is not initialized (setup form visible or no assets present).
* Use the fewest properties/tags and the least CSS/HTML standards to accomplish the task.
* Do not assume aesthetic preferences such as color, spacing, or padding unless they are specified. The style guide (`views/style_guide.scala.html`, dev only, `/style-guide/r/:repoId`) records the design conventions: read its usage notes before choosing a color, spacing, or component, and keep it current in the same change (the list is under **Style guide** below).
* Always prefer CSS Grid and Flexbox.
* The application's CSS is one bundle: `static/css/app.css` lists `@import` lines in cascade order (`core.css`, `tabs.css`, then one file per template under `pages/`, `includes/` and `htmx/`, named after it), which the build inlines into one content-hashed file. Reuse `core.css`'s `:root` variables. A template's styles go in its own file, such as the folder tree and popover styles in `static/css/htmx/folders.css`, never in a `<style>` block. The bundle applies on every page, so scope a rule by an element only its template renders (usually an ID), and a page's own rule by its body class at zero specificity (`:where(body.setup) main`). A Scala value reaches CSS as a custom property the template sets on the container (`style="--preview-box: ...px"`). Bundled CSS is served from `/assets/`, so its URLs are absolute or `data:`. A nested rule extends its parent by a pseudo-class or a pseudo-element only (`&:focus-visible`, `&::picker(select)`), and a descendant takes a rule of its own: the design tweaks panel's element picker matches a nested selector with `closest()`, which cannot match a descendant one (`& option`).
* Strongly prefer CSS variables over hard-coded values, especially if duplicated.
* Write no color literal, named colors included, in a stylesheet: a rule writes a theme color (`--theme-*`: the primary, secondary and accent colors, a step of the neutral scale or an on-color), a semantic color (`--success-color`, `--warning-color`, `--error-color`, `--info-color`) or `--shadow-color` by its token (`color: var(--theme-neutral-4)`), a shadow by its token, and an edge by a border token, which holds a width and a style but no color, followed by the edge's color in place (`border: var(--border-hairline) var(--theme-neutral-3)`), never restating a value a token holds. Every theme and semantic color is a literal `light-dark(light, dark)` pair of two hex values, so an editor shows a swatch on each line. The setup page's palette (`--setup-*`) is that page's own: `pages/setup.css` declares it on `body:where(.setup)`, as literals, and the style guide's scan leaves the file out (see **Setup background**). The only other color tokens of `core.css` are the veils (`--scrim-color`, `--asset-detail-backdrop-color`, `--dnd-dragging-background-color`, `--panel-border-color`, `--popover-background-color`, `--popover-border-color`, `--field-background-color`), a theme color or `--shadow-color` at an alpha in relative color syntax, the color inside a shadow token is a theme color or one of those at an alpha too, and the four semantic fills (`--success-background-color`, `--warning-background-color`, `--error-background-color`, `--info-background-color`), each a `light-dark()` pair of two HSL relative-color recipes over its semantic color, the one derivation among the colors; no other token aliases a color under another name or mixes one. A new color is a theme color written in the rule, a new `--theme-*` role as a hex pair when no role fits, or a new veil token when it must be translucent. Keyboard focus is one ring, `--border-heavy` in `--theme-primary`, which the `:focus-visible` rule of `core.css` draws on every control; no rule removes an outline, except for the two fields that draw their own focus indicator in its place: a text-like field's accent edge and ring, and the nav's quick search field's glow. The detail is under **Themes** below.
* Paddings, margins, gaps and insets use a step of the `--space-*` scale of `core.css`, or a role token over one (`--panel-padding`, `--viewport-gap`, `--list-row-gap`, …). The steps and the size tokens (widths, heights, radii) are in rem; a rectangular box or control rounds its corners with `--radius`, a button and a toggle group's track with `--radius-large` (a segment inside the track with `calc(var(--radius-large) - var(--space-1))`, the track's radius less its padding), a badge with `--radius-small`, a chip and a switch's track with the pill `--chip-border-radius`, and only a circle, a switch's knob or an avatar writes a radius (a percentage) in a rule; border widths, shadow offsets and the sizes the server sets in pixels (`--face-box`, `--preview-box`) stay in px. A token that JS reads as a number of pixels is registered with `@property` as a `<length>`, so its computed value is in px (`--viewport-gap`). The only literals among them are 0 and auto, centering offsets (`50%` and a negative half size), offsets larger than the scale (the 100 px of the general modal host and of the unanchored popover dialog, the snackbar's top), the 1 px width and height of `.visually-hidden`, and the em offsets and padding of a category's +/− badge in the Locations list, which belong to its glyph.
* A font size is a step of the type scale of `core.css` (`--font-size-xs`, `-small`, `-base`, `-medium`, `-large`, `-xl`, `-display`, in rem like the spacing steps; `--font-size-base` is the body's, and a control that would not inherit it, a button or a menu action, writes it), never a px or em literal, `smaller` or a size relative to a parent. A weight is `--font-weight-bold`, `--font-weight-semibold` (a button's) or `--font-weight-medium` (`normal` resets one), a tracking is `--letter-spacing-wide` or the brand's `--letter-spacing-medium`, and the leading of text that wraps is `--line-height-text`. The font is `--font`, which `body` sets, or `--font-nav`, the monospace stack of the nav's brand, entries and quick search field, which only their rules write. A glyph's own size and leading stay in its rule: the spinner's 3em, the merge arrow's 2em, the 0.5em of a category's +/− badge, and the `line-height: 1` of the menu trigger, the location pin, the close button and the snackbar's dismiss button; the Font Awesome solid weight 900 selects the icon face.
* Folder, Album, and Location actions (including the Add controls and category actions, except Add location) open popover dialogs through `static/js/common/modal.js`, anchored below the row's ⋯ trigger or the Add button and centered on it; Add location opens a centered modal, with room for its map editor. Keep their context menus as action lists; only View settings holds an inline dialog, in its menu's popover panel. A control that picks one of several values (the grid toolbar's Group and Sort, and any dropdown added later) is a **choice menu**: the same native popover panel, built by `buildChoiceMenuCtrl` from a server-rendered host of option buttons, never a `<select>`, which stays a form's field. Every menu panel wears the popover dialog's frosted surface. The detail is under **Modals**, **Context menus** and **Explorer action dialogs** below.

## Stack

- **HTMX 4** — server-driven HTML fragments, no SPA routing
- **Alpine.js** — reactive state and UI behavior (`x-data`, `x-show`, `$store`)
- **No bundler** — all JS uses native ES modules (`<script type="module">`, `import`/`export`)
- Libraries are checked into `static/js/lib/` (htmx, Alpine and its focus plugin, Split.js, interact.js, axios,
  Viselect for box selection, Leaflet for the Location pin editor and the map, supercluster for the map's on-screen
  clustering, media-chrome for the asset detail's video player, tokentweak for the dev-only design tweaks panel); versions
  and sources are listed in `static/js/lib/README.md`,
  whose **Adding a library** says how a new one is vendored, loaded and linted
- No library blocks the first render except htmx: the pages' plain library scripts are `defer` (htmx is not, so it still
  starts on DOMContentLoaded, after its extensions and the modules), and the libraries only some views need (Leaflet
  and its stylesheet, supercluster, media-chrome) are loaded by the first view that asks, through
  `js/common/vendor-loader.js`

## Template Layout

```
views/
  *.scala.html          ← full pages (index, login, setup, pipeline, settings, style_guide)
  includes/             ← reusable layout fragments (nav, header, batch ops, search results, results title)
  htmx/                 ← HTMX partials returned by routes/web/partial/**
```

Full pages include `@includes.html.html_common()` (provides the snackbar's two stacks and its announcers, the tooltip's host, and the four dialog hosts)
and `@includes.html.header_common()` (loads HTMX, Alpine, the stylesheet bundle).

HTMX partial templates live in `views/htmx/`. They are returned by the `routes/web/partial/**`
controllers as `"<!doctype html>" + template(...)`. They carry no `<style>`: a partial's styles are in
`static/css/htmx/<template>.css` (an include's in `static/css/includes/`, a page's in `static/css/pages/`), part of
the one bundle `header_common` links, so a swap carries markup only. Prefer declarative `data-app-*` attributes over inline
`<script type="module">` blocks when the behavior can be hydrated centrally from
`js/frontend-app.js`.

`htmx/folders.scala.html` (styled by `static/css/htmx/folders.css`) supplies the navigation warning, the
`#folderActions` host of the centered "Add folder" button (below the warning), and the empty
`#rootFolderList` host, marked `data-app-fragment="folder-tree"` so `js/fragments/explorer.js`
calls `reloadFolderTree(repoId)` once the tab has settled; edit `js/common/folder-tree.js` for
folder rows and action markup. The tree's interaction contract (expansion gestures, viewed-folder
highlighting) is under **Folder tree expansion and viewed scope** below. `htmx/albums.scala.html`
is the same shape for the Albums tab (`static/css/htmx/albums.css`): the two add buttons, and the empty `#albumList` host
(`data-app-fragment="album-list"`) that `js/common/album-list.js` renders (see **Albums** below), and
`htmx/locations.scala.html` for the Locations tab (`static/css/htmx/locations.css`): the `#locationActions` / `#noLocations` hosts
and the empty `#locationList` host (`data-app-fragment="location-list"`) that `js/common/location-list.js`
renders (see **Locations** below); `htmx/people.scala.html` (`static/css/htmx/people.css`) is the People tab's shell, whose
panes' lists the `peopleTab` Alpine component loads from the server (see **People tab** below); `htmx/curation.scala.html`
(`static/css/htmx/curation.css`) is Curation's shell, `htmx/curation_set.scala.html` (`static/css/htmx/curation_set.css`) a
Curation set's fragment, and `htmx/curation_queue.scala.html` (`static/css/htmx/curation_queue.css`) the rows of its Sequence
rail (see **Curation** below). Partials carry no module scripts: the repository ID is set once by `index.scala.html`, and the
explorer tab that issued a load is marked selected from `htmx:after:request`
(`js/listeners/htmx-requests.js`). The styles the two menus share (`.menu-ctrl`, `.context-menu`,
`.asset-count`) live in `core.css`.

The explorer tabs (`index.scala.html`) are the links themselves (`<a role="tab">` in a
`role="tablist"`, styled by `tabs.css` on `aria-selected="true"`); the initial tab is loaded through
`htmx.ajax` with the tab as `source`, so it is selected exactly as a click would select it. The tab list sits in
`#explorerHeader`, the explorer's header row, which stays while the list hides in triage. Where the explorer sits
against the results, and the divider between them, is under **Explorer dock** below.

## JS Directory ↔ Template Mapping

| JS directory | Template(s) it serves |
|---|---|
| `js/assets/` | asset mutation/action flows such as move, recycle, purge, and restore, plus related grid/snackbar follow-up |
| `js/fragments/` | centralized hydration for declarative HTMX fragments (`data-app-fragment="..."`): modal, inline-dialog, image-detail, the person name editor, search results, the explorer list hosts (`explorer.js`), buttons opening a page-held dialog (`dialog-openers.js`), the batch dialogs' selection field (`selection-field.js`) and their wiring (`add-to-album.js`, `add-to-location.js`), Curation's shell opening its host (`curation.js`), plus the operation lifecycle shared by every dialog (`dialog-operations.js`) |
| `js/listeners/` | domain-focused `document.body` event registration for folders, albums, Locations, people, assets, search keys, Curation (`curation.js`: opening it, the counts after each mutation in it, the grid beneath when it closes), and the Settings form's save (`settings.js`, the success snackbar); the generic htmx request outcome (`htmx-requests.js`: failures to the snackbar, declared success events, tab selection, fragment hydration); the `document`-level dialog request wiring and the popover dialog host's own dismissals (`dialogs.js`); and the map panel's URL correction (`map.js`) |
| `js/search-results/` | the search funnel (`search.js`) and its declarative triggers (`search-triggers.js`); the Search input of `includes/nav` (`search-input.js`); the sticky offsets of the results title, the toolbar and the group headers (`sticky-offsets.js`); the grid's behaviours, one module each: selection (`selection.js`), infinite scroll (`infinite-scroll.js`), the window of loaded pages (`page-window.js`), lazy images (`lazy-images.js`), metadata visibility (`metadata-visibility.js`), the ⚙ View control (`view-settings-control.js`), box selection (`box-selection.js`), the group headers' counts (`date-groups.js`), detail navigation over the grid and image loading (`detail-navigator.js`), the Detail panel of asset detail (`detail-panel.js`: open or closed, its dock, its fragment's loads, the Face selection, the Face highlights' setting and its remembered state, and what a Face correction changes on screen), the Face highlights' layer it draws (`face-highlights.js`), the Face corrections' requests (`face-corrections.js`: Remove's two clicks, Change's pick, their answers), Asset detail's fullscreen (`detail-fullscreen.js`: `f`, the toolbar's toggle and the player's button on the whole of Asset detail), every cell of an asset (`cells.js`), and the post-gesture click swallow (`click-suppression.js`) |
| `js/map/` | the map layout of `includes/search_results` and `htmx/map_view`: the `map-view` fragment hydrator (`map-view.js`, registered in `fragments/index.js` after the results hydrator), the crowded-pin panel (`map-panel.js`), and the view remembered per scope (`map-state.js`); see **Map view** |
| `js/dragdrop/` | interact.js binding modules for assets (`assets.js`, thumbnails and the trash drop zone), batch ops, people, folder-tree, album and Location drag/drop, plus the helpers they share (`helpers.js`) |
| `js/stores/` | Alpine store initialization modules shared by the app shell and feature coordinators, `sequences-to-curate.js` (the number of Sequences in Curation's queue), and `html-common.js`, whose `registerHtmlCommonBindings()` registers what `html_common` binds (the `modal`, `imageDetailLoading` and `snackbar` stores, the `$tooltip` magic and the focus plugin) on every page that includes it |
| `js/frontend-app.js` | app-wide bootstrap/composition root, delegating into asset/dragdrop/fragment/listener/search modules |
| `js/upload-form-page.js`, `js/settings-page.js` | the page modules of `pipeline.scala.html` and `settings.scala.html`. The import page's hydrates its upload form and starts no Alpine. Settings' starts Alpine with what `html_common` binds (`registerHtmlCommonBindings()`, no other store, no fragments) and registers the htmx request rule with a minimal app and the page's own listeners; see **Settings screen** |
| `js/theme-boot.js` | a plain blocking script of `includes/header_common`'s head, on every page with a nav: declares the remembered theme on `<html>` before the first paint, from the key in its `data-key`; see **Themes** |
| `js/explorer-dock-boot.js` | a plain blocking script of `index.scala.html`'s head: writes the remembered Dock side and explorer share on `<html>` before the first paint, from the key in its `data-key`; see **Explorer dock** |
| `js/setup-page.js` | the page module of `setup.scala.html`: it only starts the background scene (`startSetupBackground` on `#setupBackground`); the setup form submits through htmx and `json-enc.js`, which the page loads as plain scripts, so nothing else is registered; see **Setup background** |
| `js/setup-background/` | the background scene of `setup.scala.html`: the page side (`setup-background.js`: feature detection, the palette probe, the Worker's start with the transferred canvas, the `resize`, `visible` and `motion` messages, the fade-in on `ready`), the module Worker that draws the scene on the OffscreenCanvas (`setup-background-worker.js`) and the fields' textures it paints (`setup-background-textures.js`); see **Setup background** |
| `js/common/` | shared: modal (the owner of the four dialog hosts: the general modal, the popover dialog, asset detail and Curation), snackbar, tooltip (the `$tooltip` magic over the `#tooltip` host), navigation, folder-tree (renders the tree), album-list (renders the albums), location-list (renders the categories and Locations), context-menu-markup (builds the ⋯ menu of a folder, album or Location, the dialog-trigger buttons and a choice menu), choice-menus (builds the choice menus of a fragment from their hosts), asset-count (the `(n)` cell after a row's name), viewed-folder-scope (highlights the folder whose results are displayed), htmx-events (accessors for the htmx 4 request context), text-editing (whether focus is in a field that takes the keys typed, for `global.js` and Curation's keys), loupe (the Loupe over Curation's stage panes: the pointer-to-pixel mapping and the level ladder), clipping (the class of every pixel of a loaded image, blown, crushed or neither, read in the browser as the measurement reads them, and the Clipping mask painted from the classes), video-playback (unloading the detail's video and the Media Session), dock (a docked panel: its Dock side, its share, its divider's drag and keys, its Dock side menu's upkeep and its remembered state, the factory the explorer's and the Detail panel's docks are made from), stored-state (a remembered object under one localStorage key that modules share: `readStoredObject` and `writeStoredFields`, which sets some fields and keeps the rest; the Detail panel's view is read and written through it by `detail-panel.js` and `dock.js`), explorer-dock (the main page's Dock side, the explorer's share and the divider: the explorer's configuration of `dock`), token-kinds (the kinds and groups of the tokens, for the style guide), theme (the theme shown and remembered, for the nav's switch and the style guide, and the two colors of a `light-dark()` pair), font-availability (whether the browser has a font) |
| `js/design-tweaks.js` | the adapter of the dev-only design tweaks panel, which `includes/header_common` loads on every page: it configures the vendored tokentweak package (`lib/tokentweak.js`, with `lib/tokentweak-boot.js` for the pre-paint adoption) for Altitude; see **Design tweaks** |
| `js/models/folder.js` | DOM wrapper for folder tree elements and the owner of their expansion state |
| `js/alpine/components/` | Alpine components: `date-group-selectable.js` (a group header's checkbox over its group's cells, a day, a Location or a Sequence), `context-menu.js` (native popover menus of folders, albums and Locations, View settings and the choice menus, and the functions that close one from outside), `view-settings.js` (the View settings dialog's switches over the grid metadata set) `people-tab.js` (the People tab's buckets, Split view and pane loads), `curation.js` (Curation's state, on its shell) and `face-combobox.js` (the Change combobox of a People row in the Detail panel); `index.js` registers them |

## Alpine.js Stores (initialized in `js/stores/app-stores.js` and `js/stores/html-common.js`)

`initializeFrontendStores()` (`js/stores/app-stores.js`), which `js/frontend-app.js` runs before `Alpine.start()` on
the main page and the style guide, registers every store below. The ones `html_common` binds, `modal`,
`imageDetailLoading` and `snackbar`, it registers through `registerHtmlCommonBindings()` (`js/stores/html-common.js`),
with the focus plugin for the modal hosts' `x-trap`; Settings calls that registrar alone. Each page calls it once,
before Alpine starts.

| Store key | Purpose |
|---|---|
| `Const.state.selectedAssets` | Reactive `Set` of the selected asset IDs, and the one place a selection changes (see **Selection** below) |
| `Const.state.searchParams` | The complete search parameter set — the single source of truth for what is being searched (see **Search parameters** below) |
| `Const.state.currentView` | Current view: `repository`, `triage`, or `trashbin`. Derived from `searchParams.view` and written once at page load |
| `Const.state.resultsTotal` | The toolbar's results total: `count` and `isCapped`, set from the results fragment's `data-results-total` and `data-results-total-capped` (`js/fragments/search-results.js`). `label` reads the count followed by "+" when the server counted only up to its cap (`js/common/total-label.js`, "10000+"); `decrement` lowers an uncapped total as assets leave the grid and leaves a capped one alone, since it says only that there are more than the cap |
| `Const.state.sequencesToCurate` | The number of Sequences in Curation's queue (`js/stores/sequences-to-curate.js`): `count`, null until the first answer, which only the count endpoint sets, through `refresh`, so nothing adds or subtracts on the client, and an older answer never replaces a newer one. `hasAny` and `label` are Triage's Curate row's, `remaining` Curation's header (see **Curation**) |
| `Const.state.modal` | The active dialog host, its title and the identity of the displayed open (`js/common/modal.js`, see **Modals**); bound by the modal hosts and the popover title of `html_common` |
| `Const.state.imageDetailLoading` | `value`: whether asset detail is loading its asset, which shows its spinner (`js/search-results/detail-navigator.js`) |
| `Const.state.snackbar` | The snackbar's messages (`js/common/snackbar.js`): `items` in arrival order, read as the two stacks `errors` and `notices`, which `html_common` renders. Producers call the module's three functions, never the store (see **Snackbar** below) |
| `Const.context.repoId` | Active repo ID — set from Twirl via `window.ctx.setRepoId(...)` |
| `Const.context.gridMetadataFields` | Reactive `Set` of metadata field names shown in the grid; persisted in `localStorage` as a JSON array |

Always access the repo ID via `window.ctx.getRepoId()`, not directly from the store.

The People tab's state is no store: its `peopleTab` component holds the buckets of each pane, whether Split view is on
and the panes' Split.js sizes on each axis as component data and remembers them in `localStorage` under
`Const.localStore.peopleView`, which nothing else reads (see **People tab** below). Nor is the Loupe's level: Curation's
`curation` component reads and remembers it in `localStorage` under `Const.localStore.loupeLevel` (`rememberedLoupeLevel`,
`saveLoupeLevel`), which nothing else reads, and the Bundle kinds whose details its rail shows under
`Const.localStore.curationDetails` (`rememberedDetailKinds`, `saveDetailKinds`), a JSON array, and whether its stage shows the
Histogram strips under `Const.localStore.curationHistogram` (`rememberedHistogramShown`, `saveHistogramShown`), "true" or "false",
and whether it shows the Sequence rail under `Const.localStore.curationSequenceRail` (`rememberedSequenceRailShown`,
`saveSequenceRailShown`), "true" or "false" too (see **Curation** below). Asset detail's view is no store either: `js/search-results/detail-panel.js`
remembers whether the Detail panel is open, whether the Face highlights are on, the panel's dock and the open Metadata groups under
`Const.localStore.assetDetailView` (see **Detail panel** below).

## DOM Addressing Convention

JS code reads element identity from `data-*` attributes — **never** positional or class-based
selectors. All attribute names are defined in `Const.attributes` (`constants.js`), and are read
through `element.dataset` by their camel-cased key:

```js
Const.attributes.folderId       // "data-folder-id"        el.dataset.folderId
Const.attributes.albumId        // "data-album-id"
Const.attributes.locationId     // "data-location-id" (a Location row and its drop zone)
Const.attributes.categoryId       // "data-category-id"   (a Location row under a category)
Const.attributes.kind           // "data-kind"        (`category` | `location` on a Location row)
Const.attributes.assetId        // "data-asset-id"
Const.attributes.numOfChildren  // "data-num-of-children"
Const.attributes.expanded       // "data-expanded"
Const.attributes.viewedScope    // "data-viewed-scope" (on the folder node whose results are displayed)
Const.attributes.dataSrc        // "data-src"  (lazy-load image URL)
Const.attributes.sequenceId     // "data-app-sequence-id" (a group header's Curate) el.dataset.appSequenceId
Const.attributes.railSequenceId // "data-rail-sequence-id" (a row of Curation's Sequence rail) el.dataset.railSequenceId
Const.attributes.minFrames      // "data-min-frames"  (the fewest frames a group header's Curate stays for)
Const.attributes.frameId        // "data-frame-id"  (a frame of a Curation set in the rail, in the rail's order)
Const.attributes.columnFrameId  // "data-column-frame-id"  (a frame's column of the face strip)
Const.attributes.bundleKind     // "data-bundle-kind"  (the Bundle a frame sits under; a Contender has none)
Const.attributes.loupeLevel     // "data-level"  (the Loupe's level on a stage pane's lens, which its stylesheet shows)
Const.attributes.clippedLevel   // "data-clipped-level"  (the gray level a pixel counts as blown at, on a Curation set's root)
Const.attributes.crushedLevel   // "data-crushed-level"  (the gray level a pixel counts as crushed at)
Const.attributes.measurementBox // "data-measurement-box"  (the box the measurement fits an image into before it counts)
Const.attributes.histogram      // "data-histogram"  (a frame's Histogram for the stage's strip: a byte per bin, in Base64)
Const.attributes.crushedShadows // "data-crushed-shadows"  (the fraction of a frame's pixels crushed, as the import measured it)
Const.attributes.clippedHighlights // "data-clipped-highlights"  (the fraction of a frame's pixels blown)
Const.attributes.dockSide       // "data-app-dock-side"  (a pick of the explorer's Dock side: the options of its menu)
Const.attributes.detailDockSide // "data-app-detail-dock-side"  (a pick of the Detail panel's, its own since both docks listen on the body)
Const.attributes.detailPanelToggle // "data-app-detail-panel-toggle"  (the Detail panel's toggle in asset detail's toolbar)
Const.attributes.faceHighlightsToggle // "data-app-face-highlights-toggle"  (the Face highlights' toggle beside it)
Const.attributes.fullscreenKey     // "data-app-fullscreen-key"  (the key legend's fullscreen entry, removed with the toggle where fullscreen is not allowed)
Const.attributes.faceRow           // "data-app-face-row"  (the button of a People row that selects its Face)
Const.attributes.faceChange        // "data-app-face-change"  (a selected row's Change, which opens the combobox)
Const.attributes.faceRemove        // "data-app-face-remove"  (a selected row's Remove, with its second label in `data-app-face-remove-label-last`)
Const.attributes.readOnly          // "data-app-read-only"  (the People section of an asset in the trash: no correction)
Const.attributes.resultMessage     // "data-app-result-message"  (a correction's result on the section it answers with; a deleted Person's ID and line in `data-app-deleted-person-id` and `-message`)
Const.attributes.faceId            // "data-face-id"  (a People row, a Face highlight and its badge)
Const.attributes.personName        // "data-person-name"  (a People row's Person, with `data-person-hidden` when Hidden)
Const.attributes.suggested         // "data-suggested"  (an option of the assignable people fragment in its Suggested group)
Const.attributes.frameTimeMs       // "data-frame-time-ms"  (a People row of a Face in a Video)
Const.attributes.detailDimensions  // "data-app-detail-dimensions"  (the Detail panel's dimensions row, which reads the shown element's size)
Const.attributes.metadataGroup     // "data-app-metadata-group"  (a Metadata group's directory name, which the open groups are remembered by)
Const.attributes.detailPanelOpen   // "data-app-detail-panel-open"  (the Asset detail fragment while the Detail panel is open; the stylesheet lays the grid out from it)
Const.attributes.appAssetId        // "data-app-asset-id"  (the asset of the Detail panel's fragment and of its People section; not `data-asset-id`, which finds the grid's cells)
```

A Curation set's root carries `data-opening-pinned-id` and `data-opening-focused-id`, and the
measurement's `data-clipped-level`, `data-crushed-level` and `data-measurement-box`, which the stage's Clipping blink reads; a frame
`data-file-name`, `data-problems` and `data-in-album-or-location`, and, when it was measured, `data-histogram`, `data-crushed-shadows` and
`data-clipped-highlights`, which the stage's Histogram strip shows; a crop `data-person-id` and `data-face-box`; and the batch ops' Curate
`data-max-selection`; a group header's Curate `data-min-frames`. A frame in the rail carries `data-frame-id` and not `data-asset-id`, which finds the cells of the grid beneath
(`js/search-results/cells.js`). Each stage pane's lens is addressed by its ID (`curationLoupePinned`, `curationLoupeFocused`) as the
stage images are (`curationStagePinned`, `curationStageFocused`), and carries `data-level` (`Const.attributes.loupeLevel`), the level the Loupe writes for its label.
Each pane's Histogram strip (`curationHistogramPinned`, `curationHistogramFocused`) and Clipping canvas (`curationClippingPinned`,
`curationClippingFocused`) are addressed by ID as the images and lenses are.

The one exception is `alt-has-no-date` on a result cell, which the controller tests assert on.

The `Folder` class in `js/models/folder.js` is the canonical DOM abstraction for folder tree
nodes. `Folder.find(id)` returns the folder rendered as `#folder-{id}` or `null` when the tree
holds no such node (a rebuild removed it mid-interaction); it wraps `#children-{id}` and
`#folderName-{id}` too. `parent()` is `null` for root, which carries no parent attribute. The folder tree, including each folder's ⋯ menu (`#folderMenuCtrl-{id}` opening
the `#menu-{id}` popover), is rendered client-side by `js/common/folder-tree.js` from the JSON
tree endpoint; those IDs are stable so tree rebuilds can restore focus and dialogs can return it.
Each node of the tree JSON carries `numOfChildren` and `numOfAssets` (see **Folder asset counts**).

`reloadFolderTree(repoId)` discards superseded responses and snapshots the expanded folder IDs and
the focused control immediately before replacing the tree, so a gesture made or focus returned by a
dialog while the request was in flight is not undone by an older snapshot. Expansion is restored top
down through the folder model: a surviving branch is re-expanded only when its parent is expanded,
so a folder moved beneath a collapsed parent is normalized to collapsed, and a folder that lost its
last child gets a plain icon. The latest viewed scope is reapplied independently of that snapshot.
It calls `htmx.process(container)` after insertion; Alpine's mutation observer initializes the new
subtree. Do not also call `Alpine.initTree`, which duplicates initialization and listeners.

Folder indentation comes from each non-root node's `--depth` and `--list-indent`, the indentation
step of the explorer lists, which `core.css` declares and the Location list shares. The `.trace` grid
cell (`static/css/htmx/folders.css`) indents the icon and name while `.menu-ctrl`
stays flush left at every depth; the root row omits the trace cell. Keep renderer markup and
these grid rules in sync.

## Event Flow

Two-layer event bus, both on `document.body`:

1. **Custom DOM events** — string names in `Const.events`, dispatched via `new CustomEvent(...)`,
   handled by the domain modules in `js/listeners/`. These are business-level actions (e.g.
   `FOLDER_MOVED_EVENT`). A request element or a dialog announces its outcome declaratively:
   `data-app-success-event="FOLDER_ADDED_EVENT"` (the name verbatim), `data-app-success-detail`
   (JSON), and `data-app-success-detail-target-attr-<key>="<attribute>"` for a value read from the
   element that issued the request (`readSuccessEvent` in `js/fragments/helpers.js`).
2. **HTMX lifecycle events** — `htmx:before:request` / `htmx:after:request` / `htmx:after:settle` —
   handled by `js/listeners/htmx-requests.js` (one rule for every request: a response of 400 or
   above goes to the snackbar with its path and status; a validation replacement, a 200 with
   `HX-Retarget: this`, is swapped in and announces nothing; any other successful one dispatches the
   declared success event and, for a tab, selects it; every swap hydrates the fragments it inserted) and by
   `js/listeners/dialogs.js` for dialog opens and dialog operations. There is no router keyed on
   request URLs.

### HTMX 4 conventions

- Lifecycle events carry the request context under `event.detail.ctx`. Read it through the helpers
  in `js/common/htmx-events.js` (`getRequestPath`, `getResponseStatus`, `isRequestSuccessful`,
  `getResponseText`, `getResponseHeader`, `getRequestSource`, `getResponseRetarget`) instead of touching the detail
  directly.
- `htmx:after:request` fires when the response has arrived but **before** it is swapped in.
  Cancelling it (`preventDefault()`) drops the swap. Once the issuing element has left the DOM, htmx
  dispatches lifecycle events on `document` instead, so listeners that must see such late responses
  (dialog operations) go on `document`, not `document.body`.
  `htmx:after:settle` fires on the swap target once per swap with `detail.newContent`, the list of
  inserted nodes; `js/listeners/htmx-requests.js` hydrates `data-app-fragment` roots from there.
- A form that swaps itself with its field focused swaps with `outerHTML settle:0` (the dialogs' validation responses, the
  Settings form, the person name editor). htmx's settle copies each old element's attributes onto the new element with the
  same id and restores them afterwards, skipping `value` on the element that has focus, so without `settle:0` the field
  shows the old value and the text submitted is lost.
- Attribute inheritance is explicit: every element that issues a request declares its own
  `hx-target` / `hx-swap`. Do not rely on a parent's attributes (add `:inherited` if you ever must).
- Extensions activate by script inclusion, there is no `hx-ext`. Forms that post JSON carry the
  boolean `hx-json-enc` attribute (`json-enc.js`); the import status stream uses `hx-ws:connect`
  with an explicit `hx-target` / `hx-swap` on the connection element (`hx-ws.js`). No Alpine
  compatibility shim is loaded: Alpine's mutation observer initializes swapped-in markup, and
  nothing calls `Alpine.initTree` on it.
- Polling is declarative: the import page refreshes the nav with
  `hx-trigger="every 5s [document.visibilityState === 'visible']"` (its `hx-get` carries `current=import`, so the
  swap marks the page's entry again; see **Nav**).
- Global config (`defaultTimeout`, `noSwap`) is the `htmx-config` meta tag in
  `views/includes/header_common.scala.html`; 4xx/5xx bodies are not swapped, listeners report them.
- The file upload (`js/fragments/upload-form.js`) is **not** an HTMX request: it posts through the
  shared axios client so `onUploadProgress` can drive the progress bar, then swaps the returned
  fragment itself.
- A Curation set's fragment is **not** an HTMX request either (its shell is): the `curation` component reads it through the
  axios client and puts it in `#curationSet` itself, since it has to see the answer before anything is swapped (a 204 passes
  over a Sequence or leaves the too-few state, and a preload keeps only the opening frames) and reports a failure itself, in the
  unread state (see **Curation**). The queue's rows, the Sequence rail's fragment, are read the same way, into the rail's host.

Batch ops escalate a single-asset drag to a batch when the asset is among the selected ones: the
`assetMoved`/`assetTrashed` handlers in `js/listeners/assets.js` re-dispatch
`batchAssetsMoved`/`batchAssetsRecycled`. Person merge/name/cover-face events and the discard
follow-up live in `js/listeners/people.js`: a merge, a hide, a show, a discard or a Face correction in the Detail panel
announces `peopleChanged`, which the
People tab answers by reloading each pane with its own buckets (see **People tab**), while a rename and a cover-face
change patch every cell of the person, found by `data-person-id` under `#people`; a purge of the recycle bin announces
`trashPurged`, which refreshes the counts. `confirmPersonMerge`'s listener sends the event's whole detail as the values of
the Merge people modal's request, so a `keepDirection: true` reaches `showMergePeopleModal` as a query parameter: with it
the modal keeps the requested source and destination, and without it, as for a drop, the person with fewer faces is the
source.

### Full action flow (drag-and-drop example)

```
interact.js drag end / ondrop
  → dispatch CustomEvent on document.body
    → js/listeners/<domain>.js listener
        → shared axios client (`js/http/client.js`) or htmx.ajax() to server
          → on success: direct DOM mutation + snackbar + optional nav reload
```

Folder expansion and the folder context menus involve no request at all: the tree renderer builds
them. A failed folder or album request (a tab load, a dialog load) is reported like any other
failed request, by `js/listeners/htmx-requests.js`; the only `htmx:before:request` listener is the
dialog one on `document`. The person name editor's outcome is the one response the client has to
inspect: the saved name and the re-rendered editor both arrive as HTTP 200 with no header, so
`js/listeners/people.js` parses the response and dispatches `personNameEdited` only when it holds
no editor. A name another live person holds re-renders the editor with `conflictingPerson`: the field's error row holds the
message, naming the holder as stored and saying so if the holder is hidden, and a `Merge into X…` button dispatching
`confirmPersonMerge` with `keepDirection: true`, so the renamed person merges into the holder, whose ID survives. The
response still holds the editor, so no rename is announced, and the editor stays open behind the modal; closing the modal
leaves the editor and its error.

## Alpine.js Integration

Alpine serves four distinct roles in this codebase:

### 1. Global shared state (stores)
Stores initialized in `js/stores/app-stores.js` (run by `js/frontend-app.js`) and, for what `html_common` binds,
`js/stores/html-common.js` hold shared data — the active repo ID, current view, the set of selected assets, the active
dialog host, and the snackbar's messages. All access goes through `window.ctx`
helpers or `Alpine.store(Const.state.*)` calls; no module reads the store key strings directly.

### 2. Selection
An asset cell has no Alpine component. What is selected is the reactive `Set` of asset IDs in the
`selectedAssets` store (`js/search-results/selection.js`), and the store is the one place a
selection changes: `select`, `deselect`, `toggle`, `reset`, and `setDragging` update the set and
paint the cells they concern (`.selected` / `.masked` on every `.drag-drop[data-asset-id]` of the asset, found
through `js/search-results/cells.js`: a Location grouping holds an asset once under each of its Locations). One delegated
click listener per displayed grid toggles an asset from its checkmark or a Shift-click on its
image; box selection commits through the same methods; drag handlers read `contains(id)` to
decide whether a drop escalates to a batch and call `setDragging` to dim the selection while it
moves. Cells entering or leaving the grid are announced with `noteGridChange()`, which bumps a
reactive counter the group headers read (the DOM itself is not reactive). Both copies of the batch ops (see **Batch
ops**) bind to the set (`x-show`, `x-text`), and `reset()` from their Deselect all (×) button clears it.

### 3. Coordination around native UI
`x-data="contextMenu"` (registered with `Alpine.data` in `alpine/components/index.js`, defined in
`alpine/components/context-menu.js`) wraps each folder's, album's or Location row's ⋯ trigger and its native
`popover="auto"` panel, the ⚙ View button and its panel, and each choice menu's trigger and panel. The browser owns the menu's visibility (`:popover-open`, `popovertarget`, light dismiss on
outside clicks); the component only places the panel, moves focus into it and between its actions, closes it when focus leaves or when the page scrolls or the explorer
resizes, and cleans up. Nothing binds `x-show` or an `open` flag to it. See **Folder context
menus** below.

### 4. Rendering a page's own data
A page that renders data it already holds may do so with `x-for` templates over one component, so its
markup stays in the Twirl template instead of being built node by node in JavaScript. The style guide
is the one such page: `x-data="styleGuide"` is on its `<main>`, so the contents column and the sections
share it, and the component (`js/style-guide.js`,
registered when the page's module script imports it, before `initApp()` starts Alpine) holds the data
and the logic that needs the browser. Keep the expressions in such a template to property reads and
single method calls: nothing lints them, so anything longer belongs in the component. A card or row
repeated across sections is a Twirl reusable block (`@tokenCards(kinds, group)` in `style_guide.scala.html`),
since Alpine has no template reuse of its own. Twirl reads `@` as its own, so Alpine's shorthand is
written `@@click`. `html_common` renders the `snackbar` store the same way, on every page that includes it: each
stack is an `x-for` over the store's `errors` or `notices`, its bindings property reads, single calls on the store
(`classesOf`, `iconOf`, `isRepeated`, `countOf`, `dismiss`, `syncStack`, `onStackFocusOut`), its events written `x-on:`. The explorer lists (folder tree, albums, Locations) are still rendered by their JavaScript modules; the People tab's
`peopleTab` component (`alpine/components/people-tab.js`) renders nothing itself: it holds the tab's remembered state, binds
the bucket switches and the Duplicate pane's `x-if` to it, and has htmx load each pane's list (see **People tab** below).
The `curation` component (`alpine/components/curation.js`) renders nothing either: the server renders a Curation set, and the
fragment's bindings read the component's state (see **Curation** below).

Alpine is intentionally **not** used for routing, server communication, or HTMX trigger logic —
those responsibilities stay with HTMX and the custom event bus. The one exception is the `curation` component, whose reads and
outcomes go through the axios client (see **HTMX 4 conventions**).

## UI Patterns

**Modals** — Four dialog hosts live in `html_common.scala.html`, named by `ModalHost`: load into `#modalContent` for the
general modal, into `#popoverContent` for the popover dialog, into `#imageDetailModalContent` for
asset detail, and into `#curationModalContent` for Curation. `js/common/modal.js` is the single owner of all four: which host is active (one at a
time — a new open replaces the active dialog, whatever its host), the title,
initial focus (the fragment's autofocus selector, else the close control, else the popover host itself, never a destructive action),
focus restoration on close (the fragment's `data-app-dialog-return-focus` selector, else the
control that opened it, using the visible ⋯ trigger for a menu action), and identity: every displayed open has an `openId`
(`isModalOpenActive(openId)` guards asynchronous work), and the latest "open request" (any HTMX
request targeting a host) is tracked so a slow response for an earlier open is cancelled before it
swaps once the user dismissed or replaced it. The three modal hosts' visibility is bound through the Alpine `modal` store
(`x-show`; `x-trap.inert.noscroll` from the vendored `@alpinejs/focus` plugin, registered by
`registerHtmlCommonBindings()` in `js/stores/html-common.js`, contains focus and hides the page from assistive tech; its documented local patches
cancel delayed activation when a trap is released or removed, and count controls in open shadow
roots, the video player's sliders, as inside the trap). Escape (`global.js`) closes one thing at a time and is consumed
by it, which also keeps the browser's own Escape handling for the popover and the menus from running a second time, so
a background inline edit survives: in fullscreen it is the browser's, which leaves fullscreen while Asset detail stays
open (see **Detail navigation**); else the open listbox of a People row's Change combobox in the Detail panel closes
alone (see **Detail panel**); else an open context menu closes alone, with focus back on its trigger, so the Detail
panel's Dock side menu leaves Asset detail open (no other menu is open together with a dialog: opening a dialog closes
the menus, and a menu's opening light-dismisses the popover dialog); else the active dialog; else the map view's
crowded-pin panel; and with none of those it is broadcast for such an edit to cancel. An open select picker's Escape
is left to the picker, which closes itself and not the dialog holding it. General dialogs and Curation ignore backdrop clicks, asset detail closes on them. The general
modal is centered, 100px from the top, `--modal-content-width` wide (the Location map editor's dialog declares a wider
one); asset detail is fitted by CSS from the size as shown, which `setAssetDetailSize()` sets on its box, in its own host, and fills the viewport while its Detail panel is open (see **Detail panel**); Curation
fills the window, over the nav, the explorer and the grid, with its title and Exit in its shell (see **Curation**).
Switching hosts releases the active one in its own reactive flush before the next is activated, and hides the
popover when it was the active one. Opening any dialog closes an open context menu first, so a
dialog never appears over one. A host keeps its last fragment after it closes; opening the general modal or the
popover dialog empties the other once it has faded out, so two dialogs never share a field ID (a `<label for>`, an
autofocus selector). A validation replacement empties nothing. Curation's component empties its own host as it closes.

The popover dialog (`#popoverDialog`) is a native `<dialog popover="auto">`, a dialog by its element rather than an
ARIA role, with no scrim and no focus trap, so the page stays live. The browser owns its visibility (`:popover-open`; `modal.js` changes it only through `showPopover()` and
`hidePopover()`, and nothing binds `x-show` to it); the store names it as the active host while it is open, for the
shared title and the one-active-host rule. CSS anchor positioning places it, and no JS measures anything: every
trigger `js/common/context-menu-markup.js` builds carries `anchor-name: --<its id>`, a popover request names its
trigger's ID in `data-app-dialog-anchor`, and `openModal` sets the host's `position-anchor` to `--<that id>`. The
trigger IDs are stable, so a list rebuild that renders the trigger again re-anchors an open popover with no observer.
`openModal` also passes the trigger as `showPopover({ source })`, which puts the popover next in the Tab order after
it, so Tab out of the popover goes on from the trigger; the implicit anchor that `source` creates is a reference to
that one element, which a rebuild would leave behind, so the named `position-anchor` overrides it.
`position-area: bottom` puts the popover below its trigger and centered on it, slid back inside the viewport at a side
edge, `--popover-anchor-gap` (`0rem`, flush) from the trigger and `--viewport-gap` (`0.625rem`, 10px at the default
root) from the viewport's edges, and at most the viewport's width less a `--viewport-gap` each side, its content
wrapping anywhere (`overflow-wrap: anywhere`, so a long unbroken name wraps). When it does not fit below,
`position-try-fallbacks: flip-block, --popover-capped, --popover-capped-above` under `position-try-order: most-height`
tries the roomier side's options first (the base placement below is always tried first, and the order sorts only the
fallbacks): `flip-block` puts it above when that side is roomy enough, and `@position-try --popover-capped` (below)
or `--popover-capped-above` caps its height on the roomier side, its content scrolling; the capped-above option
swaps the block margins as `flip-block` does, so the anchor gap faces the trigger and `--viewport-gap` the viewport's
top. It follows its trigger as the explorer panel (`#explorerPanelContent`, the explorer's scroll container) scrolls and the explorer's divider moves, and
`position-visibility: anchors-visible` hides it while its row is clipped out of the explorer. An open whose trigger is gone or not displayed marks the host
`data-unanchored`, placed like the general modal: 100px from the top, centered, its height capped at
`--viewport-gap` from the bottom. A validation replacement re-hydrates into the open host and only refocuses: the
popover is neither re-anchored, hidden nor shown again.

The browser dismisses the popover on an outside click (light dismiss), dropping what was typed. Its `beforetoggle`
listener (`bindPopoverHost()`, called by `registerDialogListeners` in `js/listeners/dialogs.js`) runs the close
bookkeeping synchronously, before the click that dismissed it can request another dialog, so it never cancels the
open that click requests; focus is restored only when it was still inside the host, so a click elsewhere keeps the
focus it gave, or the body. Its `focusout` listener closes it when Tab, or Shift+Tab from the first control, moves
focus to a control outside it, leaving focus there; a focus change with no related target (a click, a validation swap
removing the focused field) is left to light dismiss. Escape, the × and a completed operation close it through
`closeModal()` and restore focus as for a modal, unless focus already sits on a control outside the host. The host
carries `tabindex="-1"`, as a context menu panel does, so a click on its title, a label or its padding moves focus to
the host rather than the body and never reads as focus leaving. Closing a dialog leaves the store's title in place,
so the popover keeps its title while it fades out; the next open sets its own.

The popover is a translucent, frosted surface: `--popover-background-color` under `--popover-backdrop-filter`, a
hairline of `--popover-border-color`, `--radius-large`, and `--sheen-shadow` over `--float-shadow`. Its title and
close control sit at the top with no divider (`.popover-toolbar`), and it fades in and out over 150 ms under
`prefers-reduced-motion: no-preference`. A form inside fills `--popover-form-width` (20rem, at most the popover's
width), and its inputs and selects the form; the form's own action (Move's button, `#popoverContent form > button`)
sits `--space-5` below its fields, while a label and an error stay `--space-2` from their field. The Delete
confirmation has no form and keeps its button's width.

Every dialog host but Curation's, whose shell has Exit, uses `.close-modal` in `core.css`: the X keeps the text color (`--theme-neutral-4`)
at rest, on hover, and on focus, with no focus outline (the modal may focus it on open). The popover's is quieter,
`--font-size-large` in its text color at 0.6. Folder, album, Location, and category dialogs use the popover host and
its close control; Add location uses the general modal host.

A **dialog** is a server-rendered form that completes one user action, hydrated from its
`data-app-fragment` kind; a **modal dialog** is one shown in a modal host, a **popover dialog** one
shown in the popover host. Attributes that describe
the dialog itself are `data-app-dialog-*` on the fragment root, whatever its presentation: autofocus
selector / select-on-focus, return-focus selector (the control focus goes to on close, chosen to
survive the page update the dialog triggers), and `kind`, naming wiring a dialog needs beyond its
form (the Add to album and Add to location selections, hydrated from `js/fragments/modal.js` through `js/fragments/add-to-album.js` and `js/fragments/add-to-location.js`). Its success
event is declared like any request element's, with `data-app-success-event` (+ `-detail`,
`-detail-target-attr-*`) on the fragment root. An outcome only the server knows (how many assets a
membership change applied) arrives as JSON in the `App-Success-Detail` response header
(`BaseController.dialogSuccessResponse`, `Const.http.successDetailHeader`), which
`dialog-operations.js` merges over the declared detail. The one attribute that describes the host is
`data-app-modal-title`, the title either host shows. Folder, album, Location, and category actions, including the Add
controls, are **popover dialogs**; Add location, Add to location, the people dialogs and the purge confirmation are
**modal dialogs**. Only View settings is an
**inline dialog** (`data-app-fragment="inline-dialog"`, see **Context menus**); its switches apply
immediately without submitting an operation.

A dialog the page already holds needs no request: a button with `data-app-open-dialog="#id"`
names a `<template>` whose content is a `modal` fragment; `js/fragments/dialog-openers.js` copies
it into the general modal host, runs `htmx.process` on it, and hydrates it like a fetched dialog, so it
opens, submits, closes, and announces its success event through the same code. The purge
confirmation in `htmx/trashbin_header.scala.html` is the one such dialog in the app (`hx-confirm` is not
used); the style guide's labelled fields specimen is another.

General HTMX modal fragments opt in with `data-app-fragment="modal"`; `js/fragments/modal.js` opens the
host the fragment was swapped into on hydration (the popover host for `#popoverContent`, else the general modal), so
a `modal` fragment's presentation follows the host its request targets and the template does not say which. It registers the modal presentation with `js/fragments/dialog-operations.js`, which
tracks each operation any dialog submits from `htmx:before:request` (capturing a handle to the dialog
it belongs to - for a modal or popover dialog, its open - and its success metadata while the fragment is still in the
DOM; a repeated submission while one is pending is dropped). When the response arrives it dispatches
the success event exactly once, lets normal page updates through even if the dialog was closed or
replaced, and closes the initiating dialog if it is still active. Validation
responses are recognised by their `HX-Retarget: this` / `HX-Reswap: outerHTML settle:0` headers
(`BaseController.dialogFormValidationResponse`; the immediate settle keeps htmx's settle step from
copying the old field's empty value attribute over the submitted value): they replace the active
form in place (values and errors kept, no success event) or, once the dialog is gone, are reported
through the snackbar.
`htmx:finally:request` clears operations left pending when a network failure skips
`htmx:after:request`, reports the failure through the snackbar, and allows another submission.
`js/listeners/dialogs.js` registers this wiring on `document`, because htmx dispatches lifecycle events
on the document when the issuing element has already left the DOM.

Asset detail: `js/fragments/image-detail.js` opens the host with its spinner immediately and hands
the image load to the detail coordinator, which gives each image request a token so only the latest
request for the active open may change the image, box size, title, loading state, or current asset
(rapid previous/next navigation, page fetches, and loads that finish after closing are ignored).
The navigation origin is the cell whose thumbnail requested the detail (`modal.js` keeps the element
that issued an open's request, `getModalOpenSource()`), set before the image loads, so previous/next
already steps from the newly opened cell while the spinner is showing. A detail opened from no cell
(a map pin) has no previous or next.
Arrow-key navigation works only while asset detail is active and no text field is focused, or the video player's
timeline or volume slider, which the arrows step, or the Detail panel's divider, which they move; Space plays or pauses
the current Video, through media-chrome inside the player, and does nothing on an image, except on a control in the
player or in the Detail panel, which it presses; `m` opens or closes the Detail panel, `p` shows or hides the Face
highlights and `f` puts Asset detail in fullscreen or leaves it, wherever focus is, a held key or a modifier left to the
browser (see **Detail panel**, and **Detail navigation** for fullscreen).

**Context menus** — Each folder's, album's or Location row's ⋯ button (its **trigger**, `.menu-trigger`) is a real
`button` with `popovertarget` pointing at a `popover="auto"` panel (`.context-menu`; `#menu-{id}`
for a folder, `#albumMenu-{id}` for an album), all built by `buildContextMenuCtrl` in
`js/common/context-menu-markup.js` as the tree or list is rendered, so opening a menu sends no request.
Each action requests its dialog into the popover host (`#popoverContent`), naming the menu's ⋯ trigger in
`data-app-dialog-anchor`; the modal owner closes the menu when the response opens (the browser would close it too,
since neither popover is the other's ancestor). Menu panels contain only their action buttons, with no inline form or
dialog host. `buildModalTriggerCtrl` builds the Add folder, Add album, Add location, and Add category buttons
(including empty-state controls) as direct requests: into the popover host, anchored to the button itself (`host`
`ModalHost.popover`, the default), or into the general modal host with no anchor (`host` `ModalHost.general`, which
Add location and "Add your first location" pass); any other host throws. Every trigger the module builds carries `anchor-name: --<its id>` (`_buildButton`),
the View settings button too, which nothing anchors to.

The ⚙ View button above the results grid (`#viewSettingsBtn`, built by
`js/search-results/view-settings-control.js` into `#viewSettingsActions`) uses
`buildDialogTriggerCtrl`, a `.dialog-trigger-ctrl` with an inline dialog host. Its `dialog-only`
panel stays invisible until the settings arrive and Alpine has bound their switches (the reveal waits a
microtask behind the mutation observer's, so no switch slides from off into its state), opens below the
button centered on it (`data-menu-align="center"`), and is cleared on close. The component records its load request and
drops a response after the panel has closed or reopened; it listens on the component root, since htmx
dispatches the request events on the button that issued the load, not on the panel beside it. The host
is emptied again as a load starts, so the swap never meets a leftover fragment: htmx settles an element
whose ID the old content also had back to its served attributes, which would strip the `aria-checked`
Alpine set as the fragment initialized. Each switch applies immediately; View settings submits nothing.

A **choice menu** (the toolbar's Group `#groupMenuHost` and Sort `#sortMenuHost`, `includes/search_results.scala.html`)
picks one of several values. The server renders a host, `data-choice-menu="<name>"` with `data-choice-menu-icon` and,
for Group in the map layout, `data-choice-menu-disabled`, holding one `button` per option: a search trigger carrying
its literals (`data-app-search="click"` with `data-app-search-sort`, or `data-app-search-group-by` and
`data-app-search-group-direction`), the effective one `aria-checked="true"`. `ensureChoiceMenus` (`js/common/choice-menus.js`,
called by `fragments/index.js` for every hydrated root) hands each host to `buildChoiceMenuCtrl`, which builds the
control into it: a `.choice-menu-ctrl` root (`data-menu-kind="choice"`, centered under its trigger), the trigger
(`action-button small`, `aria-haspopup="menu"`: the icon, the name dimmed, the checked option's text in `.choice`, a
caret), and the panel (`role="menu"`, `aria-labelledby` the trigger) the option buttons move into as `menuitemradio`
items; the trigger and panel IDs derive from the host's (`groupMenuHost` → `groupMenuBtn`, `groupMenu`). Picking an
option closes the menu with focus on the trigger (`handleActionClick`) and runs the option's search; the results
fragment comes back with a new control showing the choice, so nothing updates the label on the client, and a failed
search leaves the previous choice shown. The search funnel knows nothing of the control: an option is a literal trigger
like any other.

A **compact choice menu** is a choice menu with no words on its trigger: the host carries `data-choice-menu-compact`
and no icon, each option holds a `.glyph` (an inline `<svg><use href="#dockGlyph-…">` of a symbol sheet) before its
label in a `span`, and `buildChoiceMenuCtrl` gives the trigger (`action-button small compact`, square) a clone of the
checked option's glyph alone, and the menu's name and the choice, "Dock side: Left", as its `aria-label` and
`title`, so the choice is read without opening it. The one such menu is the explorer header's Dock side menu
(`#dockSideMenuHost`, `index.scala.html`), whose options are `[data-app-dock-side]` picks rather than search
triggers, so no server re-render shows the choice: `js/common/explorer-dock.js` re-checks the options and replaces
the trigger's glyph and name on every side change (see **Explorer dock**). The page's own hosts are built
by the first hydration (`initApp` hydrates `document`), before `initExplorerDock` checks the current option, so a
compact trigger starts on its first option's glyph and is corrected in the same task, before any paint. Until a host
is built, `core.css` hides its option buttons (`[data-choice-menu] > button`: only an unbuilt host has buttons as
direct children), so the page's Dock side menu shows nothing before the deferred modules run; a `.glyph` also carries
`width`/`height="16"`, its symbols' `viewBox`, so an unstyled one never draws at the SVG default of 300×150.

A menu opens on its checked action, else its first (`focusInitialAction`, on `toggle`; View settings has no actions
and keeps the focus its dialog hydrator sets); Down and Up move through the actions, wrapping, Home and End jump
(`handleKeydown` on the panel), Enter and Space are the button's own, and no type-ahead exists.
The browser owns popover visibility: it toggles the panel from its trigger, closes it on any click
outside, and keeps one open because a panel is never a descendant of another entity's panel.
The `contextMenu` component places the panel in the top layer against the trigger (below,
flipping above when needed, clamped to the viewport, height-capped with internal scrolling when
neither side fits; left-aligned with the trigger, or centered on it for a `data-menu-align="center"`
root); it closes the panel when focus leaves and on scroll, window resize, or explorer resize;
it never tracks a moving trigger. The panel carries `tabindex="-1"`, so a click on a dialog's
label or padding moves focus to the panel rather than out of it.
`closeContextMenu` / `closeOpenContextMenu`, exported by the component module, close a menu from
outside the component: Escape in `global.js` (focus returns to the trigger unless a dialog was
closed too), the folder model when an ancestor collapses, and `openModal`; the trigger is found as the
`[popovertarget]` button in the panel's cell, not by an ID convention. Removing the tree or list removes the open panel and, through the component's
`destroy`, its listeners. Styling lives in `core.css`: a panel wears the popover dialog's frosted surface
(`--popover-background-color` under `--popover-backdrop-filter`, a `--popover-border-color` hairline, `--radius-large`,
`--sheen-shadow` over `--float-shadow`, `--space-2` padding, as the select picker is drawn), and an action is a
`--radius-small` row in a two-column grid whose leading column is the check mark's (`✓` by `::before` on
`[aria-checked="true"]`, empty otherwise, so labels line up across every menu), tinted on hover as a picker option is
(`--theme-neutral-4` at 0.08) and when checked as a checked option is (the accent at 0.2), with the hairline between
rows. CSS sets `display` only under
`:popover-open`, because the hidden state relies on the browser's `display: none`. During
`beforetoggle`, the component briefly sets inline `display: grid` to measure and position the
hidden panel, then clears it before opening; keep the CSS `margin: 0` and `inset: auto` resets so
those viewport coordinates apply correctly. `.menu-ctrl` and the icon control `.expand-ctrl` are
excluded from folder dragging (`ignoreFrom` in `js/dragdrop/folders.js`); the rest of the row drags. A panel lives inside whatever container holds
its trigger, so no stylesheet may reach into one with a descendant selector: `#searchControl .controls > div:not(.toggle-group)`
in `includes/search_results.css` is scoped to the controls' direct children for exactly that reason, since an ID
selector outranks `.context-menu:popover-open` and would leave the closed panel displayed (the
`:not` leaves `#layoutToggle` its `.toggle-group` row).

**Folder tree expansion and viewed scope** — In a non-root row the icon and the name have separate
jobs: the name navigates (a search for the folder's results, disabled in triage and trash),
and the icon sits in a native `button` (`#expand-folder-children-{id}`, icon `#folder-icon-{id}`,
class `.expand-ctrl`) built by `js/common/folder-tree.js`. A **branch** is a non-root folder with
child folders; only branches carry expansion state, root is always expanded with no gestures, and a
leaf keeps a plain `fa-folder` icon with no indicator even when it holds assets (its button
navigates like the name). A branch's button carries an accessible name, `aria-controls` for its
children container, and `aria-expanded`, which the model keeps in step with the
`fa-folder-plus`/`fa-folder-minus` glyph; Enter and Space are single-click activations. Expansion
sends no request.

**Folder asset counts** — Every non-root row shows its folder's asset count in a `.asset-count`
cell (`#folder-count-{id}`, built by `js/common/asset-count.js`) placed directly after the name, dimmed
in parentheses: `NYC (47)`. The count is recursive:
the folder's own sorted assets plus those of every folder beneath it. Triaged and recycled assets are
excluded, and a zero renders as an empty cell, never `(0)`, so a row without assets ends at its name. The root row (labelled `/`) shows no count (the nav
carries the repository total), though the tree JSON still reports `numOfAssets` for it. The row grid
(`views/htmx/folders.scala.html`) gives the name a content-sized track that wraps when it must and the
count the remaining width, so no column is reserved for counts, rows line up on the left, and a long
name never pushes its count out of the panel. The count is not a search trigger.
The server computes the counts on every tree fetch (`FolderService.getTree`); nothing is stored.

| Target and starting state | Single-click | Double-click |
|---|---|---|
| Collapsed branch icon (`fa-folder-plus`) | Reveal direct children only | Expand all levels in this branch |
| Partly or fully expanded branch icon (`fa-folder-minus`) | Close the branch and reset descendants | Close the branch and reset descendants |
| Folder name | Navigate to the folder | No expansion action |
| Plain root or leaf icon (`fa-folder`) | Navigate to the folder | No expansion action |

`js/models/folder.js` owns the state: `expand()` (one level), `expandAll()`, and `collapse()`, which
also collapses every descendant branch (the **reset invariant**: a reopened branch shows one level;
descendant expansion never lingers hidden). Collapsing closes an open descendant menu and, if focus
was inside the hidden content, moves it to the collapsing branch's button. The renderer's gesture
handler acts on the first click immediately, ignores the later clicks of a pointer multi-click
sequence (`event.detail` above 1), and applies the double-click action from the state captured
before the first click; the comment on `_bindBranchGestures` explains why. Initial rendering shows
root and its direct children with every branch collapsed; nothing is persisted.

An expanded branch and its visible descendants form a **group**, set off from the rows around it:
between any two consecutive visible rows the space is the row gap (`--list-row-gap`, `0.5rem`, declared in
`core.css` and shared with the albums and the Locations) or twice
that where a group starts or ends, never more, whether groups end together, one expanded branch directly
follows another, or an expanded first child follows its parent's row. The tree's top and bottom get no
extra space, and root is not a group. The CSS in `static/css/htmx/folders.css` implements it as a block
margin on `.folder[data-expanded]` (grid items do not collapse margins), cancelled where an expanded
sibling precedes or where the branch is its parent's last child; expansion already toggles the
attribute, so the renderer plays no part.

Green (`--success-color`) marks the **viewed scope**: the folder whose results are displayed
and all its descendants, because the results cover that subtree. It has nothing to do with
expansion. `js/common/viewed-folder-scope.js` keeps the scope of the displayed results and marks
that one folder node with `data-viewed-scope`; the CSS in `static/css/htmx/folders.css` colors every
`.folder-icon` under the node (leaves and hidden descendants included) and no icon in a
menu or dialog. Root is never marked: viewing the root folder turns nothing green, root's icon
included, because that scope is the whole repository. The search-results fragment carries `data-results-repo-id`, `data-results-view`,
and `data-results-folder-id`, which `SearchResultsController` resolved from the parameters it was
sent, and `js/fragments/search-results.js` sets the scope when the fragment is hydrated. It reads
those attributes rather than the `searchParams` store on purpose: the highlight must follow what is
displayed, so a superseded or failed navigation never moves it. So the highlight changes only for results actually displayed, survives sorting and
pagination, is absent in triage and trash or without a folder in scope, and is reapplied by every
tree rebuild; deleting the viewed folder removes it without selecting the parent.

**Albums** — The Albums tab (`views/htmx/albums.scala.html`) is a flat list rendered by
`js/common/album-list.js` from the JSON list endpoint, one `.album` row (`#album-{id}`,
`data-album-id`) per album: menu | icon | name | `.asset-count` (`#album-count-{id}`, directly after
the name, laid out like the folder one), with icon and name as `data-app-search-album-id`
triggers and the row's `.controls` a drop zone (`js/dragdrop/albums.js`) for a single asset or the
batch mover. Albums hold pointers only: a drop adds memberships (`PUT /api/album/r/:repoId/assets`,
`assetActions.addAssetsToAlbum`; assets already in the album are skipped and reported), the grid
does not change, and the album counts are patched in place (`refreshAlbumCounts`, also called by
`refreshCounts` after every asset mutation, because recycling drops an asset from its albums).
While an album's results are displayed (`searchParams.albumId`, mirrored by `data-results-album-id`
on the results fragment, which marks the row `data-viewed-scope` and colors its icon green) the batch
ops offer "Remove from album", which deletes the memberships of the selected assets and removes
their cells. Outside the trash they always offer "Add to album": it dispatches `batchAddToAlbumRequested`, the
listener requests `add_to_album_dialog` (`AlbumActionController`) into the modal host, and
`js/fragments/add-to-album.js` fills its hidden `assetIds` field from the selection (`fillSelectionField` in
`js/fragments/selection-field.js`, which the Add to location dialog shares) and keeps the success detail naming the
chosen album. The select lists the albums, then "New album…" (an empty value), which shows the name field below it:
the server then creates that album and adds the selection in one transaction (`AlbumService.addAssetsToNewAlbum`; a
taken name is the field's error and creates nothing). With no albums, "New album…" is the only option and the name
field shows from the start, focused. The server's detail adds how many assets it `added`, the album's ID and name, and
whether it `created` it; `assetsAddedToAlbum` reports that through `assetActions.reportAddedToAlbum`, the report a drop
makes too ("Already in album" when nothing was new), resets the selection, and reloads the list for a created album
(it needs its row) or refreshes the counts otherwise. Add is a popover dialog (`add_album_dialog.scala.html`, submitted with Return) opened by the top `#addAlbumBtn` (centered above the list) or, while
there are no albums, by the centered `#addFirstAlbumBtn`; the renderer builds both controls into
their hosts and shows one or the other. Rename and Delete open popover dialogs from the album's menu; deleting the viewed album runs a
search back to the whole repository. `albumAdded` / `albumRenamed` / `albumDeleted` reload the
list, which restores focus by ID, or to the visible add button when the dialog's return control
was hidden by the change (`focusAddAlbumControlIfFocusLost`).

**Locations** — The Locations tab (`views/htmx/locations.scala.html`) is rendered by
`js/common/location-list.js` from the JSON list endpoint, which returns categories and Locations in path
order (a category directly followed by its Locations, top-level Locations interleaved by name).
Top-level Locations and categories are siblings of `#locationList` in that order; a category is a
node (`.location.category`, `#location-{id}`, `data-location-id`, `data-kind`) holding its row and,
when it has Locations, a children container (`#location-children-{id}`, `.children`) with their rows
(`.child`: `data-category-id`, `--depth: 1` and a `.trace` cell that indents it like a folder); a
top-level Location is a `.top-level` row. A category row is menu (Rename, Delete) | `fa-layer-group`
icon | name; it holds no assets and no count, so it is neither a drop target nor a search trigger and
never turns green. A category with Locations wraps its icon and name in one native button
(`#location-expand-{id}`, `.expand-ctrl`, with an accessible name, `aria-controls` for the children
container and `aria-expanded`): clicking the icon or the name, or Enter and Space, expands or
collapses its Locations and nothing else; the later clicks of a pointer multi-click are ignored, so a
double-click toggles once, and there is no recursive action because categories are one level deep.
The CSS draws a +/− badge on the icon (`::after` in the Font Awesome font, positioned so it adds no
width, so the icon lines up with top-level Location icons). A category with no Locations has a plain
icon and name, no badge, and clicking it does nothing. Categories start collapsed when the tab loads;
expansion is snapshotted immediately before every rebuild and restored afterwards (dropping categories
that no longer exist or lost their Locations), and is not persisted across page loads. Collapsing
closes an open menu among the hidden Locations and moves focus to the category's button if it was
inside. Expanded categories get the folder tree's group spacing (twice the row gap where a group
starts or ends, never more; an expanded category that is the list's first or last row gets no extra
space at that end). A Location row is menu (Rename, Delete, Move to category) | `fa-map-marker-alt`
icon | name | `.asset-count` (`#location-count-{id}`, directly after the name),
with icon and name as `data-app-search-location-id` triggers and the row's `.controls` a drop zone
(`js/dragdrop/locations.js`) for a single asset or the batch mover. Locations hold pointers only: a
drop adds memberships (`PUT /api/location/r/:repoId/assets`, `assetActions.addAssetsToLocation`;
assets already in the Location are skipped and reported), the grid does not change, and the counts
are patched in place (`refreshLocationCounts`, also called by `refreshCounts` after every asset
mutation, because recycling drops an asset from its Locations). While a Location's results are
displayed (`searchParams.locationId`, mirrored by `data-results-location-id`, which marks the row
`data-viewed-scope` and colors its own icon green, top-level or inside a category, whose icon stays
plain; inside a collapsed category the viewed Location stays hidden until it is expanded) the batch ops offer "Remove from location", which
deletes the memberships of the selected assets and removes their cells. They always offer "Add
to location" outside the trash: it dispatches `batchAddToLocationRequested`, the listener requests
`add_to_location_dialog` into the modal host, and `js/fragments/add-to-location.js` fills its hidden
`assetIds` field from the selection (`fillSelectionField`) and keeps the fragment's success detail naming the chosen
Location (its ID, and its bare name from the option's `data-name`, since the row is not rendered while
another explorer tab is displayed). The server adds how many assets it `added`, and `assetsAddedToLocation`
reports that through `assetActions.reportAddedToLocation`, the report a drop makes too ("Already in
location" when nothing was new), then resets the selection and refreshes the counts.

Add location is a modal dialog (`add_location_dialog.scala.html`, it holds the pin editor below) requested by the top `#addLocationBtn` or, while there are no rows, by the centered
`#addFirstLocationBtn`, centered in the general modal host; Add category opens a popover dialog below `#addCategoryBtn`. The renderer builds all three into their hosts and shows one host or the
other. Rename, Delete and Move to category open popover dialogs from the row's menu (`#locationMenuCtrl-{id}`
is the trigger they return focus to; Delete returns focus to `#addLocationBtn`, since the row goes
away); deleting the viewed Location runs a search back to the whole repository, and deleting a
category leaves its Locations at the top level, a viewed one still green. `locationAdded` /
`categoryAdded` / `locationRenamed` / `locationMoved` / `locationDeleted` reload the list, which
restores the expanded categories and focus by ID, or focus to the visible add control when the
dialog's return control was hidden by the change (`focusAddLocationControlIfFocusLost`). Add
completes with `dialogSuccessResponse` naming the new Location's `categoryId` (null at the top level),
and the `locationAdded` listener expands that category (`expandLocationCategory`) after the reload; a
move into a collapsed category leaves it collapsed, as moving a folder under a collapsed parent does. Add and Move share
`includes/location_category_select`, which offers categories only and `(none)` for the top level (Move passes
`labelled = false`: its title names the field); Add to
location offers Locations only, labelled `Category - Location`. Rename, Delete, and Move titles
come from `Const.UI`, chosen by kind in the controller.

**Location pin editor** — A Location's pin is placed on a map, never typed. The Add location form
nests a `data-app-fragment="location-editor"` root carrying the tile URL, the attribution and the
geocoder flag; `js/fragments/location-editor.js` (hydrated after the modal hydrator) builds a
Leaflet map in `#locationEditorMap` (`tabindex="-1"`, so Alpine's focus trap leaves Leaflet's keyboard
handling alone): a click places the pin, a drag moves it, and every placement writes the form's
hidden `latitude` / `longitude` inputs (six decimals) and the read-only `#locationPinReadout` under
the map (four decimals; "No pin yet" before). The hidden inputs are all the server sees, and a
validation replacement re-renders them with the submitted values, so the re-hydrated editor opens on
that pin at zoom 12. Otherwise the editor opens where the results map is looking when one is displayed
(`getDisplayedMapView()`, `js/map/map-state.js`), and on the world when not. One editor exists at a time: hydrating disposes of the
previous map. Leaflet measures its container on creation, when the modal host is still hidden, so the
hydrator sizes the map once the container is displayed and on every resize. A missing or
out-of-range coordinate is one error, "Place the pin on the map", rendered once under the map. The
pin is an `L.divIcon` (`.location-pin`), so Leaflet's marker images are not vendored. When the
geocoder is enabled the fragment also renders `#locationGeocode` with a Search button: Enter or the
button asks `/api/map/r/:repoId/geocode` through the shared client (the newest search wins), the
results are buttons, and a click jumps the map to the place, places the pin, and fills the name when
it is empty with the label's first segment; a failed search is a snackbar. Leaflet is a plain
script (`window.L`), loaded with its stylesheet by the first editor or map (`js/common/vendor-loader.js`); the
hydrator waits for it and skips a fragment removed meanwhile.

**People tab** — The People tab (`views/htmx/people.scala.html`, `static/css/htmx/people.css`) is a shell the browser
fills: `#people` (`x-data="peopleTab"`, `js/alpine/components/people-tab.js`; it carries `--face-box`, the face detection
box size, for the cells) holds one or two panes, the **Main pane** and the **Duplicate pane** of the glossary,
`section.pane[data-app-people-pane="main"|"duplicate"]` (`#peoplePane-main`, `#peoplePane-duplicate`, named to assistive
tech "Main pane" and "Duplicate pane"), each a `.buckets` row (`role="group"`, "People
buckets") of switches over an empty `.list` host. A switch is the View dialog's pattern,
a `button[role="switch"]` and a `<label for>` whose click flips it with no script, its id carrying the pane name so the
labels stay unique; a pane offers Matched, Hidden and Unmatched, the Main pane also Split view, labelled "Duplicate Split
View" with a faint `small.legend` after the label, "(for quick drag-and-drop)", that the switch is `aria-describedby`. The switches are the
**People buckets** (the glossary in `CONTEXT.md`: Matched person, Unmatched person, Hidden person), three disjoint sets,
and a pane lists the union of the buckets switched on, Matched alone on a first visit; with none on it shows one `.empty`
line and no grid. The server renders no switch state, as with View settings: the component asks
`GET /htmx/people/r/:repoId/list?buckets=matched,hidden` (comma-separated bucket names; a name that is no bucket is a 400)
through `htmx.ajax` into the pane's `.list`, on init, when a switch flips and when a person changes (the host's
`hx-sync="replace"` aborts a load of the pane still in flight, so the latest bucket set lands), and the response is
`htmx/people_list`: the `.grid` of `.cell.drag-drop.dropzone` cells, each with `data-person-id` and no id, since a person
may be in both panes, a hidden person's with an `fa-eye-slash` `.hidden-badge` in the image's corner and an `aria-label`
("…, hidden") on its name link, an Unmatched person told apart by its orange "Unknown N" name. The remembered state is
`localStorage` `Const.localStore.peopleView`, `{ split, buckets: { main: [...], duplicate?: [...] }, paneSizes: { stacked,
sideBySide } }`, written on every flip and drag and read once in `init()`, which falls back to the first visit's when it
is missing or unreadable, keeps only the bucket names the tab knows, reads a pane's buckets remembered under the older
names `top` and `bottom` as its own, and takes an axis's sizes only as two percentages. The component listens
for `Const.events.peopleChanged` on `document.body` from `init()` to `destroy()` (the events are dispatched on the body,
an ancestor of the shell, which a listener on the shell cannot see whatever their bubbling) and reloads each displayed
pane with its own buckets; a merge, a
hide, a show and a discard announce it (**Event flow**). Lookups inside the component start from `this.$root`, the shell
(in a `@click` handler `$el` is the button). **Split view** renders the Duplicate pane under `<template x-if="split">` and
divides the two pane elements, found from `this.$root`, with a Split.js instance (`minSize` 120) along the axis the
**Dock side** calls for (`effectiveSide()` of `js/common/explorer-dock.js`): stacked, `direction: "vertical"`, under a
left or right dock, and side by side, `"horizontal"`, under a top or bottom one, each axis with its own sizes
(`paneSizes.stacked` / `paneSizes.sideBySide`, default `[50, 50]`, written on `onDragEnd` under the axis dragged), made
in `$nextTick` once the pane exists. A `Const.events.dockSideChanged` on `document.body`, listened for from `init()` to
`destroy()` like `peopleChanged`, destroys the instance and makes it again on the new axis while Split view is on, the
panes staying put (the gutter is built by the component as a `.divider` of `core.css` with its orientation class, next
to Split.js's own); turning Split view off destroys the instance, which takes its gutter and the inline sizes along its
axis with it, before `split` drops the pane, and `destroy()` does the same when another tab replaces the fragment. The
Duplicate pane copies the Main pane's buckets the first time Split view is turned on, when it has none remembered; from
then on the two are remembered apart, across split off and on. `js/dragdrop/people.js` binds `#people .drag-drop` and
`#people .dropzone`, which match both panes, so a
cell dropped on the other pane's cell dispatches `confirmPersonMerge` as any drop does; on drag start the `autoScroll`
container becomes the dragged cell's `.pane .list` (the scrolling element: the pane itself is `overflow: hidden`), falling
back to `#explorerPanelContent` for the `#person` cover in the results title. The heights: `#explorerViews` is a grid of
`auto 1fr` at `height: 100%` (`static/css/pages/index.css`), `#explorerPanelContent` (`min-height: 0; overflow-y: auto`)
is the explorer's scroll container for every tab, `#people` is a flex container at `height: 100%`, a column under a left
or right dock and a row (`html[data-dock-side="top"]`, `"bottom"`) under a top or bottom one, and a pane is a grid of
`auto 1fr` with both minimums reset whose `.list` scrolls; alone (`.pane:only-of-type`) it is `flex: 1`, and with two
Split.js sets each pane's size inline along its axis, the height stacked and the width side by side.
Nothing of the tab is server state: switching to another tab and back restores the panes from `localStorage`.

**Map view** — `layout` (`grid` | `map`) is a search parameter (see **Search parameters**). The
toolbar's `#layoutToggle` in `includes/search_results` (a `.toggle-group` of `core.css`) is two
`data-app-search="click"` buttons with a literal `data-app-search-layout`, the current one `aria-pressed="true"`. In map layout the server
disables the Group `<select>`, gives the fragment root `class="map-layout"` (a flex column, so the map
takes every pixel under the controls) and renders `htmx/map_view` in place of `#assets`: `#mapView`
holds `#map` (`data-map-bounds="s,w,n,e"`, empty when nothing is plotted, `data-map-count`,
`data-map-tile-url`, `data-map-attribution`; `isolation: isolate`, so Leaflet's panes stay under the
modals) and the hidden `<aside id="mapPanel">`. The pin styles (`.asset-pin`, `.location-pin.on-map`)
live in `static/css/htmx/map_view.css`; `.location-pin` itself is in `core.css`, shared with the pin editor. While the
results carry a `bbox`, the `.total` cell shows the `#bboxScope` "Map area ×" chip (a `core.css` `.filter-chip`:
the whole chip is the button), whose `data-app-search-bbox=""` clears it, in either layout.

`js/map/map-view.js` hydrates `#map` (`data-app-fragment="map-view"`, after the results hydrator). One
map exists at a time: the results hydrator calls `disposeMapView()` for every new results fragment,
which cancels the pending cells request, disconnects the `ResizeObserver` that tells Leaflet the column
was resized (the divider's drag, the window), and removes the map. The initial view is the one last
looked at in this scope (`map-state.js`), else `fitBounds` on `data-map-bounds` (padded, never past zoom
15), else the world. Every `moveend`, debounced 150 ms, requests `/api/map/r/:repoId/cells` with the
store's query string plus `viewport` and `zoom`, and a response older than the latest request is
dropped. The viewport is sent in the server's form: latitudes clamped to ±90, longitudes wrapped into
-180..180 with `west > east` across the antimeridian, and the whole world once the view spans 360°.
The browser never receives a result set: the cells go into a supercluster index (radius 60 px,
`extent: 256` to match Leaflet's tiles; a cluster's count is the sum of its cells', and its largest
cell's asset represents it), and every cluster or cell in view becomes an `L.divIcon` marker, placed
in the world copy nearest the map's center so a view across the antimeridian shows both sides:

- a cell of one point is a thumbnail pin (`/content/r/:repoId/preview/:assetId`) carrying the same
  asset-detail `hx-get` a grid image does, processed with `htmx.process`;
- a **crowded pin** (a cluster, or a cell of several points) is the representative thumbnail with a
  `.drag-count-badge` count. A cluster flies to its expansion zoom when that is within the maximum zoom
  (19), and otherwise opens the panel for the union of its cells' extents; a cell of several points
  flies two levels in, and at the maximum zoom opens the panel for its own extent (a cell is
  `360 / 2^zoom / 4` degrees on a grid from the origin, the server's rule);
- a Location pin is the `.location-pin` glyph and `Category › Name`, drawn for each Location in view
  that holds a matching asset; a click runs `runSearch({ params: { locationId } })`.

Tiles that fail to load leave a blank canvas with the pins on it, and no notice.

**Crowded-pin panel** — `openMapPanel({ bbox })` (`js/map/map-panel.js`) shows `#mapPanel`, a column
laid over the map's right edge, and runs `runSearch` with `params: { bbox }`, `transient: { layout:
"grid", groupBy: null, groupDirection: null }`, `target: "#mapPanelContent"` and the panel as `source`.
`bbox` is a real store parameter, so the panel's grid is an ordinary search: infinite scroll, cursor
continuation, the page window (scrolling in `#mapPanelContent`), selection, the detail modal and
dragging to a Location row work in it unchanged, and the
URL is bookmarkable, since the map hydrator reopens the panel for the `data-results-bbox` its fragment
carries. The map endpoints and the map layout's total and bounds ignore `bbox`, so the map behind the
panel keeps plotting the whole search. `js/fragments/search-results.js` hydrates a fragment inside
`#mapPanel` as the panel's grid: it writes the count into `#mapPanelCount` ("n items here", "10000+ items here" when the total is capped, `setMapPanelCount`) and leaves
the displayed map, the total store, the viewed scope and box selection alone; the fragment's own
`#searchControl` is hidden there. The panel's continuations stay a plain grid (see **Infinite scroll +
lazy load**). The server's `HX-Replace-Url` for a panel request says `layout=grid`, so
`js/listeners/map.js` puts `layout=map` back in `htmx:before:history:update` for requests issued from
the panel. The panel's ×, Escape and the chip clear `bbox` through the funnel, which re-renders the map
without a panel; "Show only these in the grid" runs `runSearch({ params: { layout: "grid" } })`, which
keeps the area, so the grid opens scoped to it with the chip to clear it.

`js/map/map-state.js` keeps the center and zoom per scope fingerprint (the repository and the
store's `view`, `folderId`, `personId`, `albumId`, `locationId` and `q`; not the sort, layout,
grouping, paging or `bbox`) in `sessionStorage` (`Const.sessionStore.mapViews`, the 50 scopes looked
at last), so a re-render for a sort change, a panel, a trip to the grid and back, or a reload of the
tab keeps the view, while a new scope, or a new tab or window, fits the server's bounds. A move is
written to storage only once moves pause, or on `pagehide`, since Leaflet reports every frame of a
resize as a move. Storage that cannot be read or written leaves the views in memory only. It also holds the displayed map's live
view, which the pin editor opens on.

**Explorer action dialogs** — The folder, album, Location, and category forms declare
`data-app-fragment="modal"` and `data-app-modal-title` and open as popover dialogs (Add location in the general
modal host); the host supplies their heading and close control. Add and Rename submit with Return and retain
`hx-target="this"`, `hx-swap="none"`, and `hx-json-enc`, so validation replaces the active form with its submitted
values and errors. They hold one field with no label, since the title names it: a placeholder in Add's and Rename's input, which
carries an `aria-label` ("Folder name", "Album name", "Category name", or "Location name" by kind for a Location
row's Rename), and an `aria-label` alone on Move's and Add to location's select.
The dialog focuses the name field (selecting it for Rename), or the category selector for Move.
Delete leaves initial focus on the close control, so opening the confirmation cannot activate its
destructive button. Rename and Move return focus to the row's ⋯ button; Delete names a control
that survives the mutation (the parent folder's ⋯ or the list's Add button). Add returns to its
opener; for a menu action the modal owner remembers the visible ⋯ trigger. Re-hydrating validation
preserves that original return control and leaves the popover where it is. Escape, the host's ×, an outside click
and Tab out of the popover dismiss the dialog; Escape and the × return focus as above, while an outside click and
Tab leave it where they put it.

**Inline dialogs** — Only View settings uses `js/fragments/inline-dialog.js`, which gives it initial
focus. It is two columns (`htmx/view_settings_dialog.scala.html`, `static/css/htmx/view_settings_dialog.css`): on the
left the switches bound to the grid metadata set by its own Alpine component
(`x-data="viewSettings"`, `js/alpine/components/view-settings.js`), which the mutation observer
initializes as the fragment arrives, and on the right, behind a hairline, the scope: "Show triaged items", the
`triaged` search parameter read from the `searchParams` store (off unless it is `true`), whose click runs `runSearch`
with `triaged=true` or the empty literal that clears it, so the results re-render under the open panel: the View
control's host `#viewSettingsActions` is `hx-preserve` in `includes/search_results`, so the swap carries the built
control and its open panel from the old fragment into the new one (htmx moves it with `moveBefore`, which keeps the
popover showing), and `ensureViewSettingsControl` finds the host filled and builds nothing. The scope column is shown in
the repository view alone (`x-show`); in Triage and Trash the dialog is the metadata switches and nothing else. The dialog has no operation lifecycle; dismissal follows the popover's rules and
Escape returns focus to the trigger.

**Forms** — Every text-like field on every page but the setup screen takes the one field style of the FORMS section
of `core.css`: text, search, email, password and file inputs, textareas and selects. It is a sunken fill
(`--field-background-color`) carrying the page's text (`--theme-neutral-4`), a hairline of `--theme-neutral-3` at
0.25, `--radius-small`, `--space-4` × `--space-5` padding, `--font-size-medium` and an accent caret, and on focus the
accent edge and ring in place of the focus ring (see **Themes**). Its selector sits inside `:where()`, so any page's
own rule outranks it, and is written once: the focus rule is nested in the field rule (`&:focus-visible`), as the
picker's rules are in the select's, while the options take a rule of their own. Checkboxes, radios, ranges, hidden and submit inputs take none of it, nor does the
nav's quick search field, which keeps its own look. A setting that applies as it is flipped, with nothing to submit, is a `.switch` of the SWITCH
section: a `button[role="switch"]` before its `<label for>` (a button is labelable, so the label's click flips it with no script), whose `aria-checked` an
Alpine binding keeps in step with the setting; the track, `--switch-width` by `--switch-height`, takes `--theme-primary` when on, and the knob, a
pseudo-element, slides across it. The View settings dialog's switches are the one use. Every rule of the section starts at `body:not(.setup)`, so the setup form's fields and error box are
`htmx/setup_form.css`'s alone; the login page loads no app stylesheet. The upload form's file input takes the field's
box and keeps its own `--theme-neutral-2` fill (`htmx/upload_form.css`). A field keeps the width its page gives it,
except in a popover dialog, whose form fills `--popover-form-width` with its action `--space-5` below its fields
(see **Modals**). A field in error carries the `error` class: the error fill
(`--error-background-color`) with `--theme-neutral-4` text and the `--error-color` edge, which it keeps on focus,
with no ring. Its message is a `div.error` under the field, in `--font-size-small` and `--error-color` with no box:
the dialogs and the person name editor (`htmx/edit_person_name`) render it directly after the field, the Settings form
under the row of the field and its Reset button. An error with a way out carries it after the message, an
`.action-button.small`, the two wrapping in one row under the field: the person name editor's offer to merge. A label above a field in a dialog of either host (Add location's fields) is in small caps: `--font-size-xs`, semibold, uppercase,
`--letter-spacing-wide`, its text color at 0.6 (`:is(#popoverContent, #modalContent) label`); the Settings rows keep
their own labels. A one-field dialog whose title names the field (Add, Rename and the style guide's sample dialog, with a
placeholder in the input; Move to category and Add to location, with their select) shows no label, and its field
carries an `aria-label`. A select is a customizable select (`appearance:
base-select`, behind `@supports`, Chromium today), its picker drawn as the popover dialog's surface, an option tinted
on hover (`--theme-neutral-4` at 0.08) and when checked (the accent at 0.2); a browser without it keeps the native
select, styled as a field. The grid toolbar's Group and Sort are no selects but choice menus (see **Context menus**).

**Snackbar** — Always use `showSuccessSnackBar` / `showWarningSnackBar` / `showErrorSnackBar` from
`js/common/snackbar.js`. Messages render as plain text (`x-text`, which sets `textContent`); pass raw text, including
user-supplied names and server errors, without HTML markup or pre-escaping. The messages live in the Alpine `snackbar`
store, which `html_common` renders as two stacks: errors at top center (`#snackbarErrors`), kept until dismissed, and
successes and warnings ("notices") at bottom left, over the footer (`#snackbarNotices`), dismissed after 3 s and 6 s.
The notices' timers are held while the pointer is over their stack, focus is inside it or the document is hidden, and
resume with the time they had left. Every message has a dismiss button and its type's icon. A stack holds three
messages, a fourth dismissing the oldest; a message equal in type and text to one in its stack is counted on it
(`×n`) and restarts its timer, and a dismissed message leaves no trace. A stack is at most `--snackbar-width` wide and
keeps `--viewport-gap` from the viewport's edges, and a message wraps. Each stack is a `popover="manual"` element, open
while it holds a message, so it is in the top layer: above the modal hosts and the map, above a popover dialog or menu
opened before it (which it leaves open), below one opened after it. Clicking a message while the popover dialog is
open is an outside click, so the browser closes the dialog (light dismiss); the modal hosts stay open. A keyboard user
reaches a message's dismiss button by Tab, where the stacks sit in the DOM order (`html_common`'s place in the page);
no key dismisses a message. Dismissing the message whose button has focus moves focus to the next message's button in
its stack, or the previous one's when it was the last; with none left focus falls to `body`, and the notices' focus
hold is released once focus is outside their stack (Firefox fires no `focusout` for a removed element). Screen readers
hear a message through visually hidden live regions (`data-app-snackbar-announcer`), not the stacks. Each place holds
two sibling regions, never nested: `role="status"` (`="polite"`) for successes and warnings, `role="alert"`
(`="assertive"`) for errors. `x-trap.inert` hides everything outside the active trapped `.modal-box`, so each holds
its own pair, and the top-level pair serves the page and the popover dialog; `modal.js` names the active trapped host
(`getActiveTrapContainer`, `isInModalHost`). A message is appended to the fitting region heard now as a plain
paragraph, a repeat's with its count, and removed after 5 s, so a burst is announced in full. A dismissed message
fades out over `--snackbar-exit-duration`, which `snackbar.js` reads to remove it once the fade has run (a timer, not
`transitionend`), or at once under reduced motion. A page without the store logs a message and drops it: the import
page (`pipeline.scala.html`) includes no `html_common`, so the error snackbars of its upload form are only logged.

**Tooltip** — A hint about a control, shown on a click, not an outcome (that is the snackbar's). `$tooltip('Message')`
in a click handler (`@@click="$tooltip('…')"`; the Alpine UI tooltip's magic, `docs/reference/alpine-components/tooltip.md`,
with neither tippy nor Popper) is `js/common/tooltip.js`, registered by `registerHtmlCommonBindings()` with everything
else `html_common` binds. It writes the message as plain text into `#tooltip`, the one `popover="manual"` host of
`html_common` (`role="tooltip"`, no `x-data`: the module owns it as `modal.js` owns the popover dialog), and shows it
in the top layer. CSS anchor positioning places it (`#tooltip` in `core.css`): the magic sets an inline
`anchor-name: --tooltip-anchor` on the clicked element, so any element can call it with no ID or rule of its own, and
the host's `position-area: top` centers it above the element, slid inside the viewport at a side edge, `--space-2`
from the element and flipped below (`flip-block`) when the top has no room; it wears the menus' frosted surface at
`--font-size-small`, takes no pointer events and fades in over 150 ms under `prefers-reduced-motion: no-preference`,
with no exit fade. One tooltip at a time: a new call hides the shown one first, and the inline name moves with it. It
hides after 2 s, or at the next press or key anywhere (`pointerdown` and `keydown` at the document's capture phase),
which comes a tick before the anchor may leave the tree (Deselect all hides the batch ops), so no tooltip is left
anchorless at the viewport's corner. A screen reader hears the message through the snackbar's polite live region
(`announcePolitely` in `snackbar.js`): the host is `display: none` until shown, so a live region of its own would not
reliably be read. Click only, no hover or focus tooltip: a hover hint stays a native `title`. The one caller is the
batch ops' Move button (`includes/batch_ops`, either copy), a drag handle, whose hint is "Drag to folder, location, or
album"; its drag (`js/dragdrop/batch-ops.js`) arms `suppressClickAfterRelease()` at `start`, so the click the release produces on
the button, moved under the pointer, never shows the hint (at `start` rather than `end`: with inertia a flick's `end`
fires after the click), and a press that wobbles past interact's 1 px tolerance is a drag that snaps back, with no
hint. The style guide's Feedback section holds a specimen.

**Infinite scroll + lazy load** — The last `.cell` (by position: a Location page can hold its last
asset in an earlier cell too) gets class `last-cell` and carries how the next
page is reached: the encoded cursor in `data-app-search-after`, in an ungrouped grid and a grouped
one alike. An `IntersectionObserver` in
`js/search-results/infinite-scroll.js` watches it, but only while nothing follows it in the grid (a
reloaded page's last cell carries a cursor too, and the page it leads to is loaded already: see the
window below), and calls `loadNextPage(lastCellEl)`, exported from the
same module: it reads the continuation, deletes the attribute, requests the page through `runSearch`
as `transient` parameters (`{ after }`, with `isContinuousScroll`), and appends the result
after the cell. The request in flight is kept per cell with its cursor (`pendingCursorOf`), so a
second caller gets the same promise and
no page is requested twice; the detail modal is that second caller when it steps past the last
loaded cell. A cell loses its attribute as it loads, so scrolling back over it loads nothing again.
A page the server rejects is reported through the snackbar like every failed request
(`js/listeners/htmx-requests.js`), which matters here because no visible control is behind it.
Each page appended is announced to the selection store (`noteGridChange`) so the group headers
recount. A continuation continues the grid as it was rendered: it also sends the layout and grouping
of the results fragment the cell is in (`data-results-layout`, `data-results-group-by`,
`data-results-group-direction`) as transient parameters. For the main grid those are the store's own
values; for the crowded-pin panel's grid they are the plain grid it was requested as, while the store
says map.

Every thumbnail names its preview URL in `data-src`, and a first page's (`isContinuousScroll` false in
`htmx/result_cell.scala.html`) in `src` as well, so the first viewport paints before any script runs.
`js/search-results/lazy-images.js` observes every `.cell img` with one `IntersectionObserver` whose
`rootMargin` is `"100% 0px"`, one viewport above and below: an image gets its `src` as it comes within
a viewport and the transparent 1x1 placeholder back once it is more than a viewport away, the first
page's like any other, so the thumbnails held are bounded by the viewport and not by how far the
session has scrolled. A released image that comes back is a browser cache hit: the content route
answers a preview, a face and an original with `Cache-Control: private, max-age=31536000, immutable`.

The grid itself is a window of pages (`js/search-results/page-window.js`, bound by the results
hydrator after infinite scroll and before lazy images, with `#mapPanelContent` as its scroll pane for
the crowded-pin panel's grid and `#content` otherwise). A page is a record `{ after, nodes,
keptHeader, spacer }`: the first page at hydration (the initial children of `#assets`), every later
one from `htmx:after:settle` on `#assets`, keyed by the swap target, with the cursor read off infinite
scroll's request in flight (`pendingCursorOf`; `page-window.js` imports `infinite-scroll.js`, never the
reverse, as eslint forbids import cycles). From top to bottom `#assets` holds zero or more spacers,
then the loaded pages, then nothing. Once a page is appended, while the loaded cells exceed
`MAX_LOADED_CELLS` (600) and more than two pages are loaded, the topmost page is folded unless one of
its cells is within a viewport (`window.innerHeight` above or below, the observers' own margin, so a
folded page is not reloaded at once): its nodes make way for one
`<div class="page-spacer" data-app-search-after="<cursor>">` (`""` for the first page; `grid-column:
1 / -1` in `static/css/includes/search_results.css`) as tall as the top of its first cell to the bottom of
its last (headers left out: a header above the pane sticks to the pane's top, so its box says nothing
of where the page is), which keeps the scrollbar proportional. A top page none of whose cells is in
the grid any more (an action removed them) is discarded rather than folded. Spacers are observed with
one `IntersectionObserver` (`rootMargin: "100% 0px"`), and `reloadSpacer` requests the page as a
continuation (transient `after`, omitted for the first page, `isContinuousScroll` and the fragment's
layout and grouping), swapped `afterend` of the spacer, one reload in flight per spacer; on settle the
nodes are recorded, the server-rendered `data-app-search-after` is stripped from the page's last cell
(the page it leads to is loaded already), and the kept header and the spacer are removed. A 204 swaps
nothing and settles nothing, so it is caught on `htmx:after:request`: the spacer is removed and the
kept header stays for the group's cells on the next page. A reload that pushes the loaded cells over
the maximum drops the bottom page unless it is within a viewport (the drop would bring it straight
back, and the fold after that would take the page the modal stepped into): its nodes are removed and
the grid's last cell gets the dropped page's cursor back and is observed again
(`observeContinuationCell`), unless a spacer follows that cell, whose own reload brings the cursor
back on its page's last cell. A scrollbar jump can reload a page out of order and leave spacers
between loaded pages, which is allowed. Cursors are keyset positions, so after assets were removed
from a folded page's range a reloaded page can overlap the loaded page below it. `#assets` has
`overflow-anchor: none`, and every fold, reload and drop notes the viewport offset of the first cell
on screen (`htmx:before:swap` for a swap, `keepingViewport` otherwise) and restores
`scrollElement.scrollTop` after; a fold and a drop call `noteGridChange` themselves (a reload's cells
are announced by infinite scroll's own settle listener). A spacer is no `.cell` or `.result-group`
and carries no `data-asset-id`, `img` or `.metadata`, so selection, box selection, drag and drop,
lazy loading and metadata visibility never see it; the detail modal's walk reloads it (**Detail
navigation**). `pagesOf`, `foldPagesIfNeeded`, `reloadSpacer`, `isSpacer` and `MAX_LOADED_CELLS` are
exported for tooling.

**Results title** — The results fragment opens with the scope of the results, when it has one, as one block
every scope renders the same way: `includes/results_title.scala.html` (styled by `static/css/includes/results_title.css`)
wraps `includes/results_title_inner`, which lays out the kind's Font Awesome icon (`fa-user`, `fa-folder`, `fa-images`,
`fa-map-marker-alt`, `fa-search`), the title the caller renders and the way back, then a subtitle. The results total stays in
the toolbar. The title is `--font-size-display`, `--font-weight-semibold`, `--theme-primary-text`, and
truncates with an ellipsis; the block has no fill of its own, only a hairline below in `--panel-border-color`, and is
padded `--space-8` by `--space-6` (it paints the pane's background, `--theme-neutral-2` under the pane's tint, so the grid
does not show through it while it sticks). The scopes:
- a **person** through `htmx/person.scala.html`: `#person`, the block itself, is the face merge's drop zone (`.dropzone`,
  `js/dragdrop/people.js`) and the swap target of the Hide / Show / Discard links, whose responses are
  `htmx/person_inner`, the whole inside of the block. The Hide / Show links declare
  `data-app-success-event="PEOPLE_CHANGED_EVENT"` (the person id as the detail) as the Discard link declares
  `PERSON_MARKED_AS_BAD_MATCH_EVENT`, so `js/listeners/htmx-requests.js` announces a successful one and the People tab
  moves the person between its panes. The cover face (`.cover.drag-drop`, a `--person-avatar-size` circle
  that opens the Choose cover face modal) comes before the text, the Hide / Show / Discard link (`.actions`) after it, and
  the subtitle is the face count. The title is `#personName`, the inline name editor's host: `htmx/view_person_name`
  (the name, whose click swaps in `htmx/edit_person_name` at the title's size, `field-sizing: content`, its form swapping
  `outerHTML settle:0` and its error row the one part of the title that wraps) with its pencil,
  shown by `static/css/htmx/person.css` only while the title is hovered or the name has focus
- a **folder** as the path's breadcrumb (`.crumbs`, a shared primitive of `core.css` the Detail panel's Folder row wears too):
  every ancestor short of the repository root, top-level first, each a search trigger for its folder
  (`data-app-search-folder-id`, dimmed `.crumb`), separated by a dimmed `fa-chevron-right` (`.sep`), and the folder itself as
  the `.leaf` in the title style. The path wraps rather than truncating. The
  controller builds it from `FolderService.getAncestors` (root first) and the folder; the root folder is the whole
  repository and renders no block
- an **album** by name, with "Created *day*" (`Util.humanReadableDate` of `Album.createdAt`) as the subtitle
- a **Location** by name, with its category's name as the subtitle, when it has one
- the **Search text** (`.query`, in curly quotes drawn by CSS) with "in this repository" as the subtitle

The way back is the `.clear-scope` × after the title: a search trigger carrying an empty `personId`, which the store's scope
rules (see **Search parameters**) widen to clearing every place, the map area and the Search text while the view, the sort,
the grouping and the layout stay, so a person selected in Triage goes back to all of Triage. The block and the toolbar
(`#searchControl`) are both `position: sticky` at the top of `#content` (`z-index: 2`, above the group headers), the toolbar
under the title: `js/search-results/sticky-offsets.js`, run by the results hydrator, writes the title's rendered height to
`--results-title-height` on the fragment (the toolbar's `top`; 0 without a title) and the two heights together to
`--results-sticky-height` (the group headers' `top`, **Group headers**) through one `ResizeObserver`. The toolbar's former
margin is padding, painted with the pane's background, so nothing scrolls through between the two. The toolbar is a wrapping flex row of three groups, `--space-9` apart: the lead (the total, then the batch ops while assets are selected, see **Batch ops**), the controls (the layout toggle and the View, Group and Sort menus) and the trail, empty but in the trash view, where the Purge button (`htmx/trashbin_header`, rendered into the toolbar) holds its right edge. The lead and the trail grow from the same zero basis, so they split what the controls leave evenly and the controls sit on the bar's center whatever the sides measure; neither side shrinks below its content, so the batch ops push the controls right, and once the lead and the controls no longer fit on one row the controls and the trail wrap to a second one, `--space-6` below, which the sticky offsets follow. The total's "Total:" label is an eyebrow (`--font-size-xs`, uppercase, `--letter-spacing-wide`, `--theme-neutral-3`) before the count, which is `--font-size-large`, `--font-weight-medium` and tabular, so its width holds as `decrement` lowers it. The scopes are exclusive by the store's rules; the template renders
the first of person, folder, album, Location and text that is set. The crowded-pin panel hides
the fragment's title with its controls (`static/css/htmx/map_view.css`).

In the Triage view `#curateQueue` follows the toolbar, a centered row holding `#curateSequences`, the small action button that
opens Curation on the whole queue, whatever the search shows, in the `.curate.lit` look (see **Style guide** and the Buttons
section of the page). It has the `fa-check-double` icon of the batch ops' Curate at the same `--space-5` gap, then the
`sequencesToCurate` store's `label` ("Curate all 3 sequences", "Curate 1 sequence"), and `--space-9` of side padding. The row's top
border is a hairline of `--theme-neutral-3` at 0.3, inset by the toolbar's side padding (`--space-8`). The toolbar's bottom
padding is the `--space-6` above the line, and the row's block padding the `--space-6` from the line to the button and from the
button to the grid. It sits outside `#searchControl`, so it is not sticky and scrolls away with the grid, and the sticky offsets
do not count it. The row, line included, is hidden until the count arrives and while it is zero. The crowded-pin panel hides
it with the toolbar.

**Batch ops** — What the selection can do (`includes/batch_ops`): Move (a drag handle, see **Tooltip**), Recycle, Add to
album, Remove from album while an album's results are displayed, Add to location, Remove from location while a Location's
are, Curate Selected while two images or more are selected, and Deselect all (×, an icon button with an accessible name); in the trash
view Purge, Restore and Deselect all. The
count shows once ("3 selected"), after the actions and directly before Deselect all, which the views share; the buttons carry no count of their own, and they are the small action
button (`--space-4` × `--space-5` padding, `--font-size-small`, as the trash view's Purge button) and sit `--space-6`
apart. Curate is `.action-button.curate` (see the style guide's Buttons section), which the rule that turns the batch ops'
borders `--warning-background-color` on hover leaves out. The include is rendered twice, shown together while assets are selected (`x-show`): in the toolbar's lead and in
the footer. Both copies are drawn alike, their sizes set in `static/css/includes/batch_ops.css` rather than inherited, and
the footer's is placed by CSS anchor positioning: the toolbar copy is `anchor-name: --batch-ops`, and the footer copy is
`position: fixed` over the footer's row (`--footer-height`, its buttons centered in it) with `left: anchor(--batch-ops left,
var(--space-8))`, so each button sits directly below its twin wherever the explorer's size and dock side and the
toolbar's wrapping put the toolbar copy, with no script; only the left edge is read, so the results pane's scrolling does
not move it, and the crowded-pin panel's toolbar is `display: none`, so it is never the anchor. Move drags from either
copy (`js/dragdrop/batch-ops.js`): `setFixedPositionWhileDragging` lifts the button out of the scrolling results pane, as
a dragged thumbnail is, and the drop zones recognize it by its `batch-mover` class (`isBatchMoverElement` in
`js/dragdrop/helpers.js`). Curate dispatches `Const.events.batchCurateRequested`, and `js/listeners/curation.js` opens
**Curation** on the selected IDs, or, above the cap the button carries (`data-max-selection`, `Const.Curation.MAX_SELECTION`),
reports it through the snackbar and stays on the grid. The server refuses such a selection too, but only this tells the user why.

**Group headers** — A grouped grid (`htmx/results_grid_grouped.scala.html`) opens each group, a day,
a Location or a Sequence, with a `.result-group` header: a checkbox over the group; its name - a
`<time datetime="yyyy-MM-dd">` with the server-formatted day (**Saturday, January 2, 2025**), "No date",
`<span class="category">Italy</span> › Rome` (the category dimmed), "No location", a Sequence's first capture as a
`<time>` with its day and time (**Sunday, September 6, 2026, 10:00:05 AM**, `Util.humanReadableDayAndTime`: a Sequence has no
name, and the seconds tell apart the bursts of one minute), or "No sequence"; `data-group-key`
naming it; and the group's full match count across every page in `.count` — as text (**(3 items)**,
**(1 item)**) with the number itself in `data-count`, which is what the client reads and decrements. A Sequence's header with
two frames or more also has a small "Curate" action button (`data-app-sequence-id`), opening **Curation** on that one Sequence:
all its frames still out of the trash, even under a search showing only some of them. The header spans
the row and is `position: sticky` at the top of `#content`, below the toolbar and the results title when there is one (its
`top` is `--results-sticky-height`, which `js/search-results/sticky-offsets.js` keeps at their rendered heights together;
otherwise CSS only, in `static/css/includes/search_results.css`; `#assets` is an isolation root so the drag stand-in still
paints above it, and the background carries `--view-tint` so it matches the triage and trash panes).
A continuation of a group already on screen repeats no header, so the new cells read as the same
group. When the page window folds a page (**Infinite scroll + lazy load**), the header of the group
still open at the page's end stays in place before the spacer, as the page record's `keptHeader`, but
only when the next page continues the group with no header of its own; a group closed with the page
keeps nothing. The kept header keeps sticking over the group's loaded cells and its checkbox keeps
counting them (a spacer is not a header, so the walk below passes it), and it is removed when the
page reloads with its own headers, or stays for the group's cells on the next page when the reload is
a 204. The client changes a header in `js/search-results/date-groups.js` alone, and only its count and its Curate button:
`removeAssetsFromGrid` (`js/assets/asset-actions.js`) calls `decrementDateGroupOf(cellEl)` before a cell leaves, which walks
back to the nearest header, takes one off, removes the Curate button under the number it carries (`data-min-frames`, `Const.Curation.MIN_FRAMES`),
and removes the header at zero; a header whose loaded cells are all gone but whose
count is positive stays, since the group has matches on pages not loaded yet. `removeCurateButtons(sequenceIds)` takes the
button off a Sequence Curation cleared, whose kept frames stay under the header until the next search; it takes every copy,
since a Sequence overtaken on a later page has a second header (**Group by Sequence** in `altitude/AGENTS.md`). Under Group by Location
an asset in two Locations has a cell in each group (`asset-<id>-in-<locationId>`; elsewhere a cell is
`asset-<id>`), so every per-asset change goes through `js/search-results/cells.js`, which finds all of
an asset's cells by `data-asset-id`: removal takes every cell out, decrementing each header a cell
leaves, while the footer total, which counts assets, drops once per asset (unless it is capped: see `resultsTotal` under **Alpine.js Stores**).

The header's checkbox is `x-data="initDateGroupSelectable()"`
(`js/alpine/components/date-group-selectable.js`). Its set is the group's cells *in the grid*, read off
the DOM as the header's following siblings up to the next header — a group owns no element of its
own, which is what lets a continued group append cells with no header of its own. Clicking it selects
or deselects them through the `selectedAssets` store, the same path a click on a thumbnail's
checkmark takes; it never fetches the group's unloaded pages. It shows checked only when the whole group is both loaded and selected, and the
indeterminate dash otherwise, so a group still scrolling in never reads as fully selected. `paint()`
writes that state into the box: it is the box's `x-effect`, and the box's `change` handler calls it
again after `toggle()`, since a click that changes neither count re-runs no effect. The click is not
cancelled on purpose: the browser restores a cancelled checkbox's `checked` and `indeterminate` once
the click is dispatched, after the microtask Alpine runs effects in, so a `@click.prevent` box would
show its pre-click state. Its counts are
reactive: they read the store's set (so a Shift-click, a box selection and Deselect All all repaint
it) and its grid version, bumped by `removeAssetsFromGrid` when cells leave and by the infinite
scroll when a page is appended into a group already on screen. Alpine batches effects per microtask,
so a box selection over hundreds of cells repaints each header once. Headers are never
selectable (box selection targets `[data-asset-id]`), never draggable, and carry no `img` or
`.metadata`, so lazy loading and metadata visibility skip them. Images carry `data-src`, a first page's `src` too; the same centralized
search-results fragment hydrator (`js/fragments/search-results.js`) binds selection, infinite scroll, the page window, lazy image loading, metadata visibility, and
box selection for `data-app-fragment="search-results"`, and sets the viewed folder scope from the
fragment's `data-results-*` metadata. When a loaded image is more than a viewport away and is swapped for the
transparent 1x1 placeholder, its rendered size is kept as inline `width`/`height`, so the thumbnail
box a box selection hit-tests against stays where the image was (the cell's row is fixed, so the
layout is the same either way).

**Box selection** — Dragging a rectangle over the grid selects the thumbnails it touches
(`js/search-results/box-selection.js`, one controller per displayed grid, created and replaced by the
search-results hydrator). The vendored Viselect (`lib/viselect.esm.js`) owns the gesture: it draws
the rectangle inside its own fixed, clipped container on `<body>` (styled by `.selection-area` in
`static/css/includes/search_results.css`), autoscrolls `#content` within 32px of its top and bottom
edges, and keeps the list of thumbnails inside the rectangle; it adds no classes and changes no
state. Nothing visible changes during the drag. On release the box goes through the
`selectedAssets` store only: `reset()` for a replacement box, then `select()` for each thumbnail
it touched. The hit target is the `.drag-drop` div, whose box is exactly the
rendered thumbnail, and any overlap counts (`intersect: "touch"`). Rules: a plain drag from empty grid
space (padding, gaps, a cell's metadata) replaces the selection; a Shift-drag from anywhere in the
grid, thumbnails included, adds to it, with Shift read when the button goes down (`dragdrop/assets.js`
declines to start an asset drag while Shift is held, via interact's `actionChecker`); a plain drag
over a thumbnail is still an asset drag; a press without movement past 10px is a click and does
nothing. Escape, the window losing focus, the page being hidden, or the grid being replaced discard
the box without touching the selection. The click the browser fires on release, or on the release
after a cancellation, is swallowed through `click-suppression.js`, the same helper asset drags use.
Viselect only re-evaluates the rectangle, and starts its scroll loop, from a `mousemove`; the
controller therefore replays the last pointer position as a synthetic `mousemove` when a page is
appended, an image loads, the panel resizes, or a frame finds the pointer resting in an edge band
with room left to scroll. That adapter is version-bound (3.9.0) and commented in place; the library
file stays unchanged. Candidates are snapshotted on release, so a page arriving afterwards changes
nothing; a failed page is reported by the snackbar and what was loaded remains selectable. Viselect
decides once, when the drag starts, whether the container can scroll, so a box begun while the first
page fits without a scrollbar does not autoscroll in that gesture.

**Detail navigation** — Next/previous navigation and modal asset-detail loading are coordinated
from `js/search-results/detail-navigator.js`. The detail fragment (`views/htmx/view_image_detail_modal.scala.html`)
holds an `<img>` and a media-chrome player (`<media-controller>` around a `<video preload="metadata">` and its
control bar), and its dataset carries the media type; `showMedia` hides the player while any asset loads, so an emptied
player never shows in the previous frame, shows the image or the player as the type calls for, unloads the other
(`hidden`, `src` cleared), waits for `load` or `loadedmetadata`, and hands the shown element's size (`naturalWidth`,
`videoWidth`; the stored size when the element reports none) to the box, which CSS fits into the viewport below the
toolbar and refits on resize: the stored size is the file header's, while a browser turns an image upright by its EXIF
orientation. It names the asset shown to the Detail panel under the same request token, and the shown size too. `showMedia` starts a shown Video with `play()`,
falling back to muted playback where the browser refuses sound, and interrupted plays (`AbortError`) are ignored; a clip
shorter than ten seconds loops (`LOOP_MAX_DURATION_SECONDS`). Volume and mute are media-chrome's preferences
(`localStorage` keys `media-chrome-pref-volume` and `media-chrome-pref-muted`), applied once per open and never written
by the muted fallback, which sets `muted` on the element. A shown Video is described to the Media Session
(`js/common/video-playback.js`: title, Preview, play/pause/seek, previous/next when there is a cell), cleared when
another asset loads and on close. Stepping to another asset pauses a playing video, and `closeModal` unloads the videos
of the host it closes instead of pausing them, so no OS media control can resume one behind a closed modal; the video
streams from the same `/content/.../file/` URL by byte ranges. The results grid is the modal's source of truth:
the coordinator remembers the cell it shows (not the asset: a Location grouping holds an asset in a
cell under each of its Locations, and stepping from the cell keeps the walk in the group the user was
looking at), and next/previous move to the nearest `.cell` sibling of that cell in document order,
skipping `.result-group` headers (`handleShowNext` and `handleShowPrevious` share `step({ direction,
loadBeyond })`, whose `siblingStopOf` stops at a cell or a page spacer). A detail opened from no cell,
such as a map pin, has nowhere to step. A spacer met in either direction is reloaded (`reloadSpacer`
from `js/search-results/page-window.js`, shared with the scroll if already in flight) and the walk
looks again, since a 204 brings no cell and may leave the next spacer in its place. At the end of the
loaded grid, next calls `loadNextPage` on the last cell - the same page load the scroll observer
makes, shared if already in flight - and continues into the cells it appended; at the true end, and
at the true first cell, nothing happens. There is no shadow list and no JSON: a cell
removed from the grid drops out of navigation with it, and the search route is HTML only.

Fullscreen (`js/search-results/detail-fullscreen.js`, `app.detailFullscreen`) is the whole of Asset detail, on an image
as on a Video: `f` (`global.js`, dispatching `Const.events.toggleFullscreen`, wherever focus is except in a text field, a
held key or a modifier left to the browser), the toolbar's toggle after the Face highlights' (`.toolbar-toggle`,
`data-app-fullscreen-toggle`, its key in its title, `aria-pressed` following `document.fullscreenElement` on
`fullscreenchange`; removed where `document.fullscreenEnabled` is false, as the player leaves its own button out) and
the player's fullscreen button all put the host, `#imageDetailModalContainer`, in fullscreen through `requestFullscreen`,
so the Detail panel, the Face highlights' layer and the player's control bar come along: `attach` gives each fragment's
`<media-controller>` the host as its `fullscreenElement`, once media-chrome has defined the element (a property set
before an element is upgraded would shadow the class's accessor), so the player's button requests fullscreen on the host
and shows the host's state, and its `hotkeys` carries `nof`, so its own `f` is off. Under `:fullscreen` (`core.css`, by
the box's rules) the toolbar is hidden and the box is the whole screen, panel open or not, so the stage fits the media to
it, enlarged when it is smaller, and the panel keeps its share. Fullscreen is never remembered. Stepping stays in it;
Escape is the browser's, which leaves fullscreen (Chrome consumes the key, and `global.js` leaves it alone while
`document.fullscreenElement` is set), and a second Escape closes Asset detail; closing Asset detail by any path leaves
fullscreen first (`releaseActiveHost` in `common/modal.js`, for a host containing the fullscreen element). The top layer
stacks in order of entry, so a popover shown before fullscreen begins sits under the fullscreen element: the snackbar's
stacks, outside the host, are shown again on entering (`raiseSnackbarStacks`, `common/snackbar.js`), which puts them, and
the messages that join them, above it; the Dock side menu and the combobox's listbox are inside the host and open after,
and the tooltip's one caller is outside Asset detail, so nothing else moves.

**Detail panel** — The docked panel of Asset detail (the glossary's term) holding the asset's People, its Details and its
Metadata. The fragment `htmx/view_image_detail_modal` (`#imageDetailView`, styled by `static/css/htmx/view_image_detail_modal.css`)
is a grid of three areas as the main page is: the media stage (`#imageDetailStage`, a size container the media fit from the
asset's ratio in container units, so the player and the image resize with the panel), the divider (`#detailPanelDivider`, a
`.divider` with its orientation class) and the panel (`aside#detailPanel`: a header holding the Dock side menu at its end,
`#detailPanelContent`, which scrolls, and under it the key legend, a `footer.panel-keys` of two lines that stays in view
while the content scrolls: ← → Previous/Next, F Fullscreen, M Open/Close this panel; P Face highlights, Space Play/Pause.
Its fullscreen entry carries `data-app-fullscreen-key` and goes with the toolbar's toggle where `document.fullscreenEnabled`
is false; Escape is not listed, since what it does depends on what is open). Its shape is the panel's **Dock side**, `data-detail-dock-side` on the fragment with the
share as `--detail-panel-share`, one rule per side bounded by `--detail-panel-min-*` and `--detail-stage-min-*` of `core.css`;
closed (no `data-app-detail-panel-open`), the stage is the whole grid, the panel and the divider take no room and the box is
fitted to the media as before, while open the box fills the viewport (`core.css`) and the panel takes its share: 30 % of the
width for a left or right dock, 35 % of the height for top or bottom, until a drag. The panel docks right by default in every
viewport and never follows the orientation; its picks are the options of `#detailDockSideMenuHost`, a compact choice menu whose
options carry `data-app-detail-dock-side`, the panel's own attribute, since the explorer's dock takes its picks on the same body.
The dock itself (the side and share written on the fragment, the divider's drag, arrow keys, Home, End and Enter, the menu's
upkeep, the state remembered) is `js/common/dock.js`, the factory the explorer's dock is made from too (see **Explorer dock**),
configured by `js/search-results/detail-panel.js`, which the composition root creates (`app.detailPanel`) and `fragments/image-detail.js`
attaches to each fragment (`attach`: the menu's control built first, so the dock finds its trigger, then the layout and the divider).

The panel opens and closes with `m` (`global.js`, dispatching `Const.events.toggleDetailPanel`, wherever focus is except in a text
field, ignoring a held key or any modifier) and with the toolbar's toggle before the × (`.toolbar-toggle`, `includes/html_common`,
`data-app-detail-panel-toggle`, its key in its title, `aria-pressed` kept by the module); the Face highlights with `p`
(`Const.events.toggleFaceHighlights`, the same rules) and the toggle beside it (`data-app-face-highlights-toggle`). The player's
`hotkeys` carries `nof nom`, so `f` and `m` are Asset detail's (fullscreen is under **Detail navigation**). While the panel is open or the highlights are on (`wantsFragment`, the one place that
says so), the sections load as `htmx/detail_panel` (`GET /htmx/asset/r/:repoId/detail-panel/:assetId`, `AssetActionController`) through
the axios client, on open, on each step and when either is switched on with the asset's details not loaded yet: the coordinator names
the asset shown (`showAsset`) with its request's `isCurrent`, so an answer that arrives after a step or a close is dropped, as a
superseded media load is, and the host is emptied as another asset starts loading, so no asset's details read as another's. A step
names it twice: as it starts, with `load: false`, which empties the host and the layer and loads nothing, and from `showMedia` once the
asset's metadata is in, which loads the fragment, so a failed asset fetch is one error snackbar and no request for the panel. The sections
(`static/css/htmx/detail_panel.css`, by class so the style guide shows them): People is `htmx/detail_panel_people` (one row per Face,
a button (`.face-row`, `data-app-face-row`) holding the cover then the name, tighter than the explorer's list rows, Hidden marked,
`aria-pressed` while its Face is selected; read-only in the trash, where no correction is offered),
Details a `dl.detail-rows` of the file name, type, dimensions, duration, size, Date Taken with its source (`CaptureDateSource.label`),
Date Imported, the Folder as shared `.crumbs`, the albums and the Locations (`Category › Name`) each a link to its search
(`/r/:repoId?folderId=…`, `albumId`, `locationId`) in a new tab, so Asset detail stays open, the GPS position, the camera rows,
the Image quality score and the frames of a triaged image's Sequence; Metadata one closed `<details>` per extracted directory
(`data-app-metadata-group`). The dimensions row (`data-app-detail-dimensions`) is written from the shown element's size, which the
coordinator hands over, so a rotated phone photo reads as shown. What is remembered per browser, under `Const.localStore.assetDetailView`
as `{ panelOpen, highlightsOn, dock: { side?, widthShare, heightShare }, openGroups }`: whether the panel is open (closed by default),
whether the Face highlights are on (written for `p`), the dock (its part, written by the dock itself) and the open Metadata groups
by directory name, applied to every fragment that arrives; a selection is never remembered. Space on a control in the panel presses
it and the arrows on the divider move it, which `global.js` leaves to them.

The **Face highlights** (the glossary's term) are drawn from the People rows' data attributes (`data-face-id`, `data-person-name`,
`data-person-hidden`, `data-face-box` as `x,y,w,h` in the detector's pixels, `data-frame-time-ms` for a Face in a Video) into
`#faceHighlights`, a layer in the stage that shares the media's cell and box (`view_image_detail_modal.css` gives the three the same
width, ratio and `grid-area`), so a highlight is placed by fractions of the shown element's natural size (`--face-x`, `--face-y`,
`--face-width`, `--face-height`), the size the coordinator hands the panel: the detector decodes an image upright by its EXIF
orientation as a browser does, and a Video's frames are turned as its player turns them, so the two spaces are one. The layer takes
no pointer events and paints above the player, so the control bar stays usable; a highlight (`.face-highlight`, built by
`js/search-results/face-highlights.js`) is a `button` with `aria-pressed`, a `--border-hairline` outline in `--theme-neutral-1` with
`--face-highlight-radius` corners (`core.css`) over a transparent box, `--border-heavy` while selected, and its badge
(`.face-highlight-badge`, the Person's name as text at `--font-size-base`, light on `--scrim-color`, Hidden marked, semibold for the
selected one) is anchored to it by an inline `anchor-name` per index and placed `position-area: bottom`, centered under the box, with
`flip-block` above it when there is no room below and `@position-try --face-highlight-badge-inside` at the box's bottom edge when
there is none above either, so a badge never leaves the media; it is cut with an ellipsis at the layer's width (an inline-size
container) and takes no pointer events, so the player's control bar under it stays usable. The layer is emptied as soon as another asset starts loading
(`showAsset`) and drawn again when the media is shown, when the rows arrive, on `p`, on a selection and, for a Video, on its `pause`,
`play` and `seeked`: with `p` on every Face shows, the selected one emphasised; off, the selected one alone; on a Video a Face shows
only while the player is paused within `FRAME_TIME_TOLERANCE_MS` (100 ms) of its Frame time, none during playback. The selection is
one Face at most, held in `detail-panel.js` and never exported, the state the corrections act on: a click on a row's button
or on a highlight's box (which carries `data-face-id`; the badge takes no clicks) selects the Face, a click on the selected one clears it, and a
step, each open and `p` clear it; on a Video a row's click seeks to the Face's Frame time and pauses, and playing or scrubbing away
takes the highlight off while the row stays selected. A highlight's click also opens the panel when it is closed, as `m` would, and
scrolls the row into view. The style guide's Panels & layout section holds a specimen of a highlight with its badge, a selected and a
Hidden one.

The **Face corrections** (`docs/faces.md` **Corrections**) act on the selected Face: under the selected row sit Change and
Remove (`.face-actions`, shown by the row's `aria-pressed` in `detail_panel.css`, so no script toggles them; rendered for a
live asset only, the section of an asset in the trash carrying `data-app-read-only` and offering nothing, which the server
refuses too). `js/search-results/face-corrections.js` owns the two clicks and the requests: Remove's first click arms the
button and shows the label it carries ("Remove (last click)", `data-app-face-remove-label-last`), the second click on the
same Face sends `DELETE /htmx/asset/r/:repoId/face/:faceId`, and a change of the selection disarms it; Change is the
**Change combobox**, `x-data="faceCombobox"` on the row's `.face-actions` (`js/alpine/components/face-combobox.js`,
hand-built with core Alpine on the ARIA combobox pattern of `docs/reference/alpine-components/headless-combobox.md`): an
input with `role="combobox"` (`aria-expanded`, `aria-controls`, `aria-activedescendant`) that takes the buttons' place, an X
beside it, and a `role="listbox"` manual popover (`.face-listbox`, in the top layer so the panel's scrolling never clips it)
anchored under the input by CSS anchor positioning with an anchor name per Face, as wide as the input, flipped above when
there is no room below, capped at `--face-listbox-max-height` and scrolling. Opening loads the options once per opening
from `GET /htmx/people/r/:repoId/assignable?faceId=…` (`assignablePeopleUrl`, the one place the URL is built), parses the
fragment's `role="option"` rows wherever they sit (`parseAssignablePeople`) and filters them on the client as the user
types, the first `MAX_SHOWN_OPTIONS` (50) matches rendered with lazy covers, Hidden marked; the server leaves the Face's own Person
out of the options (`PersonService.assignablePeople`, one read for the group and the list).
The fragment opens with the **Suggested** group (`docs/faces.md` **Corrections**: the people whose Faces are nearest the
Face's vector, closest first, at most 3, each option marked `data-suggested` and left out of the list after it): while
nothing is typed the component renders them (`suggested`) as the ARIA listbox group pattern above the list (`matches`, the
rest), `li[role=presentation].suggested` holding the heading (`.group-heading`, an ID per Face) and a `ul[role=group]`
labelled by it, so the listbox announces the heading with its options (the "No one matches" line is `role="presentation"`
too, so the listbox holds options alone); from the first character the group is left out
and the suggested people are filtered with the rest, ahead of them. The server is the one source of the options and the
component their one renderer; the group is absent when nobody is near enough, and the list works as before. A name no
option has, within the server's name length rules (`Const.people.nameLength`), is offered as the Create row ("Create
'Alice'"); an existing name in any case or Unicode form picks that Person instead. The Face's own Person is no option, so
their name is offered as a Create row too, and the server refuses it as already theirs (a 400 in the error snackbar). Down and Up move the active option,
wrapping, through the group, the list and the Create row in that order, and reopen a closed listbox; Enter picks the
active one, which before the arrows move it is the Person of the exact name, else the first match, else the Create row,
and nothing while the input is empty; the pointer over an option makes it active;
focus leaving the combobox closes the listbox and leaves the input. A pick is announced as `Const.events.facePersonPicked`
(`faceId` with `personId` or `name`), which the corrections module answers with
`PUT /htmx/asset/r/:repoId/face/:faceId/person`; the X announces `Const.events.faceChangeCancelled`, and the panel clears
the selection and returns focus to the row. Escape with the listbox open closes it alone: `global.js` calls
`closeOpenFaceCombobox` before `closeModal`, so a second Escape closes Asset detail. `m` and `p` in the input are the
field's, as in every text field.

A correction answers the People section (`htmx/detail_panel_people`, 200), which `detail-panel.js` (`afterEdit`) swaps in
whole for the shown one: the selection clears, the rows and the Face highlights are drawn again from the new rows (with
`p` off nothing shows, with it on the remaining Faces), the result message from the root's `data-app-result-message` is a
success snackbar, with the line of a Person the correction deleted (`data-app-deleted-person-message`) after it,
`peopleChanged` reloads the People tab at once, and focus goes to the first row. A 400 (a reserved name) is the server's
message in the error snackbar and nothing changes; a 404 or 409 (the Face gone or moved meanwhile, the asset recycled)
means the section is stale: the message is shown and the panel loads the asset's details again (`reloadSection`). One
correction at a time per Face. While viewing a Person (`searchParams.personId`), an asset they have no Face left on after
a correction stays in Asset detail and is noted as leaving (`noteViewedPerson`; a later correction that gives them a Face
back keeps it, unless they were deleted): on stepping away, once the step has picked its target (`showAsset` for another
asset), or on closing (an `Alpine.effect` over the `modal` store), its cells leave the grid through
`assetActions.removeAssetsFromGrid`, so the walk continues from the cells around them, and the results title's count is
read again (`GET /htmx/people/r/:repoId/p/:personId/title` into `#person`), or, when the viewed Person was deleted, the
results go back to the whole repository (`runSearch` with an empty `personId`, as the title's × does). A detail opened from
no cell (a map pin) has no cell to remove and still refreshes the title; a Search text result stays until the next search.
The style guide's Panels & layout section holds a specimen of the actions under a selected row and of the combobox with
its listbox.

**Curation** — The modal where the user keeps the best frames of a Curation set and recycles the rest. What sorts a set and
what its outcome writes are under **Sequences and Image quality** in `altitude/AGENTS.md`, and the terms are the glossary's. It
has no page or URL of its own and adds no nav entry, so the nav keeps marking the view it opened from, and a reload shows the grid.
Three ways in open the same modal, each dispatching `Const.events.curateRequested` with its entry: Triage's
`#curateSequences` on the queue (no detail; see **Results title**), a Sequence's group header on that Sequence (`{ sequenceId }`;
see **Group headers**) and the batch ops' Curate on a selection (`{ assetIds }`, which the component posts, so they never sit in
a URL; see **Batch ops**). `js/listeners/curation.js` names the entry (`requestCuration`) and loads the shell through `htmx.ajax`
into the host, a new shell for every open, so each starts with every row open and no undo entry; `js/fragments/curation.js` hydrates it
by opening `ModalHost.curation` with focus on `#curation`, whose `tabindex="-1"` means the keys press no control by accident.

- **The shell** (`htmx/curation.scala.html`) is a header over both columns with the title, `remaining` from the `sequencesToCurate`
  store, the Sequence rail's switch (`Sequences Q`, the queue alone, `aria-pressed` while the rail shows, wearing the stage's
  pressed rule), Undo while there is one and Exit; the main column, `#curationMain`, holding `#curationSet`, which each set's
  fragment goes in, and the four states with no set, `.curation-no-set`: too few frames left (the words of `curation_set(None)`,
  for a selection's answer and a Sequence's 204 alike), the report of a resolve, the unread state, and the queue's end; and
  beside it the Sequence rail. The first three states offer Exit, and Undo when there is one, beside what they say.
- **The Sequence rail** (`aside#curationSequenceRail`, the glossary's term) is a column of `--curation-sequence-rail-width` at the
  right of the shell, on the queue alone (`x-show="isQueue && showsSequenceRail"`), outside `#curationSet`, so it outlives each
  set's fragment and stays through the states with no set. Its header reads "Sequences" and the walk's position among the rows
  (`railPosition`, "4 of 12"; in the end state, where no row is current, the count alone, "12 of 12"; nothing with no rows), and
  under it `#curationQueueHost` holds the
  queue's rows, `htmx/curation_queue.scala.html`, which `startQueue` reads through the axios client (`readQueueRows`) and takes
  `queueIds` from, each row's `data-rail-sequence-id` (`Const.attributes.railSequenceId`; not `data-app-sequence-id`, which finds a
  group header's Curate) in document order. A row (`li.curation-queue-row`) is the Sequence's cover Preview (its first frame in
  capture order, in `.curation-preview`, the Bundle rail's), its frame count ("8 frames") and its first capture's day ("12 Mar
  2026", `Util.humanReadableShortDate`, or "No date" for an undated Sequence). No binding is on a row: `paintRail`, run by a
  `$watch` on `position` and after the rows are read, toggles `.current` with `aria-current="true"` on the row at the position,
  scrolling it into view (`block: "nearest"`), and `.done` on the rows of `doneIds`, the closure set of the Sequences dealt with,
  so a long queue costs nothing per move; a done row is dimmed and wears the Preview's check (`fa-check-circle`, "Dealt with"),
  which no other row shows. One delegated click listener on the host (`onQueueRowClick`) moves the walk to an open row (`move`,
  see **The walk**); the current row and a done row do nothing. `toggleSequenceRail` (the Q key and the header's button) shows or
  hides it and remembers the choice under `Const.localStore.curationSequenceRail`, as the Histogram's is; hidden, it changes
  nothing about the walk. A selection or a single Sequence has none, and Q does nothing there.
- **The state** is `x-data="curation"` on the shell's root (`alpine/components/curation.js`), so it outlives each fragment: the
  focused frame, the pinned frame, the Picks, the Bundle kinds whose details show, the queue's IDs, its position, its done rows
  (`doneIds`) and the Sequence of the set on show (`shownSequenceId`, which the position runs ahead of while a move reads), the one
  Sequence or the selection's IDs, and the undo entry. It reads a set's frames from their `data-*` and the fragment's bindings read
  its state back.
  A set's fragment replaces the one before it in `#curationSet` after that one's Alpine tree is destroyed (`replaceSetHost`, which
  also empties the host with no set), so no loop of the old fragment runs once more on the new set's state.
  Exit and Esc close Curation through `closeModal`, and a backdrop click does not. The component notices the host going
  (`$watch` on the `modal` store), announces `Const.events.curationClosed` and empties the host, so the originals stop loading;
  an action still running announces when it finishes. Actions run one at a time (`exclusively`), so a key pressed during one does
  nothing. A failed write goes to the snackbar and leaves the set on show, since nothing was saved. Every action is saved as it
  happens, so closing loses only the undo entry.
- **A failed read** of a set or of the queue (`reading`), whether it opens Curation or follows an outcome, goes to the snackbar too
  and shows the unread state ("This set could not be read") with Try again, which runs that read again, Undo when there is one and
  Exit. No set stays on show past it, so a set whose outcome was saved cannot be acted on again: with no frames on show the keys
  of the set do nothing. The walk's position and the undo entry stay as they were, so no Sequence is passed over, and on the
  queue the Sequence rail and the moves still work there, so a Sequence that will not read cannot block the rest.
- **The Bundle rail** (`#curationRail`, `htmx/curation_set.scala.html`) is a column of its own that scrolls by itself: the notices
  (`.curation-notice`, "Showing the first 100 of 2,000 frames" with what that means for the queue when a Sequence is over the window a
  set reads, "Too close to call" or, in the warning fill, "Every frame: 8% blown highlights"), the Contenders, then
  each Bundle in the rule's order, each frame a Preview that focuses it, with its reasons and its chips (`.curation-chip`). A
  frame's `data-problems` holds what the stage's tag says is wrong with it, its Bundle first and then its chips; a Contender has
  none. The Preview carries the frame's marks: `fa-thumbtack`, labelled "Pinned", on the pinned frame, and `fa-check-circle`,
  labelled "Keep", on a Pick. A Bundle's header gives its name, its count, the details switch, Recycle and its reason (the
  first frame's first, "and 2 more"), and shows the same check while the Bundle holds a Pick, which its Recycle leaves in place. A sole Contender
  is marked (`.curation-chip.sole`), and an unmeasured frame says "Not measured". Every Bundle lists its frames, whatever the
  set's size: there is nothing to collapse or open.

  A Bundle's details, its header's reason line and the reasons under each of its frames, are hidden until its details switch is
  pressed: an `action-button small` before Recycle holding `fa-info-circle` alone, so its `aria-label` is "Details" and its title
  "Show or hide what is wrong with each frame", `aria-pressed` while they show (the stage's pressed rule, which the Bundle
  header shares, gives it the success edge and icon). The file names, the marks and the chips always show. The switch is one per
  Bundle kind and not per set: `detailKinds` on the component lists the kinds whose details show (`showsDetails`,
  `toggleDetails`), so Poor faces opened in one set is open in the next, and it is remembered in `localStorage` under
  `Const.localStore.curationDetails` (`rememberedDetailKinds`, `saveDetailKinds`), as the Loupe's level is. It has no key.
- **The stage** (`#curationStage`) is two panes side by side, from the original (`/content/.../file/`): the pinned frame on the
  left and the focused frame on the right (`leftFrameId`, `rightFrameId`), or one pane, the focused frame, when nothing is pinned
  or the pinned frame is focused; that one frame counts as the left frame. Pinning keeps nothing: Resolve keeps the Picks, or with none the one frame on the stage.
  A set opens on `openingFrames`, the rail's first two frames, the first pinned and the second focused: two Contenders when it has
  them, a sole Contender beside the first frame of a Bundle, or the first two frames of its Bundles, so every set opens on two
  frames and Enter keeps nothing until one is a Pick. A set read again after a Bundle's recycling or its Undo keeps what the user had pinned,
  the focused frame, the Picks and the zoom (`currentView`), as far as its frames are still in
  it; after a frame's recycling the next frame takes its place on the stage (`viewWithout`, below). A pane reuses its image, and the browser paints the old picture until the new
  one is available, so a pane never shows a frame under another's name: from the moment its frame changes until its image's `load` or
  `error` (`isPaneLoading`, `settledSrcs`) the image is hidden and asset detail's spinner (`.media-spinner`, `core.css`) shows after a
  moment, so a frame already in the browser's cache does not flash it. The zoom is applied on `load`, before the image shows. A Pick
  on a frame still loading is allowed, the caption naming the frame beside the spinner. The focused frame's neighbours in the rail's
  order, the frames the arrows go to, are preloaded, and a preload no longer needed stops, so a held arrow does not queue originals
  ahead of the frame on show (`preloadOnly`).

  A pane's tag (`.pane-tag`), over its top left corner, says what the rail says of the frame: a success-colored "Keep" (`.curation-chip.keep`) on a Pick, and the frame's problems
  as chips (`problemsOf`, from `data-problems`). It is in `.pane` and not on the image, so a zoom's transform leaves it in place, and
  it stays while the frame loads. The focused frame's pane has a thin accent line along its bottom, the same `--theme-accent` as the
  rail's edge on that frame, with one frame on the stage too. It is `border-bottom: var(--border-hairline)`, transparent on the
  pane that is not focused: both panes carry the border, so the two images stay the same height as the line moves.

  A pane's caption holds the frame's file name, the Pin button or the pinned label, the Keep button and the Recycle button. The Keep button reads "Keep left 1" and
  "Keep right 2", or "Keep 1" with one frame on the stage, the number a `<kbd>` (`keepLabel`, `keepKey`). Pressed, it wears the success
  color (`aria-pressed`) and its label stays, since the tag says the frame is kept. The pinned frame's caption reads a pin icon and "Pinned"
  (`.pinned-label`), centered below the image: the caption is a grid of `minmax(0, 1fr) auto minmax(max-content, 1fr)`, as the footer is. The Pin button, "Pin Space", is on every other frame, so it is always on the focused frame, which is what Space acts on.
  Pinning the focused frame (`pinFocused`), by its button or Space, replaces the pinned frame and moves focus to the next frame in the rail's order, or the previous one at
  the end, so two frames stay on the stage. There is no unpinning, since a way out with no way back to the same two frames only loses the comparison. Focusing
  the pinned frame, by an arrow or a click, shows it alone, Space does nothing there, and moving away brings the two back.

  The Recycle button (`action-button small warning`, "Recycle Shift+1" on the left frame or the only one, "Recycle Shift+2" on the
  right, `recycleKey`) sends its pane's frame to the trash at once, a Pick or not (`recycleFrame`), for a frame that is plainly
  no keeper: the grid's own recycle and a read of the set again, as a Bundle's recycling is (`recycleFrames`, which both go
  through). The set is read again in `viewWithout`: focus moves on from the focused frame to the next frame, or the previous
  one at the end, never to the pinned frame, and of two frames on the stage the one left is the pinned frame, as after a Pin, so
  the frame coming in is on the right and two stay on the stage: recycling the left frame of A and B leaves B pinned beside C,
  and recycling the right one leaves A pinned beside C. A frame alone on the stage hands that neighbour its place. With too few
  frames left the walk goes on as after a Bundle's recycling. Its Undo puts the frame back on the stage beside the frame it was
  recycled from (the undo entry's `stage`), whatever the stage shows by then.

  The Loupe is a lens in each pane (`.loupe`, `#curationLoupePinned` and `#curationLoupeFocused`, beside the images'
  `curationStagePinned` and `curationStageFocused`), last in the pane so it paints above the image, the spinner and the tag. The
  pointer over a pane's image shows it: the spot under the pointer at the original's pixels (1:1), on that pane alone. The level is
  one value for every frame, `loupeLevel` on the component, a rung of `LOUPE_LEVELS` (1 to 4 by halves): the wheel over a pane
  steps it, the only control it has, and the module hands the new rung back (`saveLevel`), which the component keeps and
  remembers under `Const.localStore.loupeLevel` (`rememberedLoupeLevel`, `saveLoupeLevel`), so it holds across sets and opens.
  The lens's label is the only place the level reads. A small original, or a zoomed one, that the pane already shows at or above the
  chosen level is shown at the first level above its fit, and the wheel steps from the level shown, so every step changes what the
  lens shows. A wheel with a modifier held is left to the browser (Ctrl zooms the page), and one with nothing under the pointer to
  magnify steps nothing. `js/common/loupe.js` places and draws it (`attachLoupe`, answering `refresh` and
  `detach`) and knows nothing of Curation: it maps the pointer through the image's bounding rect, which includes the zoom's
  transform, so the Loupe works unchanged while the stage is zoomed to a face, and draws with the image's `currentSrc` as the lens's
  background, so nothing is fetched. `watchStage` attaches it with the stage observer, `applyZoom` refreshes it, as does
  `onImageSettled` on `$nextTick`, once Alpine has shown the image, and `detachLoupe` detaches it with the observer, on the next set
  and on close. The module reads whether the image is laid out and its computed visibility, so the hidden pinned figure and a
  loading pane show no lens, and over the letterbox bars no lens shows. It writes the lens's `data-level` ("200%", the label via
  `content: attr(data-level)`),
  its inline styles, `image-rendering: pixelated` above 1:1 among them, and `hidden`, and nothing else; `curation_set.css` has the
  rest: a square a third of the pane wide, capped by `--curation-loupe-max-size` (`core.css`), the hairline edge in the pane's dark
  side, and `cursor: none` on the image of a pane whose lens shows (`.pane:has(> .loupe:not([hidden])) img`).

  The Histogram is a strip under each pane (`.histogram`, `#curationHistogramPinned` and `#curationHistogramFocused`, beside the
  images' IDs): a button, the figure's row between the image and the caption (`grid-template-rows: minmax(0, 1fr)`, the pane's
  row alone, the strip's and the caption's being implicit, so a hidden strip leaves no empty row or gap and the pane takes its room),
  `--curation-histogram-height` tall, holding the frame's Histogram in an inline `<svg viewBox="0 0 64 16">`: after two static
  `<line class="grid">` at a third and two thirds of the range, a `<path>` a channel, `.red`, `.green` and `.blue` filled and
  `.luminance` a line over them, each `d` built by the component (`histogramPath(frameId, channel)`). The data is the frame's,
  measured at import (`ImageMeasurement`, see **Sequences and Image quality** in `altitude/AGENTS.md`): its rail item carries
  `data-histogram`, the Base64 of `Histogram.relativeBytes`, a byte per bin, 64 bins a channel in the order luminance, red, green,
  blue (`HISTOGRAM_CHANNELS`), each bin over the tallest bin of the four, so the channels share one scale and the tallest reaches
  the strip's top (`HISTOGRAM_TALLEST`). `showSet` keeps the string on the frame (`frames[].histogram`), and `histogramPaths`
  decodes it (`atob`) into the four paths the first time the frame is on the stage (`histogramPathsOf`, closure state by frame ID,
  cleared with the set): `stepsOf` walks a channel's bins as steps with no gap, `areaPath` closes them down to the baseline for a
  color and `linePath` leaves them open for the luminance. The svg is `isolation: isolate` and the three colors are
  `mix-blend-mode: screen` at `fill-opacity: 0.6`, filled with `--histogram-red`, `--histogram-green` and `--histogram-blue`
  (`core.css`, the pure primaries), so where two overlap the strip reads yellow, cyan or magenta and where the three do a neutral;
  the luminance line is `fill: none` with a 1px stroke of the strip's color at full alpha (`stroke: hsl(from currentColor h s l /
  1)`, `vector-effect: non-scaling-stroke`), the strip's color a theme neutral at an alpha over the pane's backdrop, so no color
  is written in the script. In the strip's bottom corners two spans (`.crushed` left, `.blown` right, `crushedLabel`,
  `blownLabel`) read the fractions of the frame's pixels crushed and blown to one decimal ("14.2%", `percentLabel`), the stored
  `crushed_shadows` and `clipped_highlights` its rail item carries as `data-crushed-shadows` and `data-clipped-highlights`,
  monospace chips (`--font-nav`, `--font-size-xs`) on the backdrop in `--info-color` and `--error-color`, the colors the Clipping
  blink marks each side with; an empty span shows no chip. The strip is its frame's and reads no pixel, so it shows while the
  frame's image loads; a frame not measured carries none of the three attributes and its strip is empty. A failed image disables
  the button (`isImageUnreadable`, `unreadableSrcs`).

  The H key shows or hides both panes' strips: `showsHistogram` on the component, one switch for the stage, which the button's
  `x-show` reads, shown until hidden and remembered under `Const.localStore.curationHistogram` (`rememberedHistogramShown`,
  `saveHistogramShown`), so it holds across sets and opens, as the Loupe's level does. `toggleHistogram` flips and saves it, and
  does nothing with no set on show, where the change would not be seen. Hiding the strips turns the Clipping blink off, since the
  strip is its only switch, and hands focus to the shell when a strip held it; showing them again leaves the blink off. The key
  has no button: the footer's keys hint names it.

  The button is the Clipping blink's switch: `clippingBlink` on the component, one switch for the stage, false on every open and
  whenever the strips are hidden, and not stored, `aria-pressed` while on (the stage's pressed rule gives it the success edge and luminance line; the channels keep their colors), with the `aria-label`
  "Clipping blink" and the title "Blink the blown highlights and crushed shadows", since it holds no text. `toggleClippingBlink`
  flips it and, on, draws both panes' masks (`drawClipping`, async: a canvas that holds its image's mask is left alone; any other
  is blanked and, for an image with pixels, painted when the read is in, if the set, the pane's source and the blink still hold;
  the mask is painted from the read's classes, `clippingMask`, and put at the sampled size; a frame settling while the blink is on
  draws its mask from `onImageSettled`). The read is `js/common/clipping.js`'s (`readClipping`, async), made only while the blink
  is on and once Alpine has shown the image (`$nextTick`), so its decode never holds up the pane's paint: the image is resized by
  `createImageBitmap` to the measurement's box, off the main thread, and each pixel's gray is taken as OpenCV's `BGR2GRAY` does, so
  the marks are close to the pixels the stored fractions counted, not the same to the pixel. A read answers a class map, a byte
  per pixel (blown, crushed or neither), and is cached by the image's `currentSrc` for the set on show (`clippingOf`,
  `clippingReads`, closure state, since the classes are a million bytes no binding reads): the promise while it is in flight, so
  a second draw of the same source awaits the same read, then the read itself. The cache is a new map for each set (`showSet`,
  `showNoSet`), so an arrow back and forth costs nothing and a read finishing after its set has gone fills only the cache it was
  started for; a read that fails leaves the canvas blank. The canvas (`.clipping`,
  `#curationClippingPinned` and `#curationClippingFocused`, right after the image, under the spinner, the tag and the lens) is laid
  out as the image is (`position: absolute; inset: 0`, `object-fit: contain`, `transform-origin: 0 0`, `pointer-events: none`), so
  the mask lies over the picture pixel for pixel and `applyZoom` writes the image's transform to it; it is `hidden` unless the blink
  is on and the pane's image shows (`showsClippingBlink`). The blink is `curation-clipping-blink`, a `steps(1)` opacity animation at
  1 Hz, so the marks flash rather than fade, held steady under `prefers-reduced-motion: reduce`. The two colors come from the
  canvas's computed `color` (`--error-color`, the blown highlights) and `border-color` (`--info-color`, the crushed shadows), read at
  each draw (`clippingColors`), so no literal is in the script and they follow the theme; the levels and the box come from the set's
  root (`data-clipped-level`, `data-crushed-level`, `data-measurement-box`, from `ImageMeasurement`'s constants, read in `showSet`),
  so the client restates none.
- **The face strip** (`#curationFaces`), directly below the stage, is the main decision aid: eyes and expression are judged here.
  One row per Person, the Persons in the most frames first, and one column per frame in the rail's order, from the stored crops
  (`/content/.../face/`), with a dash where a frame has none. A column's
  head is the frame's Preview, which focuses it and carries the same "Keep" mark on a Pick as the rail's does. A click on a crop
  zooms both panes to that Person's box, or back out, with a transform (`applyZoom`, again when an image loads or a pane resizes); a
  frame with no Face of the Person shows whole. A box is in upright pixels while `asset.width` and `height` come from
  the file's header, so the zoom uses the displayed image's `naturalWidth` and `naturalHeight`.
- **The footer** is a grid of three columns, `minmax(0, 1fr) auto minmax(max-content, 1fr)`, so its center stays at the true center
  while the right column fits its share. That column is never smaller than its buttons, whose labels do not wrap, so in a narrow
  window it takes what they need, the center moves left, and the keys' hint stays on one line, clipped to the room that remains. At the left is that hint
  ("← → ↑ ↓ move, R recycle the frame's Bundle, H Histogram", `.keys`: the keys with no button of their own, at `--font-size-xl`, twice the
  footer's other small text, its key caps at `--font-size-large` with twice the shell's padding), and at the right edge Keep all and, on the queue alone (`x-show="isQueue"`), Previous and Next, the walk's moves (`movePrevious`, `moveNext`), each disabled while no open row is left on its side (`hasPreviousSequence`, `hasNextSequence`). A selection or a Sequence opened alone has no rows to move between and is left by Exit.
  The center holds what `keepIds` answers: the Picks; with none and one frame on the stage, that frame; and with none and two on the
  stage, nothing, so a frame on the stage is never recycled on a guess. With nothing to keep it holds the hint "Press 1 or 2 to keep a
  frame" (`.keep-hint`, in `--theme-neutral-3`) and no button, and Enter does nothing, since `settle` refuses an empty list. Otherwise
  it holds Resolve, reading what it will do: "Keep IMG_0042.jpg, recycle 3" with one frame kept, the name truncated with an ellipsis
  (`.kept`) and the button's title giving the label whole, and "Keep 2, recycle 3" with several. The keys are bound on `document`, so they go on working if Resolve held focus when
  it left. The note of frames to recycle that are in an album or a Location (`inAlbumOrLocation`, `.note`) takes a row of its own
  above the three columns, and is empty while nothing is to be kept, because it would otherwise count every frame. The row is there
  whenever any frame of the set is in one (`hasFrameInAlbumOrLocation`), holding one line while the note is empty (`min-height: 1lh`),
  so a frame's Pick does not shrink the panes mid-comparison, and its text sizes no column (`contain: inline-size`), or it would widen
  the right one and move the center.
- **The keys** are a `document` listener while Curation is the active host, ignored while a text field has focus
  (`isTextEditingTarget`, `common/text-editing.js`) or with Ctrl, Cmd or Alt held. Shift with 1 or 2 is told by the key's
  position (`event.code`, `recycleKeyOf`), since the symbol it types differs by layout; on a layout whose digits are on the
  shifted level (AZERTY) that press types the digit and stays the Pick's key, and Recycle is the button's alone. Only the arrows repeat when held, so a held key
  cannot resolve set after set or run through the queue, and `handleKey`'s `preventDefault` keeps ↑ and ↓ from scrolling the rail as well, also while a set is
  being resolved or read, when they move nothing. Enter or Space on a focused control presses that control, and a pointer's click hands focus back to the shell
  (`refocusAfterPointer`), so Enter resolves next.

  | Key | Action |
  |---|---|
  | ← ↑ | Move the focused frame back through the rail's order |
  | → ↓ | Move the focused frame on through the rail's order |
  | 1 | Toggle the left frame as a Pick, or the only frame when one is on the stage |
  | 2 | Toggle the right frame as a Pick; nothing when one frame is on the stage |
  | Shift+1 | Recycle the left frame at once, or the only frame when one is on the stage |
  | Shift+2 | Recycle the right frame at once; nothing when one frame is on the stage |
  | Space | Pin the focused frame and move on; nothing on the pinned frame |
  | Enter | Resolve with the Picks; with none, the frame on the stage when there is one, and otherwise nothing |
  | A | Keep all |
  | R | Recycle the focused frame's Bundle, leaving a Pick in it; a Contender has none |
  | H | Show or hide the Histogram strips, turning the Clipping blink off as they hide; nothing with no set on show |
  | Q | Show or hide the Sequence rail (nothing on a selection or a single Sequence) |
  | N | Move to the nearest open row below in the Sequence rail; nothing with none, and nothing on a selection or a single Sequence |
  | P | Move to the nearest open row above; the same limits |
  | Z | Undo the last action |
  | Esc | Close Curation (`global.js`) |

- **The walk.** On the queue, opening reads the queue's rows into the Sequence rail (`startQueue`) and requests one set's
  fragment at a time. A row is **open** or **done** (code and guide words, never shown): done once its set is resolved or kept
  (`settle` marks it, a Keep all that changed nothing included), or once its Sequence answers with no content when reached, a
  Sequence gone under two frames meanwhile or one a Bundle's or a frame's recycling left too few of (`markDone`); a done row stays
  in place, dimmed with its check, and the walk passes over it. Undo of an outcome opens its row again, and Start again opens every
  row (`doneIds` cleared), so a Sequence over the window, done for this walk, is offered again. Every move goes to an open row
  (`openRowFrom`, `readQueueFrom`): N (`moveNext`) to the nearest open row below and P (`movePrevious`) to the nearest above, each
  doing nothing with none (`hasNextSequence`, `hasPreviousSequence`, which the footer's buttons are disabled by); a click on an open
  row to that row; and after a Resolve, a Keep all or a recycling that left too few frames (`nextSet`, `readSet`) to the nearest
  open row at or below the position, else the first open row from the top, else the end state. A row that answers with no content
  when reached becomes done and the move goes on in its direction, the move after an outcome wrapping to the top before it ends; a
  move of N, P or a click that finds nothing further stays on the set on show and its row. The end state comes only when every row
  is done, the count read again and no row current (`position` -1, the rail's header reading the count alone): with Sequences
  left (over the window, or imported meanwhile) it shows them with Start again, which reads the rows afresh, and with none it reads
  "All done". The next set's opening frames are preloaded, the next open row in the
  direction of the last move (`preloadNextSet`).

  **Latest move wins.** N, P and a row click are moves (`move`): the walk stands on the target row at once, so the rail's highlight
  moves before the read answers, and each read of the queue takes a token (`moveToken`); a read a later move has overtaken finds its
  token stale when its answer comes and drops it, shows nothing and reports nothing, so the last move is the set that opens. The
  writes, Resolve, Keep all, Recycle, Undo, Try again and Start again, run one at a time through `exclusively`, which also refuses
  to start while a move is reading (`movesInFlight`), and no move starts while one runs (`isBusy`). N and P do not repeat while
  held, and an overtaken read is aborted (`AbortController`, `nextMoveToken`), so no dead read holds a write up. **Remembered
  views.** Leaving a set by a move, or by an Undo that reads another set, saves `currentView()` under its Sequence
  (`rememberShownView`, `rememberedViews`): once as the move starts, so a failed read loses nothing, and again as the set read
  replaces it, with what the user did meanwhile; restored when the walk returns to it, for as long as Curation stays open. A done
  row's set is never remembered, a row going done forgets it, and Start again forgets them all once the rows are in.

  A selection or a Sequence opened from its header has no rows, no next set and no Previous or Next: a Keep all that changes
  nothing closes Curation, Exit leaves it with no outcome; a Resolve, or a Keep all that changes something, stays
  open on a report of what was kept and recycled; and a Bundle's or a frame's recycling that leaves too few frames shows the
  too-few state.
- **The outcomes.** Resolve and Keep all post to `/api/curation/.../resolve`. Recycling a Bundle or a frame is the grid's own
  `DELETE /api/asset/.../move`, and reads the set again. Undo holds one entry, the last mutation with the Sequence of the set it was
  made on (`shownSequenceId`), and reads that set again by its own ID wherever the walk has gone (`returnToSequence`): its row is
  open again and the walk goes back to it, or, when Start again has replaced the rows and it is in none, the rows are read afresh
  (`loadQueueRows`), which lists the undone Sequence with its cover, count and date, the done marks of the rows still listed stay,
  and the walk stands on it. The return and the set's read are one read (`reading`), so a failure in either shows the unread state
  and Try again runs both. A
  Resolve or a Keep all goes through `/reopen`, with what `resolve` answered, and its set opens as a new one; a recycled Bundle or
  frame, which has no Pick, goes through the grid's own `PUT /api/asset/.../restore`, and its set keeps the view: as it is if the set is still on
  show, else as it was left, a recycled frame back on the stage either way. Either answers as a restore does, and `reportRestore`
  (`assets/asset-actions.js`) reports it. A Keep all that changed nothing leaves the entry as it was.
- **The grid beneath** keeps its scroll position, and its selection is reset on every close, resolved or not: a set opened on a
  selection is done with, and a selection left standing would make the next one additive. `Const.events.curationClosed` carries
  what the session left changed, net of its undos (`recycledIds`, `clearedSequenceIds`). With nothing changed, nothing else moves.
  Otherwise `js/listeners/curation.js` takes the frames still recycled out through `removeAssetsFromGrid`, as the grid's own Recycle
  does, with the group counts and the total, and takes the Curate buttons off the Sequences cleared; kept frames stay under their
  old header until the next search. The map layout has no cells, so closing there runs the search again.
- **The counts.** `Const.events.curationChanged`, announced after each Resolve, Keep all, Recycle of a Bundle or a frame, and Undo, runs
  `refreshCounts` (see **Nav refresh**), so the nav, folder, album and Location counts and the number of Sequences to curate are
  true. A move of the walk changes nothing and announces nothing. The store is also read when the Triage results render
  (`js/fragments/search-results.js`), when Curation opens and when its queue runs out.
- **The styles** are `static/css/htmx/curation.css` (the host, which fills the window, the shell's columns and the Sequence rail's
  frame), `curation_set.css` (the set) and `curation_queue.css` (the Sequence rail's rows), with the sizes `--curation-rail-width`,
  `--curation-sequence-rail-width` (the Sequence rail's column), `--curation-preview-size`, `--curation-crop-size`, `--curation-strip-max-height`,
  `--curation-loupe-max-size` (the most a lens takes, a third of the pane's width under it), `--curation-histogram-height` (the
  Histogram strip under each pane) and `--curation-kept-name-max-width` of
  `core.css`. The last is the most width the file name on Resolve takes: `.kept` holds it to the
  smaller of the token and `20vw`, since the footer's center column takes its full width before the keys' hint gets any, so in a
  narrow window the name's share leaves that hint room. The style guide's Badges & states section holds the notice, the chip, a Bundle's row
  with its marks and the Sequence rail's rows, a current and a done one, and its usage note describes the pane's tag and the keep hint,
  whose rules apply only inside the set (see **Style guide**).

**Metadata field visibility** — Fields default to `display:none`. Use
`window.ctx.addGridMetadataField(name)` / `removeGridMetadataField(name)`, which mutate the
reactive `gridMetadataFields` set and persist it to `localStorage`. A reactive effect per displayed
grid (`js/search-results/metadata-visibility.js`) keeps one `show-<field>` class per visible field
on `#assets`, and the CSS in `static/css/includes/search_results.css` shows the matching
`.metadata > .<field>` rows: no per-cell work, nothing to re-apply for appended pages.

**Settings screen** — `settings.scala.html` (`/settings/r/:repoId`, an Admin's page, `SettingsController`) is shaped like
the import page: `header_common`, the shared nav, one `main`, `html_common` for the snackbar, `json-enc.js`, and the page
module `js/settings-page.js`, which starts Alpine for `html_common`'s bindings (`registerHtmlCommonBindings()`: the
focus plugin and the `modal`, `imageDetailLoading` and `snackbar` stores; the nav and the form carry none) and registers the htmx request rule with a minimal app and the
save listener (`js/listeners/settings.js`); the template passes `Const.NavEntry.SETTINGS` as the nav's `current`. `main` holds the one form, `htmx/settings_form.scala.html`
(`#settingsForm`), rendered by `SettingsFormController.form` for the page and again by its save, `POST
/htmx/settings/r/:repoId`: `hx-target="this"`, `hx-swap="outerHTML settle:0"`, `hx-json-enc`,
`data-app-success-event="SETTINGS_SAVED_EVENT"`. Its sections are the Setting groups, each a grid of label and control
rows (`.setting` is `display: contents`, so the labels of a section line up); a field is a text input or a checkbox with
`value="true"` for a flag, named after its Setting (`field-<name>`), followed by its Reset button, its error, a restart
notice ("Saved X, running Y until the server restarts") for a Restart setting whose saved value is not the running one,
its note, plain information always shown for a Setting the controller gives one (`.note`, one today: "Running N threads:
<reason>" on `import.parallelism`, from `Altitude.importParallelismResolution`), and its help text ending in `Default: …`.
The Settings of the other engine are not rendered (PostgreSQL only:
`face.recognition.hnsw_ef_search`, `db.postgres.read_statement_timeout`). Reset is `data-app-reset-setting`, handled by one
delegated click listener in the page module that fills the field from the row's `data-setting-default`
(`Const.attributes.settingDefault`), a checkbox by checking it, and clears its `error` class; nothing is written until Save. A failed validation comes
back as a validation replacement (`HX-Retarget: this`, see above) with the submitted values kept and each error beside its
field, so no success event fires; a save answers the saved form and `SETTINGS_SAVED_EVENT` shows "Settings saved". The CSS
is the partial's own file, `static/css/htmx/settings_form.css`, over `core.css` variables (`--section-vertical-space`, `--control-*-space`,
`--theme-neutral-3`, `--warning-color`); its one rule for the control's grid lets the field, an input or a select, share
the first row with its Reset and spans everything after them, and `core.css`'s error mark (`form :is(input, select).error`)
covers a select as it does an input; the page adds none. `includes/nav` shows the Settings link (`.menu.settings`)
to an Admin only (`RequestContext.account`), so `NavController` requires login and both nav loads happen behind one.

**Setup background** — `setup.scala.html` (`/setup`, `SetupController`; the index redirects here until the repository
exists) runs a full-screen animated scene behind the page, a top-down view of a jet over clouds and farmland. Its canvas,
`<canvas id="setupBackground">`, is the first element of the body; `static/css/pages/setup.css` fixes it over the whole
viewport at `z-index: -1` (above the root's background, below every element of the page, so the markup is otherwise
untouched), `pointer-events: none`, and `opacity: 0` with a 600 ms transition to `.ready`, so the form never sits on a
flash of blank canvas. The form's container (`:where(body.setup) main > div`) is centered vertically in `main`, which
fills the viewport above the footer and pads its top and bottom alike. It is a frosted card: a
radial gradient from `--setup-form-base`, opaque at the center, to `--setup-form-card-edge-color`, the same color at
zero alpha, at the corners, over `backdrop-filter: blur(12px)`, so the center is solid and the edges frost into the scene;
`--setup-form-card-border`, the outline in the form's accent; a 6px `border-radius` (a radius, not a spacing, so a
literal, as is the blur's); `--section-vertical-space` padding. Its first row, above the form, is the page's title, `#setupTitle` ("Altitude",
uppercase, centered, in `--setup-form-accent`, the outline's color). The card and the form on it are a carve-out from the
theme: `static/css/htmx/setup_form.css` styles the fields, their placeholder, hover, focus and error
states, the labels, the database type line and the full-width submit button from the `--setup-form-*` tokens alone, so the form keeps its
palette whatever the theme colors become. The page's palette, the scene's `--setup-background-*` tokens and the form's
`--setup-form-*` tokens, is declared at the top of `pages/setup.css` on `body:where(.setup)`: the page's own, not
`:root` tokens of `core.css`, literals with no `light-dark()` pair (the form's edge, border, focus and field tokens
follow its three colors, base, muted and accent), inherited by the canvas and the form. The style guide's scan
leaves `setup.css` out (`StyleGuideController.excludedFiles`), so the palette is on no card and in no audit; its
uses of core tokens are the spacing steps, `--text-shadow`, and the error box's `--theme-neutral-2`, `--error-color`
and `--theme-neutral-4`. The page has no top bar; the footer loses its
background and takes the chrome's text shadow (`--text-shadow`), so the scenery runs under it. The page module,
`js/setup-page.js`,
only calls `startSetupBackground(canvas)` of `js/setup-background/setup-background.js`: the form submits through htmx
and `json-enc.js`, which the page loads as plain scripts, so there is nothing else to register. The page side returns at
once where `canvas.transferControlToOffscreen` is not a function: no fallback, the canvas stays empty and the page looks
as it does without a scene. Otherwise it resolves the palette, since a Worker has no stylesheet: each token is probed by
setting `canvas.style.color = "var(--token)"` and reading `getComputedStyle(canvas).color`, which resolves
whatever the token holds, a literal, a relative color or a `light-dark()` pair, against the page; the computed string (`rgb()`, `color(srgb …)` or
`oklab(…)`, by recipe and browser) is painted into a 1 × 1 scratch 2D context and read back with `getImageData`, so
every color reaches the Worker as sRGB channels `[r, g, b]` (integers 0–255), to which the Worker applies alphas of its
own (gradient stops, shadows). The palette's keys are `fields` (six, `--setup-background-field-1` … `-6`, in order),
`fieldEdge` (`--setup-background-field-edge`), `road`, `cloud`, `cloudShadow`, `jet`, `jetOutline` (the
`--setup-background-*` tokens of those names), `lightLeft`
(`--error-color`, the red navigation light on the left wingtip), `lightRight` (`--success-color`, the green one on
the right) and `strobe` (`--theme-neutral-4`, the white strobe). It then reads the canvas's CSS size from
`getBoundingClientRect()` and `devicePixelRatio`, starts the module Worker,
`new Worker(new URL("./setup-background-worker.js", import.meta.url), { type: "module" })`, and posts `init` with the
canvas transferred (`transferControlToOffscreen()`, in the transfer list). Every message is a plain object with a
`type`:

| Direction | Message | Fields | When |
|---|---|---|---|
| page → Worker | `init` | `canvas` (the transferred OffscreenCanvas), `width`, `height`, `dpr`, `palette`, `reduced`, `hidden` (`document.hidden`: a page opened in a background tab draws its first frame and waits to be shown) | once, on start |
| page → Worker | `resize` | `width`, `height`, `dpr` | a `ResizeObserver` on the canvas (the entry's `contentRect`, CSS px; its first notification repeats `init`'s size), and a change of `devicePixelRatio`, caught by a `matchMedia("(resolution: …dppx)")` query for the current ratio, re-armed for the new one each time it fires |
| page → Worker | `visible` | `hidden` (`document.hidden`) | `document`'s `visibilitychange` |
| page → Worker | `motion` | `reduced` | the `(prefers-reduced-motion: reduce)` query's `change` |
| Worker → page | `ready` | none | after the Worker's first frame is drawn; the page adds `.ready` to the canvas, which fades it in |

Reduced motion is watched live, so switching it on or off while the page is open stops or starts the scene; under it the
fade transition is dropped (`transition: none`), so the still frame appears at once. A Worker that does not load (the
file missing, a browser without module Workers) or throws fires the Worker's `error` event: the page logs one line
naming the feature and does nothing else, so the canvas stays transparent. The scene itself, the clock, the ground, the
clouds, the jet and the draw order, is the Worker's, `js/setup-background/setup-background-worker.js`.

The Worker draws in CSS px: the bitmap is `width × dpr` by `height × dpr` with `dpr` capped at 2 and the context
scaled to match, so a 3× phone does not triple the fill rate; the context is opaque (`alpha: false`), as the
field-edge fill covers it every frame. The clock is `requestAnimationFrame` (a Worker owning an OffscreenCanvas has it): a frame
advances the scene by `min(timestamp − last, 100 ms)`, so a resumed tab or a stalled frame moves it one short step,
and the scene keeps a running time `t` in seconds, which the lights read. One frame handle is pending at
most: a start requests a frame only when none is pending, a stop cancels it. On `init` the Worker sizes the bitmap,
generates the scene, draws the first frame synchronously (the page's fade-in waits on `ready`, and a hidden document
gets no frame callbacks), posts `ready`, then starts the loop unless `reduced` or `hidden`. `visible { hidden: true }`
cancels the pending frame; `visible { hidden: false }` resets the clock (the next frame's `last` is its own timestamp,
so nothing jumps) and starts the loop unless `reduced`; `motion` does the same for `reduced`, and `reduced: true`
leaves the frame drawn last as the still. A `resize` equal to the current size and ratio is a no-op (the observer's
first notification is one); a new ratio alone resizes the bitmap; a new size also refits the scene; either draws one
frame at once, since sizing the bitmap clears it and the blank would reach the page before the next frame, and a still
would otherwise stay stretched or blank.

The ground scrolls down at 50 px/s (the view flies up). It is a list of blocks, topmost first, each the ground from one
road across to the next: a road 10 px wide along the block's top, the width of the viewport, and under it 2–5 bands,
each 60–220 px tall, cut into cells between the block's roads down, which run from the road across to the block's
bottom, where the next block's road across is, so the roads down one block end at a road across and the next block's
start past it at positions of its own (T-junctions, not a grid). The roads down a block stand 2–5 fields apart: each
span between two is the sum of that many field widths of 60–320 px, the first span starting up to 320 px left of the
edge and the last running past the right edge, and every band cuts each span into 2–5 cells (as many as the field
widths let it), each 60 px plus a random share of the rest. The whole canvas is painted `fieldEdge`, then each block in
a frame at its top: its cells, each 3 px short on the right and at the bottom, so a field edge runs between cells and
between bands and along each side of a road as its verges (the first band starts a road and an edge below the block's
top, and a span's last cell ends an edge short of the road); then its roads in `road` over them; then its vehicles. A
cell's color is one of the six field colors and never its left neighbour's (a pick among the other five, across a
road too), and one of ten textures at random, painted over its color in translucent dark and light ink from the cell's
corner so it scrolls with the cell (`js/setup-background/setup-background-textures.js`: furrows across, down and on the
diagonal, crop rows, tramlines, strip crops inside a headland, an orchard, mottled pasture, contour strips and a centre
pivot; most are 128 px tiles repeated by a `CanvasPattern`, the headland and the pivot are drawn to the cell); a band
or a cell wholly outside the viewport is skipped. The block list holds every block that overlaps the viewport (the
topmost covers the top edge); a block whose top passes the bottom edge is dropped with its vehicles, and new ones are
generated above the topmost, so nothing repeats. On resize the blocks are regenerated when the width grows (the roads
and the cells are cut for the width) and kept otherwise: blocks are dropped or added above as the height asks, and
added below the lowest when a taller viewport uncovers ground under it.

The vehicles are 3 × 5 px rectangles the long way along their road, half of them in the cloud white at alpha 0.9 and
half in the shadow ink at 0.7, keeping to the right of the road (eastbound on the south side, southbound on the west),
each at a speed of its own of 15–35 px/s over the ground, so a northbound one still scrolls down the screen, slower
than the fields. A block starts with 0–2 on its road across at random x and, by a chance of 0.5 per road, one on
each road down it; they belong to the block (a vehicle's road is the block's road across or the index of one down it,
and its position an x on the road across or an offset from the block's top on a road down, so the ground's scrolling
moves it for nothing). A vehicle reaching the end of a road down turns onto the road across there, either way at
random: this block's at the top, the block below's at the bottom, where it moves to that block's list, or leaves the
scene when there is no block below, that junction being past the bottom edge. One on a road across turns, by a chance
of 0.35 at each junction it passes, down this block's road or up the block above's, and past either side of the
viewport comes round the other, the road running on past both. The vehicles are stepped after the blocks are recycled,
and a vehicle that changed block is moved once every block has been stepped, so none is stepped twice in a frame.

Cloud shadows come next, on the ground: each cloud's
circles as one path, filled with `cloudShadow` at alpha 0.28 and offset (−40, +40) px from the cloud, so the light
comes from the upper right. The clouds scroll at 80 px/s, 1.6 × the ground. A cloud is 4–8 circles of radius 30–90 px whose centres fall
within an ellipse of 220 × 120 px around its centre (a uniform angle and a square-rooted distance spread them over its
area), the radii and the ellipse at a scale of 1 or 2 picked per cloud, each circle a radial gradient of `cloud` from alpha 0.6 at the centre to 0 at the rim, built once at the origin when
the cloud is made and drawn under a translate, so no frame builds a gradient (and no `ctx.filter`, slow in Safari).
There are 4–8 clouds, the count picked once at init; a cloud whose top edge passes the bottom edge is replaced by one
spawned above the top at a random x, its top edge 0–400 px above the viewport, so the sky is never evenly spaced. A
resize leaves the clouds where they are: one now past the right edge scrolls off and is replaced as usual.

The jet is the nearest thing in the scene: 420 px long, nose up, anchored at 18 % of the width and rising out of the
bottom edge, which cuts it 28 % of its length behind its centre, so the nose, the wings and the engines show and the
tailplane and the fin stay below the edge; on a viewport narrower than 600 px it is 270 px long at 14 % of the width,
where its right wing may show through the frosted card. Its parts are `Path2D`s built once, in units of the length
(centreline x = 0, nose at −0.5, tail at 0.5) and drawn under a scale of the length, so one builder serves both
sizes, in the proportions of a twin-engine wide-body seen from above: a fuselage a tenth of the length wide with an
ogive nose and a tapered tail cone, wings spanning the length tip to tip and swept 32° with the trailing edge kinked
inboard, two pods ahead of the leading edge on pylons that widen back over the wing, a tailplane, and the fin as a thin
centreline strip near the tail. The skin is `jet` with a soft `jetOutline` at 1 px (the line width divided by the
scale), part over part so each covers what is under it, and it is shaded by linear gradients built in the same units,
dark along the sides and light along the crown, so the fuselage and the pods read as tubes and the wings lighten
toward the root. The markings are translucent cloud and shadow ink over the skin: slats along the leading edge, flaps
along the trailing edge with four fairings a wing, the rear spar's line, the pylons, and each pod's intake lip, mouth
and exhaust (clipped to the pod); the cockpit glazing, the cabin windows and four doors a side are the outline ink.
The jet casts no shadow. It is drawn under a transform, translated to the anchor and scaled to the length, and holds
still: the scenery moves under it. The lights are drawn
last, in the same frame. Each wingtip carries its lamp at all times, a dot 2 % of the length in the light's color
halfway to the outline ink, outlined as the skin is, so the tips keep their shape between blinks; a lit lamp glows
over it with a radial gradient cached at the origin per color, solid in the full color out to the dot's radius, then
a halo fading to nothing at three times it: red `lightLeft` on the left wingtip and green `lightRight` on the right,
on for 0.5 s of every 1 s, and a white `strobe` at each wingtip, two 60 ms flashes starting 120 ms apart every
1.2 s. A frame's draw order is field-edge fill → each block's cells, roads and vehicles → cloud shadows → clouds →
jet → lights.

**Explorer dock** — The main page (`index.scala.html`, `static/css/pages/index.css`) is a grid of three areas,
`explorer`, `gutter` and `content`, whose shape is the **Dock side**: where the explorer sits against the results,
`left`, `right`, `top` or `bottom`, written on `<html>` as `data-dock-side`. One rule per side sets the tracks and
the areas: the explorer's track is `minmax(<its minimum>, var(--explorer-share))`, its share of the width (left,
right) or of the height (top, bottom), the gutter's is `auto`, as thick as the divider, and the results take the
rest, with their own minimum (`--explorer-min-width`, `--explorer-min-height`, `--content-min-width`,
`--content-min-height` of `core.css`, registered as lengths so the script reads them in px). Across the width the
explorer's minimum is `min-content`: its header's natural width, the tabs and the Dock side control in full, since the
panel (`#explorerPanelContent`, `contain: inline-size`) contributes no width of its own, floored by
`--explorer-min-width` as the explorer's `min-width`; the script measures the header at its min-content for the
divider's bounds (`headerMinContentWidth`). Each area draws the
hairline on the edge facing the divider, one rule per side. The trash bin shows the results alone
(`main:has(> #content.trashbin)`: one area, the explorer and the divider not displayed). Until this browser has a
pick, the side follows the viewport's proportions, a portrait viewport docking the explorer at the bottom and any
other on the left, and a rotation re-docks it; a pick holds from then on. The state is per browser, under
`Const.localStore.explorerDock` as `{ side?, widthShare, heightShare }`: the width share serves left and right, the
height share top and bottom (defaults 25 and 35 %), so a switch within an axis keeps the size.

`static/js/explorer-dock-boot.js`, a plain blocking script in the page's head with the key in its `data-key`, writes
the side and `--explorer-share` on `<html>` before the first paint, so nothing moves under the user. From
`initExplorerDock(view)` (`js/common/explorer-dock.js`, called by the page script; it returns at once in the trash
bin, where `hasExplorerDock()` is false) the module owns both. The mechanics are `js/common/dock.js`'s, a factory
(`createDock`) taking the storage key, the pick attribute, the layout attribute and share property, the container, the
size reference, the divider, the defaults (the side and the two shares), the minimums and whether the default side follows
the orientation; the explorer's module is its configuration (`data-dock-side` and `--explorer-share` on `<html>`, the side
by the viewport, 25 % and 35 %, the header's min-content as the width's floor), and the Detail panel's is the other (see
**Detail panel**). The explorer's dock: it re-docks on the orientation query's `change` while
nothing is picked, takes a pick from any `[data-app-dock-side]` click on the page (`setDockSide`), and dispatches
`Const.events.dockSideChanged` on `document.body` after every side change, with the side in `detail.side`, for
whatever lays itself out by the side: the People tab's Split view stacks its panes under a left or right dock and sets
them side by side under a top or bottom one, remaking its Split.js instance on the event (see **People tab**).
`effectiveSide()` reads the attribute.

The pick is the **Dock side menu** at the end of the explorer's header (`#explorerHeader`: the tab list, growing, then
the menu, centered on the row and inset by `--panel-padding`; in triage, where the tab list is hidden, the menu stands
alone at the row's end, and in the trash bin it is hidden with the explorer). `#dockSideMenuHost` is a compact choice
menu (see **Context menus**): four options, Left, Right, Top and Bottom, each `data-app-dock-side="<side>"` with the
side's glyph, a `<use>` of `includes/dock_glyphs.scala.html`, the hidden `<svg class="svg-sprite">` of four symbols
(a frame with the docked band filled, in `currentColor`) that `index.scala.html` and the style guide include. The
module checks the option of the effective side (`aria-checked`) and gives the trigger a clone of its glyph and the
side's name in `applyEffectiveSide`, on init and every change, so the trigger shows and reads the current side
without opening the menu; picking the side already picked changes nothing, and picking an option closes the menu
with focus back on the trigger, as any choice menu does.

The divider, `#explorerDivider` (`role="separator"`, `aria-controls="explorer"`, `tabindex="0"`), sits in the
gutter area. It is a `.divider` of `core.css`, the one drawing of a draggable divider, which the People panes'
Split.js gutter wears too: `--space-3` thick along its axis, a transparent band with a short dotted grip centered on
it, drawn in `currentColor` by a `radial-gradient` tile repeated along the divider, so hover and `[data-dragging]`
tint it `--theme-primary` with one color change; the cursor is `col-resize` or `row-resize`. Its orientation is its
class, `.vertical` (a column) or `.horizontal` (a row), and a divider with neither, the explorer's, runs across the
Dock side's axis (`html[data-dock-side]`), so it is laid out before any script runs. A pointer drag
(`setPointerCapture`, `touch-action: none`; `main` is `user-select: none` meanwhile; the pointer's own focus on the separator, which draws no ring, makes the arrow keys work right after the drag)
sets the share from the pointer's distance from the explorer's edge of `main`, within the bounds taken as the drag
starts (the explorer's minimum, and all but the results' minimum and the divider, as percentages of `main`'s size),
and remembers it on release; the arrow keys along the axis move the divider the way they point, 1 % (Shift, 5 %),
Home and End take it to the bounds; Enter and a double-click reset the axis's default. The remembered share is the
user's own, bounded when the user sets it; the share the page is laid out with is bounded again on every write and
on `resize`, so a visit in a small window never narrows what a wide one remembered. The separator's
`aria-orientation` is the divider's own (`vertical` for a left or right dock), and `aria-valuenow`, `-valuemin` and
`-valuemax` are the laid-out share and its bounds, rounded. Everything that reacts to the explorer's size reacts to
the divider as it did to Split.js: the context menus close through their `ResizeObserver` on `#explorer`, the map
resizes through its own, the sticky offsets through theirs. The style guide's Panels & layout section shows the
divider in both orientations.

**Nav** — `includes/nav` is the bar of every page behind a login, inside the page's `<nav>`, styled by the NAVIGATION
block of `core.css`: the `.brand` (the `fa-chevron-up` mark in `--theme-accent` and the name, lowercase, in `--font-nav`
at `--font-size-xl`, on a `--theme-primary` to `--theme-accent` gradient clipped to the text, which glows in the primary
on hover) in a `--nav-brand-width` slot, then one `.menu` per entry, all of one width (lowercase `--font-nav` at `--font-size-medium`,
`--font-weight-medium`, `--theme-neutral-3`; Triage in `--success-color` and Trash in `--warning-color`, each with its
count as a `.count` pill inside the label, `--theme-secondary` on the chip tokens; an entry with nothing behind it is
`.empty`, `--theme-neutral-3`, "(0)" in its label and not a link; a linked entry's link glows in the entry's own
color, `currentColor`, while the entry is hovered, so an empty one does not react). Between Browse and Triage sits the
`.search` cell, as wide as an entry, holding the quick search form (**Search input**) centred in it at
`--nav-search-width`: the form is `--font-nav` at `--font-size-base` in
`--theme-neutral-4`, centred text, with no border or fill, a `fa-search` mark and the placeholder "quick search" at 0.6 alpha, and a
`.clear` (`fa-times`) control shown while there is text. While the field has focus the form widens to
`--nav-search-focus-width` and glows in `--theme-accent` (a 1px ring and a 14px halo at 0.55 alpha); that glow is the
field's focus indicator, one of the two places a rule sets `outline: none` (see **Themes**). The bar is
`--theme-primary` at 0.1 alpha over the page. The entry of the page shown carries `aria-current="page"`, which the
server renders from the include's `current` parameter, a `Const.NavEntry` key: the main page's three views
(`IndexController` passes the `view` query parameter, `repository` by default, the same value the client seeds its
search from), and `import`, `settings` and `style-guide`, which those templates pass as constants. The marked entry is
`--theme-accent` text, bold, on the accent at 0.16 alpha. Nothing marks an entry client-side, so a reload of the nav
passes `current` again (below); the style guide's navigation section holds the specimen. The last cell, at the right edge after
Style Guide, or after Settings where that entry is not shown, is the theme switch: a `button.menu.theme-switch`, as wide as an entry and
its whole cell the target, icon only with the accessible name "Toggle light and dark theme". It shows `fa-sun` in the dark theme and
`fa-moon` in the light one, the theme a press goes to, in `--theme-neutral-3`, glowing in its own color on hover. CSS picks the
icon from `data-theme` on `<html>`, so a nav reload needs no client step, and the one delegated click in `global.js` (`[data-app-theme-switch]`)
works on every page, those without Alpine included. See **Themes**.

**Nav refresh** — After any successful asset mutation, reload the nav to update counts.
Folder deletion also recycles assets throughout its subtree, so the `folderDeleted` listener
calls `app.reloadNav()` before awaiting the folder-tree refresh. Reuse `FrontendApp.reloadNav()`
from event listeners; it loads the nav fragment with the view shown, read from the search parameter store:
```js
htmx.ajax("GET", `/htmx/nav/r/${this.context.getRepoId()}?current=${view}`, { swap: "innerHTML", target: "nav" })
```

`includes/nav` renders the Search input on every page (the import page's polling reload passes `current=import`).
The form is `hx-preserve`, so a swap keeps the element itself, with its listeners and the text being typed.

Asset mutations (move, recycle, purge, restore, sorting from triage) also call
`app.reloadFolderCounts()`, which runs `refreshFolderCounts(repoId)` from `js/common/folder-tree.js`:
it re-fetches the tree JSON and patches each `#folder-count-{id}` in place, leaving expansion, focus,
and open menus alone. If the response holds a folder the DOM lacks (restoring assets can un-recycle
their folders), it falls back to a full render. Folder operations (add, rename, move, delete) keep
using `reloadFolderTree`, which renders fresh counts as part of the rebuild. `app.reloadAlbumCounts()`
and `app.reloadLocationCounts()` do the same for the album and Location lists (`refreshAlbumCounts`,
`refreshLocationCounts`), and fetch nothing while another explorer tab is active. `refreshCounts` in
`js/assets/asset-actions.js`, which runs after every asset mutation, makes these reloads and reads the number of Sequences to
curate again (`Const.state.sequencesToCurate`, see **Curation**).

Asset move/recycle/purge/restore and album and Location membership UI flows are implemented in `js/assets/asset-actions.js`.
A restore answers `{restored, duplicates}`: an asset whose content was imported again after it was recycled stays in
the trash, so only the `restored` cells leave the grid and the snackbar warns about the rest.
Drag/drop interact.js bindings live in `js/dragdrop/`. Event-listener modules call these
coordinators directly via `app.assetActions` / `app.searchDetailCoordinator`, while
`FrontendApp` remains the composition root that wires them together.

**Control states** — The action buttons, a toggle group's segments, the filter chip, the menu trigger and a context
menu's actions share one transition rule in the DEFAULT CORE section of `core.css` (background, border color, color,
shadow, translate and scale over 160 ms, ease-out; a menu action's own rule shortens it to 120 ms, ease). A hovered
`.action-button` lifts 1px, and a pressed one, toggle segment or filter chip scales to 0.97: both live in one
`@media (prefers-reduced-motion: no-preference)` block, so nothing moves when the system asks for reduced motion while
the colors and shadows still change. A disabled button takes neither the hover nor the press look
(`:hover:not(:disabled)`, `:active:not(:disabled)`). `.action-button.primary`, the accent call to action, is a
dialog's or a form's main action: Add in the Add location and Add to location dialogs, Move, Merge, the Settings
Save, Upload from computer, and the empty lists' "Add your first album" and "Add your first location"; a destructive
confirmation stays `.error`. The batch ops' buttons (`.batch-ops .action-button`) keep a normal weight, the small button size, and draw the
variants' own edges.

**Themes** — The app has a light and a dark theme and ships in the dark one: `:root` in `core.css` declares
`color-scheme: dark`, as does the `color-scheme` meta tag of `includes/header_common`. The nav's theme switch (see **Nav**), the
style guide's theme switcher and the design tweaks panel's toggle declare another scheme inline on the root element. The first two go
through `chooseTheme` of `js/common/theme.js`: it declares `color-scheme` and `data-theme` on `<html>`, remembers the theme in
`localStorage` (`Const.localStore.theme`) and announces `Const.events.themeChanged`. `js/theme-boot.js`, a plain blocking script of
`includes/header_common`'s with the key in its `data-key`, declares the two before the first paint on every page with a nav; the setup
page passes `themed = false`, since it has no nav and a palette of its own. Nothing remembered leaves the stylesheet's dark theme. Curation's `.pane-tag` and `.loupe`
declare `color-scheme: dark` on themselves, because the pane beneath them is dark in either theme, so the tag's chips and the
lens's edge and label take the dark side of every token whichever theme is shown. Every color is a `:root` token
of `core.css`, and every theme and semantic color is a literal `light-dark(light, dark)` pair of two hex values, one per
scheme, so the browser resolves the pair by the scheme in force, no rule is written once per theme, and an editor shows
a swatch on each line. `:root` opens with the theme colors, in three groups. The brand colors: `--theme-primary` (the
one hue, indigo: the selected tab, the focus ring, selection, drop targets, chips and pins, the start of the brand's
gradient and the tint of the nav bar), with
`--theme-primary-text` (the primary as text or a thin edge on a surface, 4.5:1 where the primary itself is not: the
filter chip) and `--theme-on-primary` (text and glyphs on the primary fill: a pin's path, a drop target's row, the
checkmark of a selected cell; dark in the dark theme, since the primary cannot carry light text); `--theme-secondary`
(the supporting surface, lifted off the page: a button, a hovered tab, row or menu action, a count pill in the
nav, and at 0.5 the track of a toggle group); and `--theme-accent` (teal: where you are and what to do next. The current nav entry's text and
its tint, the brand's mark and the end of its gradient, and the call to action: the fill of `.action-button.primary`,
a dialog's or a form's main action) with `--theme-on-accent` (text on the accent fill, an ink tinted with its hue).
The neutral scale, `--theme-neutral-1` to `--theme-neutral-4`, ordered from the lightest surface to the darkest text in
the light theme: `-1` the lightest color in either theme, which no rule draws as a surface: at a low alpha it is the
light of a sheen or a hover veil; `-2` the page surface, a small button's fill, the pressed segment of a toggle group
and, at 0.84, a floating surface (the popover dialog, an open select's picker); `-3` lines and secondary text (the edge of a cell, a menu, a field, a
button or a quiet control, a tab at rest, a disabled entry, a muted label; 4.5:1 on the page and on the secondary
surface); `-4` text on the app's own surfaces, the text typed into a field included. The dark side of the scale is not
in lightness order, since `-1` stays light there, and `-1` and `-4` hold one value in the dark theme by design; the
style guide compares both sides and flags neither. A rule writes a theme color by its token
(`color: var(--theme-neutral-4)`, `border: var(--border-hairline) var(--theme-neutral-3)`); no token aliases a theme color under
another name or mixes one at a percentage outside `:root`. Three kinds of color stand apart, with literal pairs of their
own: the channel colors of Curation's Histogram (`--histogram-red`, `--histogram-green`, `--histogram-blue`, the pure
primaries, which its strip draws as light so that their overlaps read yellow, cyan, magenta and neutral; the strip is dark
in either theme, so each pair holds one color); the four semantic colors (`--success-color`, `--warning-color`, `--error-color`, `--info-color`: green, amber,
red, and a blue cooler than the primary so a notice does not read as a selection), the text, icon and edge of a
status, each at least 4.5:1 on the page surface, which a rule writes directly too (an unnamed person's name and the
trash entry of the nav are `--warning-color`; nothing draws `--info-color` yet), each with its fill
(`--success-background-color`, `--warning-background-color`, `--error-background-color`,
`--info-background-color`), the chrome's one recipe over its color in HSL, per scheme
(`light-dark(hsl(from var(--success-color) h calc(s * 0.7) 90%), hsl(from var(--success-color) h calc(s * 0.6) 35%))`:
the hue kept, the saturation at 70% or 60%, and the lightness set to 90% in the light theme and 35% in the dark one, so
the four fills sit at one lightness; a fill carries `--theme-neutral-4` in either theme and its semantic color only as
an edge, since the color on its own fill reads about 3:1; a success, warning or error button takes no fill token but its
color at 0.16 as its fill, with the color itself as its text and edge); and `--shadow-color`, the ink of the shadows and scrims, each at the alpha it needs
(`--scrim-color: hsl(from var(--shadow-color) h s l / 0.45)`). The only other color tokens are the veils, a theme
color or `--shadow-color` at an alpha in relative color syntax, tokens because a translucent color cannot be written
as a theme color: `--scrim-color` and `--asset-detail-backdrop-color` (`--shadow-color` at 0.45 and 0.9),
`--dnd-dragging-background-color` (`--theme-neutral-3` at 0.48), `--panel-border-color` (`--shadow-color` at
0.18, the edge of a chrome panel and the divider inside one), `--popover-background-color` and
`--popover-border-color` (`--theme-neutral-2` at 0.84 and `--theme-neutral-3` at 0.3, the surface and the edge of a
floating surface: the popover dialog and an open select's picker), and `--field-background-color` (`--shadow-color`
at 0.23, a field's sunken fill); a veil follows the pair of the color it reads. The frost behind a floating surface is
a token too, `--popover-backdrop-filter` (`blur(10px) saturate(200%)`), since the popover dialog and the picker both
draw it. A border
token holds a width and a style and no color: `--border-hairline` (`1px solid`), `--border-heavy` (`2px solid`, its
width also `--border-heavy-width` for a size that clears the edge) and `--border-double` (`2px double`). A rule writes
the edge's color after it, in place (`border: var(--border-hairline) var(--theme-neutral-3)`): `--theme-neutral-3`
on a hairline around a cell, a menu or a quiet control and on a heavy edge around a modal dialog; at 0.3 on the
hairline dividing a context menu's actions; at 0.45 on a
hairline around a button (a semantic or the accent button's edge is its own color); at 0.25 on a hairline around a
field (`--theme-accent` while it has focus, `--error-color` in error); `--popover-border-color` on a hairline around a
floating surface; `--theme-primary-text` at 0.4 on
a hairline around a chip; `--panel-border-color` on a hairline around a chrome panel or dividing one; `--theme-secondary` on the double trace of
a list; `--theme-primary` on the heavy focus ring. A token holding a shadow keeps its offsets with its color, which is
a theme color or `--shadow-color` at an alpha: `--drop-shadow`, the one shadow under a modal dialog, a panel, a pin or
the checkmark; `--float-shadow`, the ink at 0.45 at 12px/32px, the one shadow under a floating surface;
`--field-focus-shadow`, a 2px spread of `--theme-accent` at 0.15, a focused field's ring; `--text-shadow`, whose light
side is the ink at alpha 0 since a dark halo has no place under dark text; and `--box-shadow`, `--theme-neutral-4` at 0.07, the light line under a panel's edge. Three more draw the buttons:
`--sheen-shadow`, an inset 1px line of `--theme-neutral-1` at 0.08 along a button's top edge, on every button and
over `--float-shadow` on a floating surface;
`--soft-shadow`, two layers of the ink (0.25 at 1px/2px, 0.15 at 2px/6px), under a button at rest, a small button on
hover and the pressed segment of a toggle group; and `--lift-shadow`, the ink at 0.3 at 4px/12px, under a hovered
button. A rule lists the ones it draws (`box-shadow: var(--sheen-shadow), var(--soft-shadow)`). No two
border or shadow tokens hold one value: a rule that needs an edge or a shadow writes the token that has it, and a new
one is a new look, described here. What is drawn on a semantic fill or over an image (the label of a marker, the
snackbar's text, a pin's frame) reads `--theme-neutral-4`; what is drawn on the primary fill reads
`--theme-on-primary`, and on the accent fill `--theme-on-accent`. A light theme is therefore the light side of every
pair; both sides are real, and the style guide's switch shows either. Keyboard focus is one ring: `:focus-visible { outline: var(--border-heavy) var(--theme-primary); outline-offset: 2px }` in
`core.css`, on every element, inset (`outline-offset: -2px`) where a panel clips its rows; the browser withholds it
after a pointer, including the focus a closing dialog returns to its trigger, so no rule removes an outline, with two
exceptions, each a field that draws its own focus indicator in place of the ring: a text-like field (see **Forms**)
sets `outline: none` on `:focus-visible`, which it matches after a pointer too, and turns its edge `--theme-accent`
with `--field-focus-shadow` around it, while a field in error keeps its `--error-color` edge with no ring; and the
nav's quick search input sets `outline: none` because its form's accent glow on `:focus-within` is that field's focus
indicator (see **Nav**). The setup
page's palette is no `:root` token: `pages/setup.css` declares the `--setup-*` tokens on `body:where(.setup)`, as
literals with no pairs, since the page is an illustration and a form in a look of its own, which it keeps in either
theme (see **Setup background**), and recolors the focus ring there to the form's accent
(`body:where(.setup) :focus-visible { outline-color: var(--setup-form-accent) }`).

Stylesheets reference the tokens and write no color literal, named colors included. A new color is a theme color
written in the rule, or a new veil token, described here, when it must be translucent. The rule that a new
value is a new token with a meaning, or an existing token whose meaning fits rather than one that merely holds the same
value, is for lengths: the `--space-*` steps and the role tokens over them. A color a rule takes from a token at an
alpha is written in relative color syntax too, so it follows the token's pair: the tints of the triage and trash views
are `rgb(from var(--success-background-color) r g b / 0.07)` and `rgb(from var(--warning-color) r g b / 0.05)`, the
nav bar is the primary at 0.1, the current nav entry the accent at 0.16, the brand's hover glow the primary at 0.8, a
toggle group's track the secondary at 0.5, and an entry's hover glow its own text color at 0.8, written over
`currentColor` in the same syntax, as are the hover tints of a toggle segment (0.06) and the menu trigger (0.12). A
hovered button lightens its own fill by 0.04 in OKLCH, `oklch(from var(--button-fill) calc(l + 0.04) c h)`, one rule
for every variant: `.action-button` and each variant declare the fill as `--button-fill` on their own rule (as the
triage and trash views declare `--view-tint`), and `background` reads it. The filter chip's fill is a gradient,
`--chip-fill` on its rule (`linear-gradient(in oklch 180deg, …)`, the primary at 0.12 down to `--theme-neutral-2`),
and its hover lays `--theme-neutral-1` at 0.08 over it as a one-color gradient (`linear-gradient(<color> 0 0)`).
The SVG data URI of `static/css/includes/search_results.css` (the check of a selected asset) carries its fill as a
literal, because a data URI cannot read a token.

**Style guide** — `views/style_guide.scala.html` is a dev-only page (`/style-guide/r/:repoId`,
`StyleGuideController`; the nav shows its button only in dev) with a section per foundation (colors, typography, spacing, borders and shadows, panels, icons)
and per shared primitive of `core.css` and `tabs.css` (buttons, forms, navigation and tabs, menus and dialogs,
feedback, badges and states), plus a token audit. It is the main page's chrome without the explorer and the grid, and
starts the whole app (`initApp()`), so it loads `lib/interact.min.js` and `lib/json-enc.js` itself.

- **Discovered, nothing to maintain:** the tokens and the icons. The server's source scan is embedded as JSON
  (`#styleGuideScan`); the `styleGuide` Alpine component (`js/style-guide.js`) reads it, and the page's `x-for`
  templates render what the component holds (see **Rendering a page's own data** above): each `:root` token resolved
  against the loaded stylesheets and placed by the kind of its value and its group (`@tokenCards("border shadow")` asks
  the component for those kinds, one after another, so borders and shadows share one grid;
  `@tokenCards("color", "theme")` asks for the colors of one group), with its note (the comment directly above its
  declaration), reference count, an "Unused" flag, an "Off palette" flag, and the tokens sharing its value ("Same
  value as"); a click copies its `var()` reference. The token audit lists the tokens declared outside `:root` (an override names the `:root` value it
  replaces), the ones only set from JavaScript, the color literals grouped by resolved color, each naming the
  token that already holds that color in the theme shown, and the length literals (the px, rem and em values of a
  font size, a width or height, a bound, a grid's tracks or an inset), each with its property and locations. The
  kinds and the groups (`TOKEN_KINDS`, `kindOf`, `groupOf`) are `js/common/token-kinds.js`'s; the design tweaks panel's
  adapter configures the same sections, so the two place a token alike.
- **Token cards:** a token whose declaration references other tokens, a recipe, shows the declaration as written (or
  the one the alpha slider declared inline) and a "From --a, --b" line naming them; any other shows the value it
  resolves to. A token's group comes from its name, and a length's also from what it references. The colors are
  **Theme colors** (`--theme-*`: the brand colors, the neutral scale and the on-colors, each a literal hex pair),
  **Status, shadow and channels** (the four semantic colors, their fills, `--shadow-color` and the Histogram's three channel
  colors) and **Derived**
  (the veils, such as `--scrim-color`, `--asset-detail-backdrop-color`, `--popover-background-color` and
  `--field-background-color`, each a theme color or `--shadow-color` at an alpha). The lengths are
  **Scale** (`--space-*`), **Roles** (a length referencing a step, which its From line names) and **Sizes** (any other
  length, such as a width or the radii, `--radius`, `--radius-small`, `--radius-large` and the pill `--chip-border-radius`); a value of space-separated lengths, such as a padding shorthand, is a length,
  drawn as a padded "Padding" sample. The typography tokens (`--font-size-*`, `--font-weight-*`, `--letter-spacing-*`,
  `--line-height-*`) are a kind by name rather than by value, since a font size is a length and a weight a bare
  number, each drawn on sample text in the Typography section (`@tokenCards("typography")`). "Off palette" marks a color, border or shadow token outside the Theme colors
  and Status, shadow and channels groups whose declaration writes a color literal (a hex color, a color
  function with its channels written out, or a named color), which keeps the palette in the theme and semantic tokens; a relative-color recipe is no literal. "Same value as"
  names the tokens of the same kind (color, border, shadow or font) that resolve to the same value (a color to the
  same color in both themes). Two tokens that take their color from the same tokens are not flagged, since two
  tokens reading one color share it by design, while two recipes computing one color are, as they should be one token; a
  token without a color is compared by its value alone.
- **Hand-written, keep in sync:** the specimens. **A new or changed shared primitive in `core.css` or `tabs.css` (a
  class meant for reuse) adds or updates its specimen in `style_guide.scala.html` in the same change.** A feature's own
  design conventions can have one too: Curation's notice, chip and Bundle row (`htmx/curation_set.css`) and the Sequence rail's
  rows (`htmx/curation_queue.css`), a done one and a current one side by side in `.sg-curation-queue`, are in Badges & states,
  where `.sg-curation-bundle` gives the row the rail's width, and the Detail panel's sections (`htmx/detail_panel.css`: a People
  row, selected and not, Change and Remove under a selected row, the Change combobox with its listbox and its Suggested group,
  Details rows with the crumbs and a Metadata group), the toolbar toggles and the Face highlights
  (`htmx/view_image_detail_modal.css`: a highlight and its badge over a stand-in for a photo, `.sg-face-highlights`) are in Panels &
  layout, in `.sg-detail-panel`. Specimens
  use the real classes; the page's own styles (`static/css/pages/style_guide.css`: `sg-` classes, and its bare elements
  under `body.style-guide`) only lay the page out, from the `:root` variables.
  Three more things keep the page current:
  - A changed size, font, or icon set updates the values the specimens state in text.
  - `core.css` comments its boxed section headings (`/* NAVIGATION */` and the like) and, in a line or two, a rule
    whose selector or values need a reason (the quick search form, the field and its focus rule, the popover dialog's
    placement), pointing to this guide for the detail; it comments no `:root` token. A token, and what a rule draws,
    is documented in this guide and in the style guide's prose, never beside its declaration, so the two cannot
    drift. The guide would show a comment directly above a `:root` token as its note; `core.css` has none, by design.
  - A new section also needs its `#styleGuideToc` link and its ID in `StyleGuideControllerTests`.
- **Live specimens:** an Add button and a ⋯ menu built with `buildModalTriggerCtrl` / `buildContextMenuCtrl`
  (`initStyleGuide()`, after the app has started: no template, so they are the explorer's own markup), both
  opening `htmx/style_guide_sample_dialog.scala.html` as a popover dialog below the control, a one-field popover with
  no label (its input's `aria-label` is "Sample name"). A blank name returns the validation replacement, its error
  under the field; a name completes and dispatches `Const.events.styleGuideSampleSubmitted`, which the page answers with
  a snackbar. Nothing is persisted. The Forms section's Labelled fields button (`data-app-open-dialog`, see
  **Modals**) opens a page-held `<template>` dialog in the general modal host, a labelled input and a labelled select,
  so its labels take the dialog's small caps; it requests and submits nothing, and the × or Escape closes it.
- **Theme switcher:** the Light / Dark `.toggle-group` at the top of the contents column (`#styleGuideTheme`), which
  stays on screen. `setTheme` calls `chooseTheme` (see **Themes**), so the choice is the whole app's, and the component answers
  `Const.events.themeChanged`, which the nav's switch announces too, by following the theme and resolving the tokens again:
  the color a token holds and its alpha are those of the theme shown, while the tokens sharing its value are compared
  in both themes. The page opens in the remembered theme, else in the stylesheet's.
- **Font tester:** the Typography section's right half (its left half holds the `--font` card, the type scale's
  cards, the sizes on the real components and the text colors). The typed value becomes the lorem specimen's `font-family`, and the status says whether the browser
  has that font, decided by measuring the text with the value in front of each generic family against the generic
  family alone (`isFontAvailable` in `js/common/font-availability.js`). The input is the component's `fontInput`
  (`x-model`), which the specimen's style and the status
  derive from. Nothing is requested from the server.
- **Alpha slider:** the `--scrim-color` card (`ALPHA_SLIDER_TOKEN` in `js/style-guide.js`) has a slider under its
  copy button. Dragging it replaces the alpha of the token's relative color (`hsl(from var(--shadow-color) h s l / 0.6)`
  after a drag) and declares the whole value inline on the root element, so the page repaints with it until it is
  reloaded; nothing is stored. It works on the declaration as written, which keeps the color as declared, and should a
  token hold a `light-dark()` pair, only the color of the theme shown changes and the other is left alone; the card
  shows the value, and Copy value copies it for pasting into `core.css`. The slider starts at the alpha the token's
  color has in the theme shown, and a color that already is such a replacement has its alpha replaced rather than
  wrapped again. The dragged alpha and the value it gives live on the token, so a card follows
  its token alone. A drag made in one theme survives a switch to the other: the tokens are resolved again from the
  inline declaration, and the cards re-render from the new tokens. A token card is therefore a `div` holding the copy
  button and, for that token, the slider row: an input cannot sit inside a button.

The scan leaves the guide's own files out, so a specimen never counts as a use of the token it demonstrates, and the
vendored stylesheets of `static/css` (`font-awesome.min.css`, `leaflet.css`), whose literals are not the app's to
tokenize, `pages/setup.css`, whose `--setup-*` palette is the setup page's own, and the design tweaks panel's adapter
(`js/design-tweaks.js`), which names tokens to configure the panel, not to use them; all are listed in
`StyleGuideController.excludedFiles` (the panel itself is under `lib/`, which the scan skips). The same scan is served
as JSON by `GET /style-guide/scan.json` (dev only) as the panel's sources.

**Design tweaks** — A dev-only panel (`includes/header_common` loads it inside `@if(Environment.isDev)`, like the nav's
Style Guide button; `Ctrl+Alt+T` toggles it, there is no nav entry) that changes the value of any `:root` token of the bundle live, on the real markup and data of the
running app, so the look of every page, dialog and htmx fragment can be explored without touching a rule. The panel is
the vendored **tokentweak** package (`lib/tokentweak.js`, 0.3.0, `lib/README.md`), a framework-free token editor:
sections of cards with a control per kind of value (a color written as a recipe over another token gets the recipe
view: a swatch of the resolved color, a select for the source token, a mode select per channel and a slider in the
modes that write a number, the alpha slider, a detach that writes the resolved hex, and the text field as the escape
hatch), a Light / Dark toggle, Inspect (pick an element, filter to the tokens its rules reference), snapshots (save,
load, rename, duplicate, A/B, import and export as JSON) and the exports (a prompt of the changes, the CSS before/after
lines, the snapshot JSON). The package's README documents every option; this entry covers what Altitude passes it.
`js/design-tweaks.js`, the adapter, is the only Altitude code of the panel.

- **What the adapter configures:** `storageKey: Const.localStore.designTweaks` and `hostId: "designTweaks"`; the
  stylesheet to read, `"/assets/"` (the bundle, by href substring); no `trigger` (the panel has no nav entry) and the
  shortcut `Ctrl+Alt+T`, the only way to toggle it; `sources: "/style-guide/scan.json"`, the
  server's scan (locations and notes for the CSS export, the literals for the Not reachable tab); the sections in the
  style guide's order (Theme colors `--theme-*`; Status, shadow and channels, the semantic colors, fills, `--shadow-color`
  and the Histogram's channel colors;
  Derived, the other colors; Typography, the fonts and the typography kinds; Scale, the steps; Roles, the lengths that
  reference a step; Sizes, the other lengths; Borders and shadows; Panels; what none claims goes to the automatic
  Other); the spacing scale `--space-<n>`; the type scale, `--font-size-{xs,small,base,medium,large,xl,display}` from
  `--font-size-base`; no `seed`: the package's seed is a formula-driven control (a configuration names the hue and
  lightness tokens its sliders start from and, per target token, a formula from the seed), which Altitude does not
  configure, since every theme and semantic color is a literal pair with the full sliders; the recipe view serves the
  veils, the borders and the shadows, whose colors are written over a token; the two tokens
  the server sets inline on their containers as scoped tokens, `--preview-box` on `#assets` (200px, labeled
  `Const.AssetView.PREVIEW_BOX_PIXELS` in the exports) and `--face-box` on `#people` (80px,
  `FaceDetectionService.faceDetectionBoxPx`), 40..600 by 10, in Panels; the explorer size as an extra control in
  Panels (no token of the panel's own: `explorerShare`, `setExplorerShare` and `hasExplorerDock` of
  `js/common/explorer-dock.js`, the explorer's share of its Dock side's axis, 10..70 %, default 25, the width's, since
  the package takes one number, "Set on the main page, where the explorer is" elsewhere); and
  `exports: { fileName: "core.css" }`.
- **How an override applies:** one `CSSStyleSheet` in `document.adoptedStyleSheets`, rewritten on every change: a
  marker rule first, `:root { --tokentweak: designTweaks }` (the storage key), then one `:root` rule with every tweaked
  token, then one `!important` rule per scoped token on its container. Adopted sheets cascade after the document's, so
  they win over the bundle at equal specificity, with no `!important` on the root tokens and no `@layer`. The explorer
  size is applied through `setExplorerShare`, at `load` when the page has a dock.
- **No flash on load:** the package keeps the sheet's text (`css`) and the theme in its `localStorage` key, and
  `lib/tokentweak-boot.js`, a plain blocking script in the head whose tag names the key in `data-key="designTweaks"`,
  adopts that text and declares the theme before the first paint. The adapter's module, deferred as every module is,
  finds the boot sheet by its marker rule (`cssRules[0]` holds `--tokentweak` equal to the key), takes it over and
  builds the panel; without one it appends a new sheet. No `window` global is involved.
- **The panel itself:** a `popover="manual"` host (`#designTweaks`) in the top layer, above the asset detail and
  every dialog without a z-index, holding a shadow root with the package's own sheet (`--tt-*` palette and pinned
  inherited text properties on the host, so tweaking the app's tokens never restyles the panel). It docks left, right
  or bottom and collapses to a strip; dock, open and collapsed state are remembered. The modal's focus trap marks the
  host inert like everything outside the dialog; the package's inert guard (a `MutationObserver`, `inertGuard`, on by
  default) removes the attribute, so the panel stays usable while a dialog is open, though the trap still returns
  focus to the dialog from the panel's text fields.
- **Themes:** the toggle sets `document.documentElement.style.colorScheme`, as the style guide's switcher does,
  remembered with the tweaks. Editing a color edits the side of its `light-dark()` pair for the theme shown; the
  export writes the full pair. `js/common/theme.js` is not involved: the two keep separate copies of the pair logic.
- **Dev only:** nothing of the panel ships outside dev. The boot script and the adapter are behind
  `Environment.isDev` in `header_common`, and the panel's stylesheet is constructed in the
  shadow root, not part of the bundle. The vendored files are served under `/static/` like every lib but referenced
  nowhere else. `StyleGuideControllerTests` asserts the index and the nav of a non-dev app carry none of
  `design-tweaks`, `designTweaks` and `tokentweak`.

## Key Files

| File | Purpose |
|---|---|
| `static/js/constants.js` | All string constants: events, attributes, store keys, view names |
| `static/js/app.js` | thin bootstrap that exposes `initApp()` and starts `FrontendApp` |
| `static/js/context.js` | `window.ctx` — repo ID (set once by `index.scala.html`) and metadata field settings |
| `static/js/http/client.js` | shared axios client for non-HTMX requests; use per-request `validateStatus` overrides only where the UI intentionally handles a non-2xx response |
| `static/js/models/folder.js` | DOM wrapper around folder tree nodes (`Folder.find(id)`); owns branch expansion (`expand`, `expandAll`, `collapse` with descendant reset) |
| `static/js/common/viewed-folder-scope.js` | tracks the folder scope of the displayed results and marks it in the tree |
| `static/js/common/modal.js` | owner of the four dialog hosts: `openModal`, `closeModal`, open identity (`isModalOpenActive`), the element that requested an open (`getModalOpenSource`), the popover dialog's anchor and its own dismissals (`bindPopoverHost`), `setAssetDetailSize` (the size as shown, which the box is fitted from); a closing host leaves fullscreen |
| `static/js/common/video-playback.js` | the asset-detail Video beyond its element: `unloadVideo`, and the Media Session (`describeInMediaSession`, `clearMediaSession`) |
| `static/js/common/anchored-panel.js` | viewport placement of the popover menus and View settings (the popover dialog is placed by CSS anchor positioning) |
| `static/js/fragments/dialog-operations.js` | lifecycle of the operations every dialog submits; fragment kinds register their `isActive`/`close` |
| `static/js/fragments/inline-dialog.js` | View settings fragment shown in its popover |
| `static/js/common/snackbar.js` | `showSuccessSnackBar`, `showWarningSnackBar`, `showErrorSnackBar`; the `snackbar` store (`createSnackbarStore`: the two stacks, the cap and merge, the notices' timers, focus on dismiss), the announcer (two regions per place; `announcePolitely` lends the polite one to the tooltip) and `raiseSnackbarStacks` (the open stacks shown again, above an element that went fullscreen after them) |
| `static/js/common/tooltip.js` | the `$tooltip` magic (`tooltipMagic`), `showTooltip` and `hideTooltip`: the hint above a clicked element, in the `#tooltip` host, placed by CSS anchor positioning (see **Tooltip**) |
| `static/js/stores/html-common.js` | `registerHtmlCommonBindings()`: what `html_common` binds (the `modal`, `imageDetailLoading` and `snackbar` stores, the `$tooltip` magic, the focus plugin), called once per page before Alpine starts: from `initializeFrontendStores()` and `settings-page.js` |
| `static/js/search-results/selection.js` | the `selectedAssets` store (reactive set of IDs, paints the cells) and the grid's delegated click listener |
| `static/js/alpine/components/date-group-selectable.js` | Alpine component for a group header's checkbox: selects its group's loaded cells (a day, a Location or a Sequence) as a set |
| `static/js/search-results/box-selection.js` | box selection: Viselect gesture on the grid, committed through the store on release |
| `static/js/search-results/infinite-scroll.js` | `loadNextPage` and the observer on the last cell, watched only while nothing follows it; `pendingCursorOf` and `observeContinuationCell` for the page window |
| `static/js/search-results/page-window.js` | the window of loaded pages: folds pages a viewport above into `.page-spacer`s, reloads a spacer as a continuation when it nears (`reloadSpacer`), drops pages a viewport below, keeps the group header still open at a fold, and corrects the scroll position itself |
| `static/js/search-results/lazy-images.js` | loads thumbnails within a viewport, placeholders them once more than a viewport away, the first page's included |
| `static/js/search-results/metadata-visibility.js` | keeps the `show-<field>` classes on `#assets` in step with the metadata fields set |
| `static/js/listeners/htmx-requests.js` | the outcome of every htmx request: failures, validation replacements (no event), declared success events, tab selection, fragment hydration |
| `static/js/settings-page.js` | the Settings page module: Alpine with what `html_common` binds (`registerHtmlCommonBindings()`), the request rule over its one form, the save listener, the Reset buttons |
| `static/js/listeners/settings.js` | `SETTINGS_SAVED_EVENT` → the success snackbar |
| `views/settings.scala.html` | The Settings page: nav, `main` with the form, `html_common` |
| `views/htmx/settings_form.scala.html` | The one Settings form: sections per group, a field per applicable Setting with Reset, error, restart notice and help |
| `static/js/setup-page.js` | the setup page module: starts the background scene on `#setupBackground`, nothing else |
| `static/js/setup-background/setup-background.js` | the page side of the setup background: `startSetupBackground` (feature detection with no fallback, the palette probed to `[r, g, b]` channels, the module Worker and the transferred canvas, `resize`, `visible` and `motion` posted as they change, `.ready` on the Worker's first frame) |
| `static/js/setup-background/setup-background-worker.js` | the Worker of the setup background: the scene on the OffscreenCanvas (the ground's bands and cells, the clouds and their shadows, the jet and its lights), the clamped clock with one pending frame, the first frame drawn before `ready`, the still under reduced motion and the pause of a hidden tab, the refit on `resize` |
| `static/js/setup-background/setup-background-textures.js` | the ten textures of the fields, each painting a cell from its corner: tiles repeated by a pattern (furrows, crop rows, tramlines, strip crops, an orchard, pasture, contours) and the headland and the centre pivot drawn to the cell |
| `views/setup.scala.html` | The setup page: the scene's canvas first in the body, the message hosts, `main` with the title and the setup form (`htmx/setup_form`) on the frosted card, the footer; its layout, the canvas's placement and fade-in, the card, the title and the footer are `static/css/pages/setup.css` |
| `static/js/fragments/dialog-openers.js` | `data-app-open-dialog` buttons opening a page-held `<template>` dialog in the general modal host |
| `static/js/search-results/click-suppression.js` | swallows the click the browser fires after an asset drag or a box gesture |
| `static/js/alpine/components/context-menu.js` | Alpine component coordinating a folder's, album's or Location row's native popover menu; closes a menu from outside (Escape, ancestor collapse, dialog open) |
| `static/js/alpine/components/people-tab.js` | Alpine component of the People tab: the remembered buckets of the Main and Duplicate panes, Split view and the panes' sizes on each axis (`Const.localStore.peopleView`), the pane loads through `htmx.ajax`, the Split.js instance along the Dock side's axis and its remake on `dockSideChanged`, the reload on `peopleChanged` |
| `views/htmx/people.scala.html`, `people_list.scala.html` | The People tab's shell (the panes and their bucket switches, no list) and one pane's list for the buckets asked for; styled by `static/css/htmx/people.css` |
| `views/htmx/detail_panel.scala.html`, `detail_panel_people.scala.html`, `assignable_people.scala.html` | The Detail panel's three sections (People, Details, Metadata; styled by `static/css/htmx/detail_panel.css`), its People section on its own (one row per Face, a button selecting it, with the Face, Person, box, Frame time and Hidden data attributes the Face highlights read; under each row, for a live asset, Change and Remove and the Change combobox's markup, its listbox rendering the Suggested group and then the list from one option block; a correction's response, its root carrying the result message and any deleted Person) and the Change combobox's options, the Suggested group of the Face first, which the combobox parses (`docs/faces.md`, **Corrections**) |
| `views/htmx/view_image_detail_modal.scala.html` | Asset detail's fragment: the media stage with the `<img>`, the player and the Face highlights' layer over them, the divider and the Detail panel's shell with its Dock side menu; its grid and the highlights' look are `static/css/htmx/view_image_detail_modal.css` |
| `static/js/stores/search-params.js` | the search parameter set, its defaults, and the scope rules that decide what a change clears |
| `static/js/search-results/search.js` | `runSearch` — the single entry point for every search request |
| `static/js/search-results/search-triggers.js` | binds `data-app-search` elements to `runSearch` |
| `static/js/search-results/search-input.js` | `bindSearchInput`: the nav's Search input (`#searchForm`), submitting and clearing `q` and following the store |
| `static/js/search-results/detail-navigator.js` | next/previous over the grid's cells, reloading a folded page met on the way, media loading in the asset-detail modal (the box fitted from the shown element's size, the Detail panel told of each asset and size shown), and a shown Video's playback (autoplay with a muted fallback, Space's play/pause) |
| `static/js/search-results/detail-panel.js` | `createDetailPanel`: the Detail panel's open state, its dock (through `common/dock.js`), the load of `htmx/detail_panel` under the coordinator's request, the dimensions as shown, the Face highlights' setting (`p`) and which Faces they show, the Face selection (a row's or a highlight's click, a Video's seek to the Frame time; held in the module, never exported), the toolbar toggles, the remembered view (`Const.localStore.assetDetailView`), and a correction's outcome (the section swapped in, the snackbar, `peopleChanged`, the asset the viewed Person left leaving the grid on stepping away or closing) |
| `static/js/search-results/detail-fullscreen.js` | `createDetailFullscreen`: Asset detail in fullscreen (`toggle`, on `f` and the toolbar's toggle, which it removes where `document.fullscreenEnabled` is false), the player's `fullscreenElement` set to the host on each fragment (`attach`), the toggle's pressed state and the snackbar's stacks raised on `fullscreenchange` |
| `static/js/search-results/face-corrections.js` | `createFaceCorrections`: Remove's two clicks and their reset, the Change pick's request, and what each answer means (the section to swap in, a message, a stale section to reload); `parsePeopleSection` |
| `static/js/alpine/components/face-combobox.js` | the `faceCombobox` Alpine component of a People row's Change: the options loaded once per opening for the Face (`assignablePeopleUrl`, `parseAssignablePeople`), the Suggested group while nothing is typed, the rest filtered as typed, the Create row, the keys, the anchored listbox popover, the pick and the X announced as events; `closeOpenFaceCombobox` for Escape |
| `static/js/search-results/face-highlights.js` | `createFaceHighlights`: draws the Face highlights into `#faceHighlights` (`show`: a toggle button per Face placed by fractions of the shown size, its badge anchored to it; `clear`), and `FRAME_TIME_TOLERANCE_MS`, how far from its Frame time a Video's Face counts as current |
| `static/js/common/dock.js` | `createDock`: a docked panel's side, share, divider (drag and keys), Dock side menu upkeep and remembered state, configured by the explorer's and the Detail panel's docks; `isVerticalAxis` |
| `static/js/search-results/date-groups.js` | keeps a group header's count current as cells leave the grid, and its Curate button (`removeCurateButtons`) |
| `static/js/search-results/cells.js` | every cell of an asset (`cellsOf`, `thumbnailsOf`) and the asset of a cell (`assetIdOf`): a Location grouping holds an asset once per Location |
| `static/js/common/folder-tree.js` | renders the folder tree, its recursive asset counts (`numOfAssets` in the tree JSON, after each name), and each folder's menu from the JSON tree endpoint; patches the counts in place after asset mutations |
| `static/js/common/context-menu-markup.js` | builds the ⋯ menu cell of a folder, album or Location row, the dialog-trigger buttons and a choice menu from its host (`buildChoiceMenuCtrl`, with the compact, glyph-only variant of the Dock side menu) |
| `views/includes/dock_glyphs.scala.html` | The hidden SVG symbol sheet of the four Dock side glyphs, drawn by the Dock side menu's options and trigger through `<use>`; included by `index.scala.html` and the style guide |
| `static/js/common/album-list.js` | renders the album list, its counts (after each name), and each album's menu from the JSON list endpoint; patches the counts in place; marks the viewed album |
| `static/js/common/location-list.js` | renders the categories (expandable nodes holding their Locations) and top-level Locations, the counts after each name, and each row's menu from the JSON list endpoint; restores expansion across rebuilds; patches the counts in place; marks the viewed Location |
| `static/js/fragments/selection-field.js` | `fillSelectionField`: fills a batch dialog's hidden `assetIds` field from the selection, shared by the Add to album and Add to location dialogs |
| `static/js/fragments/add-to-album.js` | the Add to album dialog: its selection field, the new album's name field shown while "New album…" is chosen, and the success detail naming the chosen album |
| `static/js/fragments/add-to-location.js` | fills the Add to location dialog's hidden selection field and keeps its success detail naming the chosen Location (ID and label) |
| `static/js/fragments/location-editor.js` | the Location pin editor: Leaflet map in the Add location modal, click and drag to place the pin, hidden coordinate inputs, readout, place-name search |
| `static/js/common/asset-count.js` | the `(n)` asset count cell placed after a row's name, shared by the folder tree, the album list and the Location list |
| `views/includes/html_common.scala.html` | The snackbar's two stacks (manual popovers over the `snackbar` store) and its announcers + the four dialog hosts: the three Alpine-bound modal hosts and the popover dialog |
| `views/style_guide.scala.html` | The dev-only style guide: static specimens of the shared primitives, and the Alpine templates of the tokens, the icons and the token audit; its own layout is `static/css/pages/style_guide.css` |
| `static/js/style-guide.js` | the `styleGuide` Alpine component (the source scan resolved in the browser: tokens, icons, token audit; the theme switcher's and the font tester's state) and the style guide's live menu and dialog specimens |
| `static/js/common/token-kinds.js` | the kinds of token (`TOKEN_KINDS`, `kindOf`) and their groups (`groupOf`, `paletteGroupOf`), the token references of a declaration (`referencedNamesOf`), for the style guide |
| `static/js/common/theme.js` | the two themes: `applyTheme`, `chooseTheme`, `toggleTheme`, `rememberedTheme`, `inTheme`, `resolveColor`, and the two colors of a `light-dark()` pair (`themeColorsOf`, `withThemeColor`) |
| `static/js/common/font-availability.js` | `isFontAvailable` and `asFontFamily`, the font tester's check |
| `static/js/common/explorer-dock.js` | the main page's Dock side, the explorer's configuration of `common/dock.js`: `initExplorerDock(view)`, `setDockSide(side)`, `effectiveSide()`, `isVerticalAxis`, the explorer's remembered share per axis, `explorerShare()` / `setExplorerShare(percent)`, which the design tweaks adapter passes as an extra control, and the `dockSideChanged` event |
| `static/js/theme-boot.js` | the blocking boot script of `includes/header_common`: the remembered theme on `<html>` before the first paint |
| `static/js/explorer-dock-boot.js` | the blocking boot script of the main page's head: the Dock side and the share on `<html>` before the first paint |
| `static/js/design-tweaks.js` | the design tweaks panel's adapter: `createTokenTweaks` of the vendored tokentweak package with Altitude's storage key, host id, stylesheet, trigger, shortcut, sources URL, sections, scales, the two scoped tokens, the explorer control and the export file name |
| `static/js/lib/tokentweak.js` | the tokentweak package (vendored, dev only): the panel, its store and adopted sheet, the controls per kind, Inspect, the exports; its options are its README's |
| `static/js/lib/tokentweak-boot.js` | tokentweak's boot script (vendored, dev only): adopts the stored override sheet and theme before the first paint, keyed by its tag's `data-key` |
| `views/includes/search_results.scala.html` | Search grid wrapper with the results title (**Results title**), the Group and Sort controls, the grid / map layout toggle and the "Map area ×" chip; the controller passes in the rendered grid partial, or the map shell, and the scope the title names (the person, the folder's path, the album, the Location). Its CSS (`static/css/includes/search_results.css`) gives `#assets` `overflow-anchor: none` and the page spacer its full row; `#assets` carries the preview size as `--preview-box` for the cell size |
| `views/includes/results_title.scala.html`, `results_title_inner.scala.html` | The results title block and its inside (icon, title, the way back, subtitle; a person's cover face and actions); styled by `static/css/includes/results_title.css` |
| `views/htmx/person.scala.html`, `person_inner.scala.html`, `view_person_name.scala.html`, `edit_person_name.scala.html` | The person scope's results title: the block that is the merge drop zone and the person actions' swap target, its inside, and the inline name editor's two states; the person's own styles are `static/css/htmx/person.css` |
| `static/js/search-results/sticky-offsets.js` | `bindStickyOffsets`: writes the results title's rendered height to `--results-title-height` (the toolbar's sticky offset) and the title's and toolbar's together to `--results-sticky-height` (the group headers') on the fragment |
| `views/htmx/result_cell.scala.html` | One asset cell, shared by both grids, with no Alpine of its own; a page's last cell carries the cursor of the next page, and the thumbnail its preview URL in `data-src`, a first page's in `src` too. `data-media-type` names what the asset is; the cell of an asset with a duration, a Video or an animated GIF, wears a play badge over its Preview and a `duration` metadata row (`Util.humanReadableDuration`, `m:ss` or `h:mm:ss`), shown by the View control's Duration switch like every other field |
| `views/htmx/results_grid.scala.html` | The ungrouped grid: the page's cells, infinite-scroll trigger by cursor |
| `views/htmx/results_grid_grouped.scala.html` | The grouped grid: a group header per day, Location or Sequence, infinite-scroll trigger by cursor |
| `views/htmx/map_view.scala.html` | The map layout's shell: `#map` with the bounds and tile settings, and the crowded-pin panel `#mapPanel`; its CSS, the pin styles included, is `static/css/htmx/map_view.css` |
| `static/js/common/vendor-loader.js` | loads Leaflet (with its stylesheet), supercluster and media-chrome once, when a view first needs them |
| `static/js/map/map-view.js` | the map view: Leaflet map, viewport cells requests, supercluster pins and their clicks, one map at a time (`disposeMapView`) |
| `static/js/map/map-panel.js` | the crowded-pin panel: `openMapPanel` runs the `bbox` search into `#mapPanelContent`; close, "Show only these in the grid" |
| `static/js/map/map-state.js` | the map view remembered per search scope in `sessionStorage`, and the displayed map's live view for the pin editor |
| `static/js/listeners/map.js` | puts `layout=map` back into the URL the server pushes for a panel request |
| `views/htmx/curation.scala.html`, `curation_set.scala.html` | Curation's shell (its header, the host of a set's fragment and the states with no set) and a Curation set's fragment (the Bundle rail, the stage, the face strip and the footer); styled by `static/css/htmx/curation.css` and `curation_set.css` |
| `static/js/alpine/components/curation.js` | Alpine component on Curation's shell: its state, reading the sets, the walk of the queue, the stage's zoom and its Loupe (attached, refreshed and detached with the stage), the outcomes, Undo and the keys; `requestCuration` names what the next shell opens on |
| `static/js/listeners/curation.js` | Curation's events: opening it, the batch ops' cap, the counts after each mutation, the grid beneath when it closes |
| `static/js/fragments/curation.js` | `hydrateCurationFragment`: opens the Curation host on its shell |
| `static/js/stores/sequences-to-curate.js` | the `sequencesToCurate` store: the number of Sequences in Curation's queue, set only by the count endpoint |
| `static/js/common/text-editing.js` | `isTextEditingTarget`: whether focus is in a field that takes the keys typed, so a page's keyboard shortcuts leave them to it |
| `static/js/common/loupe.js` | the Loupe: `attachLoupe({ panes, levels, readLevel, saveLevel })` over panes of `{ paneEl, imageEl, lensEl }`, answering `refresh`, `setLevel` and `detach`; the pointer-to-pixel mapping under `object-fit: contain` and the wheel through the ladder, with no knowledge of the page it is on |
| `static/js/common/clipping.js` | `readClipping(imageEl, { fitBox, clippedLevel, crushedLevel })`, async: the class of every pixel (a `Uint8Array` at the sampled size: blown, crushed or neither) of a loaded same-origin image, resized to the box off the main thread and grayed as the import's measurement does; `clippingMask(read, { blownColor, crushedColor })`: the Clipping mask of a read, an `ImageData` at its size; with no knowledge of the page it is on |

## Search parameters

Every search in the app — the initial load, folder and person navigation, the Search input, the Sort
and Group choice menus, continuous scroll, the page the detail modal loads at the end of the grid — goes through
**one** function, `runSearch` in `js/search-results/search.js`. A caller supplies only the parameter
it knows about; the `searchParams` store supplies the rest. Nothing else builds a URL for
`/htmx/search/r/:repoId`.

`js/stores/search-params.js` owns the parameters (`view`, `folderId`, `personId`, `albumId`,
`locationId`, `q`, `bbox`, `triaged`, `layout`, `sort`, `groupBy`, `groupDirection`, `rpp`) and the rules for combining them:
choosing a folder, a person, an album, or a Location clears the other three, and a view clears all four. The store
holds no position: a search starts at its first page, and a later page is one request's `after` cursor. The map area (`bbox`, the crowded-pin panel's scope) belongs to the search it was
drawn on, so a view, a folder, a person, an album, a Location or a new `q` clears it too. Search text
(`q`) is one more place to look, the whole repository outside the trash: a change of `q` clears the
folder, person, album and Location and returns `view` to the repository view, and each of those four,
and a view, clears `q`. The whole `CLEARS` table:

| Changed | Cleared (back to its default) |
|---|---|
| `view` | `folderId`, `personId`, `albumId`, `locationId`, `bbox`, `q`, `triaged` |
| `folderId`, `personId`, `albumId` or `locationId` | the other three, `bbox`, `q` |
| `q` | `view`, `folderId`, `personId`, `albumId`, `locationId`, `bbox` |

The layout, the grouping, `rpp` and the sort narrow or reorder what is already in scope and survive
all of those: a chosen sort is kept from one place to the next, and with no sort chosen the server's
default applies (Relevance with text, newest import without). The triage filter (`triaged`), off by default so that the repository view holds the sorted assets alone,
survives a folder, person, album, Location or `q`, but it is the repository view's: the View
settings dialog's "Show triaged items" switch (see **Inline dialogs**) is shown in that view alone, and a
change of view clears it. The combinations the server refuses
are settled by the store itself (`withoutRefusedCombinations`, on seeding from the URL and after every
change): `q` with the trash view becomes the repository view, `sort=relevance`
(`Const.search.sortRelevance`) without `q` drops the sort, so Relevance is the one sort that ends with
the text it ranked, and `triaged` in any view but the repository view is dropped (the triage view
refuses it). The server also refuses a page size outside 1 to 500; the store keeps only whole numbers
in that range (`normalize`, with
`Const.search.maxRpp` the client's copy of `Const.Search.MAX_RPP`) and turns anything else into its
default, so a hand-edited `?rpp=1000` falls back to the server's page size instead of failing every
search. A parameter still at
its default is left out of the request, so a default is never spelled out on both sides — except
`view`, which is always sent, and whose values match `Const.Search.View.*` server-side verbatim.

The Search input is `#searchForm` in `includes/nav` (`role="search"`: an `<input type="search" name="q">`
labelled "quick search" by its placeholder, and a `.clear` button; no submit button), rendered in every page's nav
(see **Nav refresh**) and bound once by `bindSearchInput()` (`js/search-results/search-input.js`) from
`FrontendApp.start()` on the pages that run it (the main page and the style guide); on the others (import, settings)
the form's own `action`, a GET of `/r/:repoId`, opens Browse with the text as `q`, which seeds the search there.
On the main page Enter submits the form, which runs `runSearch({ params: { q } })` with the trimmed value; the
clear control (which empties the input and submits), the input's native clear (Escape: the `search` event with an
empty value; the stylesheet hides the native button) and an empty submit clear `q` the same way, and an
empty submit with no `q` in the store does nothing, so it never leaves the folder or view being looked
at. The input holds no state: an Alpine effect writes `searchParams.q` into it, so a URL with `q` fills
it on load and choosing a folder, person, album, Location or view empties it. The page is laid out for
the view it was loaded with, so from Triage or Trash a text search is not a swap: the store is merged
and the browser navigates to `/r/<repoId>?<the store's query string>`, a full load of Browse that
seeds the store from that URL, layout and grouping included.

`includes/search_results` takes the Search text the results were matched by (`text`, set when `q` had a
usable term) and puts it on the fragment as `data-results-q`. The Sort menu offers "Relevance"
only then, checked when it is the effective sort, since the server
refuses it without text.

`groupBy` (`dateTaken`) with `groupDirection` (`asc`/`desc`) groups the grid by
day, and `location` and `sequence`, whose order is fixed, by Location and by Sequence with an empty direction; each option of the
Group menu in `search_results.scala.html` carries both, and "No
grouping" sets both to empty, which the store normalizes to `null`. The menu offers Sequence in the Triage view alone. Outside
it the server groups nothing for `sequence`, the menu marks the grouping the server used, and the store keeps what was asked
for, as the URL does, so a return to Triage groups by Sequence again. A search has no page
number, grouped or not: the grid is continued by the transient `after` cursor its last cell carries. Grouping is not
remembered in `localStorage`; like every other parameter it lives in the store and the bookmarkable
URL. The layout is the one exception: the store's `merge` writes a changed `layout` to
`localStorage` (`Const.localStore.resultsLayout`), and seeding takes the layout from the URL first and
from `localStorage` when the URL has none, so a map user opens on the map (`Const.search.layout`
names the two values).

The browser URL is authoritative exactly once, at page load: `index.scala.html` seeds the store from
`window.location.search`, so a bookmarked or shared search still opens. After that the store is the
source of truth and the friendly URL the server pushes back (`HX-Replace-Url`) is only a projection
of it — never read back. The server correspondingly reads nothing but its own query parameters; it
does not merge with `HX-Current-URL`, and there is no "new search" flag.

Triggers are declarative and hydrated centrally by `bindSearchTriggers`, called from
`js/fragments/index.js` (and from `reloadFolderTree`, which builds its rows after the folders tab
has settled):

```html
<a data-app-search="click" data-app-search-person-id="abc">          <!-- literal parameter -->
<button data-app-search="click" data-app-search-sort="filename0">     <!-- a choice menu's option: literals too -->
<button data-app-search="click" data-app-search-group-by="dateTaken" data-app-search-group-direction="desc">
<button data-app-search="click" data-app-search-bbox="">             <!-- an empty literal clears the parameter -->
```

The hydrator also owns the rule that folder navigation does nothing in triage and trash, so that
guard lives in one place rather than in markup.

Per-request flags that must not be remembered (`isContinuousScroll`, a continuation's
`after`, and the grid layout and no grouping of the crowded-pin panel's request while the store says
map) are passed as `transient` and are serialized into that one request only. `runSearch` also takes
a `target` and `swap` (the panel searches into `#mapPanelContent`) and a `source`, the element htmx
issues the request from, for a caller that recognises its responses in the lifecycle events
(`js/listeners/map.js`). A server-side action that should change what is
displayed reports it as a custom event and lets JS run the search — as the people merge does with
`personMerged` — rather than redirecting to a search URL of its own.

