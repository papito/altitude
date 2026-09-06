# Migrate Altitude's frontend from htmx 2 to htmx 4

## Original goals

Establish whether the vendored htmx was current, then scope what an upgrade to
htmx 4 would cost. The audit found the vendored copy was htmx 2.0.2 (August
2024), eight patch releases and roughly twenty months behind, with both
extension copies older still — they were htmx 1.x-era files.

## Status

Executed 2026-09-05. Phase 0 is committed (`52cf6ebd`). Phases 1 through 4 sit
uncommitted in the working tree; `make compile` passes. Phase 5 turned out to be
unnecessary, see the deviations below. `make lint` could not run (no Node on the
machine). Browser smoke tests are still outstanding.

Deviations from the plan, found while reading the shipped 4.0.0 sources:

- `htmx-2-compat` was not used. It re-fires the old event names but hands them
  the htmx 4 detail (`detail.ctx`), so every `pathInfo` / `xhr` / `successful`
  access would have failed anyway, and `preventDefault()` on the re-fired
  `htmx:beforeRequest` cannot cancel the real request. The listeners were
  ported directly instead, through `static/js/common/htmx-events.js`.
- `implicitInheritance` was not enabled. The shipped `upgrade-check` script and
  a manual read of every template found one implicit inheritance: the `<img>`
  inside the last grid cell inherited `hx-swap="afterend"` in htmx 2, which
  was a bug (the detail modal content landed after its container). Explicit
  inheritance fixes it, so the checker's remaining hit there is intentional.
- `htmx:load` was replaced by `htmx:after:settle` (`detail.newContent`), not
  `htmx:after:init`: the latter only fires for elements that carry htmx
  attributes, so it would have missed the grid cells and fragment roots.
- Two extra breakages surfaced: `HX-Retarget: this` no longer resolves unless
  the form declares `hx-target="this"` (added to the add/rename folder
  modals), and `hx-swap="#content"` on the purge button throws in htmx 4
  where htmx 2 silently fell back to innerHTML (changed to `innerHTML`).
- `hx-ws` swaps raw text frames as HTML, so the pipeline page cancels
  non-markup frames (`connected`, pings) in `htmx:ws:before:message:incoming`.
- Vendored versions are now recorded in `static/js/lib/README.md`.

The recommendation is to hold the 4.x jump for now. htmx 4.0.0 shipped
2026-08-28 and npm's `latest` tag still points at 2.0.10, so upstream is not yet
steering anyone onto it. Phases 1 and 2 are worth doing on htmx 2 regardless and
shrink the eventual jump to renames plus a WebSocket rewrite.

Nothing below has been verified in a browser. Every claim about htmx 4 behavior
was verified by reading the shipped 4.0.0 dist source; every claim about
Altitude was verified by inspecting this repository. The two have not been run
together.

## Phase 0 — completed

Applied to the working tree, not committed:

- `../../altitude/static/js/lib/htmx.min.js` — 2.0.2 to 2.0.10.
- `../../altitude/static/js/lib/json-enc.js` — htmx 1.x-era copy to
  `htmx-ext-json-enc@2.0.3`.
- `altitude/static/js/lib/ws.js` — htmx 1.x-era copy to `htmx-ext-ws@2.0.4`.
- `altitude/views/htmx/delete_folder_modal.scala.html:26` — `hx-vars` to
  `hx-vals`. `hx-vars` is removed in htmx 4 and has been superseded since htmx
  1.9, so this was safe to do early.

Verified: `make compile` passes and the generated Twirl source carries the new
attribute; `getExpressionVars` (needed by json-enc 2.0.3) exists in 2.0.10;
ws 2.0.4 retains `ws-connect`, `wsOpen`, `wsClose`, and `wsAfterMessage`; all
three vendored files are byte-identical to upstream. `.prettierignore` covers
`altitude/static/js/lib**`, so `make lint` will not reformat them.

Not verified: browser behavior. A smoke test of the five json-enc forms and the
import status stream is still outstanding.

