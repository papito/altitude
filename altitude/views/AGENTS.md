# Frontend – Agent Guide

## Stack

- **HTMX 4** — server-driven HTML fragments, no SPA routing
- **Alpine.js** — reactive state and UI behavior (`x-data`, `x-show`, `$store`)
- **No bundler** — all JS uses native ES modules (`<script type="module">`, `import`/`export`)
- Libraries are checked into `static/js/lib/` (htmx, Alpine and its focus plugin, Split.js, interact.js, axios,
  Viselect for box selection, Leaflet for the Location pin editor and the map); versions and sources are listed in `static/js/lib/README.md`

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

`htmx/folders.scala.html` supplies the folder tab's styles, the navigation warning, the
`#folderActions` host of the centered "Add folder" button (below the warning), and the empty
`#rootFolderList` host, marked `data-app-fragment="folder-tree"` so `js/fragments/explorer.js`
calls `reloadFolderTree(repoId)` once the tab has settled; edit `js/common/folder-tree.js` for
folder rows and action markup. The tree's interaction contract (expansion gestures, viewed-folder
highlighting) is under **Folder tree expansion and viewed scope** below. `htmx/albums.scala.html`
is the same shape for the Albums tab: styles, the two add buttons, and the empty `#albumList` host
(`data-app-fragment="album-list"`) that `js/common/album-list.js` renders (see **Albums** below), and
`htmx/locations.scala.html` for the Locations tab: styles, the `#locationActions` / `#noLocations` hosts
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
| `js/listeners/` | domain-focused `document.body` event registration for folders, albums, Locations, people, assets, and search keys; the generic htmx request outcome (`htmx-requests.js`: failures to the snackbar, declared success events, tab selection, fragment hydration); and the `document`-level dialog request wiring (`dialogs.js`) |
| `js/search-results/` | the search funnel (`search.js`) and its declarative triggers (`search-triggers.js`); the grid's behaviours, one module each: selection (`selection.js`), infinite scroll (`infinite-scroll.js`), lazy images (`lazy-images.js`), metadata visibility (`metadata-visibility.js`), the ⚙ View control (`view-settings-control.js`), box selection (`box-selection.js`), the date headers' counts (`date-groups.js`), detail navigation over the grid and image loading (`detail-navigator.js`), and the post-gesture click swallow (`click-suppression.js`) |
| `js/dragdrop/` | interact.js binding modules for assets (`assets.js`, thumbnails and the trash drop zone), batch ops, people, folder-tree, album and Location drag/drop, plus the helpers they share (`helpers.js`) |
| `js/stores/` | Alpine store initialization modules shared by the app shell and feature coordinators |
| `js/frontend-app.js` | app-wide bootstrap/composition root, delegating into asset/dragdrop/fragment/listener/search modules |
| `js/common/` | shared: modal, snackbar, navigation, folder-tree (renders the tree), album-list (renders the albums), location-list (renders the parents and Locations), context-menu-markup (builds the ⋯ menu of a folder, album or Location and the dialog-trigger buttons), asset-count (the `(n)` cell and its column sizing), viewed-folder-scope (highlights the folder whose results are displayed), htmx-events (accessors for the htmx 4 request context) |
| `js/models/folder.js` | DOM wrapper for folder tree elements and the owner of their expansion state |
| `js/alpine/components/` | Alpine components: `date-group-selectable.js` (a date header's checkbox over its day's cells) and `context-menu.js` (native popover menus of folders and albums, and the functions that close one from outside); `index.js` registers them |

## Alpine.js Stores (initialized in `js/frontend-app.js` and `js/stores/app-stores.js`)

| Store key | Purpose |
|---|---|
| `Const.state.selectedAssets` | Reactive `Set` of the selected asset IDs, and the one place a selection changes (see **Selection** below) |
| `Const.state.searchParams` | The complete search parameter set — the single source of truth for what is being searched (see **Search parameters** below) |
| `Const.state.currentView` | Current view: `repository`, `triage`, or `trashbin`. Derived from `searchParams.view` and written once at page load |
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

