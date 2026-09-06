# Frontend – Agent Guide

## Stack

- **HTMX 4** — server-driven HTML fragments, no SPA routing
- **Alpine.js** — reactive state and UI behavior (`x-data`, `x-show`, `$store`)
- **No bundler** — all JS uses native ES modules (`<script type="module">`, `import`/`export`)
- Libraries are checked into `static/js/lib/` (htmx, Alpine and its focus plugin, Split.js, interact.js, axios,
  Viselect for box selection); versions and sources are listed in `static/js/lib/README.md`

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

`htmx/folders.scala.html` supplies the folder tab's styles, navigation warning, and empty
`#rootFolderList` host. Its module script selects the tab, sets the repository context, and calls
`reloadFolderTree(repoId)`; edit `js/common/folder-tree.js` for folder rows and action markup. The
tree's interaction contract (expansion gestures, viewed-folder highlighting) is under **Folder tree
expansion and viewed scope** below.

## JS Directory ↔ Template Mapping

| JS directory | Template(s) it serves |
|---|---|
| `js/assets/` | asset mutation/action flows such as move, recycle, purge, and restore, plus related grid/snackbar follow-up |
| `js/dragdrop/` | interact.js binding modules for batch ops, people, and folder-tree drag/drop flows |
| `js/fragments/` | centralized hydration for declarative HTMX fragments (`data-app-fragment="..."`) such as modal, inline-dialog, image-detail, and inline editor fragments, plus the operation lifecycle shared by every dialog (`dialog-operations.js`) |
| `js/listeners/` | domain-focused `document.body` event registration for folders, people, assets, HTMX/search lifecycle wiring, and the `document`-level dialog request wiring (`dialogs.js`) |
| `js/search-results/` | the search funnel (`search.js`) and its declarative triggers (`search-triggers.js`), plus search/detail coordination helpers such as detail navigation, image loading, drag/drop (`dragon-drop.js`), box selection (`box-selection.js`), and the post-gesture click swallow they share (`click-suppression.js`) used by fragment hydrators and grid views |
| `js/stores/` | Alpine store initialization modules shared by the app shell and feature coordinators |
| `js/frontend-app.js` | app-wide bootstrap/composition root, minimal context-store setup, and delegation into asset/dragdrop/fragment/listener/search modules |
| `js/common/` | shared: modal, snackbar, navigation, folder-tree (renders the tree and each folder's menu), folder-menu (closes a folder menu from outside its component), viewed-folder-scope (highlights the folder whose results are displayed) |
| `js/models/folder.js` | DOM wrapper for folder tree elements and the owner of their expansion state |
| `js/alpine/components/` | Alpine components: `selectable.js` (asset grid multi-select) and `folder-menu.js` (native popover folder menus); `index.js` registers them |

Legacy feature folders often used a consistent file split:
- `event_handlers.js` — custom DOM event listeners (`document.body.addEventListener(Const.events.*)`)
- `htmx_event_handlers.js` — HTMX lifecycle listeners (`htmx:before:request`, `htmx:after:request`)
- `dragon-drop.js` — interact.js drag-and-drop wiring (now split into `js/dragdrop/` modules for batch, people/person, and folder-tree flows)

## Alpine.js Stores (initialized in `js/frontend-app.js` and `js/stores/app-stores.js`)

| Store key | Purpose |
|---|---|
| `Const.state.selectedAssets` | `Map` of currently selected asset IDs for batch ops |
| `Const.state.searchParams` | The complete search parameter set — the single source of truth for what is being searched (see **Search parameters** below) |
| `Const.state.currentView` | Current view: `repository`, `triage`, or `trashbin`. Derived from `searchParams.view` and written once at page load |
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
Const.attributes.viewedScope    // "alt-viewed-scope" (on the folder node whose results are displayed)
Const.attributes.dataSrc        // "alt-data-src"  (lazy-load image URL)
```

The `Folder` class in `js/models/folder.js` is the canonical DOM abstraction for folder tree
nodes. Construct it with an ID: `new Folder(id)` — it wraps `#folder-{id}`, `#children-{id}`,
`#folderName-{id}`. The folder tree, including each folder's ⋯ menu (`#folderMenuCtrl-{id}` opening
the `#menu-{id}` popover), is rendered client-side by `js/common/folder-tree.js` from the JSON
tree endpoint; those IDs are stable so tree rebuilds can restore focus and dialogs can return it.

