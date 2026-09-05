# Frontend – Agent Guide

## Stack

- **HTMX 4** — server-driven HTML fragments, no SPA routing
- **Alpine.js** — reactive state and UI behavior (`x-data`, `x-show`, `$store`)
- **No bundler** — all JS uses native ES modules (`<script type="module">`, `import`/`export`)
- Libraries are checked into `static/js/lib/` (htmx, Alpine and its focus plugin, Split.js, interact.js, axios);
  versions and sources are listed in `static/js/lib/README.md`

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
| `js/assets/` | asset mutation/action flows such as move, recycle, purge, and restore, plus related grid/snackbar follow-up |
| `js/dragdrop/` | interact.js binding modules for batch ops, people, and folder-tree drag/drop flows |
| `js/fragments/` | centralized hydration for declarative HTMX fragments (`data-app-fragment="..."`) such as modal, image-detail, and inline editor fragments |
| `js/listeners/` | domain-focused `document.body` event registration for folders, people, assets, HTMX/search lifecycle wiring, and the `document`-level modal request wiring |
| `js/search-results/` | search/detail coordination and helpers such as detail navigation, image loading, and drag/drop used by fragment hydrators and grid views |
| `js/stores/` | Alpine store initialization modules shared by the app shell and feature coordinators |
| `js/frontend-app.js` | app-wide bootstrap/composition root, minimal context-store setup, and delegation into asset/dragdrop/fragment/listener/search modules |
| `js/common/` | shared: modal, snackbar, navigation, nodes |
| `js/models/folder.js` | DOM wrapper for folder tree elements |
| `js/alpine/components/selectable.js` | asset grid multi-select |

Legacy feature folders often used a consistent file split:
- `event_handlers.js` — custom DOM event listeners (`document.body.addEventListener(Const.events.*)`)
- `htmx_event_handlers.js` — HTMX lifecycle listeners (`htmx:before:request`, `htmx:after:request`)
- `dragon-drop.js` — interact.js drag-and-drop wiring (now split into `js/dragdrop/` modules for batch, people/person, and folder-tree flows)

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
2. **HTMX lifecycle events** — `htmx:before:request` / `htmx:after:request` / `htmx:after:settle` —
   handled in `htmx_event_handlers.js`. Used to toggle fold/expand state and short-circuit requests
   when no server round-trip is needed.

### HTMX 4 conventions

- Lifecycle events carry the request context under `event.detail.ctx`. Read it through the helpers
  in `js/common/htmx-events.js` (`getRequestPath`, `getResponseStatus`, `isRequestSuccessful`,
  `getResponseText`, `getRequestTarget`) instead of touching the detail directly.
- `htmx:after:request` fires when the response has arrived but **before** it is swapped in.
  Cancelling it (`preventDefault()`) drops the swap. Once the issuing element has left the DOM, htmx
  dispatches lifecycle events on `document` instead, so listeners that must see such late responses
  (modal operations) go on `document`, not `document.body`.
  `htmx:after:settle` fires on the swap target once per swap with `detail.newContent`, the list of
  inserted nodes; `frontend-app.js` hydrates `data-app-fragment` roots from there.
- Attribute inheritance is explicit: every element that issues a request declares its own
  `hx-target` / `hx-swap`. Do not rely on a parent's attributes (add `:inherited` if you ever must).
- Extensions activate by script inclusion, there is no `hx-ext`. Forms that post JSON carry the
  boolean `hx-json-enc` attribute (`json-enc.js`); the import status stream uses `hx-ws:connect`
  with an explicit `hx-target` / `hx-swap` on the connection element (`hx-ws.js`).
- Global config (`defaultTimeout`, `noSwap`) is the `htmx-config` meta tag in
  `views/includes/header_common.scala.html`; 4xx/5xx bodies are not swapped, listeners report them.
- The file upload (`js/fragments/upload-form.js`) is **not** an HTMX request: it posts through the
  shared axios client so `onUploadProgress` can drive the progress bar, then swaps the returned
  fragment itself.

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
        → shared axios client (`js/http/client.js`) or htmx.ajax() to server
          → on success: direct DOM mutation + snackbar + optional nav reload
