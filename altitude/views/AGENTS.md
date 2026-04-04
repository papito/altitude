# Frontend – Agent Guide

## Stack

- **HTMX** — server-driven HTML fragments, no SPA routing
- **Alpine.js** — reactive state and UI behavior (`x-data`, `x-show`, `$store`)
- **No bundler** — all JS uses native ES modules (`<script type="module">`, `import`/`export`)
- Libraries are checked into `static/js/lib/` (htmx, Alpine, Split.js, interact.js)

## Template Layout

```
views/
  *.scala.html          ← full pages (index, login, setup, pipeline)
  includes/             ← reusable layout fragments (nav, header, batch ops, search results)
  htmx/                 ← HTMX partials returned by routes/web/partial/**
```

Full pages include `@includes.html.html_common()` (provides both modal containers and `#snackbar`)
and `@includes.html.header_common()` (loads HTMX, Alpine, core CSS).

HTMX partial templates live in `views/htmx/`. They are returned by the `routes/web/partial/**`
controllers as `"<!doctype html>" + template(...)`. They regularly include inline `<style>` blocks
(scoped styles for that component) and inline `<script type="module">` blocks for JS setup.

## JS Directory ↔ Template Mapping

| JS directory | Template(s) it serves |
|---|---|
| `js/folders/` | `htmx/folders.scala.html`, `htmx/folder_children.scala.html` |
| `js/search-results/` | `includes/search_results.scala.html`, `htmx/results_grid.scala.html` |
| `js/people/` | `htmx/people.scala.html`, `htmx/person.scala.html`, `htmx/person_inner.scala.html` |
| `js/person/` | `htmx/person.scala.html` (single person view) |
| `js/batch-ops/` | `includes/batch_ops.scala.html` |
| `js/service/assetService.js` | consumed by `js/search-results/event_handlers.js` |
| `js/common/` | shared: modal, snackbar, navigation, nodes |
| `js/models/folder.js` | DOM wrapper for folder tree elements |
| `js/alpine/components/selectable.js` | asset grid multi-select |

Each JS section has a consistent file split:
- `event_handlers.js` — custom DOM event listeners (`document.body.addEventListener(Const.events.*)`)
- `htmx_event_handlers.js` — HTMX lifecycle listeners (`htmx:beforeRequest`, `htmx:afterRequest`)
- `dragon-drop.js` — interact.js drag-and-drop wiring

## Alpine.js Stores (defined in `app.js`)

| Store key | Purpose |
|---|---|
| `Const.state.selectedAssets` | `Map` of currently selected asset IDs for batch ops |
| `Const.state.currentView` | Current view: `repository`, `triage`, or `trashbin` |
| `Const.context.repoId` | Active repo ID — set from Twirl via `window.ctx.setRepoId(...)` |
| `Const.context.gridMetadataFields` | `Set` of metadata field names shown in the grid; persisted in `localStorage` |

Always access the repo ID via `window.ctx.getRepoId()`, not directly from the store.

## DOM Addressing Convention

JS code reads element identity from custom `alt-*` attributes — **never** positional or
class-based selectors. All attribute names are defined in `Const.attributes` (`constants.js`):

```js
Const.attributes.folderId       // "alt-folder-id"
Const.attributes.assetId        // "alt-asset-id"
Const.attributes.numOfChildren  // "alt-num-of-children"
Const.attributes.expanded       // "alt-expanded"
Const.attributes.dataSrc        // "alt-data-src"  (lazy-load image URL)
```

The `Folder` class in `js/models/folder.js` is the canonical DOM abstraction for folder tree
nodes. Construct it with an ID: `new Folder(id)` — it wraps `#folder-{id}`, `#children-{id}`,
`#menu-{id}`, `#folderName-{id}`.

## Event Flow

Two-layer event bus, both on `document.body`:

1. **Custom DOM events** — string names in `Const.events`, dispatched via `new CustomEvent(...)`,
   handled in `event_handlers.js`. These are business-level actions (e.g. `FOLDER_MOVED_EVENT`).
2. **HTMX lifecycle events** — `htmx:beforeRequest` / `htmx:afterRequest` — handled in
   `htmx_event_handlers.js`. Used to toggle fold/expand state and short-circuit requests when no
   server round-trip is needed.

Batch ops escalate a single-asset drag to a batch when selected assets exist: the
`assetMoved`/`assetTrashed` handlers in `search-results/event_handlers.js` re-dispatch
`batchAssetsMoved`/`batchAssetsRecycled` if `selectedAssets` store is non-empty.

## UI Patterns

**Modals** — Two containers live in `html_common.scala.html`. Load into `#modalContent` for general
modals; load into `#imageDetailModalContent` for asset detail. Use `showModal()` /
`showAssetDetailModal()` / `closeModal()` from `js/common/modal.js`. ESC key is wired globally in
`global.js`.

**Snackbar** — Always use `showSuccessSnackBar` / `showWarningSnackBar` / `showErrorSnackBar` from
`js/common/snackbar.js`. Auto-dismisses after 3 s.

**Infinite scroll + lazy load** — The last `.cell` gets class `last-cell`; HTMX fires on
`intersect` to load `?page=N`. Images use `alt-data-src` instead of `src`; `IntersectionObserver`
in `infinite-scroll.js` swaps `src` in/out as cells enter/leave the viewport. Always call both
`initLazyLoad()` and `initInfiniteScroll()` in the search results inline `<script>`.

**Metadata field visibility** — Fields default to `display:none`. Use
`window.ctx.addGridMetadataField(name)` / `removeGridMetadataField(name)` to persist to
`localStorage`. The `viewSettingChanged` event applies visibility changes to
`#assets .metadata > div.{fieldName}`. Call `showOrHideAssetGridMetadata` on initial load.

**Nav refresh** — After any asset mutation, reload the nav to update counts:
```js
htmx.ajax("GET", `/htmx/nav/r/${window.ctx.getRepoId()}`, { swap: "innerHTML", target: "nav" })
```

## Key Files

| File | Purpose |
|---|---|
| `static/js/constants.js` | All string constants: events, attributes, store keys, view names |
| `static/js/app.js` | Alpine init, store registration, `initApp()` entry point |
| `static/js/context.js` | `window.ctx` — repo ID and metadata field settings |
| `static/js/models/folder.js` | DOM wrapper around folder tree nodes |
| `static/js/common/modal.js` | `showModal`, `showAssetDetailModal`, `closeModal` |
| `static/js/common/snackbar.js` | `showSuccessSnackBar`, `showWarningSnackBar`, `showErrorSnackBar` |
| `static/js/alpine/components/selectable.js` | Alpine component for per-asset selection |
| `views/includes/html_common.scala.html` | Snackbar + modal DOM containers |
| `views/includes/search_results.scala.html` | Search grid wrapper with sort controls |
| `views/htmx/results_grid.scala.html` | Individual asset cells, infinite-scroll trigger |