`reloadFolderTree(repoId)` discards superseded responses and snapshots the expanded folder IDs and
the focused control immediately before replacing the tree, so a gesture made or focus returned by a
dialog while the request was in flight is not undone by an older snapshot. Expansion is restored top
down through the folder model: a surviving branch is re-expanded only when its parent is expanded,
so a folder moved beneath a collapsed parent is normalized to collapsed, and a folder that lost its
last child gets a plain icon. The latest viewed scope is reapplied independently of that snapshot.
It calls `htmx.process(container)` after insertion; Alpine's mutation observer initializes the new
subtree. Do not also call `Alpine.initTree`, which duplicates initialization and listeners.

Folder indentation comes from each non-root node's `--depth` and the `--folder-indent` variable
in `htmx/folders.scala.html`. The `.trace` grid cell indents the icon and name while `.menu-ctrl`
stays flush left at every depth; the root row omits the trace cell. Keep renderer markup and
these grid rules in sync.

## Event Flow

Two-layer event bus, both on `document.body`:

1. **Custom DOM events** — string names in `Const.events`, dispatched via `new CustomEvent(...)`,
   handled in `event_handlers.js`. These are business-level actions (e.g. `FOLDER_MOVED_EVENT`).
2. **HTMX lifecycle events** — `htmx:before:request` / `htmx:after:request` / `htmx:after:settle` —
   handled by the listener modules. Used to settle modal requests, report failed requests, and
   follow up on completed operations.

### HTMX 4 conventions

- Lifecycle events carry the request context under `event.detail.ctx`. Read it through the helpers
  in `js/common/htmx-events.js` (`getRequestPath`, `getResponseStatus`, `isRequestSuccessful`,
  `getResponseText`, `getRequestTarget`) instead of touching the detail directly.
- `htmx:after:request` fires when the response has arrived but **before** it is swapped in.
  Cancelling it (`preventDefault()`) drops the swap. Once the issuing element has left the DOM, htmx
  dispatches lifecycle events on `document` instead, so listeners that must see such late responses
  (dialog operations) go on `document`, not `document.body`.
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

The listener modules sit on the **HTMX lifecycle** side of this, not the custom event side.
Folder expansion and the folder context menus involve no request at all: the tree renderer builds
them, so `js/listeners/htmx-folders.js` only reports failed folder requests (the folders tab load)
from `htmx:after:request`. Shared HTMX/search listener wiring (`htmx:after:request`,
`htmx:after:settle`) lives in `js/listeners/htmx-search.js` and delegates back into
`frontend-app.js`; the only `htmx:before:request` listener is the dialog one on `document`.
People-specific HTMX follow-up for discard actions and the inline person-name editor lives in
`js/listeners/htmx-people-inline-editor.js`.

## Alpine.js Integration

Alpine serves three distinct roles in this codebase:

### 1. Global shared state (stores)
Stores initialized in `js/frontend-app.js` and `js/stores/app-stores.js` hold shared data — the active
repo ID, current view, and the set of selected assets. All access goes through `window.ctx`
helpers or `Alpine.store(Const.state.*)` calls; no module reads the store key strings directly.

