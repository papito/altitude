# Front-end Architecture Audit

Audited on 2026-09-07 against branch `feature/grouping` at commit `a9bfd3ba`.

Scope: everything under `altitude/static/` (56 application modules, ~5,000 lines, ~216 KB
unminified; vendored libraries ~340 KB JS + 74 KB CSS), all 35 Twirl templates under
`altitude/views/`, the two AGENTS guides, `build.mill`, the lint and formatter configuration, and
the controllers that produce the HTMX partials. Every file was read in full. Claims marked
**verified** were checked by running a command or reading library source; claims marked **likely**
are inferred from the code and should be confirmed in a browser before acting on them.

The architecture is a hand-rolled composition of HTMX 4, Alpine 3.13, native ES modules, and a
custom-event bus. It is not a framework anyone else uses, so this report judges it on its own
terms: does the design hold together, where does it cost more than it returns, and what will hurt
as the library grows.

---

## Executive summary

The front-end is in far better shape than a "hand-rolled HTMX/Alpine" label suggests. The hard
problems of server-driven UIs are solved deliberately: one search funnel owns every URL, stale
responses are discarded by identity rather than by luck, cleanup hooks exist where components own
listeners, and the module graph is acyclic. No memory leak was found.

The costs come from three places:

1. **Three of everything.** Three transports to the server (htmx attributes, `htmx.ajax`, axios),
   three attribute namespaces on the DOM (`hx-*`, `data-app-*`, `alt-*`), and three event layers
   (custom events on `body`, htmx lifecycle on `body`, htmx lifecycle on `document`, plus Alpine
   `x-on:htmx:*` on menu panels). The right handler for a response depends on registration order
   across those layers, which no single file shows.
2. **Alpine used where it is expensive and fought where it is in the way.** Every asset cell is a
   reactive component, so the grid's cost grows with Alpine's, while the modal code spends 300
   lines polling `x-show` and patching a focus-trap plugin to make a `div` behave like `<dialog>`.
3. **No safety net.** There are zero JavaScript tests, and the ESLint configuration is inert
   (**verified**: the flat config has an empty `rules` block, so `npm run lint` passes anything).
   The 605-line `views/AGENTS.md` is doing the job that tests and smaller modules should do, and
   it has already started to drift from the code.

The performance risk that matters is unbounded grid growth under infinite scroll combined with
per-cell reactivity, per-cell inline style writes, and per-image observers. Everything else is
second order.

Recommended order of work: fix lint and add tests for the pure modules (hours), delete the dead
paths listed below (hours), let the server announce events with `HX-Trigger` so the response router
can go (a day), move per-cell metadata visibility to CSS classes and cap the grid's DOM cost (a
day), then decide the selection model and the `<dialog>` migration (each a few days).

---

## 1. How it works, in one page

- `index.scala.html` is the only real page. Its inline module script calls `initApp()`, seeds the
  `searchParams` store from the URL, mounts Split.js, simulates a click on the active explorer tab,
  and calls `runSearch()`. Everything after that is HTMX swaps into `#content` and
  `#explorerPanelContent`.
- `FrontendApp` (`static/js/frontend-app.js`) is the composition root: it registers stores, wires
  `document.body` listeners, starts Alpine, binds interact.js, and hydrates `data-app-fragment`
  roots on load and after every `htmx:after:settle`.
- Server state changes travel one of three ways: an `hx-*` attribute on the element, `htmx.ajax()`
  from JS (nav reload, merge modal, every search), or the axios client for JSON APIs (moves,
  recycles, tree and album JSON). Success is reported back through `CustomEvent`s named in
  `constants.js`, which the `listeners/*` modules turn into DOM patches, count refreshes, and
  snackbars.
- Alpine holds five stores (selection, results total, search params, current view, modal) and
  three components (`selectable` per cell, `dateGroupSelectable` per header, `contextMenu` per
  folder/album). Templates bind to stores with `x-show`, `x-text`, `:class`.
- The folder tree and album list are rendered client-side from JSON, including their native
  `popover` menus; every other fragment is server-rendered Twirl.

---

## 2. What is good

**The search funnel.** `search-results/search.js` and `stores/search-params.js` are the best
part of the codebase. One function builds every search URL; the store is the single source of
truth; the browser URL is read exactly once and thereafter is only a projection pushed by the
server. The scope rules (`CLEARS`) are a data table, not scattered `if`s. The three exported
functions are pure and trivially unit-testable. The server side matches: `SearchResultsController`
reads only its own query string.