Folder indentation comes from each non-root node's `--depth` and the `--folder-indent` variable
in `htmx/folders.scala.html`. The `.trace` grid cell indents the icon and name while `.menu-ctrl`
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
   above goes to the snackbar with its path and status; a successful one dispatches the declared
   success event and, for a tab, selects it; every swap hydrates the fragments it inserted) and by
   `js/listeners/dialogs.js` for modal opens and dialog operations. There is no router keyed on
   request URLs.

### HTMX 4 conventions

- Lifecycle events carry the request context under `event.detail.ctx`. Read it through the helpers
  in `js/common/htmx-events.js` (`getRequestPath`, `getResponseStatus`, `isRequestSuccessful`,
  `getResponseText`, `getRequestSource`, `getResponseRetarget`) instead of touching the detail
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

Alpine serves three distinct roles in this codebase:

### 1. Global shared state (stores)
Stores initialized in `js/frontend-app.js` and `js/stores/app-stores.js` hold shared data — the active
repo ID, current view, and the set of selected assets. All access goes through `window.ctx`
helpers or `Alpine.store(Const.state.*)` calls; no module reads the store key strings directly.

### 2. Selection
An asset cell has no Alpine component. What is selected is the reactive `Set` of asset IDs in the
`selectedAssets` store (`js/search-results/selection.js`), and the store is the one place a
selection changes: `select`, `deselect`, `toggle`, `reset`, and `setDragging` update the set and
paint the cell they concern (`.selected` / `.masked` on `#asset-<id> .drag-drop`). One delegated
click listener per displayed grid toggles an asset from its checkmark or a Shift-click on its
image; box selection commits through the same methods; drag handlers read `contains(id)` to
decide whether a drop escalates to a batch and call `setDragging` to dim the selection while it
moves. Cells entering or leaving the grid are announced with `noteGridChange()`, which bumps a
reactive counter the date headers read (the DOM itself is not reactive). The footer binds to the
set (`x-show`, `x-text`), and `reset()` from its Deselect All button clears it.

### 3. Coordination around native UI
`x-data="contextMenu"` (registered with `Alpine.data` in `alpine/components/index.js`, defined in
`alpine/components/context-menu.js`) wraps each folder's or album's ⋯ trigger and its native
`popover="auto"` panel. The browser owns the menu's visibility (`:popover-open`, `popovertarget`, light dismiss on
outside clicks); the component only places the panel, closes it when focus leaves or when the page scrolls or the explorer
resizes, and cleans up. Nothing binds `x-show` or an `open` flag to it. See **Folder context
menus** below.

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
`js/app.js` contains focus and hides the page from assistive tech; its documented local patch
cancels delayed activation when a trap is released or removed). Escape (`global.js`) closes the
active modal and any open context menu and is consumed by them, so a background inline edit
survives; general dialogs ignore backdrop clicks, asset detail closes on them. Explorer action dialogs
are anchored below the row's ⋯ trigger, or the Add button. Their request buttons declare
`data-app-modal-anchor` with that control's selector; `modal.js` preserves it through validation,
repositions on content/viewport/explorer resizing and scrolling, and releases its observers on close
or replacement. `common/anchored-panel.js` supplies placement shared with context menus: below and
left-aligned normally, flipping above or capping height on the roomier side when necessary, with
horizontal placement constrained to the viewport. The box is hidden until measured to avoid a
flash at the default position. Anchored forms use natural content width in `core.css`, keeping
inputs at their existing width and only the standard padding at the right. The Location map editor
retains its explicitly declared width. Other general dialogs retain `--modal-content-width` and the
host's centered placement; asset detail is sized with `setAssetDetailSize()` in its own host. Opening any modal closes an open context menu first, so a
modal never appears over one.