### 2. Per-element component registry
`x-data="initSelectable(id)"` (defined in `alpine/components/selectable.js`, exposed on `window`
so Twirl can reference it by name) creates a reactive object for each asset cell. Crucially,
the `selectedAssets` store holds a `Map<id, componentProxy>`. This means external JS — drag
handlers, service calls — can reach into individual asset cells and mutate their reactive state
(`.drag()`, `.drop()`, `.deselect()`) imperatively, causing Alpine to update CSS class bindings
without those modules needing to know anything about the DOM structure. Box selection
(`js/search-results/box-selection.js`) reaches a cell's component the other way, through
`Alpine.$data(cellEl)`, and calls `toggle()` on the ones the box touched that are not yet selected;
it has no selection state of its own.

### Data flow summary

- **Alpine → drag handler**: drag handlers read store state (e.g. is this asset selected?) to
  decide whether to escalate to a batch operation.
- **Drag handler → Alpine**: on drag start/end, handlers call methods on component proxies
  stored in `selectedAssets.items` to reflect dragging state in the UI.
- **Event handler → Alpine**: after a server call succeeds, the handler calls
  `Alpine.store(...).reset()` to clear selection, which in turn dispatches `deselectAll` so each
  component proxy deselects itself.

### 3. Coordination around native UI
`x-data="folderMenu"` (registered with `Alpine.data` in `alpine/components/index.js`, defined in
`alpine/components/folder-menu.js`) wraps each folder's ⋯ trigger and its native `popover="auto"`
panel. The browser owns the menu's visibility (`:popover-open`, `popovertarget`, light dismiss on
outside clicks); the component only places the panel, switches it between its actions and the
inline dialog an action loads, closes it when focus leaves or when the page scrolls or the explorer
resizes, and cleans up. Nothing binds `x-show` or an `open` flag to it. See **Folder context
menus** below.

Alpine is intentionally **not** used for routing, server communication, or HTMX trigger logic —
those responsibilities stay with HTMX and the custom event bus.

## UI Patterns

**Modals** — Two hosts live in `html_common.scala.html`: load into `#modalContent` for general
dialogs and into `#imageDetailModalContent` for asset detail. `js/common/modal.js` is the single owner
of both: which host is active (one at a time — a new open replaces the active modal), the title,
initial focus (the fragment's autofocus selector, else the close control, never a destructive action),
focus restoration on close (the element focused when the open was requested, else the fragment's
`data-app-dialog-return-focus` selector), and identity: every displayed open has an `openId`
(`isModalOpenActive(openId)` guards asynchronous work), and the latest "open request" (any HTMX
request targeting a host) is tracked so a slow response for an earlier open is cancelled before it
swaps once the user dismissed or replaced it. Visibility is bound through the Alpine `modal` store
(`x-show`; `x-trap.inert.noscroll` from the vendored `@alpinejs/focus` plugin registered in
`js/app.js` contains focus and hides the page from assistive tech; its documented local patch
cancels delayed activation when a trap is released or removed). Escape (`global.js`) closes the
active modal and any open folder menu and is consumed by them, so a background inline edit
survives; general dialogs ignore backdrop clicks, asset detail closes on them. General-dialog width is CSS only
(`--modal-content-width` in `core.css`, shrinking to the viewport); asset detail is sized to the
image with `setAssetDetailSize()`. General-dialog placement is the host's CSS (box centered
horizontally, `padding-top` from the top). Opening any modal closes an open folder menu first, so a
modal never appears over one.

A **dialog** is a server-rendered form that completes one user action, hydrated from its
`data-app-fragment` kind; a **modal dialog** is one shown in the modal host. Attributes that describe
the dialog itself are `data-app-dialog-*` on the fragment root, whatever its presentation: autofocus
selector / select-on-focus, return-focus selector (the control focus goes to on close, chosen to
survive the page update the dialog triggers), success event + detail (+ `success-detail-target-attr-*`
read from the issuing element), and `close-on-success`, defaulting to true. Attributes that describe
the modal host are `data-app-modal-*`: title and kind. The three folder dialogs are **inline
dialogs** (`data-app-fragment="inline-dialog"`, see **Folder context menus**); the people and
view-settings dialogs are modal dialogs.