**Race handling is treated as a first-class concern.** Monotonic sequence guards in
`folder-tree.js` and `album-list.js`, open identities in `modal.js`, image request tokens in
`detail-navigator.js`, the per-cell pending-page map in `fragments/search-results.js`, and the
"only the response the open panel is waiting for may show" rule in the context menu component.
These are the bugs that make HTMX apps feel broken, and they are handled by identity, not by
timing.

**Declarative hydration.** `data-app-fragment`, `data-app-search`, and `data-app-dialog-*` let
markup declare intent and let one hydrator per kind bind it, idempotently (the `*Bound` flags).
This is the right pattern for HTMX: the server ships HTML, the client recognizes shapes.

**Native platform first.** Popover API for menus, `<time datetime>`, the `hidden` attribute,
IntersectionObserver, `:popover-open` styling, `queueMicrotask` coalescing, WeakMaps for
per-element state, AbortController for upload cancellation, ResizeObserver with the
first-notification guard. Very little is reinvented.

**Cleanup discipline where it counts.** Alpine `destroy()` hooks detach listeners and observers;
box selection keeps a `detach` list per gesture; image loads clean their listeners; the
continuation observer `unobserve`s a cell the moment it loads. See section 6.

**Acyclic module graph, explicit composition.** `FrontendApp` passes dependencies into
`createAssetActions` and `createSearchDetailCoordinator` instead of reaching for globals
(**verified**: a walk of every `import` from `app.js` and `global.js` found no cycles).

**Vendoring with provenance.** `static/js/lib/README.md` records version and source for every
library. No bundler, no npm runtime dependency, one JVM to deploy. This matches the product goal.

**Security hygiene.** Snackbar writes `textContent`; validation messages are parsed with
`DOMParser`; forms post JSON through `hx-json-enc`; Twirl escapes by default.

**Documentation of intent.** Module headers and template comments explain *why* (the checkbox
click-cancel rationale in `date-group-selectable.js`, the `settle:0` rationale in
`BaseController`, the Viselect replay adapter). This is rare and valuable even where this report
argues the mechanism itself should change.

**Accessibility effort.** Focus is placed on open, restored on close, and preserved across tree
rebuilds by stable IDs; branch buttons carry `aria-expanded`/`aria-controls`; menus have labels;
modals use `role="dialog"` with `aria-modal` and `inert` on the rest of the page.

---

## 3. What is bad

### 3.1 Three transports, three namespaces, three event layers

- **Transports.** Folder delete is an `hx-delete` attribute; folder move is an axios `PUT` to an
  `/htmx/` route that returns an empty body (`listeners/folders.js:32`); nav reload and the merge
  modal are `htmx.ajax()`; the upload is axios because htmx 4's `fetch` has no upload progress.
  The stated boundary ("htmx for HTML, axios for JSON") is not held.
- **DOM namespaces.** `hx-*` for htmx, `data-app-*` for hydration, `alt-*` for identity. `alt-*`
  is not a valid custom-attribute prefix, so those values are not reachable through `dataset`, and
  `constants.js:41-46` carries a CAUTION block admitting the strings are duplicated in templates.
- **Event layers.** `listeners/htmx-search.js` registers `htmx:after:request` on `document.body`;
  `listeners/dialogs.js` registers the same event on `document`; the `contextMenu` component
  registers it on the panel. Bubbling order (panel, body, document) is what makes
  `FrontendApp.handleAfterRequest` need the early return at `frontend-app.js:95` so it does not
  double-report a dialog failure that `dialogs.js` will settle a few microseconds later. That
  ordering constraint is documented in prose only.

### 3.2 Response routing by URL and body sniffing

`frontend-app.js:90-129` is a hand-written router: it classifies responses by path prefix
(`/htmx/folder/r/<id>/`, `/htmx/trash/.../purge`, `/htmx/search/r/`) and dispatches to handlers.
`listeners/htmx-people-inline-editor.js:97` goes further and sniffs the response HTML for
`id="editPersonName"` to tell a validation replacement from a success. `dialog-operations.js:112`
treats the *presence* of an `HX-Retarget` header as "validation failed".

The server knows exactly what it just did. It should say so (section 8, item A).

### 3.3 State lives in several places, and the DOM usually wins

"What is selected" is a `Map<id, componentProxy>` in a store, a `selected` flag on each Alpine
component, and a `.selected` class on each cell. `reset()` on the store cannot clear the map
directly; it dispatches `deselectAll` so every component deselects itself
(`stores/app-stores.js:17`). Date group counts live in `data-count`; the continuation lives on the
last cell; the viewed scope is a module variable plus an attribute; expansion is an attribute.
Each choice is defended in the docs, and the "DOM is the source of truth" rule is coherent for
HTMX. But a store that contains UI component objects is neither a store nor a view.