Both modal hosts use `.close-modal` in `core.css`: the X stays white (`--modal-close-color`)
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
`-detail-target-attr-*`) on the fragment root. The one attribute that describes the modal host is
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
The navigation origin is set to the requested asset before its image loads, so previous/next
already uses the newly opened asset while the spinner is showing.
Arrow-key navigation works only while asset detail is active and no text field is focused.

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
cell (`#folder-count-{id}`, built by `js/common/asset-count.js`) placed before the icon, dimmed in
parentheses. The count is recursive:
the folder's own sorted assets plus those of every folder beneath it. Triaged and recycled assets are
excluded, and a zero renders as an empty cell, never `(0)`. The root row (labelled `/`) shows no count (the nav
carries the repository total), though the tree JSON still reports `numOfAssets` for it. Every row is its own grid, so after each render `sizeCountColumn` measures the widest count and sets
`--folder-count-column` (declared in `views/htmx/folders.scala.html`) on the list to that width; the
column is then uniform across rows and sibling icons stay aligned whether or not a row shows a count.
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

Green (`--success-font-color`) marks the **viewed scope**: the folder whose results are displayed
and all its descendants, because the results cover that subtree. It has nothing to do with
expansion. `js/common/viewed-folder-scope.js` keeps the scope of the displayed results and marks
that one folder node with `data-viewed-scope`; the CSS in `htmx/folders.scala.html` colors every
`.folder-icon` under the node (root and leaves included, hidden descendants too) and no icon in a
menu or dialog. The search-results fragment carries `data-results-repo-id`, `data-results-view`,
and `data-results-folder-id`, which `SearchResultsController` resolved from the parameters it was
sent, and `js/fragments/search-results.js` sets the scope when the fragment is hydrated. It reads
those attributes rather than the `searchParams` store on purpose: the highlight must follow what is
displayed, so a superseded or failed navigation never moves it. So the highlight changes only for results actually displayed, survives sorting and
pagination, is absent in triage and trash or without a folder in scope, and is reapplied by every
tree rebuild; deleting the viewed folder removes it without selecting the parent.

**Albums** — The Albums tab (`views/htmx/albums.scala.html`) is a flat list rendered by
`js/common/album-list.js` from the JSON list endpoint, one `.album` row (`#album-{id}`,
`data-album-id`) per album: menu | `.asset-count` (`#album-count-{id}`, column sized like the folder
one through `--album-count-column`) | icon | name, with icon and name as `data-app-search-album-id`
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
order (a category directly followed by its Locations, top-level Locations interleaved by name), so the
list is rendered in that order as it comes: one `.location` row (`#location-{id}`, `data-location-id`,
`data-kind`) per entry, `.category`, `.top-level`, or `.child` (under a category: `data-category-id`,
`--depth: 1` and a `.trace` cell that indents it like a folder). A category row is menu (Rename, Delete)
| `fa-layer-group` icon | name; it holds no assets, so it is neither a drop target nor a search
trigger. A Location row is menu (Rename, Delete, Move to category) | `.asset-count`
(`#location-count-{id}`, sized through `--location-count-column`) | `fa-map-marker-alt` icon | name,
with icon and name as `data-app-search-location-id` triggers and the row's `.controls` a drop zone
(`js/dragdrop/locations.js`) for a single asset or the batch mover. Locations hold pointers only: a
drop adds memberships (`PUT /api/location/r/:repoId/assets`, `assetActions.addAssetsToLocation`;
assets already in the Location are skipped and reported), the grid does not change, and the counts
are patched in place (`refreshLocationCounts`, also called by `refreshCounts` after every asset
mutation, because recycling drops an asset from its Locations). While a Location's results are
displayed (`searchParams.locationId`, mirrored by `data-results-location-id`, which marks the row
`data-viewed-scope` and colors its icon green) the batch footer offers "Remove from location", which
deletes the memberships of the selected assets and removes their cells. The footer always offers "Add
to location (n)" outside the trash: it dispatches `batchAddToLocationRequested`, the listener requests
`add_to_location_dialog` into the modal host, and `js/fragments/add-to-location.js` fills its hidden
`assetIds` field from the selection and keeps the fragment's success detail naming the chosen
Location; on success `assetsAddedToLocation` resets the selection and refreshes the counts.