General HTMX modal fragments opt in with `data-app-fragment="modal"`; `js/fragments/modal.js` opens the
host on hydration and registers the modal presentation with `js/fragments/dialog-operations.js`, which
tracks each operation any dialog submits from `htmx:before:request` (capturing a handle to the dialog
it belongs to - for a modal, its open - and its success metadata while the fragment is still in the
DOM; a repeated submission while one is pending is dropped). When the response arrives it dispatches
the success event exactly once and lets normal page updates through even if the dialog was closed or
replaced; only the still-active initiating dialog is closed or gets its form replaced. Validation
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
The navigation origin is set to the requested asset before its image loads, so previous/next
already uses the newly opened asset while the spinner is showing.
Arrow-key navigation works only while asset detail is active and no text field is focused.

**Folder context menus** — Each folder's ⋯ button (its **trigger**) is a real `button` with
`popovertarget` pointing at a `popover="auto"` panel (`#menu-{id}`), all built with the tree by
`js/common/folder-tree.js`, so opening a menu sends no request. The panel shows either its
**actions** (`.actions`: Add folder; Rename and Delete for non-root folders) or one **inline
dialog** in its dialog host (`.dialog`, `#menuDialog-{id}`). Each action is an HTMX request for its
dialog into that host; the response replaces the actions (they stay in the DOM, hidden, so their
htmx wiring survives) until the panel closes, which discards the dialog and shows the actions again.
Only the response to the request the open panel is waiting for may show: the component records the
panel's own open request from `htmx:before:request` and cancels, in `htmx:after:request`, any
response for the host that is not that request or arrives while the panel is closed; failed loads
are reported by `js/listeners/htmx-folders.js`. After every swap into the host (the load, or a
validation replacement of the form) the component re-places the panel for its new height. The
browser owns visibility: it toggles the panel from its trigger, closes it on any click outside, and
keeps one open at a time because a folder's panel is never a DOM descendant of another folder's
panel. The `folderMenu` component places the panel in the top layer against the trigger (below,
flipping above when needed, clamped to the viewport, height-capped with internal scrolling when
neither side fits), closes it when focus leaves and on scroll, window resize, or explorer resize;
it never tracks a moving trigger. The panel carries `tabindex="-1"`, so a click on a dialog's
heading, label, or padding moves focus to the panel rather than out of it; a validation swap that
removes the focused field (`htmx-swapping` on the form) is not focus leaving either.
`js/common/folder-menu.js` closes a menu from outside the component: Escape in `global.js` (focus
returns to the trigger unless a modal was closed too), the folder model when an ancestor collapses,
`openModal`, and a completed inline-dialog operation (`closeFolderMenu` with the declared return
control). Removing the tree removes the open panel and, through the component's `destroy`, its
listeners. Styling lives in `views/htmx/folders.scala.html`; CSS sets `display` only under
`:popover-open`, because the hidden state relies on the browser's `display: none`. During
`beforetoggle`, the component briefly sets inline `display: grid` to measure and position the
hidden panel, then clears it before opening; keep the CSS `margin: 0` and `inset: auto` resets so
those viewport coordinates apply correctly. `.menu-ctrl` and the icon control `.expand-ctrl` are
excluded from folder dragging (`ignoreFrom` in `js/dragdrop/folders.js`); the rest of the row drags.

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

Green (`--success-font-color`) marks the **viewed scope**: the folder whose results are displayed
and all its descendants, because the results cover that subtree. It has nothing to do with
expansion. `js/common/viewed-folder-scope.js` keeps the scope of the displayed results and marks
that one folder node with `alt-viewed-scope`; the CSS in `htmx/folders.scala.html` colors every
`.folder-icon` under the node (root and leaves included, hidden descendants too) and no icon in a
menu or dialog. The search-results fragment carries `data-results-repo-id`, `data-results-view`,
and `data-results-folder-id`, which `SearchResultsController` resolved from the parameters it was
sent, and `js/fragments/search-results.js` sets the scope when the fragment is hydrated. It reads
those attributes rather than the `searchParams` store on purpose: the highlight must follow what is
displayed, so a superseded or failed navigation never moves it. So the highlight changes only for results actually displayed, survives sorting and
pagination, is absent in triage and trash or without a folder in scope, and is reapplied by every
tree rebuild; deleting the viewed folder removes it without selecting the parent.