### 3.4 Per-cell Alpine components

Every asset cell is `x-data="initSelectable(id)"` with a `:class` effect
(`htmx/result_cell.scala.html:21`). That is one reactive scope, one proxy, and one effect per
asset, forever, under infinite scroll. `date-group-selectable.js:159` reaches each cell's
component through `Alpine.$data`, and box selection does the same. Alpine is the wrong tool for a
10,000-row list; section 5 quantifies it.

### 3.5 The modal fights `x-show`

`common/modal.js` is 303 lines. It needs `whenHostDisplayed` (`modal.js:262`), which polls
`getComputedStyle` in a `setTimeout` loop up to 100 times because `x-show` reveals in a later
task; a `nextTick` handoff when switching hosts so two `x-trap.inert` effects do not undo each
other; `.noreturn.noautofocus` modifiers so the plugin stays out of the way; and a local patch to
the vendored focus plugin (`alpine-focus.esm.js:933`) so a released trap cannot fire late. All of
this reimplements what `<dialog>.showModal()` provides natively: top layer, focus containment,
inert background, Escape, backdrop, synchronous display.

### 3.6 CSS ships with fragments

Nineteen templates carry inline `<style>` blocks. `includes/search_results.scala.html` re-sends
about 220 lines of CSS on every search; `htmx/folders.scala.html` on every tab switch. Each swap
inserts a new stylesheet into the document, which triggers a full style recalculation, and the
same rules are parsed again and again. The only value that genuinely depends on the server is the
preview box size (`Const.AssetView.PREVIEW_BOX_PIXELS`), which could be one CSS variable.

Separately, `/static` is served with no caching or versioning strategy (**verified**: no
`Cache-Control` or ETag configuration anywhere in `src/`).

### 3.7 No tests, inert lint

- `package.json` has no test script, and there is no JS test file anywhere in the repository.
- **Verified:** ESLint 9 reads only `eslint.config.js`, which contains `settings` and nothing
  else; `npx eslint --print-config` on `app.js` reports `"rules": {}`. The older `.eslintrc.json`
  is ignored, and it names `eslint-plugin-html`, which is not installed. `npm run lint` currently
  cannot fail.
- The Scala side has 32 controller tests that pin the HTTP contract. Nothing pins the client's
  half of it.

### 3.8 Documentation as the type system

`views/AGENTS.md` is 605 lines of prose contract. It is excellent prose, and it is already
drifting: it describes `event_handlers.js` and `htmx_event_handlers.js` files that were deleted;
`constants.js:41-46` and `context.js:6-10` reference SSP template syntax the project no longer
uses; `AlbumActionController` says "Add is a modal dialog" when it is inline. Every rule in that
document that a test or a type could enforce is a rule that will eventually be broken silently.

### 3.9 Bleeding-edge foundation

htmx 4.0.0 shipped on 2026-08-28, ten days before this audit, and the project's own migration
plan notes npm `latest` still points at 2.0.10. Two shims sit in the request path:
`hx-alpine-compat.js` (its `htmx_before_swap` handler at lines 43-53 computes a target and then
does nothing with it) and a `json-enc.js` pinned to a community-repo commit. Being this early is
a legitimate choice for a single-user self-hosted app, but it means every odd swap behaviour has
two suspects before the app's own code.

---

## 4. Patterns to improve

Severity: **H** worth doing soon, **M** worth doing when touching the area, **L** cosmetic.

### A. Response handling

| # | Sev | Finding | Improvement |
|---|-----|---------|-------------|
| A1 | H | `frontend-app.js:90-129` routes responses by URL prefix. Four of its six branches do the same thing: show a snackbar on a non-2xx status. | One generic rule ("any htmx response ≥ 400 shows `path` + status") replaces those four branches. The remaining two (purge success refreshes counts; person name edited / discarded) become server-announced events, below. |
| A2 | H | Success events are declared client-side in `data-app-dialog-success-event` + `-detail` + `-detail-target-attr-*` (`dialog-operations.js:59-70`, `fragments/helpers.js:59-79`), then looked up by key in `Const.events`. | Let the server emit `HX-Trigger: {"FOLDER_ADDED_EVENT": {"target": "body", "parentId": "…"}}`. **Verified** in the vendored htmx 4: every `HX-*` response header lands in `ctx.hx`, and `HX-Trigger` is dispatched from the `finally` stage; the JSON form accepts a per-event `target` selector and passes the object as `event.detail`. Naming `body` as the target is what makes this work when the dialog has already left the DOM, which is the case the current tracker exists for. The existing `document.body` listeners then receive it unchanged. Deletes the success-metadata parsing, `dispatchSuccessEvent`, and the `Const.events[key]` indirection. |
| A3 | M | Validation is detected by header *presence* (`getResponseRetarget`) and, for the person name, by body sniffing (`htmx-people-inline-editor.js:97`). | Use status codes: success is `204` (already in `noSwap`), validation is `200` with the re-rendered form and `HX-Retarget: this`. Then "validation" is `status === 200` on a dialog request, and `extractValidationMessages` is only needed for the closed-dialog case. |
| A4 | L | `isExplorerRequest` includes the repo ID in its prefix; `isSearchRequest` does not. | Goes away with A1. |