Add location is a modal dialog (`add_location_dialog.scala.html`, it holds the pin editor below) requested by the top `#addLocationBtn` or, while there are no rows, by the centered
`#addFirstLocationBtn`; Add category also opens a modal from `#addCategoryBtn`. The renderer builds all three into their hosts and shows one host or the
other. Rename, Delete and Move to category open separate modals from the row's menu (`#locationMenuCtrl-{id}`
is the trigger they return focus to; Delete returns focus to `#addLocationBtn`, since the row goes
away); deleting the viewed Location runs a search back to the whole repository. `locationAdded` /
`categoryAdded` / `locationRenamed` / `locationMoved` / `locationDeleted` reload the list, which
restores focus by ID, or to the visible add control when the dialog's return control was hidden by
the change (`focusAddLocationControlIfFocusLost`). Add and Move share
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
that pin at zoom 12 instead of the world view. One editor exists at a time: hydrating disposes of the
previous map. Leaflet measures its container on creation, when the modal host is still hidden, so the
hydrator sizes the map once the container is displayed and on every resize. A missing or
out-of-range coordinate is one error, "Place the pin on the map", rendered once under the map. The
pin is an `L.divIcon` (`.location-pin`), so Leaflet's marker images are not vendored. When the
geocoder is enabled the fragment also renders `#locationGeocode` with a Search button: Enter or the
button asks `/api/map/r/:repoId/geocode` through the shared client (the newest search wins), the
results are buttons, and a click jumps the map to the place, places the pin, and fills the name when
it is empty with the label's first segment; a failed search is a snackbar. Leaflet is loaded as a
plain script (`window.L`) with its stylesheet by `index.scala.html`.

The server also accepts `bbox` and `layout=grid|map` for results, mirrored as `data-results-*` attributes. In map layout `search_results` disables Group and renders `htmx/map_view` without the `#assets` wrapper; there `bbox` is the crowded-pin panel's scope, so the total and `data-map-bounds` cover the whole search and the cells request sends the store's parameters verbatim plus `viewport` and `zoom`. That shell carries `#map`, `data-map-bounds="s,w,n,e"` (empty when no points), `data-map-count`, `data-map-tile-url` and `data-map-attribution`. Unit 7 adds the map hydrator, panel, layout toggle and the `layout` / `bbox` parameters to the client store.

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

**Infinite scroll + lazy load** — The last `.cell` gets class `last-cell` and carries how the next
page is reached: its number in `data-app-search-next-page` (an ungrouped grid) or the encoded
cursor in `data-app-search-after` (a grouped grid). An `IntersectionObserver` in
`js/search-results/infinite-scroll.js` watches it and calls `loadNextPage(lastCellEl)`, exported from the
same module: it reads the continuation, deletes the attribute, requests the page through `runSearch`
as `transient` parameters (`{ p }` or `{ after }`, with `isContinuousScroll`), and appends the result
after the cell. The request in flight is kept per cell, so a second caller gets the same promise and
no page is requested twice; the detail modal is that second caller when it steps past the last
loaded cell. A cell loses its attribute as it loads, so scrolling back over it loads nothing again.
A page the server rejects is reported through the snackbar like every failed request
(`js/listeners/htmx-requests.js`), which matters here because no visible control is behind it.
Each page appended is announced to the selection store (`noteGridChange`) so the date headers
recount.

