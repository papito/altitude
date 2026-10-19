# Frontend – Agent Guide

## Do

The rules of [altitude/AGENTS.md](../AGENTS.md) **Do** apply here too. For frontend work:

* For UI work, prompt the developer to run "make db" and "make watch" if localhost:8080 is not accessible or if the repo is not initialized (setup form visible or no assets present).
* Use the fewest properties/tags and the least CSS/HTML standards to accomplish the task.
* Do not assume aesthetic preferences such as color, spacing, or padding unless they are specified. The style guide (`views/style_guide.scala.html`, dev only, `/style-guide/r/:repoId`) records the design conventions: read its usage notes before choosing a color, spacing, or component, and keep it current in the same change (the list is under **Style guide** below).
* Always prefer CSS Grid and Flexbox.
* The application's CSS is one bundle: `static/css/app.css` lists `@import` lines in cascade order (`core.css`, `tabs.css`, then one file per template under `pages/`, `includes/` and `htmx/`, named after it), which the build inlines into one content-hashed file. Reuse `core.css`'s `:root` variables. A template's styles go in its own file, such as the folder tree and popover styles in `static/css/htmx/folders.css`, never in a `<style>` block. The bundle applies on every page, so scope a rule by an element only its template renders (usually an ID), and a page's own rule by its body class at zero specificity (`:where(body.setup) main`). A Scala value reaches CSS as a custom property the template sets on the container (`style="--preview-box: ...px"`). Bundled CSS is served from `/assets/`, so its URLs are absolute or `data:`.
* Strongly prefer CSS variables over hard-coded values, especially if duplicated.
* Write no color literal, named colors included, in a stylesheet: a rule writes a theme color (`--theme-*`: the two seeds, `--theme-base` and `--theme-accent`, or one of the roles derived from them), a status color (`--success-color`, `--warning-color`, `--error-color`) or `--shadow-color` by its token (`color: var(--theme-text)`), and an edge or a shadow by its token (`border: var(--panel-border)`), never restating one a token holds. The seeds, the status colors and `--shadow-color` hold literal `light-dark(light, dark)` pairs; every other theme color is a `light-dark()` pair of OKLCH relative-color recipes over a seed, one per scheme. The setup page's palette (`--setup-*`) is that page's own: `pages/setup.css` declares it on `body:where(.setup)`, as literals, and the style guide's scan leaves the file out (see **Setup background**). The only other color tokens of `core.css` are the veils (`--scrim-color`, `--asset-detail-backdrop-color`, `--dnd-dragging-background-color`), a theme color or `--shadow-color` at an alpha in relative color syntax, the color inside a border or shadow token is a theme color or one of those at an alpha too, and the three status fills (`--success-background-color`, `--warning-background-color`, `--error-background-color`), each its status color darkened (lightened, in the light theme) by one recipe; no other token aliases a color under another name or mixes one. A new color is a theme color written in the rule, a new `--theme-*` role with a recipe over a seed when no role fits, or a new veil token when it must be translucent. Keyboard focus is the one `--focus-ring`, which the `:focus-visible` rule of `core.css` draws on every control; no rule removes an outline. The detail is under **Themes** below.
* Paddings, margins, gaps and insets use a step of the `--space-*` scale of `core.css`, or a role token over one (`--panel-padding`, `--viewport-gap`, `--list-row-gap`, …). The steps and the size tokens (widths, heights, radii) are in rem; a rectangular box or control rounds its corners with `--radius`, a badge with `--radius-small`, and only a circle or an avatar writes a radius (a percentage) in a rule; border widths, shadow offsets and the sizes the server sets in pixels (`--face-box`, `--preview-box`) stay in px. A token that JS reads as a number of pixels is registered with `@property` as a `<length>`, so its computed value is in px (`--viewport-gap`). The only literals among them are 0 and auto, centering offsets (`50%` and a negative half size), offsets larger than the scale (the batch bar's and the general modal host's 100 px, the snackbar's top), and the em offsets and padding of a category's +/− badge in the Locations list, which belong to its glyph.
* A font size is a step of the type scale of `core.css` (`--font-size-xs`, `-small`, `-base`, `-medium`, `-large`, `-xl`, `-display`, in rem like the spacing steps; `--font-size-base` is the body's, and a control that would not inherit it, a button or a menu action, writes it), never a px or em literal, `smaller` or a size relative to a parent. A weight is `--font-weight-bold` or `--font-weight-medium` (`normal` resets one), a tracking is `--letter-spacing-wide`, and the leading of text that wraps is `--line-height-text`. A glyph's own size and leading stay in its rule: the spinner's 3em, the merge arrow's 2em, the 0.5em of a category's +/− badge, and the `line-height: 1` of the menu trigger, the location pin and the close button; the Font Awesome solid weight 900 selects the icon face.
* Folder, Album, and Location actions (including Add controls and category actions) open separate modals through `static/js/common/modal.js`. Anchor those modals below the row's menu trigger or the Add button and size ordinary forms to their content without widening inputs. Keep their context menus as action lists; only View settings uses an inline popover dialog. The detail is under **Modals**, **Context menus** and **Explorer action dialogs** below.

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
  includes/             ← reusable layout fragments (nav, header, batch ops, search results)
  htmx/                 ← HTMX partials returned by routes/web/partial/**
```

Full pages include `@includes.html.html_common()` (provides both modal containers and `#snackbar`)
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
renders (see **Locations** below). Partials carry no module scripts: the repository ID is set once by `index.scala.html`, and the
explorer tab that issued a load is marked selected from `htmx:after:request`
(`js/listeners/htmx-requests.js`). The styles the two menus share (`.menu-ctrl`, `.context-menu`,
`.asset-count`) live in `core.css`.

The explorer tabs (`index.scala.html`) are the links themselves (`<a role="tab">` in a
`role="tablist"`, styled by `tabs.css` on `aria-selected="true"`); the initial tab is loaded through
`htmx.ajax` with the tab as `source`, so it is selected exactly as a click would select it.

## JS Directory ↔ Template Mapping

| JS directory | Template(s) it serves |
|---|---|
| `js/assets/` | asset mutation/action flows such as move, recycle, purge, and restore, plus related grid/snackbar follow-up |
| `js/fragments/` | centralized hydration for declarative HTMX fragments (`data-app-fragment="..."`): modal, inline-dialog, image-detail, the person name editor, search results, the explorer list hosts (`explorer.js`), buttons opening a page-held dialog (`dialog-openers.js`), the Add to location dialog's selection field (`add-to-location.js`), plus the operation lifecycle shared by every dialog (`dialog-operations.js`) |
| `js/listeners/` | domain-focused `document.body` event registration for folders, albums, Locations, people, assets, search keys, and the Settings form's save (`settings.js`, the success snackbar); the generic htmx request outcome (`htmx-requests.js`: failures to the snackbar, declared success events, tab selection, fragment hydration); the `document`-level dialog request wiring (`dialogs.js`); and the map panel's URL correction (`map.js`) |
| `js/search-results/` | the search funnel (`search.js`) and its declarative triggers (`search-triggers.js`); the Search input of `includes/nav` (`search-input.js`); the grid's behaviours, one module each: selection (`selection.js`), infinite scroll (`infinite-scroll.js`), the window of loaded pages (`page-window.js`), lazy images (`lazy-images.js`), metadata visibility (`metadata-visibility.js`), the ⚙ View control (`view-settings-control.js`), box selection (`box-selection.js`), the group headers' counts (`date-groups.js`), detail navigation over the grid and image loading (`detail-navigator.js`), every cell of an asset (`cells.js`), and the post-gesture click swallow (`click-suppression.js`) |
| `js/map/` | the map layout of `includes/search_results` and `htmx/map_view`: the `map-view` fragment hydrator (`map-view.js`, registered in `fragments/index.js` after the results hydrator), the crowded-pin panel (`map-panel.js`), and the view remembered per scope (`map-state.js`); see **Map view** |
| `js/dragdrop/` | interact.js binding modules for assets (`assets.js`, thumbnails and the trash drop zone), batch ops, people, folder-tree, album and Location drag/drop, plus the helpers they share (`helpers.js`) |
| `js/stores/` | Alpine store initialization modules shared by the app shell and feature coordinators |
| `js/frontend-app.js` | app-wide bootstrap/composition root, delegating into asset/dragdrop/fragment/listener/search modules |
| `js/upload-form-page.js`, `js/settings-page.js` | the page modules of `pipeline.scala.html` and `settings.scala.html`: each registers the htmx request rule with a minimal app (no stores, no fragments) and the page's own listeners; see **Settings screen** |
| `js/setup-page.js` | the page module of `setup.scala.html`: it only starts the background scene (`startSetupBackground` on `#setupBackground`); the setup form submits through htmx and `json-enc.js`, which the page loads as plain scripts, so nothing else is registered; see **Setup background** |
| `js/setup-background/` | the background scene of `setup.scala.html`: the page side (`setup-background.js`: feature detection, the palette probe, the Worker's start with the transferred canvas, the `resize`, `visible` and `motion` messages, the fade-in on `ready`), the module Worker that draws the scene on the OffscreenCanvas (`setup-background-worker.js`) and the fields' textures it paints (`setup-background-textures.js`); see **Setup background** |
| `js/common/` | shared: modal, snackbar, navigation, folder-tree (renders the tree), album-list (renders the albums), location-list (renders the categories and Locations), context-menu-markup (builds the ⋯ menu of a folder, album or Location and the dialog-trigger buttons), asset-count (the `(n)` cell after a row's name), viewed-folder-scope (highlights the folder whose results are displayed), htmx-events (accessors for the htmx 4 request context), video-playback (unloading the detail's video and the Media Session), explorer-split (the main page's Split.js instance and its remembered sizes), token-kinds and theme (the kinds, groups and themes of the tokens, for the style guide), font-availability (whether the browser has a font) |
| `js/design-tweaks.js` | the adapter of the dev-only design tweaks panel, which `includes/header_common` loads on every page: it configures the vendored tokentweak package (`lib/tokentweak.js`, with `lib/tokentweak-boot.js` for the pre-paint adoption) for Altitude; see **Design tweaks** |
| `js/models/folder.js` | DOM wrapper for folder tree elements and the owner of their expansion state |
| `js/alpine/components/` | Alpine components: `date-group-selectable.js` (a group header's checkbox over its group's cells, a day or a Location) and `context-menu.js` (native popover menus of folders, albums and Locations, and the functions that close one from outside); `index.js` registers them |

## Alpine.js Stores (initialized in `js/frontend-app.js` and `js/stores/app-stores.js`)

| Store key | Purpose |
|---|---|
| `Const.state.selectedAssets` | Reactive `Set` of the selected asset IDs, and the one place a selection changes (see **Selection** below) |
| `Const.state.searchParams` | The complete search parameter set — the single source of truth for what is being searched (see **Search parameters** below) |
| `Const.state.currentView` | Current view: `repository`, `triage`, or `trashbin`. Derived from `searchParams.view` and written once at page load |
| `Const.state.resultsTotal` | The toolbar's results total: `count` and `isCapped`, set from the results fragment's `data-results-total` and `data-results-total-capped` (`js/fragments/search-results.js`). `label` reads the count followed by "+" when the server counted only up to its cap (`js/common/total-label.js`, "10000+"); `decrement` lowers an uncapped total as assets leave the grid and leaves a capped one alone, since it says only that there are more than the cap |
| `Const.context.repoId` | Active repo ID — set from Twirl via `window.ctx.setRepoId(...)` |
| `Const.context.gridMetadataFields` | Reactive `Set` of metadata field names shown in the grid; persisted in `localStorage` as a JSON array |

Always access the repo ID via `window.ctx.getRepoId()`, not directly from the store.

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
```

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
   `js/listeners/dialogs.js` for modal opens and dialog operations. There is no router keyed on
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
- Attribute inheritance is explicit: every element that issues a request declares its own
  `hx-target` / `hx-swap`. Do not rely on a parent's attributes (add `:inherited` if you ever must).
- Extensions activate by script inclusion, there is no `hx-ext`. Forms that post JSON carry the
  boolean `hx-json-enc` attribute (`json-enc.js`); the import status stream uses `hx-ws:connect`
  with an explicit `hx-target` / `hx-swap` on the connection element (`hx-ws.js`). No Alpine
  compatibility shim is loaded: Alpine's mutation observer initializes swapped-in markup, and
  nothing calls `Alpine.initTree` on it.
- Polling is declarative: the import page refreshes the nav with
  `hx-trigger="every 5s [document.visibilityState === 'visible']"`.
- Global config (`defaultTimeout`, `noSwap`) is the `htmx-config` meta tag in
  `views/includes/header_common.scala.html`; 4xx/5xx bodies are not swapped, listeners report them.
- The file upload (`js/fragments/upload-form.js`) is **not** an HTMX request: it posts through the
  shared axios client so `onUploadProgress` can drive the progress bar, then swaps the returned
  fragment itself.

Batch ops escalate a single-asset drag to a batch when the asset is among the selected ones: the
`assetMoved`/`assetTrashed` handlers in `js/listeners/assets.js` re-dispatch
`batchAssetsMoved`/`batchAssetsRecycled`. Person merge/name/cover-face events and the discard
follow-up live in `js/listeners/people.js`; a purge of the recycle bin announces `trashPurged`,
which refreshes the counts.

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
no editor.

## Alpine.js Integration

Alpine serves four distinct roles in this codebase:

### 1. Global shared state (stores)
Stores initialized in `js/frontend-app.js` and `js/stores/app-stores.js` hold shared data — the active
repo ID, current view, and the set of selected assets. All access goes through `window.ctx`
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
reactive counter the group headers read (the DOM itself is not reactive). The footer binds to the
set (`x-show`, `x-text`), and `reset()` from its Deselect All button clears it.

### 3. Coordination around native UI
`x-data="contextMenu"` (registered with `Alpine.data` in `alpine/components/index.js`, defined in
`alpine/components/context-menu.js`) wraps each folder's, album's or Location row's ⋯ trigger and its native
`popover="auto"` panel. The browser owns the menu's visibility (`:popover-open`, `popovertarget`, light dismiss on
outside clicks); the component only places the panel, closes it when focus leaves or when the page scrolls or the explorer
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
written `@@click`. The explorer lists (folder tree, albums, Locations) are still rendered by their
JavaScript modules.

Alpine is intentionally **not** used for routing, server communication, or HTMX trigger logic —
those responsibilities stay with HTMX and the custom event bus.

## UI Patterns

**Modals** — Two hosts live in `html_common.scala.html`: load into `#modalContent` for general
dialogs and into `#imageDetailModalContent` for asset detail. `js/common/modal.js` is the single owner
of both: which host is active (one at a time — a new open replaces the active modal), the title,
initial focus (the fragment's autofocus selector, else the close control, never a destructive action),
focus restoration on close (the fragment's `data-app-dialog-return-focus` selector, else the
control that opened it, using the visible ⋯ trigger for a menu action), and identity: every displayed open has an `openId`
(`isModalOpenActive(openId)` guards asynchronous work), and the latest "open request" (any HTMX
request targeting a host) is tracked so a slow response for an earlier open is cancelled before it
swaps once the user dismissed or replaced it. Visibility is bound through the Alpine `modal` store
(`x-show`; `x-trap.inert.noscroll` from the vendored `@alpinejs/focus` plugin registered in
`js/app.js` contains focus and hides the page from assistive tech; its documented local patches
cancel delayed activation when a trap is released or removed, and count controls in open shadow
roots, the video player's sliders, as inside the trap). Escape (`global.js`) closes the
active modal and any open context menu and is consumed by them, so a background inline edit
survives; with neither open it closes the map view's crowded-pin panel, and with none of those it is
broadcast for such an edit to cancel. General dialogs ignore backdrop clicks, asset detail closes on them. Explorer action dialogs
are anchored below the row's ⋯ trigger, or the Add button. Their request buttons declare
`data-app-modal-anchor` with that control's selector; `modal.js` preserves it through validation,
repositions on content/viewport/explorer resizing and scrolling, and releases its observers on close
or replacement. `placeAnchoredPanel` (`common/anchored-panel.js`) supplies placement shared with
context menus: below and left-aligned normally, flipping above or capping height on the roomier side
when necessary, with horizontal placement constrained to the viewport and the panel kept
`--viewport-gap` (`0.625rem`, 10px at the default root) from every viewport edge. The box is hidden until measured to avoid a
flash at the default position. Anchored forms use natural content width in `core.css`, keeping
inputs at their existing width and only the standard padding at the right. The Location map editor
retains its explicitly declared width. Other general dialogs retain `--modal-content-width` and the
host's centered placement; asset detail is fitted by CSS from the stored size `setAssetDetailSize()` sets on its box, in its own host. Opening any modal closes an open context menu first, so a
modal never appears over one.

Both modal hosts use `.close-modal` in `core.css`: the X keeps the text color (`--theme-text`)
at rest, on hover, and on focus, with no focus outline (the modal may focus it on open).
Folder, album, Location, and category dialogs use the general modal host and its close control.

A **dialog** is a server-rendered form that completes one user action, hydrated from its
`data-app-fragment` kind; a **modal dialog** is one shown in the modal host. Attributes that describe
the dialog itself are `data-app-dialog-*` on the fragment root, whatever its presentation: autofocus
selector / select-on-focus, return-focus selector (the control focus goes to on close, chosen to
survive the page update the dialog triggers), and `kind`, naming wiring a dialog needs beyond its
form (the view settings checkboxes, hydrated in `js/fragments/inline-dialog.js`, and the Add to
location selection, hydrated from `js/fragments/modal.js` through `js/fragments/add-to-location.js`). Its success
event is declared like any request element's, with `data-app-success-event` (+ `-detail`,
`-detail-target-attr-*`) on the fragment root. An outcome only the server knows (how many assets a
membership change applied) arrives as JSON in the `App-Success-Detail` response header
(`BaseController.dialogSuccessResponse`, `Const.http.successDetailHeader`), which
`dialog-operations.js` merges over the declared detail. The one attribute that describes the modal host is
`data-app-modal-title`. Folder, album, Location, and category actions, including the Add controls,
are **modal dialogs**, as are the people dialogs and purge confirmation. Only View settings is an
**inline dialog** (`data-app-fragment="inline-dialog"`, see **Context menus**); its checkboxes apply
immediately without submitting an operation.

A dialog the page already holds needs no request: a button with `data-app-open-dialog="#id"`
names a `<template>` whose content is a `modal` fragment; `js/fragments/dialog-openers.js` copies
it into the modal host, runs `htmx.process` on it, and hydrates it like a fetched dialog, so it
opens, submits, closes, and announces its success event through the same code. The purge
confirmation in `htmx/trashbin_header.scala.html` is the one such dialog (`hx-confirm` is not
used).

General HTMX modal fragments opt in with `data-app-fragment="modal"`; `js/fragments/modal.js` opens the
host on hydration and registers the modal presentation with `js/fragments/dialog-operations.js`, which
tracks each operation any dialog submits from `htmx:before:request` (capturing a handle to the dialog
it belongs to - for a modal, its open - and its success metadata while the fragment is still in the
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
timeline or volume slider, which the arrows step; Space plays or pauses the current Video, through media-chrome inside
the player, and does nothing on an image.

**Context menus** — Each folder's, album's or Location row's ⋯ button (its **trigger**, `.menu-trigger`) is a real
`button` with `popovertarget` pointing at a `popover="auto"` panel (`.context-menu`; `#menu-{id}`
for a folder, `#albumMenu-{id}` for an album), all built by `buildContextMenuCtrl` in
`js/common/context-menu-markup.js` as the tree or list is rendered, so opening a menu sends no request.
Each action requests its dialog into `#modalContent`; the modal owner closes the menu when the
response opens. Menu panels contain only their action buttons, with no inline form or dialog host.
`buildModalTriggerCtrl` builds the Add folder, Add album, Add location, and Add category buttons
(including empty-state controls) as direct requests to the same modal host.

The ⚙ View button above the results grid (`#viewSettingsBtn`, built by
`js/search-results/view-settings-control.js` into `#viewSettingsActions`) uses
`buildDialogTriggerCtrl`, a `.dialog-trigger-ctrl` with an inline dialog host. Its `dialog-only`
panel stays invisible until the settings arrive, opens below the button centered on it
(`data-menu-align="center"`), and is cleared on close. The component records its load request and
drops a response after the panel has closed or reopened. Each checkbox applies immediately;
View settings submits nothing. Its trigger shows no focus ring (`.dialog-trigger-ctrl > button`).
The browser owns popover visibility: it toggles the panel from its trigger, closes it on any click
outside, and keeps one open because a panel is never a descendant of another entity's panel.
The `contextMenu` component places the panel in the top layer against the trigger (below,
flipping above when needed, clamped to the viewport, height-capped with internal scrolling when
neither side fits; left-aligned with the trigger, or centered on it for a `data-menu-align="center"`
root); it closes the panel when focus leaves and on scroll, window resize, or explorer resize;
it never tracks a moving trigger. The panel carries `tabindex="-1"`, so a click on a dialog's
label or padding moves focus to the panel rather than out of it.
`closeContextMenu` / `closeOpenContextMenu`, exported by the component module, close a menu from
outside the component: Escape in `global.js` (focus returns to the trigger unless a modal was
closed too), the folder model when an ancestor collapses, and `openModal`; the trigger is found as the
`[popovertarget]` button in the panel's cell, not by an ID convention. Removing the tree or list removes the open panel and, through the component's
`destroy`, its listeners. Styling lives in `core.css`; CSS sets `display` only under
`:popover-open`, because the hidden state relies on the browser's `display: none`. During
`beforetoggle`, the component briefly sets inline `display: grid` to measure and position the
hidden panel, then clears it before opening; keep the CSS `margin: 0` and `inset: auto` resets so
those viewport coordinates apply correctly. `.menu-ctrl` and the icon control `.expand-ctrl` are
excluded from folder dragging (`ignoreFrom` in `js/dragdrop/folders.js`); the rest of the row drags. A panel lives inside whatever container holds
its trigger, so no stylesheet may reach into one with a descendant selector: `#searchControl > div`
in `search_results.scala.html` is scoped to direct children for exactly that reason, since an ID
selector outranks `.context-menu:popover-open` and would leave the closed panel displayed.

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
footer offers "Remove from album", which deletes the memberships of the selected assets and removes
their cells. Add is a separate modal (`add_album_dialog.scala.html`, submitted with Return) opened by the top `#addAlbumBtn` (centered above the list) or, while
there are no albums, by the centered `#addFirstAlbumBtn`; the renderer builds both controls into
their hosts and shows one or the other. Rename and Delete open separate modals from the album's menu; deleting the viewed album runs a
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
plain; inside a collapsed category the viewed Location stays hidden until it is expanded) the batch footer offers "Remove from location", which
deletes the memberships of the selected assets and removes their cells. The footer always offers "Add
to location (n)" outside the trash: it dispatches `batchAddToLocationRequested`, the listener requests
`add_to_location_dialog` into the modal host, and `js/fragments/add-to-location.js` fills its hidden
`assetIds` field from the selection and keeps the fragment's success detail naming the chosen
Location (its ID, and its bare name from the option's `data-name`, since the row is not rendered while
another explorer tab is displayed). The server adds how many assets it `added`, and `assetsAddedToLocation`
reports that through `assetActions.reportAddedToLocation`, the report a drop makes too ("Already in
location" when nothing was new), then resets the selection and refreshes the counts.

Add location is a modal dialog (`add_location_dialog.scala.html`, it holds the pin editor below) requested by the top `#addLocationBtn` or, while there are no rows, by the centered
`#addFirstLocationBtn`; Add category also opens a modal from `#addCategoryBtn`. The renderer builds all three into their hosts and shows one host or the
other. Rename, Delete and Move to category open separate modals from the row's menu (`#locationMenuCtrl-{id}`
is the trigger they return focus to; Delete returns focus to `#addLocationBtn`, since the row goes
away); deleting the viewed Location runs a search back to the whole repository, and deleting a
category leaves its Locations at the top level, a viewed one still green. `locationAdded` /
`categoryAdded` / `locationRenamed` / `locationMoved` / `locationDeleted` reload the list, which
restores the expanded categories and focus by ID, or focus to the visible add control when the
dialog's return control was hidden by the change (`focusAddLocationControlIfFocusLost`). Add
completes with `dialogSuccessResponse` naming the new Location's `categoryId` (null at the top level),
and the `locationAdded` listener expands that category (`expandLocationCategory`) after the reload; a
move into a collapsed category leaves it collapsed, as moving a folder under a collapsed parent does. Add and Move share
`includes/location_category_select`, which offers categories only and `(none)` for the top level; Add to
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
was resized (the Split.js drag, the window), and removes the map. The initial view is the one last
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
`data-app-fragment="modal"` and `data-app-modal-title`; the shared host supplies their heading and
close control. Add and Rename submit with Return and retain `hx-target="this"`, `hx-swap="none"`,
and `hx-json-enc`, so validation replaces the active form with its submitted values and errors.
The modal focuses the name field (selecting it for Rename), or the category selector for Move.
Delete leaves initial focus on the close control, so opening the confirmation cannot activate its
destructive button. Rename and Move return focus to the row's ⋯ button; Delete names a control
that survives the mutation (the parent folder's ⋯ or the list's Add button). Add returns to its
opener; for a menu action the modal owner remembers the visible ⋯ trigger. Re-hydrating validation
preserves that original return control and anchor. Escape and the host's X dismiss the dialog.

**Inline dialogs** — Only View settings uses `js/fragments/inline-dialog.js`, which gives it initial
focus and binds its checkboxes. It has no operation lifecycle; dismissal follows the popover's
rules and Escape returns focus to the trigger.

**Snackbar** — Always use `showSuccessSnackBar` / `showWarningSnackBar` / `showErrorSnackBar` from
`js/common/snackbar.js`. Messages render as plain text via `textContent`; pass raw text, including
user-supplied names and server errors, without HTML markup or pre-escaping. Auto-dismisses after 3 s.

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
`<div class="page-spacer" data-app-search-after="<cursor>">` (`""` for the first page; `flex-basis:
100%` in `includes/search_results.scala.html`) as tall as the top of its first cell to the bottom of
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

**Group headers** — A grouped grid (`htmx/results_grid_grouped.scala.html`) opens each group, a day or
a Location, with a `.result-group` header: a checkbox over the group; its name - a
`<time datetime="yyyy-MM-dd">` with the server-formatted day (**Saturday, January 2, 2025**), "No date",
`<span class="category">Italy</span> › Rome` (the category dimmed), or "No location"; `data-group-key`
naming it; and the group's full match count across every page in `.count` — as text (**(3 items)**,
**(1 item)**) with the number itself in `data-count`, which is what the client reads and decrements. The header spans
the row and is `position: sticky` at the top of `#content` (CSS only, in
`static/css/includes/search_results.css`; `#assets` is an isolation root so the drag stand-in still
paints above it, and the background carries `--view-tint` so it matches the triage and trash panes).
A continuation of a group already on screen repeats no header, so the new cells read as the same
group. When the page window folds a page (**Infinite scroll + lazy load**), the header of the group
still open at the page's end stays in place before the spacer, as the page record's `keptHeader`, but
only when the next page continues the group with no header of its own; a group closed with the page
keeps nothing. The kept header keeps sticking over the group's loaded cells and its checkbox keeps
counting them (a spacer is not a header, so the walk below passes it), and it is removed when the
page reloads with its own headers, or stays for the group's cells on the next page when the reload is
a 204. The one client-side change to a header is its count: `removeAssetsFromGrid`
(`js/assets/asset-actions.js`) calls `decrementDateGroupOf(cellEl)` from
`js/search-results/date-groups.js` before a cell leaves, which walks back to the nearest header,
takes one off, and removes the header at zero; a header whose loaded cells are all gone but whose
count is positive stays, since the group has matches on pages not loaded yet. Under Group by Location
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
(`hidden`, `src` cleared), waits for `load` or `loadedmetadata`, and hands the stored width and height to the box, which
CSS fits into the viewport below the toolbar and refits on resize. `showMedia` starts a shown Video with `play()`,
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

**Metadata field visibility** — Fields default to `display:none`. Use
`window.ctx.addGridMetadataField(name)` / `removeGridMetadataField(name)`, which mutate the
reactive `gridMetadataFields` set and persist it to `localStorage`. A reactive effect per displayed
grid (`js/search-results/metadata-visibility.js`) keeps one `show-<field>` class per visible field
on `#assets`, and the CSS in `static/css/includes/search_results.css` shows the matching
`.metadata > .<field>` rows: no per-cell work, nothing to re-apply for appended pages.

**Settings screen** — `settings.scala.html` (`/settings/r/:repoId`, an Admin's page, `SettingsController`) is shaped like
the import page: `header_common`, the shared nav, one `main`, `html_common` for the snackbar, `json-enc.js`, and the page
module `js/settings-page.js`, which registers the htmx request rule with a minimal app and the save listener
(`js/listeners/settings.js`), and `highlightNav("settings")`. `main` holds the one form, `htmx/settings_form.scala.html`
(`#settingsForm`), rendered by `SettingsFormController.form` for the page and again by its save, `POST
/htmx/settings/r/:repoId`: `hx-target="this"`, `hx-swap="outerHTML settle:0"`, `hx-json-enc`,
`data-app-success-event="SETTINGS_SAVED_EVENT"`. Its sections are the Setting groups, each a grid of label and control
rows (`.setting` is `display: contents`, so the labels of a section line up); a field is a text input, or a checkbox with
`value="true"` for a flag, named after its Setting (`field-<name>`), followed by its Reset button, its error, a restart
notice ("Saved X, running Y until the server restarts") for a Restart setting whose saved value is not the running one,
and its help text ending in `Default: …`. The Settings of the other engine are not rendered (PostgreSQL only:
`face.recognition.hnsw_ef_search`, `db.postgres.read_statement_timeout`). Reset is `data-app-reset-setting`, handled by one
delegated click listener in the page module that fills the input from the row's `data-setting-default`
(`Const.attributes.settingDefault`) and clears its `error` class; nothing is written until Save. A failed validation comes
back as a validation replacement (`HX-Retarget: this`, see above) with the submitted values kept and each error beside its
field, so no success event fires; a save answers the saved form and `SETTINGS_SAVED_EVENT` shows "Settings saved". The CSS
is the partial's own file, `static/css/htmx/settings_form.css`, over `core.css` variables (`--section-vertical-space`, `--control-*-space`,
`--theme-muted`, `--warning-color`); the page adds none. `includes/nav` shows the Settings link (`.menu.settings`)
to an Admin only (`RequestContext.account`), so `NavController` requires login and both nav loads happen behind one.

**Setup background** — `setup.scala.html` (`/setup`, `SetupController`; the index redirects here until the repository
exists) runs a full-screen animated scene behind the page, a top-down view of a jet over clouds and farmland. Its canvas,
`<canvas id="setupBackground">`, is the first element of the body; `static/css/pages/setup.css` fixes it over the whole
viewport at `z-index: -1` (above the root's background, below every element of the page, so the markup is otherwise
untouched), `pointer-events: none`, and `opacity: 0` with a 600 ms transition to `.ready`, so the form never sits on a
flash of blank canvas. The form's container (`:where(body.setup) main > div`) is a frosted card: a radial gradient
from `--setup-form-base`, opaque at the center, to `--setup-form-card-edge-color`, the same color at zero alpha, at
the corners, over `backdrop-filter: blur(12px)`, so the center is solid and the edges frost into the scene;
`--setup-form-card-border`, the outline in the form's accent; a 6px `border-radius` (a radius, not a spacing, so a
literal, as is the blur's); `--section-vertical-space` padding. The card and the form on it are a carve-out from the
theme: `static/css/htmx/setup_form.css` styles the fields, their placeholder, hover, focus, read-only and error
states, the labels and the full-width submit button from the `--setup-form-*` tokens alone, so the form keeps its
palette whatever the theme colors become. The page's palette, the scene's `--setup-background-*` tokens and the form's
`--setup-form-*` tokens, is declared at the top of `pages/setup.css` on `body:where(.setup)`: the page's own, not
`:root` tokens of `core.css`, literals with no `light-dark()` pair (the form's edge, border, focus and field tokens
follow its three colors, base, muted and accent), inherited by the canvas and the form. The style guide's scan
leaves `setup.css` out (`StyleGuideController.excludedFiles`), so the palette is on no card and in no audit; its
uses of core tokens are the spacing steps, `--text-shadow`, and the error box's `--theme-base`, `--error-color`
and `--theme-text`. The brand bar stays opaque; the footer loses its
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
the right) and `strobe` (`--theme-text`, the white strobe). It then reads the canvas's CSS size from
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

**Nav refresh** — After any successful asset mutation, reload the nav to update counts.
Folder deletion also recycles assets throughout its subtree, so the `folderDeleted` listener
calls `app.reloadNav()` before awaiting the folder-tree refresh. Reuse `FrontendApp.reloadNav()`
from event listeners; it loads the nav fragment with:
```js
htmx.ajax("GET", `/htmx/nav/r/${this.context.getRepoId()}?search=true`, { swap: "innerHTML", target: "nav" })
```

`includes/nav` renders the Search input only when its `search` parameter is true: `index.scala.html`
passes it, and `search=true` asks `NavController` for it on a reload, so the main page's nav keeps
its input while the import page's nav (and its polling reload) has none. The form is `hx-preserve`,
so the swap keeps the element itself, with its listeners and the text being typed.

Asset mutations (move, recycle, purge, restore, sorting from triage) also call
`app.reloadFolderCounts()`, which runs `refreshFolderCounts(repoId)` from `js/common/folder-tree.js`:
it re-fetches the tree JSON and patches each `#folder-count-{id}` in place, leaving expansion, focus,
and open menus alone. If the response holds a folder the DOM lacks (restoring assets can un-recycle
their folders), it falls back to a full render. Folder operations (add, rename, move, delete) keep
using `reloadFolderTree`, which renders fresh counts as part of the rebuild. `app.reloadAlbumCounts()`
and `app.reloadLocationCounts()` do the same for the album and Location lists (`refreshAlbumCounts`,
`refreshLocationCounts`), and fetch nothing while another explorer tab is active.

Asset move/recycle/purge/restore and album and Location membership UI flows are implemented in `js/assets/asset-actions.js`.
A restore answers `{restored, duplicates}`: an asset whose content was imported again after it was recycled stays in
the trash, so only the `restored` cells leave the grid and the snackbar warns about the rest.
Drag/drop interact.js bindings live in `js/dragdrop/`. Event-listener modules call these
coordinators directly via `app.assetActions` / `app.searchDetailCoordinator`, while
`FrontendApp` remains the composition root that wires them together.

**Themes** — The app has a light and a dark theme and ships in the dark one: `:root` in `core.css` declares
`color-scheme: dark`, as does the `color-scheme` meta tag of `includes/header_common`, and only the style guide's theme
switcher and the design tweaks panel's toggle declare another scheme, on the page shown. Every color is a `:root` token
of `core.css`. `:root` opens with the theme colors: two seeds, `--theme-base` (the app's surface, whose hue is the
neutrals': the accent's at a low chroma) and `--theme-accent` (the one hue: selection, drop targets, chips and pins),
each a literal `light-dark(light, dark)` pair, and the roles derived from them, each a `light-dark()` pair of two OKLCH
relative-color recipes, one per scheme, so the browser resolves the pair by the scheme in force and no rule is written
once per theme. From the base: `--theme-sunken` (a surface below it: a read-only field), `--theme-hover` (under a tab
or a row the pointer is over), `--theme-raised` (a surface lifted off the base), `--theme-faint` (the faintest thing
still seen, 3:1 on the base: a quiet control's edge, disabled text), `--theme-muted` (secondary text and the edge of a
control or a cell, 4.5:1 on the raised surface too), `--theme-text` (text on the app's own surfaces), `--theme-field`
and `--theme-on-field` (an input's field, the lightest surface in either theme, and the text typed into it). From the
accent: `--theme-accent-text` (the accent as text or a thin edge on a surface, 4.5:1 where the accent itself is not:
the filter chip) and `--theme-on-accent` (text and glyphs on the accent fill: a pin's path, a drop target's row, the
checkmark of a selected cell; dark in the dark theme, since the accent cannot carry light text). Each recipe sets a
lightness per role and takes the seed's hue, so editing a seed moves the whole chrome and the neutrals stay one hue. A
rule writes a theme color by its token (`color: var(--theme-text)`, `border: 1px solid var(--theme-muted)`); no token
aliases a theme color under another name or mixes one at a percentage outside `:root`. Two kinds of color stand
apart, with literal pairs of their own: the three status colors (`--success-color`, `--warning-color`,
`--error-color`), the text, icon and edge of a status, at one lightness per theme and at least 4.5:1 on the base,
which a rule writes directly too (an unnamed person's name and the trash entry of the nav are `--warning-color`), and
from which the status fills derive by the chrome's one recipe (`--success-background-color: light-dark(oklch(from
var(--success-color) calc(l + 0.4) calc(c * 0.3) h), oklch(from var(--success-color) calc(l - 0.29) calc(c * 0.8) h))`:
in the dark theme the color 0.29 darker in OKLab lightness at 80% of its chroma, in the light theme 0.40 lighter at
30%; a fill carries `--theme-text` in either theme and its status color only as an edge, since the color on its own
fill reads about 3:1; the same for the three); `--shadow-color`, the ink
of the shadows and scrims, each at the alpha it needs (`--scrim-color: rgb(from var(--shadow-color) r g b / 0.45)`).
The only other color tokens are the veils, a theme color or `--shadow-color` at an alpha in relative color syntax,
tokens because a translucent color cannot be written as a theme color: `--scrim-color` and
`--asset-detail-backdrop-color` (`--shadow-color` at 0.45 and 0.9) and `--dnd-dragging-background-color`
(`--theme-muted` at 0.48); a veil follows the pair of the color it reads. A token holding a border or a shadow keeps
its width and style with its color, which is a theme color (`--control-border: 1px solid var(--theme-faint)`,
`--focus-ring: 2px solid var(--theme-accent)`, `--list-trace-border`; `--border-thin` and `--border-thick` are solid
`--theme-muted`) or `--shadow-color` at an alpha (`--panel-border: 1px solid rgb(from var(--shadow-color) r g b / 0.18)`,
the edge of a chrome panel and the divider inside one; the shadows: `--drop-shadow`, the one shadow under a dialog, a
panel, a pin or the checkmark, `--text-shadow`, whose light side is the ink at alpha 0 since a dark halo has no place
under dark text, and `--box-shadow`, `--theme-text` at 0.07, the light line under a panel's edge and a button's hover
look). No two border or shadow tokens hold one value: a rule that needs an edge or a shadow writes the token that has
it, and a new one is a new look, described here. What is drawn on a status fill or over an image (the label
of a button or a marker, the snackbar's text, a pin's frame) reads `--theme-text`; what is drawn on the accent fill
reads `--theme-on-accent`. A light theme is therefore the two seeds' light sides, the light recipe of each role, and
the light sides of the three status colors and `--shadow-color`; both sides are real, and the style guide's switch
shows either. Keyboard focus is one ring: `:focus-visible { outline: var(--focus-ring); outline-offset: 2px }` in
`core.css`, on every element, inset (`outline-offset: -2px`) where a panel clips its rows; the browser withholds it
after a pointer, including the focus a closing dialog returns to its trigger, so no rule removes an outline. The setup
page's palette is no `:root` token: `pages/setup.css` declares the `--setup-*` tokens on `body:where(.setup)`, as
literals with no pairs, since the page is an illustration and a form in a look of its own, which it keeps in either
theme (see **Setup background**), and sets `--focus-ring` there to the form's accent.

Stylesheets reference the tokens and write no color literal, named colors included. A new color is a theme color
written in the rule, or a new veil token, described here, when it must be translucent. The rule that a new
value is a new token with a meaning, or an existing token whose meaning fits rather than one that merely holds the same
value, is for lengths: the `--space-*` steps and the role tokens over them. A color a rule takes from a token at an
alpha is written in relative color syntax too, so it follows the token's pair: the tints of the triage and trash views
are `rgb(from var(--success-background-color) r g b / 0.07)` and `rgb(from var(--warning-color) r g b / 0.05)`.
The two SVG data URIs of `static/css/includes/search_results.css` (the check of a selected asset, the arrow of a
dropdown) carry their fill as a literal, because a data URI cannot read a token.

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
  **Theme colors** (`--theme-*`: the two seeds as literal pairs, the roles as recipes with their From line),
  **Status and shadow** (the status colors, their fills and `--shadow-color`) and **Derived**
  (the veils, `--scrim-color`, `--asset-detail-backdrop-color` and `--dnd-dragging-background-color`, each a theme
  color or `--shadow-color` at an alpha). The lengths are
  **Scale** (`--space-*`), **Roles** (a length referencing a step, which its From line names) and **Sizes** (any other
  length, such as a width or the two radii, `--radius` and `--radius-small`); a value of space-separated lengths, such as a padding shorthand, is a length,
  drawn as a padded "Padding" sample. The typography tokens (`--font-size-*`, `--font-weight-*`, `--letter-spacing-*`,
  `--line-height-*`) are a kind by name rather than by value, since a font size is a length and a weight a bare
  number, each drawn on sample text in the Typography section (`@tokenCards("typography")`). "Off palette" marks a color, border or shadow token outside the Theme colors
  and Status and shadow groups whose declaration writes a color literal (a hex color, a color
  function with its channels written out, or a named color), which keeps the palette at the seeds and the recipes over them; a relative-color recipe is no literal. "Same value as"
  names the tokens of the same kind (color, border, shadow or font) that resolve to the same value (a color to the
  same color in both themes). Two tokens that take their color from the same tokens are not flagged, since two
  tokens reading one color share it by design, while two recipes computing one color are, as they should be one token; a
  token without a color is compared by its value alone.
- **Hand-written, keep in sync:** the specimens. **A new or changed shared primitive in `core.css` or `tabs.css` (a
  class meant for reuse) adds or updates its specimen in `style_guide.scala.html` in the same change.** Specimens
  use the real classes; the page's own styles (`static/css/pages/style_guide.css`: `sg-` classes, and its bare elements
  under `body.style-guide`) only lay the page out, from the `:root` variables.
  Three more things keep the page current:
  - A changed size, font, or icon set updates the values the specimens state in text.
  - `core.css` carries no comments beyond its boxed section headings (`/* NAVIGATION */` and the like): a token or a
    rule is documented in this guide and in the style guide's prose, never beside its declaration, so the two cannot
    drift. The guide would show a comment directly above a `:root` token as its note; `core.css` has none, by design.
  - A new section also needs its `#styleGuideToc` link and its ID in `StyleGuideControllerTests`.
- **Live specimens:** an Add button and a ⋯ menu built with `buildModalTriggerCtrl` / `buildContextMenuCtrl`
  (`initStyleGuide()`, after the app has started: no template, so they are the explorer's own markup), both
  opening `htmx/style_guide_sample_dialog.scala.html` in the modal host. A blank name returns the validation
  replacement; a name completes and dispatches `Const.events.styleGuideSampleSubmitted`, which the page answers with
  a snackbar. Nothing is persisted.
- **Theme switcher:** the Light / Dark `.toggle-group` at the top of the contents column (`#styleGuideTheme`), which
  stays on screen. `setTheme` declares `color-scheme` inline on the root element, which every `light-dark()` token
  follows (`applyTheme` and `rememberedTheme` of `js/common/theme.js`),
  remembers the choice in `localStorage` (`Const.localStore.styleGuideTheme`), and resolves the tokens again:
  the color a token holds and its alpha are those of the theme shown, while the tokens sharing its value are compared
  in both themes. The page opens in the remembered theme, else in the stylesheet's. Only the style guide reads the
  choice; the rest of the app stays in the stylesheet's theme.
- **Font tester:** the Typography section's right half (its left half holds the `--font` card, the type scale's
  cards, the sizes on the real components and the text colors). The typed value becomes the lorem specimen's `font-family`, and the status says whether the browser
  has that font, decided by measuring the text with the value in front of each generic family against the generic
  family alone (`isFontAvailable` in `js/common/font-availability.js`). The input is the component's `fontInput`
  (`x-model`), which the specimen's style and the status
  derive from. Nothing is requested from the server.
- **Alpha slider:** the `--scrim-color` card (`ALPHA_SLIDER_TOKEN` in `js/style-guide.js`) has a slider under its
  copy button. Dragging it replaces the alpha of the token's relative color (`rgb(from var(--shadow-color) r g b / 0.6)`
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
Style Guide button) that changes the value of any `:root` token of the bundle live, on the real markup and data of the
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
  stylesheet to read, `"/assets/"` (the bundle, by href substring); the trigger `#designTweaksBtn` (the nav's Tweaks
  entry, a `div.menu` like the empty Triage entry, so it needs no style of its own; the package listens for it on the
  document, so a reloaded nav keeps working) and the shortcut `Ctrl+Alt+T`; `sources: "/style-guide/scan.json"`, the
  server's scan (locations and notes for the CSS export, the literals for the Not reachable tab); the sections in the
  style guide's order (Theme colors `--theme-*`; Status and shadow, the status colors, fills and `--shadow-color`;
  Derived, the other colors; Typography, the fonts and the typography kinds; Scale, the steps; Roles, the lengths that
  reference a step; Sizes, the other lengths; Borders and shadows; Panels; what none claims goes to the automatic
  Other); the spacing scale `--space-<n>`; the type scale, `--font-size-{xs,small,base,medium,large,xl,display}` from
  `--font-size-base`; no `seed`: the package's seed is a formula-driven control (a configuration names the hue and
  lightness tokens its sliders start from and, per target token, a formula from the seed), which Altitude does not
  configure, since the two seeds have the full sliders and the stylesheet derives the other theme colors from them,
  which the panel edits as recipes, channel by channel, the source as well, or detaches to a literal; the two tokens
  the server sets inline on their containers as scoped tokens, `--preview-box` on `#assets` (200px, labeled
  `Const.AssetView.PREVIEW_BOX_PIXELS` in the exports) and `--face-box` on `#people` (80px,
  `FaceDetectionService.faceDetectionBoxPx`), 40..600 by 10, in Panels; the explorer width as an extra control in
  Panels (no token: `explorerPercent`, `setExplorerSizes` and `hasExplorerSplit` of `js/common/explorer-split.js`,
  10..70 %, default 25, "Set on the main page, where the explorer is" elsewhere, phrased as the Split sizes in the
  prompt); and `exports: { fileName: "core.css" }`.