### B. Selection model

| # | Sev | Finding | Improvement |
|---|-----|---------|-------------|
| B1 | H | Per-cell `x-data="initSelectable"` + `Map<id, proxy>` in the store + `deselectAll` event round trip + `gridSelectionChanged` broadcast + `Alpine.$data(cell)` lookups in two modules. | Selection becomes a reactive `Set<assetId>` in the store. One delegated `click` listener on `#assets` toggles the ID and the cell's class. `drag()`/`drop()`/`deselect()` become `classList` operations on `#asset-<id>`. Date headers and box selection read the Set. Deletes `selectable.js`, `notifyGridSelectionChanged`, the `deselectAll` listener, and one reactive scope per cell. |
| B2 | M | `date-group-selectable.js` recounts by walking siblings and calling `Alpine.$data` on each. | With B1 it counts IDs in the Set against the IDs in its DOM range, and an `Alpine.effect` on the Set replaces the `gridSelectionChanged` subscription and the microtask coalescing. |

### C. Naming and module layout

| # | Sev | Finding | Improvement |
|---|-----|---------|-------------|
| C1 | M | Two files named `context-menu.js` (`common/` and `alpine/components/`); two named `dragon-drop.js` (`common/` and `search-results/`) plus a `dragdrop/` directory. `search-results/dragon-drop.js` binds interact.js as an import side effect (`frontend-app.js:30`) while every other binding goes through `bindAppDragDrop`. | Move it to `dragdrop/assets.js` exporting `bindAssetDragDrop`; rename `common/context-menu.js` to `common/context-menu-markup.js` or fold it into the component file. |
| C2 | M | `listeners/htmx-search.js` registers Escape, next/previous, and view-setting listeners, none of which are htmx. `listeners/htmx-routes.js` is two predicates. `htmx-people-inline-editor.js` also handles Discard. | Name by domain: `listeners/search.js`, `listeners/people.js` (merge the two people files). Fold `htmx-routes.js` into A1. |
| C3 | M | `fragments/search-results.js` (364 lines) does viewed-scope sync, builds the View control, metadata visibility, infinite scroll, lazy images, and exports `handleViewSettingChanged` for another listener. | Split into `search-results/infinite-scroll.js`, `search-results/lazy-images.js`, `search-results/metadata-visibility.js`; the hydrator becomes a ten-line sequence of `bind*` calls. |
| C4 | L | `getLazyImageObserver` stores its observer as `app.lazyImageObserver` (`fragments/search-results.js:276`), an ad-hoc property nobody declares. | Module-level singleton like `nextPageObserver`, or a field declared in `FrontendApp`. |
| C5 | L | Alpine is reached three ways: ESM import (`modal.js`, `search.js`, …), injected parameter (`asset-actions.js`, `detail-navigator.js`), bare global (`selectable.js:28`, `context.js:14`). The ESM import returns the same singleton, so injection buys nothing. | Import it everywhere; drop the `Alpine` parameters. Declare `htmx` and `interact` as globals in the (repaired) lint config. |

### D. Smells and dead code