**Date headers** — A grouped grid (`htmx/results_grid_grouped.scala.html`) opens each day with a
`.date-group` header: a checkbox over the day, a `<time datetime="yyyy-MM-dd">` with the
server-formatted day (**Saturday, January 2, 2025**), and the day's full match count across every
page in `.count` — as text (**(3 items)**, **(1 item)**) with the number itself in `data-count`,
which is what the client reads and decrements. The header spans
the row and is `position: sticky` at the top of `#content` (CSS only, in
`includes/search_results.scala.html`; `#assets` is an isolation root so the drag stand-in still
paints above it, and the background carries `--view-tint` so it matches the triage and trash panes).
A continuation of a day already on screen repeats no header, so the new cells read as the same
group. The one client-side change to a header is its count: `removeAssetsFromGrid`
(`js/assets/asset-actions.js`) calls `decrementDateGroupOf(cellEl)` from
`js/search-results/date-groups.js` before a cell leaves, which walks back to the nearest header,
takes one off, and removes the header at zero; a header whose loaded cells are all gone but whose
count is positive stays, since the day has matches on pages not loaded yet.

The header's checkbox is `x-data="initDateGroupSelectable()"`
(`js/alpine/components/date-group-selectable.js`). Its set is the day's cells *in the grid*, read off
the DOM as the header's following siblings up to the next header — a group owns no element of its
own, which is what lets a continued day append cells with no header of its own. Clicking it selects
or deselects them through the `selectedAssets` store, the same path a click on a thumbnail's
checkmark takes; it never fetches the day's unloaded pages. It shows checked only when the whole day is both loaded and selected, and the
indeterminate dash otherwise, so a day still scrolling in never reads as fully selected. `paint()`
writes that state into the box: it is the box's `x-effect`, and the box's `change` handler calls it
again after `toggle()`, since a click that changes neither count re-runs no effect. The click is not
cancelled on purpose: the browser restores a cancelled checkbox's `checked` and `indeterminate` once
the click is dispatched, after the microtask Alpine runs effects in, so a `@click.prevent` box would
show its pre-click state. Its counts are
reactive: they read the store's set (so a Shift-click, a box selection and Deselect All all repaint
it) and its grid version, bumped by `removeAssetsFromGrid` when cells leave and by the infinite
scroll when a page is appended into a day already on screen. Alpine batches effects per microtask,
so a box selection over hundreds of cells repaints each header once. Headers are never
selectable (box selection targets `[data-asset-id]`), never draggable, and carry no `img` or
`.metadata`, so lazy loading and metadata visibility skip them. Images use `data-src` instead of `src`; the same centralized
search-results fragment hydrator (`js/fragments/search-results.js`) binds selection, infinite scroll, lazy image loading, metadata visibility, and
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
from `js/search-results/detail-navigator.js`. The results grid is the modal's source of truth:
the coordinator remembers the asset it shows, and next/previous move to the nearest `.cell`
sibling of that asset's cell (`#asset-<id>`) in document order, skipping `.date-group` headers.
At the end of the loaded grid, next calls `loadNextPage` on the last cell - the same page load the
scroll observer makes, shared if already in flight - and continues into the cells it appended; at
the true end, and at the first cell, nothing happens. There is no shadow list and no JSON: a cell
removed from the grid drops out of navigation with it, and the search route is HTML only.

**Metadata field visibility** — Fields default to `display:none`. Use
`window.ctx.addGridMetadataField(name)` / `removeGridMetadataField(name)`, which mutate the
reactive `gridMetadataFields` set and persist it to `localStorage`. A reactive effect per displayed
grid (`js/search-results/metadata-visibility.js`) keeps one `show-<field>` class per visible field
on `#assets`, and the CSS in `includes/search_results.scala.html` shows the matching
`.metadata > .<field>` rows: no per-cell work, nothing to re-apply for appended pages.

**Nav refresh** — After any successful asset mutation, reload the nav to update counts.
Folder deletion also recycles assets throughout its subtree, so the `folderDeleted` listener
calls `app.reloadNav()` before awaiting the folder-tree refresh. Reuse `FrontendApp.reloadNav()`
from event listeners; it loads the nav fragment with:
```js
htmx.ajax("GET", `/htmx/nav/r/${window.ctx.getRepoId()}`, { swap: "innerHTML", target: "nav" })
```