## Confirmed decisions

- Target htmx 4.0.0, not a 4.0.x beta.
- Phases land independently, each revertible on its own.
- Phases 1 and 2 ship on htmx 2, against a working baseline, so they can be
  validated without a version jump confusing the result.
- Continue vendoring into `../../altitude/static/js/lib`. No bundler, no npm runtime
  dependency.

## What breaks

### Upload progress — the one hard blocker

htmx 4 issues every request through `fetch()` and this cannot be reverted. The
`htmx:xhr:progress` event is removed with no replacement, because `fetch()`
exposes no upload progress. The `hx-multipart` extension does not help; it
handles `multipart/mixed` responses, not upload streaming.

Affected: `altitude/static/js/fragments/upload-form.js:53` (the progress
listener) and `altitude/views/htmx/upload_form.scala.html:59-60` (`hx-post`
plus `hx-encoding="multipart/form-data"`).

The fix is to stop routing the upload through htmx. axios is already vendored at
1.14.0 and already wrapped in `../../altitude/static/js/http/client.js`, which
`../../AGENTS.md` names as the preferred client for non-htmx requests.

### JSON encoding moves to a community extension

htmx 4 core ships no JSON encoder. `hx-encoding` accepts only
`multipart/form-data`; everything else is coerced to `URLSearchParams`. Five
forms depend on JSON bodies and the cask controllers parse them with `ujson`,
so form encoding would mean a server change.

`bigskysoftware/htmx-4-community-extensions` carries a `json-enc` written for
htmx 4. It was checked against the shipped 4.0.0 dist and the API it depends on
is all present: `registerExtension` dispatches `htmx_before_request(elt, detail)`
by rewriting `:` to `_` (`htmx.js:1509`), `attributeValue` is on the internal API
handed to `init`, and `ctx.vals` is populated at `htmx.js:488` under the comment
"make available for json extensions".

Two caveats, both supply chain rather than correctness. The repo is two commits
dated 2026-03-04, roughly six months before 4.0.0 shipped, with no activity
since. Its npm package was never published, so the `npm install` and
`cdn.jsdelivr.net/npm/...` paths in its own README both return 404; the
jsDelivr `gh` path works.

The markup changes shape. It is a boolean attribute rather than an `hx-ext`
value, and it inherits, so one parent may carry `hx-json-enc:inherited` instead
of repeating it:

```html
<form hx-post="/folder/add" hx-ext="json-enc">   <!-- htmx 2 -->
<form hx-post="/folder/add" hx-json-enc>         <!-- htmx 4 -->
```

Affected: `add_folder_modal`, `rename_folder_modal`, `delete_folder_modal`,
`edit_person_name`, `setup_form`.

Note that `hx-ext="json-enc"` on `delete_folder_modal` is already dead markup.
`htmx.config.methodsThatUseUrlParams` defaults to `["get", "delete"]`, so the
DELETE sends parameters in the URL and `encodeParameters` is never called. That
matches the server, where `htmxDeleteFolder(repoId: String, id: String)` binds
`id` as a cask query parameter. The attribute can simply be removed.

### The WebSocket connection changes shape

Every part of the contract moves: the attribute, the swap semantics, and the
event names. htmx 2 treats each incoming element as an implicit out-of-band
swap; htmx 4 requires an explicit `hx-target` and `hx-swap` on the connection
element.

```html
<!-- htmx 2 -->
<div hx-ext="ws" ws-connect="/import/status?userId=...">
  <div id="statusText"></div>
</div>

<!-- htmx 4 -->
<div hx-ws:connect="/import/status?userId=..."
     hx-target="#statusText"
     hx-swap="beforeend">
  <div id="statusText"></div>
</div>
```

Outgoing messages also change — htmx 2 nested metadata under `HEADERS`, htmx 4
reserves `headers` and puts values at the top level — but Altitude only
receives, so that part costs nothing.