| # | Sev | Finding | Improvement |
|---|-----|---------|-------------|
| D1 | H | **Verified dead paths.** `Const.events.folderTrashed` is dispatched (`search-results/dragon-drop.js:194`) but has no listener, and the branch is unreachable because the `#trash` dropzone's `accept` list (`dragon-drop.js:174`) excludes folder rows. `folderCollapsed`, `toggleAsset` are never referenced. `detailShown` is dispatched and never listened to. `resultsTotal.increment` and `htmx-events.getRequestTarget` are unused. The nav search box and the Settings link (`includes/nav.scala.html:45-53`) are inert UI. | Delete, or implement folder-to-trash if it is a wanted feature (open question 4). |
| D2 | M | **Likely double initialization.** `frontend-app.js:146-148` calls `Alpine.initTree(node)` for `#folderNavWarning` on `htmx:after:settle`. htmx 4 fires that event after an awaited settle timeout (**verified** in `htmx.min.js`), by which time Alpine's mutation observer has already initialized the node. Alpine 3.13.10 has no re-init guard (**verified**: no `_x_marker` in the bundle; the guard arrived in a later 3.14.x release). The rule "do not call `initTree`" is stated in `folder-tree.js:138` and `views/AGENTS.md`. | Remove the call and confirm the warning still shows in triage/trash. If it does not, the cause is the compat shim's mutation deferral, not a missing init. |
| D3 | M | `hx-alpine-compat.js` was added in the same commit as D2 (`a3db0b86`). It defers Alpine mutations during swaps; the app uses no morph swaps, which is the shim's main purpose. | Test without it. Keep at most one of D2 and the shim. |
| D4 | M | `models/folder.js` constructor throws when the node is absent; three call sites wrap it in `try/catch` (`folder-tree.js:462`, `asset-actions.js:95`, `listeners/folders.js:65`). Root is its own parent (`folder-tree.js:273`), so `isDescendantOrSelf` needs a cycle guard and `_restoreExpandedState` special-cases root. | Add `Folder.find(id)` returning `null`; emit no parent attribute on root. |
| D5 | M | Metadata visibility writes inline `display` on every metadata div of every cell, on hydration, on every appended page, and on every checkbox change (`fragments/search-results.js:96-112, 339-364`). | Toggle `show-<field>` classes on `#assets` and let CSS do it: `#assets.show-fileName .metadata .fileName { display: block }`. Zero per-cell work, no re-application per page. |
| D6 | M | `alt-*` attributes (section 3.1). | Migrate to `data-*` (`data-asset-id`), read through `dataset`, and shrink `Const.attributes` to dataset keys. Mechanical, template-wide. |
| D7 | L | `window.ctx.setRepoId(…)` is repeated in `index`, `folders`, `albums`, `people`, `trashbin_header`. The repo ID cannot change without a full page load. | Set it once in `index`. The per-partial module scripts then exist only for `selectTab`, which a single `htmx:after:settle` listener on the tab panel can do. |
| D8 | L | `context.js:53-65` re-registers the same reactive Set with `Alpine.store` on every add/remove (a no-op that reads as if it does something) and persists the Set as a comma-joined string. | Mutate the Set; persist JSON. |
| D9 | L | `selectable.js:37-45`: the comment says `@click.shift` "does not seem to work", so the handler checks `shiftKey` itself, making the modifier redundant. The same `<img>` also carries `hx-trigger="click[!event.shiftKey]"`, so two frameworks split one click. | Drop the modifier and the speculative comment. With B1 the click is one delegated listener. |
| D10 | L | `index.scala.html:219-225` loads the explorer tab by finding an `<a>` and calling `.click()`. | `hx-trigger="load"` on the tab that should be active, or an explicit `htmx.ajax` in `initApp`. |
| D11 | L | `pipeline.scala.html:107` polls the nav every 5 s with `setInterval` while a WebSocket already pushes import status. | Push counts over the socket, or `hx-trigger="every 5s [document.visibilityState === 'visible']"`. |
| D12 | L | `people.scala.html:84-124`: four radio buttons each repeat five `hx-*` attributes. | One `hx-get` on the group with `hx-trigger="change"` and `hx-include`. |
| D13 | L | `hx-confirm` (native `confirm()`) for purge, while everything else uses the app's dialogs. | Route through an inline or modal dialog. |
| D14 | L | `listeners/folders.js:32-34` builds a query string by interpolation for a PUT. | Pass `params`. |
| D15 | L | `constants.js:1` is `export let Const`; the CAUTION comment at 41-46 references SSP. `context.js:6-10` shows `<%= %>` syntax. | `const`; fix the comments. |
| D16 | L | Tab list: `<button role="tab">` wrapping `<a hx-get>` is interactive content inside interactive content (invalid HTML); `aria-selected="True"` is capitalized and `tabs.css:50` keys on that exact string. | Make the `<a>` the tab, lowercase `true`. |
| D17 | L | `edit_person_name.scala.html:22` reads `fieldErrors(Api.Field.Folder.NAME)` under a `Person.NAME` guard (copy-paste; works only while both constants are `"name"`). `people.scala.html:152` calls `.get` on an Option in a template. `choose_person_cover_face_modal.scala.html:37` is missing a semicolon, so the hover border and cursor never apply. | Fix. |
| D18 | L | `static/js/out/` is a mill state directory inside `static/` (**verified**: gitignored, but present on disk), and `build.mill:135` copies `static` wholesale into the JAR. | Filter the copy or delete the directory. |

### E. Complexity hotspots