Asset mutations (move, recycle, purge, restore, sorting from triage) also call
`app.reloadFolderCounts()`, which runs `refreshFolderCounts(repoId)` from `js/common/folder-tree.js`:
it re-fetches the tree JSON and patches each `#folder-count-{id}` in place, leaving expansion, focus,
and open menus alone. If the response holds a folder the DOM lacks (restoring assets can un-recycle
their folders), it falls back to a full render. Folder operations (add, rename, move, delete) keep
using `reloadFolderTree`, which renders fresh counts as part of the rebuild. `app.reloadAlbumCounts()`
and `app.reloadLocationCounts()` do the same for the album and Location lists (`refreshAlbumCounts`,
`refreshLocationCounts`), and fetch nothing while another explorer tab is active.

Asset move/recycle/purge/restore and album and Location membership UI flows are implemented in `js/assets/asset-actions.js`,
and drag/drop interact.js bindings live in `js/dragdrop/`. Event-listener modules call these
coordinators directly via `app.assetActions` / `app.searchDetailCoordinator`, while
`FrontendApp` remains the composition root that wires them together.

## Key Files

| File | Purpose |
|---|---|
| `static/js/constants.js` | All string constants: events, attributes, store keys, view names |
| `static/js/app.js` | thin bootstrap that exposes `initApp()` and starts `FrontendApp` |
| `static/js/context.js` | `window.ctx` — repo ID (set once by `index.scala.html`) and metadata field settings |
| `static/js/http/client.js` | shared axios client for non-HTMX requests; use per-request `validateStatus` overrides only where the UI intentionally handles a non-2xx response |
| `static/js/models/folder.js` | DOM wrapper around folder tree nodes (`Folder.find(id)`); owns branch expansion (`expand`, `expandAll`, `collapse` with descendant reset) |
| `static/js/common/viewed-folder-scope.js` | tracks the folder scope of the displayed results and marks it in the tree |
| `static/js/common/modal.js` | modal owner: `openModal`, `closeModal`, open identity (`isModalOpenActive`), explorer dialog anchoring, `setAssetDetailSize` |
| `static/js/common/anchored-panel.js` | viewport placement shared by popover menus and anchored explorer modals |
| `static/js/fragments/dialog-operations.js` | lifecycle of the operations every dialog submits; fragment kinds register their `isActive`/`close` |
| `static/js/fragments/inline-dialog.js` | View settings fragment shown in its popover |
| `static/js/common/snackbar.js` | `showSuccessSnackBar`, `showWarningSnackBar`, `showErrorSnackBar` |
| `static/js/search-results/selection.js` | the `selectedAssets` store (reactive set of IDs, paints the cells) and the grid's delegated click listener |
| `static/js/alpine/components/date-group-selectable.js` | Alpine component for a date header's checkbox: selects its day's loaded cells as a set |
| `static/js/search-results/box-selection.js` | box selection: Viselect gesture on the grid, committed through the store on release |
| `static/js/search-results/infinite-scroll.js` | `loadNextPage` and the observer on the last cell |
| `static/js/search-results/lazy-images.js` | loads thumbnails as they approach the viewport, placeholders them once far past it |
| `static/js/search-results/metadata-visibility.js` | keeps the `show-<field>` classes on `#assets` in step with the metadata fields set |
| `static/js/listeners/htmx-requests.js` | the outcome of every htmx request: failures, declared success events, tab selection, fragment hydration |
| `static/js/fragments/dialog-openers.js` | `data-app-open-dialog` buttons opening a page-held `<template>` dialog in the modal host |
| `static/js/search-results/click-suppression.js` | swallows the click the browser fires after an asset drag or a box gesture |
| `static/js/alpine/components/context-menu.js` | Alpine component coordinating a folder's or album's native popover menu; closes a menu from outside (Escape, ancestor collapse, modal open) |
| `static/js/stores/search-params.js` | the search parameter set, its defaults, and the scope rules that decide what a change clears |
| `static/js/search-results/search.js` | `runSearch` — the single entry point for every search request |
| `static/js/search-results/search-triggers.js` | binds `data-app-search` elements to `runSearch` |
| `static/js/search-results/detail-navigator.js` | next/previous over the grid's cells and image loading in the asset-detail modal |
| `static/js/search-results/date-groups.js` | keeps a date header's count current as cells leave the grid |
| `static/js/common/folder-tree.js` | renders the folder tree, its recursive asset counts (`numOfAssets` in the tree JSON), and each folder's menu from the JSON tree endpoint; patches the counts in place after asset mutations |
| `static/js/common/context-menu-markup.js` | builds the ⋯ menu cell of a folder or album and the dialog-trigger buttons |
| `static/js/common/album-list.js` | renders the album list, its counts, and each album's menu from the JSON list endpoint; patches the counts in place; marks the viewed album |
| `static/js/common/location-list.js` | renders the categories and Locations, their counts, and each row's menu from the JSON list endpoint; patches the counts in place; marks the viewed Location |
| `static/js/fragments/add-to-location.js` | fills the Add to location dialog's hidden selection field and keeps its success detail naming the chosen Location |
| `static/js/fragments/location-editor.js` | the Location pin editor: Leaflet map in the Add location modal, click and drag to place the pin, hidden coordinate inputs, readout, place-name search |
| `static/js/common/asset-count.js` | the `(n)` asset count cell and the column sizing shared by the folder tree and the album list |
| `views/includes/html_common.scala.html` | Snackbar + the two Alpine-bound modal hosts |
| `views/includes/search_results.scala.html` | Search grid wrapper with the Group and Sort controls; the controller passes in the rendered grid partial |
| `views/htmx/result_cell.scala.html` | One asset cell, shared by both grids, with no Alpine of its own; a page's last cell carries the next page number or the cursor |
| `views/htmx/results_grid.scala.html` | The ungrouped grid: the page's cells, infinite-scroll trigger by page number |
| `views/htmx/results_grid_grouped.scala.html` | The grouped grid: a date header per day, infinite-scroll trigger by cursor |