**Inline dialogs** — `views/htmx/{add,rename,delete}_folder_dialog.scala.html` are
`data-app-fragment="inline-dialog"` fragments: the heading (`.dialog-title`, the modal title's type
treatment) sits inside the fragment root so a validation replacement carries it, the name field has
an explicit `size` (the panel stays `max-content` wide), and Delete's root is a wrapper around the
heading and the confirm button. Add and Rename keep `hx-target="this"`, `hx-swap="none"`, and
`hx-json-enc`. `js/fragments/inline-dialog.js` places initial focus (the declared selector with
optional select, else the panel itself, so a held Enter from the menu cannot fire Delete; one Tab
reaches the button) and registers the inline presentation with `dialog-operations.js`: an
operation's dialog is active while that fragment is still in an open panel, and closing it closes
the panel with focus on the declared return control (the folder's trigger, or the parent's after a
deletion). Dismissal follows the menu's rules with no confirmation; Escape returns focus to the
trigger.

**Snackbar** — Always use `showSuccessSnackBar` / `showWarningSnackBar` / `showErrorSnackBar` from
`js/common/snackbar.js`. Auto-dismisses after 3 s.

**Infinite scroll + lazy load** — The last `.cell` gets class `last-cell` and carries the page to
load next in `data-app-search-next-page`. An `IntersectionObserver` in
`js/fragments/search-results.js` watches it and requests that page through `runSearch`, appending
the result after the cell; the cell is unobserved and loses the attribute as it fires, so scrolling
back over it loads nothing again. A page the server rejects is reported through the snackbar by
`FrontendApp.handleAfterRequest` (`isSearchRequest` in `js/listeners/htmx-routes.js`), since no
visible control is behind the request. Images use `alt-data-src` instead of `src`; the same centralized
search-results fragment hydrator binds infinite scroll, lazy image loading, metadata visibility, and
box selection for `data-app-fragment="search-results"`, and sets the viewed folder scope from the
fragment's `data-results-*` metadata. When a loaded image scrolls out of view and is swapped for the
transparent 1x1 placeholder, its rendered size is kept as inline `width`/`height`, so the thumbnail
box a box selection hit-tests against stays where the image was (the cell's row is fixed, so the
layout is the same either way).