By size: `folder-tree.js` 555, `search-results.js` 364, `box-selection.js` 349, `album-list.js`
310, `asset-actions.js` 309, `modal.js` 303, `context-menu` component 275, `detail-navigator.js`
249.

- **`box-selection.js`** spends 60 lines (`refreshGeometry`, `resumeAutoscrollAtEdge`,
  `replayPointer`) dispatching synthetic `mousemove` events so Viselect re-evaluates its rectangle.
  The adapter is version-bound and larger than a hand-rolled rectangle would be: `mousedown` →
  `mousemove` → `mouseup` on `#assets`, a fixed `div` for the box, `getBoundingClientRect`
  intersection over `[data-asset-id]`, and a small autoscroll loop. Owning it removes the library,
  the replay hacks, and the "Viselect decides once whether the container can scroll" caveat.
- **`folder-tree.js` and `album-list.js`** duplicate the renderer skeleton (sequence guards,
  `_patchAssetCounts`, focus snapshot/restore, `_ensureAddControls`, `htmx.process` +
  `bindSearchTriggers`). A shared `createListRenderer({ fetch, buildRow, container, countVar })`
  would halve the album file and remove the copy-paste risk between the two.
- **`modal.js`** shrinks to a fraction with `<dialog>` (section 8).
- **`context-menu` component `place()`** (45 lines of measure/flip/clamp) is what CSS anchor
  positioning does declaratively (section 8).

---

## 5. Performance risks

Ordered by how soon they bite as a library grows.

**P1 (H). Unbounded grid growth.** Infinite scroll appends cells forever. At 10,000 assets that is
roughly 120,000 DOM nodes in one `flex-wrap` container, 10,000 Alpine scopes with their proxies
and `:class` effects, 10,000 IntersectionObserver targets on the lazy-image observer, and sticky
headers whose stacking is recomputed on every layout. Layout of a flex-wrap container is linear in
children, and it reruns on resize, on every Split.js drag, and whenever a cell changes size.
Mitigations, cheapest first:

1. `content-visibility: auto; contain-intrinsic-size: 210px 260px` on `.cell`. One CSS rule; the
   browser skips layout and paint for off-screen cells. Cell height is fixed by the grid rows, so
   the intrinsic size is exact.
2. B1, which removes the per-cell reactive cost.
3. Prune pages far above the viewport and keep a window of N pages. Forward continuation already
   works by cursor; backward needs either a `before` cursor or re-requesting the pruned page by
   its stored `after` value (the ungrouped grid has page numbers already).
4. Virtualization only if the above is not enough; it conflicts with "the DOM is the source of
   truth" and should be a last resort.

**P2 (M). Lazy image observer geometry.** `rootMargin: "0px 100% 0px 100%"`
(`fragments/search-results.js:282`) extends the root horizontally on a vertically scrolling grid,
so images start loading only once they are already visible, and they are unloaded the moment they
leave, forcing a re-decode on every scroll back. Set a vertical margin (`"200% 0px"`) or, simpler,
drop the observer and the placeholder swap for `loading="lazy" decoding="async"` and let the
browser's image cache manage memory. Keep the observer only if measured memory on real libraries
demands unloading.

**P3 (M). Count refresh fan-out.** Every asset mutation fires three requests
(`asset-actions.js:21-25`): the nav fragment, the entire folder tree JSON, and the album list.
Server-side the tree is two queries (**verified**: one folder query plus one `GROUP BY` count
query), so the cost is payload size and the client-side walk, times the number of drops in a
burst. Debounce `refreshCounts` (200 ms), or have the mutation response carry the affected counts,
or push them via `HX-Trigger`.

**P4 (M). `interact.dynamicDrop(true)`** (`dragdrop/index.js:12`) recomputes every dropzone's
rectangle on every `dragmove`. Dropzones are every folder row, every album row, every person cell,
and the trash. On a tree with hundreds of folders that is hundreds of `getBoundingClientRect`
calls per pointer move, each of which can force layout after the transform write in
`dragMoveListener`. Recompute rects on `scroll` of `#explorer` instead, which is the only reason
`dynamicDrop` was enabled.

**P5 (M). Fragment CSS** (section 3.6): a style recalculation per swap and the same bytes on every
search. Move stable rules to `core.css` or a per-feature stylesheet; emit
`style="--preview-box: 200px"` on the fragment root for the one server-dependent value.

**P6 (M). First load.** No bundler and an import depth of 8 from `app.js` (**verified**) means up
to eight sequential round trips before `initApp` runs, then `runSearch`, then the tab fragment,
then the tree JSON, then images. `/static` is uncached, so this repeats on every load. Without a
bundler: emit `<link rel="modulepreload">` for every module (a small mill task can list them),
serve `/static` with a long `Cache-Control` and a version query string, and server-render the
first results page into `index` (the controller already resolves the same parameters) so results
paint with the page instead of two round trips later.