## Search parameters

Every search in the app — the initial load, folder and person navigation, the Sort and Group
dropdowns, continuous scroll, the page the detail modal loads at the end of the grid — goes through
**one** function, `runSearch` in `js/search-results/search.js`. A caller supplies only the parameter
it knows about; the `searchParams` store supplies the rest. Nothing else builds a URL for
`/htmx/search/r/:repoId`.

`js/stores/search-params.js` owns the parameters (`view`, `folderId`, `personId`, `albumId`,
`locationId`, `q`, `sort`, `groupBy`, `groupDirection`, `rpp`, `p`) and the rules for combining them:
choosing a folder, a person, an album, or a Location clears the other three, a view clears all four, and any change other than paging
returns to page 1. Grouping is a reorder like the sort and survives all of those. A parameter still at
its default is left out of the request, so a default is never spelled out on both sides — except
`view`, which is always sent, and whose values match `Const.Search.View.*` server-side verbatim.

`groupBy` (`dateTaken`) with `groupDirection` (`asc`/`desc`) groups the grid by
day; the Group dropdown in `search_results.scala.html` sets both from its selected option, and "No
grouping" sets both to empty, which the store normalizes to `null`. A grouped search has no page
number: the serializer leaves `p` out whenever `groupBy` is set (the server rejects the pair), and
the grid is continued by the transient `after` cursor its last cell carries. Grouping is not
remembered in `localStorage`; like every other parameter it lives in the store and the bookmarkable
URL.

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
```

The hydrator also owns the rule that folder navigation does nothing in triage and trash, so that
guard lives in one place rather than in markup.

Per-request flags that must not be remembered (`isContinuousScroll`, a continuation's `p` or
`after`) are passed as `transient` and are serialized into that one request only. A server-side action that should change what is
displayed reports it as a custom event and lets JS run the search — as the people merge does with
`personMerged` — rather than redirecting to a search URL of its own.