**Box selection** — Dragging a rectangle over the grid selects the thumbnails it touches
(`js/search-results/box-selection.js`, one controller per displayed grid, created and replaced by the
search-results hydrator). The vendored Viselect (`lib/viselect.esm.js`) owns the gesture: it draws
the rectangle inside its own fixed, clipped container on `<body>` (styled by `.selection-area` in
`includes/search_results.scala.html`), autoscrolls `#content` within 32px of its top and bottom
edges, and keeps the list of thumbnails inside the rectangle; it adds no classes and changes no
state. Nothing visible changes during the drag. On release the box goes through the existing
selection code only: `selectedAssets.reset()` for a replacement box, then `toggle()` on each touched
component that is not yet selected. The hit target is the `.drag-drop` div, whose box is exactly the
rendered thumbnail, and any overlap counts (`intersect: "touch"`). Rules: a plain drag from empty grid
space (padding, gaps, a cell's metadata) replaces the selection; a Shift-drag from anywhere in the
grid, thumbnails included, adds to it, with Shift read when the button goes down (`dragon-drop.js`
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

**Detail navigation** — Shadow-results syncing, next/previous navigation, paged JSON fetching,
and modal asset-detail loading are coordinated from `js/search-results/detail-navigator.js`. Its
JSON fetches use `currentSearchUrl({ p })`, which leaves the store alone, so paging the shadow
results never moves the visible page.

**Metadata field visibility** — Fields default to `display:none`. Use
`window.ctx.addGridMetadataField(name)` / `removeGridMetadataField(name)` to persist to
`localStorage`. The `viewSettingChanged` event applies visibility changes to
`#assets .metadata > div.{fieldName}`. Call `showOrHideAssetGridMetadata` on initial load.

**Nav refresh** — After any successful asset mutation, reload the nav to update counts.
Folder deletion also recycles assets throughout its subtree, so the `folderDeleted` listener
calls `app.reloadNav()` before awaiting the folder-tree refresh. Reuse `FrontendApp.reloadNav()`
from event listeners; it loads the nav fragment with:
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
| `static/js/models/folder.js` | DOM wrapper around folder tree nodes; owns branch expansion (`expand`, `expandAll`, `collapse` with descendant reset) |
| `static/js/common/viewed-folder-scope.js` | tracks the folder scope of the displayed results and marks it in the tree |
| `static/js/common/modal.js` | modal owner: `openModal`, `closeModal`, open identity (`isModalOpenActive`), `setAssetDetailSize` |
| `static/js/fragments/dialog-operations.js` | lifecycle of the operations every dialog submits; fragment kinds register their `isActive`/`close` |
| `static/js/fragments/inline-dialog.js` | inline dialog fragments shown in a folder menu panel |
| `static/js/common/snackbar.js` | `showSuccessSnackBar`, `showWarningSnackBar`, `showErrorSnackBar` |
| `static/js/alpine/components/selectable.js` | Alpine component for per-asset selection |
| `static/js/search-results/box-selection.js` | box selection: Viselect gesture on the grid, committed through `selectable` on release |
| `static/js/search-results/click-suppression.js` | swallows the click the browser fires after an asset drag or a box gesture |
| `static/js/alpine/components/folder-menu.js` | Alpine component coordinating a folder's native popover menu |
| `static/js/stores/search-params.js` | the search parameter set, its defaults, and the scope rules that decide what a change clears |
| `static/js/search-results/search.js` | `runSearch` — the single entry point for every search request — and `currentSearchUrl` |
| `static/js/search-results/search-triggers.js` | binds `data-app-search` elements to `runSearch` |
| `static/js/common/folder-tree.js` | renders the folder tree and each folder's menu from the JSON tree endpoint |
| `static/js/common/folder-menu.js` | closes a folder menu from outside its component (Escape, ancestor collapse, modal open, completed inline dialog) |
| `views/includes/html_common.scala.html` | Snackbar + the two Alpine-bound modal hosts |
| `views/includes/search_results.scala.html` | Search grid wrapper with sort controls |
| `views/htmx/results_grid.scala.html` | Individual asset cells, infinite-scroll trigger |

## Search parameters

Every search in the app — the initial load, folder and person navigation, the sort dropdown,
continuous scroll, the shadow results behind asset detail — goes through **one** function,
`runSearch` in `js/search-results/search.js`. A caller supplies only the parameter it knows about;
the `searchParams` store supplies the rest. Nothing else builds a URL for `/htmx/search/r/:repoId`.

`js/stores/search-params.js` owns the parameters (`view`, `folderId`, `personId`, `q`, `sort`,
`rpp`, `p`) and the rules for combining them: choosing a folder clears the person and vice versa, a
view clears both, and any change other than paging returns to page 1. A parameter still at its
default is left out of the request, so a default is never spelled out on both sides — except
`view`, which is always sent, and whose values match `Const.Search.View.*` server-side verbatim.

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
```

The hydrator also owns the rule that folder navigation does nothing in triage and trash, so that
guard lives in one place rather than in markup.

Per-request flags that must not be remembered (`isContinuousScroll`) are passed as `transient` and
are serialized into that one request only. A server-side action that should change what is
displayed reports it as a custom event and lets JS run the search — as the people merge does with
`personMerged` — rather than redirecting to a search URL of its own.