```

`htmx_event_handlers.js` sits on the **HTMX lifecycle** side of this, not the custom event side.
It intercepts `htmx:before:request` to short-circuit requests the client can already resolve
locally (e.g. collapsing a folder), and `htmx:after:request` to apply post-response state changes
(e.g. marking a folder as expanded).

In the current structure, that HTMX lifecycle logic is split by concern: folder-specific
before/after request behavior lives in `js/listeners/htmx-folders.js`, while shared HTMX/search
listener wiring remains in `js/listeners/htmx-search.js` and delegates back into `frontend-app.js`.
People-specific HTMX follow-up for discard actions and the inline person-name editor lives in
`js/listeners/htmx-people-inline-editor.js`.

## Alpine.js Integration

Alpine serves two distinct roles in this codebase:

### 1. Global shared state (stores)
Stores initialized from `js/stores/app-stores.js` hold data any module needs to read — the active
repo ID, current view, and the set of selected assets. All access goes through `window.ctx`
helpers or `Alpine.store(Const.state.*)` calls; no module reads the store key strings directly.

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

**Modals** — Two hosts live in `html_common.scala.html`: load into `#modalContent` for general
dialogs and into `#imageDetailModalContent` for asset detail. `js/common/modal.js` is the single owner
of both: which host is active (one at a time — a new open replaces the active modal), the title,
initial focus (the fragment's autofocus selector, else the close control, never a destructive action),
focus restoration on close (the element focused when the open was requested, else the fragment's
`data-app-modal-return-focus` selector), and identity: every displayed open has an `openId`
(`isModalOpenActive(openId)` guards asynchronous work), and the latest "open request" (any HTMX
request targeting a host) is tracked so a slow response for an earlier open is cancelled before it
swaps once the user dismissed or replaced it. Visibility is bound through the Alpine `modal` store
(`x-show`; `x-trap.inert.noscroll` from the vendored `@alpinejs/focus` plugin registered in
`js/app.js` contains focus and hides the page from assistive tech). Escape (`global.js`) closes the
active modal and is consumed by it, so a background inline edit survives; general dialogs ignore
backdrop clicks, asset detail closes on them. General-dialog width is CSS only
(`--modal-content-width` in `core.css`, shrinking to the viewport); asset detail is sized to the
image with `setAssetDetailSize()`.

General HTMX modal fragments opt in with `data-app-fragment="modal"` plus `data-app-modal-*`
attributes on the fragment root (title, autofocus selector / select-on-focus, return-focus selector - the control focus goes to on close, chosen to survive the page update the dialog triggers -
success event + detail, `close-on-success`, defaulting to true). `js/fragments/modal.js` opens the
host on hydration and tracks each operation the fragment submits from `htmx:before:request`
(capturing the open it belongs to and its success metadata while the fragment is still in the DOM;
a repeated submission while one is pending is dropped). When the response arrives it dispatches the
success event exactly once and lets normal page updates through even if the dialog was closed or
replaced; only the still-active initiating dialog is closed or gets its form replaced. Validation
responses are recognised by their `HX-Retarget: this` / `HX-Reswap: outerHTML` headers
(`BaseController.modalFormValidationResponse`): they replace the active form in place (values and
errors kept, no success event) or, once the dialog is gone, are reported through the snackbar.
`js/listeners/modal.js` registers this wiring on `document`, because htmx dispatches lifecycle events
on the document when the issuing element has already left the DOM.

Asset detail: `js/fragments/image-detail.js` opens the host with its spinner immediately and hands
the image load to the detail coordinator, which gives each image request a token so only the latest
request for the active open may change the image, box size, title, loading state, or current asset
(rapid previous/next navigation, page fetches, and loads that finish after closing are ignored).
Arrow-key navigation works only while asset detail is active and no text field is focused.

**Snackbar** — Always use `showSuccessSnackBar` / `showWarningSnackBar` / `showErrorSnackBar` from
`js/common/snackbar.js`. Auto-dismisses after 3 s.

**Infinite scroll + lazy load** — The last `.cell` gets class `last-cell`; HTMX fires on
`intersect` to load `?page=N`. Images use `alt-data-src` instead of `src`; the centralized
search-results fragment hydrator in `js/fragments/search-results.js` binds infinite scroll,
lazy image loading, and metadata visibility for `data-app-fragment="search-results"`.

**Detail navigation** — Shadow-results syncing, next/previous navigation, paged JSON fetching,
and modal asset-detail loading are coordinated from `js/search-results/detail-navigator.js`.

**Metadata field visibility** — Fields default to `display:none`. Use
`window.ctx.addGridMetadataField(name)` / `removeGridMetadataField(name)` to persist to
`localStorage`. The `viewSettingChanged` event applies visibility changes to
`#assets .metadata > div.{fieldName}`. Call `showOrHideAssetGridMetadata` on initial load.

**Nav refresh** — After any asset mutation, reload the nav to update counts:
```js
htmx.ajax("GET", `/htmx/nav/r/${window.ctx.getRepoId()}`, { swap: "innerHTML", target: "nav" })
```

Asset move/recycle/purge/restore UI flows are now implemented in `js/assets/asset-actions.js`,
and drag/drop interact.js bindings live in `js/dragdrop/`. Event-listener modules call these
coordinators directly via `app.assetActions` / `app.searchDetailCoordinator`, while
`FrontendApp` remains the composition root that wires them together.

## Key Files

| File | Purpose |
|---|---|
| `static/js/constants.js` | All string constants: events, attributes, store keys, view names |
| `static/js/app.js` | thin bootstrap that exposes `initApp()` and starts `FrontendApp` |
| `static/js/context.js` | `window.ctx` — repo ID and metadata field settings |
| `static/js/http/client.js` | shared axios client for non-HTMX requests; use per-request `validateStatus` overrides only where the UI intentionally handles a non-2xx response |
| `static/js/models/folder.js` | DOM wrapper around folder tree nodes |
| `static/js/common/modal.js` | modal owner: `openModal`, `closeModal`, open identity (`isModalOpenActive`), `setAssetDetailSize` |
| `static/js/common/snackbar.js` | `showSuccessSnackBar`, `showWarningSnackBar`, `showErrorSnackBar` |
| `static/js/alpine/components/selectable.js` | Alpine component for per-asset selection |
| `views/includes/html_common.scala.html` | Snackbar + the two Alpine-bound modal hosts |
| `views/includes/search_results.scala.html` | Search grid wrapper with sort controls |
| `views/htmx/results_grid.scala.html` | Individual asset cells, infinite-scroll trigger |