Affected: `altitude/views/pipeline.scala.html:58` (connection element) and
`:76`, `:80`, `:84` (the `wsOpen`, `wsClose`, `wsAfterMessage` handlers).

### Inheritance becomes explicit

htmx 4 no longer walks up the DOM for attributes. Anything meant to inherit
needs an `:inherited` suffix. Altitude mostly places `hx-target` and `hx-swap`
directly on the requesting element, so exposure looks low, but that is an
impression rather than an audit, and a missed inheritance fails silently.

`htmx.config.implicitInheritance = true` restores htmx 2 behavior as a
migration scaffold. The templates with nested markup are the ones to check:
`people.scala.html`, `search_results.scala.html`, `results_grid.scala.html`.

### Event renames

htmx 4 restructures every event name to `htmx:phase:action`. Fifteen of the
eighteen listener sites need renaming. The `htmx-2-compat` extension maps the
old names, so this can be deferred past the cutover.

| htmx 2                | htmx 4                | Sites |
|-----------------------|-----------------------|-------|
| `htmx:load`           | `htmx:after:init`     | 4     |
| `htmx:afterRequest`   | `htmx:after:request`  | 4     |
| `htmx:beforeRequest`  | `htmx:before:request` | 3     |
| `htmx:afterSwap`      | `htmx:after:swap`     | 1     |
| ws connection events  | see the `hx-ws` ext   | 3     |
| `htmx:xhr:progress`   | removed               | 1     |
| `htmx:abort`          | unchanged             | 2     |

The sites are in `upload-form-page.js`, `listeners/htmx-search.js`,
`fragments/upload-form.js`, `fragments/search-results.js`,
`fragments/modal.js`, `search-results/infinite-scroll.js`, and
`views/pipeline.scala.html`.

`htmx.onLoad()` survives but now fires on `htmx:after:process` rather than
`htmx:after:init`. Altitude uses raw listeners, so this is informational.

### Configuration changes

`defaultTimeout` defaults to 60000ms where htmx 2 had none. For a photo-library
importer this is a live risk on large uploads, though if the upload moves to
axios in Phase 1 the exposure moves with it. `htmx.config.defaultTimeout = 0`
restores htmx 2 behavior.

`defaultSettleDelay` drops from 20ms to 1ms. Given the recent folder-tree
flicker work, re-check drag/drop and badge transitions after the cutover.

`hx-ext` is removed as an attribute — extensions activate by script inclusion.
All six usages disappear rather than change.

## What was verified safe

Each of these was checked against the codebase rather than assumed:

- The whole htmx JavaScript API surface Altitude uses: `find` (23 calls),
  `ajax` (5), `on` (3), `trigger`, `process`, `findAll`. All still shipped.
- Drag and drop. `static/js/dragdrop/` never references htmx; it is interact.js
  end to end.
- The folder tree. `common/folder-tree.js` sets `hx-get`, `hx-target`,
  `hx-swap`, `hx-trigger`, and `hx-vals` dynamically then calls
  `htmx.process()`. Every one of those survives.
- Error-response swapping. htmx 4 swaps 4xx and 5xx by default, but the folder
  validation handlers deliberately return HTTP 200 with replacement modal
  markup, so nothing changes.
- Trigger syntax. All five distinct `hx-trigger` values are simple, with no
  `from:`, `target:`, or `queue:`, so the HCON quoting change and the removed
  `queue` modifier do not apply.
- Event filters. `click[!event.shiftKey]` in `results_grid.scala.html:45` still
  evaluates; htmx 4 retains a `Function`-constructor path.
- `htmx:abort`. Same name, same `htmx.trigger(el, "htmx:abort")` usage, so the
  upload cancel button is unaffected.
- OOB swap ordering reverses in htmx 4, but Altitude uses no `hx-swap-oob`.
- History behavior. htmx 4 drops the localStorage cache; Altitude uses no
  `hx-history`, `hx-history-elt`, or `hx-boost`.
