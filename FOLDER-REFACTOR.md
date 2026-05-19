# Folder Tree Refactor — JSON-driven tree

## Motivation

The old HTMX-based folder tree had a structural bug: after a folder **move**, expand/collapse
state and child-counts were tracked in DOM attributes and patched manually by JavaScript.
This made state stale and hard to reason about.

## Design decisions (from grilling session)

| # | Question | Decision |
|---|----------|----------|
| 1 | Bug target | Stale state after folder move (manually-patched DOM attributes) |
| 2 | Data loading | **Eager** — one JSON fetch returns the full tree; re-fetch after every mutation |
| 3 | Client rendering | **Vanilla JS DOM builder** — produces identical DOM structure to old Twirl templates; preserves all CSS, drag-and-drop, and the `Folder` JS model |
| 4 | Context menu placement | `icon \| folder-name (grows) \| ⋯` — always visible, right-pinned |
| 5 | After mutation | Re-fetch + re-render whole tree; **snapshot expanded IDs before, restore after** |
| 6 | Backend scope | Keep all mutation endpoints as-is; add **one** new `GET /api/folder/r/:repoId/tree` endpoint |

## Files changed

### Backend

| File | Change |
|------|--------|
| `altitude/src/altitude/core/routes/api/FolderController.scala` | **New.** `GET /api/folder/r/:repoId/tree` — recursively builds non-recycled folder tree JSON using `getById` + `getChildren` per level. `numOfChildren` = actual non-recycled child count. |
| `altitude/src/altitude/core/App.scala` | Register `FolderController`. |
| `altitude/src/altitude/core/routes/web/partial/FolderActionController.scala` | `showFoldersTab` simplified — no longer fetches root folder (template takes no params). |

### Templates

| File | Change |
|------|--------|
| `altitude/views/htmx/folders.scala.html` | Replaced entire Twirl-rendered tree with an empty `<div id="rootFolderList">` shell. Inline script calls `reloadFolderTree(repoId)` on load. CSS `.controls` grid updated to `max-content 1fr max-content` (icon \| name \| ⋯). |
| `altitude/views/htmx/add_folder_modal.scala.html` | `hx-swap="none"`, removed `hx-target`. Success is handled by the existing `folderAdded` event → `reloadFolderTree()`. |
| `altitude/views/htmx/rename_folder_modal.scala.html` | `hx-swap="none"`, dispatches new `folderRenamed` event instead of patching `#folderName-{id}` in place. |
| `altitude/views/htmx/delete_folder_modal.scala.html` | **Bug fix:** replaced `@{folder.id}` (`Option[String]` → rendered as `Some(uuid)`) with `@{folder.persistedId}` so the `folderDeleted` event detail carries the real UUID. |

### JavaScript

| File | Change |
|------|--------|
| `altitude/static/js/common/folder-tree.js` | **New.** Exports `reloadFolderTree(repoId)`: snapshots expanded IDs → fetches `/api/folder/r/:repoId/tree` → rebuilds DOM → `htmx.process()` + `Alpine.initTree()` → restores expanded state. DOM builder produces the same element structure as the old templates. Expand/collapse is pure-JS: clicking the icon shows/hides the pre-rendered `children` div. |
| `altitude/static/js/constants.js` | Added `folderRenamed: "FOLDER_RENAMED_EVENT"`. |
| `altitude/static/js/models/folder.js` | `expand()` / `collapse()` now show/hide `childrenEl` directly. `updateVisualState()` no longer calls `clearChildren()` (which would destroy pre-rendered DOM). `clearChildren()` now just hides the container. |
| `altitude/static/js/listeners/folders.js` | All mutation handlers (`folderMoved`, `folderAdded`, `folderDeleted`) + new `folderRenamed` handler call `reloadFolderTree()` after success. No more manual DOM patching (`incrementNumOfChildren`, `addChild`, `remove`, etc.). |
| `altitude/static/js/listeners/htmx-folders.js` | Stripped to context-menu toggle only. Removed `/children` and `/add` intercept logic (no longer applicable). |

## JSON tree shape

```json
{
  "id": "uuid",
  "parentId": "uuid",
  "name": "folder name",
  "numOfChildren": 2,
  "isRoot": false,
  "children": [ { ... } ]
}
```

- `numOfChildren` = `children.length` (non-recycled only; computed server-side from `FolderService.getChildren`)
- Root node: `id == parentId`, `isRoot: true`

## How expand / collapse works (new)

All children at every level are rendered into the DOM on the initial fetch. Non-root
`children` divs start with `display: none`. Clicking the folder icon:

- **Expanded** → calls `folder.collapse()` → sets `childrenEl.style.display = "none"`
- **Leaf** → navigates to the folder (clicks the name span)
- **Collapsed with children** → calls `folder.expand()` → sets `childrenEl.style.display = ""`

No HTMX request is made for expand/collapse.

## How mutations work (new)

1. Mutation request fires (existing HTMX or axios call — unchanged server-side).
2. On success, the corresponding event fires (`folderAdded` / `folderDeleted` / `folderMoved` / `folderRenamed`).
3. Event listener calls `reloadFolderTree(repoId)`:
   - Snapshots currently-expanded folder IDs.
   - Fetches fresh tree from server (always accurate).
   - Tears down and rebuilds `#rootFolderList` DOM.
   - Restores previously-expanded folders (best-effort; silently skips deleted/moved folders).

This eliminates all manual DOM patching and guarantees the tree is always in sync with the server.

## Drag and drop

No changes. `interact.js` uses CSS selectors (`#rootFolderList .drag-drop`,
`#rootFolderList .dropzone`) which are live — newly rendered elements are automatically
picked up without rebinding.