**P7 (L). Per-cell inline style writes** (D5) and `sizeCountColumn` forcing one layout per
distinct count text (`asset-count.js:40-43`; `ch` units or `canvas.measureText` avoid it).

**P8 (L). Payload.** Full Font Awesome CSS (58 KB) for about fifteen icons; axios (53 KB) for a
dozen calls where `fetch` plus one XHR for upload progress would do; interact.js (98 KB) for drag
and drop. About 340 KB of vendored JS before the app's own 216 KB. Acceptable for a self-hosted
tool; noted so the trade-off is deliberate.

---

## 6. Memory leak review

Every `addEventListener`, timer, observer, and module-level container was traced. **No unbounded
leak was found.** The code is unusually careful about this.

Safe by construction:

- `document.body` / `document` listeners are registered once at startup (`listeners/*`,
  `global.js`, `click-suppression.js`).
- `dateGroupSelectable.destroy()` removes its body listener; `contextMenu.destroy()` and the
  close path detach scroll, resize, and the ResizeObserver. Both rely on Alpine's mutation observer
  calling `destroyTree` for removed nodes, which holds for htmx swaps and for the
  `container.innerHTML = ""` rebuilds. The one way this could fail is a swap during which Alpine
  mutations are deferred and never flushed; the compat shim's `htmx_finally_request` guards that.
- Box selection keeps every listener and observer in `gesture.detach` and clears it on release,
  cancel, or grid replacement; `bindBoxSelection` destroys the previous controller before creating
  the next.
- `pendingOperations` (a Map keyed by request context) is cleared in both `after:request` and
  `finally:request`. `pendingPageLoads` and `pendingImageLoads` are WeakMaps.
- `selectedAssets.items` holds component proxies, but every results hydration calls `reset()`, so
  proxies of a replaced grid never outlive it, and batch flows reset after removing cells.
- Snackbar and click-suppression timers are cleared before re-arming. The modal display poll stops
  at 100 attempts or when the open is superseded.

Watch list (not leaks today, but the shapes that become leaks):

- `app.lazyImageObserver` and `nextPageObserver` are never `disconnect()`ed, and every `<img>`
  ever loaded is `observe()`d without a matching `unobserve` when its cell is removed or the grid
  is replaced. Modern engines hold observation targets weakly, so detached grids are still
  collectable, but the current grid's target list grows without bound (P1). Call `disconnect()`
  on the lazy observer in `hydrateSearchResultsFragment` and `unobserve` images in
  `removeAssetsFromGrid`.
- `modal.js` keeps `returnFocus.opener` (a DOM element) for the life of an open. If a modal stays
  open across a grid replacement it retains one detached element until close. Negligible.
- Split.js and interact.js instances are created once and never destroyed. Fine, because every
  view change is a full page load. It would matter if navigation ever becomes in-page.
- `hx-alpine-compat.js` maintains a `deferCount`; if a swap ever threw between `before_swap` and
  both `after_swap` and `finally_request`, Alpine would stay in deferred mode and no new component
  would initialize. Not observed; listed because the failure mode is silent.

---

## 7. Correctness issues found in passing

Not the audit's question, but each was verified while reading and is cheap to fix.

- Lint is inert (3.7). Anything can be committed under `make lint`.
- Folder dropped onto Trash does nothing, silently (D1).
- `Alpine.initTree` double-binds the folder warning (D2, likely).
- `ContentViewController` has no authentication (its own TODO), so every preview, file, and face
  URL is public. Server-side, but it is the front-end's image pipeline.
- `edit_person_name.scala.html:22` wrong error key; `people.scala.html:152` `Option.get`;
  `choose_person_cover_face_modal.scala.html:37` missing semicolon (D17).
- `static/js/out/` would be packaged (D18).

---

## 8. Recommendations

### Prioritized roadmap

1. **Repair the safety net (half a day).** Port the rules from `.eslintrc.json` into
   `eslint.config.js` (or delete the old file), add `no-unused-vars`, `no-undef` with `htmx`,
   `interact`, `Alpine` as globals, and `import/no-cycle`. Add a test runner (Node's built-in
   `node:test` with `jsdom`, or Vitest) and cover the pure modules first: `search-params.js`,
   `fragments/helpers.js`, `date-groups.js`, `click-suppression.js`, `asset-count.js`. Then a
   handful of Playwright flows (search, group, select, drag to folder, each dialog) against the
   existing `ControllerTestCore` server.
