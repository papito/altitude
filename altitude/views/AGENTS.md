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
(scoped styles for that component). Prefer declarative `data-app-*` attributes over inline
`<script type="module">` blocks when the behavior can be hydrated centrally from
`js/frontend-app.js`.

## JS Directory ↔ Template Mapping

| JS directory | Template(s) it serves |
|---|---|
| `js/fragments/` | centralized hydration for declarative HTMX fragments (`data-app-fragment="..."`) such as modal, image-detail, and inline editor fragments |
| `js/listeners/` | domain-focused `document.body` event registration for folders, people, assets, and HTMX/search lifecycle wiring |
| `js/search-results/` | search-results helpers such as detail navigation and drag/drop used by fragment hydrators and grid views |
| `js/frontend-app.js` | app-wide bootstrap/composition root, shared Alpine stores, drag/drop bindings, search-results coordination, asset actions, and delegation into fragment/listener modules |
| `js/common/` | shared: modal, snackbar, navigation, nodes |
| `js/models/folder.js` | DOM wrapper for folder tree elements |
| `js/alpine/components/selectable.js` | asset grid multi-select |

Legacy feature folders often used a consistent file split:
- `event_handlers.js` — custom DOM event listeners (`document.body.addEventListener(Const.events.*)`)
- `htmx_event_handlers.js` — HTMX lifecycle listeners (`htmx:beforeRequest`, `htmx:afterRequest`)
- `dragon-drop.js` — interact.js drag-and-drop wiring (now centralized in `js/frontend-app.js` for batch, people/person, and folder-tree flows)

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
`assetMoved`/`assetTrashed` handlers are now registered via `js/listeners/assets.js`,
which re-dispatches `batchAssetsMoved`/`batchAssetsRecycled` if the
`selectedAssets` store is non-empty. Person merge/name/cover-face events,
person discard follow-up, people/person drag-and-drop, folder-tree drag-and-drop,
and trash purge request outcomes are coordinated from `js/frontend-app.js` via the
domain listener modules.

### Full action flow (drag-and-drop example)

```
interact.js drag end / ondrop
  → dispatch CustomEvent on document.body
    → event_handlers.js listener
        → fetch() or htmx.ajax() to server
          → on success: direct DOM mutation + snackbar + optional nav reload
```

`htmx_event_handlers.js` sits on the **HTMX lifecycle** side of this, not the custom event side.
It intercepts `htmx:beforeRequest` to short-circuit requests the client can already resolve
locally (e.g. collapsing a folder), and `htmx:afterRequest` to apply post-response state changes
(e.g. marking a folder as expanded).

In the current structure, that HTMX lifecycle logic is split by concern: folder-specific
before/after request behavior lives in `js/listeners/htmx-folders.js`, while shared HTMX/search
listener wiring remains in `js/listeners/htmx-search.js` and delegates back into `frontend-app.js`.

## Alpine.js Integration

Alpine serves two distinct roles in this codebase:

### 1. Global shared state (stores)
Stores in `app.js` hold data any module needs to read — the active repo ID, current view, and
the set of selected assets. All access goes through `window.ctx` helpers or
`Alpine.store(Const.state.*)` calls; no module reads the store key strings directly.

### 2. Per-element component registry
`x-data="initSelectable(id)"` (defined in `alpine/components/selectable.js`, exposed on `window`
so Twirl can reference it by name) creates a reactive object for each asset cell. Crucially,
the `selectedAssets` store holds a `Map<id, componentProxy>`. This means external JS — drag
handlers, service calls — can reach into individual asset cells and mutate their reactive state
(`.drag()`, `.drop()`, `.deselect()`) imperatively, causing Alpine to update CSS class bindings
without those modules needing to know anything about the DOM structure.

### Data flow summary

- **Alpine → drag handler**: drag handlers read store state (e.g. is this asset selected?) to
  decide whether to escalate to a batch operation.
- **Drag handler → Alpine**: on drag start/end, handlers call methods on component proxies
  stored in `selectedAssets.items` to reflect dragging state in the UI.
- **Event handler → Alpine**: after a server call succeeds, the handler calls
  `Alpine.store(...).reset()` to clear selection, which in turn dispatches `deselectAll` so each
  component proxy deselects itself.

Alpine is intentionally **not** used for routing, server communication, or HTMX trigger logic —
those responsibilities stay with HTMX and the custom event bus.

## UI Patterns

**Modals** — Two containers live in `html_common.scala.html`. Load into `#modalContent` for general
modals; load into `#imageDetailModalContent` for asset detail. Use `showModal()` /
`showAssetDetailModal()` / `closeModal()` from `js/common/modal.js`. ESC key is wired globally in
`global.js`. General HTMX modal fragments can opt into centralized hydration by adding
`data-app-fragment="modal"` plus `data-app-modal-*` attributes (title, width, autofocus selector,
success event/action metadata) on the fragment root; `js/fragments/` hydrators handle modal
display, focus/select controls, dispatch success events, and close the modal after successful
requests, while `js/frontend-app.js` remains the composition root that triggers hydration.

**Snackbar** — Always use `showSuccessSnackBar` / `showWarningSnackBar` / `showErrorSnackBar` from
`js/common/snackbar.js`. Auto-dismisses after 3 s.

**Infinite scroll + lazy load** — The last `.cell` gets class `last-cell`; HTMX fires on
`intersect` to load `?page=N`. Images use `alt-data-src` instead of `src`; the centralized
search-results fragment hydrator in `js/fragments/search-results.js` binds infinite scroll,
lazy image loading, and metadata visibility for `data-app-fragment="search-results"`.

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
| `static/js/app.js` | thin bootstrap that exposes `initApp()` and starts `FrontendApp` |
| `static/js/context.js` | `window.ctx` — repo ID and metadata field settings |
| `static/js/models/folder.js` | DOM wrapper around folder tree nodes |
| `static/js/common/modal.js` | `showModal`, `showAssetDetailModal`, `closeModal` |
| `static/js/common/snackbar.js` | `showSuccessSnackBar`, `showWarningSnackBar`, `showErrorSnackBar` |
| `static/js/alpine/components/selectable.js` | Alpine component for per-asset selection |
| `views/includes/html_common.scala.html` | Snackbar + modal DOM containers |
| `views/includes/search_results.scala.html` | Search grid wrapper with sort controls |
| `views/htmx/results_grid.scala.html` | Individual asset cells, infinite-scroll trigger |