- **How an override applies:** one `CSSStyleSheet` in `document.adoptedStyleSheets`, rewritten on every change: a
  marker rule first, `:root { --tokentweak: designTweaks }` (the storage key), then one `:root` rule with every tweaked
  token, then one `!important` rule per scoped token on its container. Adopted sheets cascade after the document's, so
  they win over the bundle at equal specificity, with no `!important` on the root tokens and no `@layer`. The explorer
  width is applied through `setExplorerSizes`, at `load` when the page has a split.
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
  `Environment.isDev` in `header_common`, the button in `nav`, and the panel's stylesheet is constructed in the
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
| `static/js/common/modal.js` | modal owner: `openModal`, `closeModal`, open identity (`isModalOpenActive`), the element that requested an open (`getModalOpenSource`), explorer dialog anchoring, `setAssetDetailSize` |
| `static/js/common/video-playback.js` | the asset-detail Video beyond its element: `unloadVideo`, and the Media Session (`describeInMediaSession`, `clearMediaSession`) |
| `static/js/common/anchored-panel.js` | viewport placement shared by popover menus and anchored explorer modals |
| `static/js/fragments/dialog-operations.js` | lifecycle of the operations every dialog submits; fragment kinds register their `isActive`/`close` |
| `static/js/fragments/inline-dialog.js` | View settings fragment shown in its popover |
| `static/js/common/snackbar.js` | `showSuccessSnackBar`, `showWarningSnackBar`, `showErrorSnackBar` |
| `static/js/search-results/selection.js` | the `selectedAssets` store (reactive set of IDs, paints the cells) and the grid's delegated click listener |
| `static/js/alpine/components/date-group-selectable.js` | Alpine component for a group header's checkbox: selects its group's loaded cells (a day or a Location) as a set |
| `static/js/search-results/box-selection.js` | box selection: Viselect gesture on the grid, committed through the store on release |
| `static/js/search-results/infinite-scroll.js` | `loadNextPage` and the observer on the last cell, watched only while nothing follows it; `pendingCursorOf` and `observeContinuationCell` for the page window |
| `static/js/search-results/page-window.js` | the window of loaded pages: folds pages a viewport above into `.page-spacer`s, reloads a spacer as a continuation when it nears (`reloadSpacer`), drops pages a viewport below, keeps the group header still open at a fold, and corrects the scroll position itself |
| `static/js/search-results/lazy-images.js` | loads thumbnails within a viewport, placeholders them once more than a viewport away, the first page's included |
| `static/js/search-results/metadata-visibility.js` | keeps the `show-<field>` classes on `#assets` in step with the metadata fields set |
| `static/js/listeners/htmx-requests.js` | the outcome of every htmx request: failures, validation replacements (no event), declared success events, tab selection, fragment hydration |
| `static/js/settings-page.js` | the Settings page module: the request rule over its one form, the save listener, the Reset buttons |
| `static/js/listeners/settings.js` | `SETTINGS_SAVED_EVENT` → the success snackbar |
| `views/settings.scala.html` | The Settings page: nav, `main` with the form, `html_common` |
| `views/htmx/settings_form.scala.html` | The one Settings form: sections per group, a field per applicable Setting with Reset, error, restart notice and help |
| `static/js/setup-page.js` | the setup page module: starts the background scene on `#setupBackground`, nothing else |
| `static/js/setup-background/setup-background.js` | the page side of the setup background: `startSetupBackground` (feature detection with no fallback, the palette probed to `[r, g, b]` channels, the module Worker and the transferred canvas, `resize`, `visible` and `motion` posted as they change, `.ready` on the Worker's first frame) |
| `static/js/setup-background/setup-background-worker.js` | the Worker of the setup background: the scene on the OffscreenCanvas (the ground's bands and cells, the clouds and their shadows, the jet and its lights), the clamped clock with one pending frame, the first frame drawn before `ready`, the still under reduced motion and the pause of a hidden tab, the refit on `resize` |
| `static/js/setup-background/setup-background-textures.js` | the ten textures of the fields, each painting a cell from its corner: tiles repeated by a pattern (furrows, crop rows, tramlines, strip crops, an orchard, pasture, contours) and the headland and the centre pivot drawn to the cell |
| `views/setup.scala.html` | The setup page: the scene's canvas first in the body, the brand bar, the message hosts, `main` with the setup form (`htmx/setup_form`) on the frosted card, the footer; its layout, the canvas's placement and fade-in, the card and the footer are `static/css/pages/setup.css` |
| `static/js/fragments/dialog-openers.js` | `data-app-open-dialog` buttons opening a page-held `<template>` dialog in the modal host |
| `static/js/search-results/click-suppression.js` | swallows the click the browser fires after an asset drag or a box gesture |
| `static/js/alpine/components/context-menu.js` | Alpine component coordinating a folder's, album's or Location row's native popover menu; closes a menu from outside (Escape, ancestor collapse, modal open) |
| `static/js/stores/search-params.js` | the search parameter set, its defaults, and the scope rules that decide what a change clears |
| `static/js/search-results/search.js` | `runSearch` — the single entry point for every search request |
| `static/js/search-results/search-triggers.js` | binds `data-app-search` elements to `runSearch` |
| `static/js/search-results/search-input.js` | `bindSearchInput`: the nav's Search input (`#searchForm`), submitting and clearing `q` and following the store |
| `static/js/search-results/detail-navigator.js` | next/previous over the grid's cells, reloading a folded page met on the way, media loading in the asset-detail modal, and a shown Video's playback (autoplay with a muted fallback, Space's play/pause) |
| `static/js/search-results/date-groups.js` | keeps a group header's count current as cells leave the grid |
| `static/js/search-results/cells.js` | every cell of an asset (`cellsOf`, `thumbnailsOf`) and the asset of a cell (`assetIdOf`): a Location grouping holds an asset once per Location |
| `static/js/common/folder-tree.js` | renders the folder tree, its recursive asset counts (`numOfAssets` in the tree JSON, after each name), and each folder's menu from the JSON tree endpoint; patches the counts in place after asset mutations |
| `static/js/common/context-menu-markup.js` | builds the ⋯ menu cell of a folder, album or Location row and the dialog-trigger buttons |
| `static/js/common/album-list.js` | renders the album list, its counts (after each name), and each album's menu from the JSON list endpoint; patches the counts in place; marks the viewed album |
| `static/js/common/location-list.js` | renders the categories (expandable nodes holding their Locations) and top-level Locations, the counts after each name, and each row's menu from the JSON list endpoint; restores expansion across rebuilds; patches the counts in place; marks the viewed Location |
| `static/js/fragments/add-to-location.js` | fills the Add to location dialog's hidden selection field and keeps its success detail naming the chosen Location (ID and label) |
| `static/js/fragments/location-editor.js` | the Location pin editor: Leaflet map in the Add location modal, click and drag to place the pin, hidden coordinate inputs, readout, place-name search |
| `static/js/common/asset-count.js` | the `(n)` asset count cell placed after a row's name, shared by the folder tree, the album list and the Location list |
| `views/includes/html_common.scala.html` | Snackbar + the two Alpine-bound modal hosts |
| `views/style_guide.scala.html` | The dev-only style guide: static specimens of the shared primitives, and the Alpine templates of the tokens, the icons and the token audit; its own layout is `static/css/pages/style_guide.css` |
| `static/js/style-guide.js` | the `styleGuide` Alpine component (the source scan resolved in the browser: tokens, icons, token audit; the theme switcher's and the font tester's state) and the style guide's live menu and dialog specimens |
| `static/js/common/token-kinds.js` | the kinds of token (`TOKEN_KINDS`, `kindOf`) and their groups (`groupOf`, `paletteGroupOf`), the token references of a declaration (`referencedNamesOf`), for the style guide |
| `static/js/common/theme.js` | the two themes: `applyTheme`, `rememberedTheme`, `inTheme`, `resolveColor`, and the two colors of a `light-dark()` pair (`themeColorsOf`, `withThemeColor`) |
| `static/js/common/font-availability.js` | `isFontAvailable` and `asFontFamily`, the font tester's check |
| `static/js/common/explorer-split.js` | the main page's Split.js instance over the explorer and the content area: `initExplorerSplit(view)`, the remembered sizes, `setExplorerSizes(percent)`, which the design tweaks adapter passes as an extra control |
| `static/js/design-tweaks.js` | the design tweaks panel's adapter: `createTokenTweaks` of the vendored tokentweak package with Altitude's storage key, host id, stylesheet, trigger, shortcut, sources URL, sections, scales, the two scoped tokens, the explorer control and the export file name |
| `static/js/lib/tokentweak.js` | the tokentweak package (vendored, dev only): the panel, its store and adopted sheet, the controls per kind, Inspect, the exports; its options are its README's |
| `static/js/lib/tokentweak-boot.js` | tokentweak's boot script (vendored, dev only): adopts the stored override sheet and theme before the first paint, keyed by its tag's `data-key` |
| `views/includes/search_results.scala.html` | Search grid wrapper with the Group and Sort controls, the grid / map layout toggle and the "Map area ×" chip; the controller passes in the rendered grid partial, or the map shell. Its CSS (`static/css/includes/search_results.css`) gives `#assets` `overflow-anchor: none` and the page spacer its full row; `#assets` carries the preview size as `--preview-box` for the cell size |
| `views/htmx/result_cell.scala.html` | One asset cell, shared by both grids, with no Alpine of its own; a page's last cell carries the cursor of the next page, and the thumbnail its preview URL in `data-src`, a first page's in `src` too. `data-media-type` names what the asset is; the cell of an asset with a duration, a Video or an animated GIF, wears a play badge over its Preview and a `duration` metadata row (`Util.humanReadableDuration`, `m:ss` or `h:mm:ss`), shown by the View control's Duration checkbox like every other field |
| `views/htmx/results_grid.scala.html` | The ungrouped grid: the page's cells, infinite-scroll trigger by cursor |
| `views/htmx/results_grid_grouped.scala.html` | The grouped grid: a group header per day or Location, infinite-scroll trigger by cursor |
| `views/htmx/map_view.scala.html` | The map layout's shell: `#map` with the bounds and tile settings, and the crowded-pin panel `#mapPanel`; its CSS, the pin styles included, is `static/css/htmx/map_view.css` |
| `static/js/common/vendor-loader.js` | loads Leaflet (with its stylesheet), supercluster and media-chrome once, when a view first needs them |
| `static/js/map/map-view.js` | the map view: Leaflet map, viewport cells requests, supercluster pins and their clicks, one map at a time (`disposeMapView`) |
| `static/js/map/map-panel.js` | the crowded-pin panel: `openMapPanel` runs the `bbox` search into `#mapPanelContent`; close, "Show only these in the grid" |
| `static/js/map/map-state.js` | the map view remembered per search scope in `sessionStorage`, and the displayed map's live view for the pin editor |
| `static/js/listeners/map.js` | puts `layout=map` back into the URL the server pushes for a panel request |

## Search parameters

Every search in the app — the initial load, folder and person navigation, the Search input, the Sort
and Group dropdowns, continuous scroll, the page the detail modal loads at the end of the grid — goes through
**one** function, `runSearch` in `js/search-results/search.js`. A caller supplies only the parameter
it knows about; the `searchParams` store supplies the rest. Nothing else builds a URL for
`/htmx/search/r/:repoId`.

`js/stores/search-params.js` owns the parameters (`view`, `folderId`, `personId`, `albumId`,
`locationId`, `q`, `bbox`, `layout`, `sort`, `groupBy`, `groupDirection`, `rpp`) and the rules for combining them:
choosing a folder, a person, an album, or a Location clears the other three, and a view clears all four. The store
holds no position: a search starts at its first page, and a later page is one request's `after` cursor. The map area (`bbox`, the crowded-pin panel's scope) belongs to the search it was
drawn on, so a view, a folder, a person, an album, a Location or a new `q` clears it too. Search text
(`q`) is one more place to look, the whole repository outside the trash: a change of `q` clears the
folder, person, album and Location and returns `view` to the repository view, and each of those four,
and a view, clears `q`. The whole `CLEARS` table:

| Changed | Cleared (back to its default) |
|---|---|
| `view` | `folderId`, `personId`, `albumId`, `locationId`, `bbox`, `q` |
| `folderId`, `personId`, `albumId` or `locationId` | the other three, `bbox`, `q` |
| `q` | `view`, `folderId`, `personId`, `albumId`, `locationId`, `bbox` |

The layout, the grouping, `rpp` and the sort narrow or reorder what is already in scope and survive
all of those: a chosen sort is kept from one place to the next, and with no sort chosen the server's
default applies (Relevance with text, newest import without). The two combinations the server refuses
are settled by the store itself (`withoutRefusedCombinations`, on seeding from the URL and after every
change): `q` with the trash view becomes the repository view, and `sort=relevance`
(`Const.search.sortRelevance`) without `q` drops the sort, so Relevance is the one sort that ends with
the text it ranked. The server also refuses a page size outside 1 to 500; the store keeps only whole numbers
in that range (`normalize`, with
`Const.search.maxRpp` the client's copy of `Const.Search.MAX_RPP`) and turns anything else into its
default, so a hand-edited `?rpp=1000` falls back to the server's page size instead of failing every
search. A parameter still at
its default is left out of the request, so a default is never spelled out on both sides — except
`view`, which is always sent, and whose values match `Const.Search.View.*` server-side verbatim.

The Search input is `#searchForm` in `includes/nav` (`role="search"`: an `<input type="search" name="q">`
and a Search button), rendered in the main page's nav only (see **Nav refresh**) and bound once by
`bindSearchInput()` (`js/search-results/search-input.js`) from `FrontendApp.start()`. Enter and the
button submit the form, which runs `runSearch({ params: { q } })` with the trimmed value; the input's
native clear (the `search` event with an empty value) and an empty submit clear `q` the same way, and an
empty submit with no `q` in the store does nothing, so it never leaves the folder or view being looked
at. The input holds no state: an Alpine effect writes `searchParams.q` into it, so a URL with `q` fills
it on load and choosing a folder, person, album, Location or view empties it. The page is laid out for
the view it was loaded with, so from Triage or Trash a text search is not a swap: the store is merged
and the browser navigates to `/r/<repoId>?<the store's query string>`, a full load of Browse that
seeds the store from that URL, layout and grouping included.

`includes/search_results` takes the Search text the results were matched by (`text`, set when `q` had a
usable term) and puts it on the fragment as `data-results-q`. The Sort dropdown offers "Relevance"
only then, selected when it is the effective sort, since the server
refuses it without text.

`groupBy` (`dateTaken`) with `groupDirection` (`asc`/`desc`) groups the grid by
day; the Group dropdown in `search_results.scala.html` sets both from its selected option, and "No
grouping" sets both to empty, which the store normalizes to `null`. A search has no page
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
<select data-app-search="change" data-app-search-from-value="sort">  <!-- parameter from the element's value -->
<select data-app-search="change" data-app-search-from-selected-option>  <!-- every data-app-search-<param> literal of the selected <option> -->
  <option data-app-search-group-by="dateTaken" data-app-search-group-direction="desc">
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