- No `hx-*` attribute is emitted from Scala. Every one lives in a template.
- Removed attributes Altitude never used: `hx-params`, `hx-request`,
  `hx-disinherit`, `hx-inherit`, `hx-prompt`, `hx-disable`.
- `intersect` triggers remain core, so infinite scroll keeps working.

## Remaining phases

### Phase 1 — move the upload off htmx

Rewrite `../../altitude/static/js/fragments/upload-form.js` to post through the shared
axios client with `onUploadProgress`, preserving the existing abort behavior and
the cancel endpoint. Remove `hx-post` and `hx-encoding` from
`upload_form.scala.html` once the JavaScript owns the request.

This removes the single hard blocker while still on htmx 2, so it can be
validated against a working baseline. Ships independently of everything else.

### Phase 2 — settle the JSON encoder

Decide between vendoring the community `json-enc` and writing the equivalent,
then prove it against 4.0.0 on one form. `add_folder_modal` is the smallest.
Confirm the cask endpoint still parses the body.

Either way the five templates swap `hx-ext="json-enc"` for `hx-json-enc`, and
the dead attribute on `delete_folder_modal` can be dropped. The fallback, if
neither approach works, is form encoding on those five endpoints — a server
change, and one worth discovering before the cutover rather than during it.

### Phase 3 — cut over behind compatibility shims

Replace the core with 4.0.0 and load `htmx-2-compat`, `hx-alpine-compat`, and
`hx-ws` from `dist/ext/`. Set `implicitInheritance = true` and
`defaultTimeout = 0`. The goal of this phase is a running application, not a
clean one.

`hx-alpine-compat` matters here beyond compatibility. It defers Alpine's
mutation observer until after htmx's settle phase, so Alpine initializes against
the final DOM rather than an intermediate one. That is the class of bug the
recent folder-tree work has been chasing, and it is worth evaluating on its own
merits.

### Phase 4 — rewrite the WebSocket connection

Convert `pipeline.scala.html` to `hx-ws:connect` with an explicit target and
swap, and rename the three connection event handlers. Verify against a real
import run. This is the only place in the application where swap semantics
changed rather than merely being renamed, so reading the diff is not enough.

Check what the import status endpoint emits: markup written for implicit
per-element OOB swaps may need adjusting for an explicit target and swap.

### Phase 5 — remove the scaffolding

One commit per shim, each independently revertible:

1. Rename the remaining event listeners and drop `htmx-2-compat`.
2. Audit inheritance, add `:inherited` where needed, drop
   `implicitInheritance`.

Keep `hx-alpine-compat`. It is a real integration, not a shim.

## Open decisions

- Adopt the community `json-enc` or write the equivalent. The repo is dormant
  and unpublished, so "official" buys reputation rather than maintenance. It is
  roughly thirty vendored lines either way, and either way they become ours.
- Whether to keep vendoring at all, given htmx's own position on it
  (https://htmx.org/essays/vendoring/). This affects how every later phase is
  applied, so it is worth settling early.
- Whether to adopt `innerMorph` on the folder tree. htmx 4 ships idiomorph in
  core, and combined with `hx-alpine-compat` it preserves component state across
  swaps.
- Whether to record vendored library versions somewhere. Nothing in the repo
  pinned a version, which is why two extension copies drifted several years
  behind the core without it being visible. `../../README.md` and the nested
  `../../AGENTS.md` files name htmx but pin no version.

## Validation and documentation constraints

Follow the repository's frontend guidance. `../../AGENTS.md` does not trigger the
integration-test red-green-refactor cycle for front-end-facing code, so these
phases are verified by `make compile` plus manual browser checks. Phase 2's
fallback and any change to the import status endpoint would be server work and
would require the prescribed cycle.

The paths that exercise the swapped extensions are the ones to smoke test: the
five json-enc forms (add, rename and delete folder; edit person name; setup) and
the import status stream on the pipeline page.

Update the applicable `../../AGENTS.md` files when implementation lands. This document
describes intended work, not the running application, and should be kept
distinct from documentation of current behavior.