2. **Delete the dead paths and fix the small bugs (half a day).** D1, D2, D3, D15, D17, D18.
3. **Let the server announce events (a day).** A1 to A3. This removes the response router, the
   success-metadata attributes, and the body sniffing, and it is the single largest readability
   gain available.
4. **Grid cost (a day).** D5 (CSS classes for metadata), P1 item 1 (`content-visibility`), P2
   (lazy-load margins or native lazy loading), observer disconnects from section 6.
5. **Static delivery (a day).** P5 (CSS out of fragments), P6 (cache headers, `modulepreload`,
   server-rendered first page).
6. **Selection model (a few days).** B1 and B2. Unlocks P1 pruning and deletes the most indirect
   code in the app.
7. **`<dialog>` migration (a few days).** Replaces `modal.js` polling and handoffs, the focus
   plugin and its patch, and the `x-trap` modifiers. The context menu already trusts the native
   popover; this is the same bet for dialogs.
8. **Hand-rolled box selection** instead of Viselect plus its replay adapter, when next touching
   that file.
9. **Shared list renderer** for folders and albums, when next touching either.

### Alternatives worth weighing

- **Alpine's role.** Alpine does five stores, a handful of `x-show`/`x-text` bindings, and three
  components. The app's own patterns (declarative hydration, custom events) already cover most of
  that. This report does not recommend removing Alpine now, only not extending its surface until
  B1 is decided. If B1 lands and `<dialog>` lands, the remaining Alpine surface is small enough
  that dropping it (44 KB + 37 KB plugin + 4 KB shim) becomes a real option.
- **Custom elements for lifecycle.** The `data-app-fragment` + `*Bound` flag + `htmx:after:settle`
  hydration reimplements `connectedCallback`, and there is no equivalent of
  `disconnectedCallback` for non-Alpine hydrators (the `#assets` listeners, box selection). A
  `<search-results>` or `<asset-grid>` element gets both hooks from the platform, works with
  htmx swaps unchanged, and needs no library. This is the most direct answer to "what should the
  hand-rolled framework be built on".
- **htmx 2.0.10 until 4.x settles.** The migration plan itself recommended holding. Staying on
  4.0.0 is fine for a single user, but budget time for upstream fixes.
- **Bundling.** If "no bundler" is a preference rather than a rule, a single `esbuild` command
  (Node is already in the dev loop for Prettier and ESLint) turns 56 requests into one minified
  file with source maps, and removes P6 entirely. If it is a rule, `modulepreload` plus cache
  headers gets most of the way.

### Cutting-edge options

Applicable because this is a self-hosted, single-user app whose browser can be chosen.

- **`<dialog>` with `showModal()`** for both hosts. Baseline across browsers.
- **CSS anchor positioning** (`anchor-name`, `position-anchor`, `position-area`,
  `position-try-fallbacks`) replaces `contextMenu.place()`, including flip and viewport clamping,
  with a few declarations. Shipped in Chromium and Safari; confirm Firefox for the target browser.
- **Invoker commands** (`command` / `commandfor` on buttons) open popovers and dialogs with no
  JS at all, replacing `popovertarget` plus the wiring in `common/context-menu.js`. Chromium
  135+; check the others.
- **`content-visibility: auto`** for the grid (P1). Baseline.
- **`loading="lazy"` + `decoding="async"`** on thumbnails instead of the observer (P2). Baseline.
- **`HX-Trigger`** and `hx-on:` attributes for server-announced and element-local behaviour
  instead of centralized URL sniffing (A1, A2).
- **Current Alpine 3.x** if Alpine stays: brings the re-initialization guard that makes D2
  harmless. Re-test the `.shift` click modifier noted in `selectable.js` after upgrading.
- **`Element.moveBefore()`** (Chromium 133+) to reparent folder rows on move without destroying
  their Alpine state or open popovers; niche, but exactly this app's problem.

---

## 9. Open questions

These change the recommendations above and are the user's to decide.

1. **Target browsers.** If "current Chromium, Firefox, Safari" is acceptable, the native
   `<dialog>`, anchor positioning, invoker commands, and `content-visibility` options are all
   available. If older browsers matter, only `<dialog>` and `content-visibility` are safe.
2. **Is "no bundler" a rule or a preference?** Determines P6's fix.
3. **Expected library size.** Assets per repository and folders per tree decide whether P1
   pruning is needed in the next release or the one after.
4. **Folder-to-trash drop.** Wanted feature, or dead code to delete?
5. **htmx 4.0.0 vs 2.0.10** until upstream's `latest` moves.
6. **Alpine's future.** Keep as the reactive layer, or plan to retire it once B1 and `<dialog>`
   land?
