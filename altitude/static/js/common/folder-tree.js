/**
 * JSON-driven folder tree. The tree endpoint returns every folder, so expansion and context menus
 * need no request; a rebuild restores expansion, the viewed folder scope and focus.
 *
 * A non-root row shows its folder's recursive asset count, excluding triaged and recycled assets;
 * the root shows none, since the nav carries the repository total. A branch's icon button expands
 * and collapses it; a leaf's is a search trigger, as the name is. Indentation is the `--depth`
 * custom property, from which the CSS sizes the `.trace` cell between the ⋯ button and the icon.
 */
import { Const } from "../constants.js"
import { context } from "../context.js"
import { Folder } from "../models/folder.js"
import { http } from "../http/client.js"
import { buildAssetCountEl, setAssetCount } from "./asset-count.js"
import {
    buildContextMenuCtrl,
    buildModalTriggerCtrl,
    buildNoMenuCell,
} from "./context-menu-markup.js"
import { showErrorSnackBar } from "./snackbar.js"
import { applyViewedFolderScope } from "./viewed-folder-scope.js"
import { openSearchPanel } from "../search-results/search-panel.js"
import { bindSearchTriggers } from "../search-results/search-triggers.js"

// ─── public ─────────────────────────────────────────────────────────────────

// A reload started while a fetch is in flight discards the older response, so a stale tree is never
// rendered
let _reloadSeq = 0

export async function reloadFolderTree(repoId) {
    const seq = ++_reloadSeq

    try {
        const response = await http.get(`/api/folder/r/${repoId}/tree`)
        if (seq !== _reloadSeq) return

        _renderTree(response.data, repoId)
    } catch (error) {
        // A newer reload superseded this one - let it report its own outcome
        if (seq !== _reloadSeq) return

        console.error("Failed to load folder tree", error)
        showErrorSnackBar("Failed to load folder tree")
    }
}

// Same guard for count refreshes, which never replace the DOM unless they have to
let _countsSeq = 0

/**
 * Patches each row's asset count in place, leaving expansion, focus and open menus alone, and
 * stands down if a full reload started meanwhile. Restoring assets can un-recycle their folders,
 * so a folder the DOM lacks means a full render.
 */
export async function refreshFolderCounts(repoId) {
    const seq = ++_countsSeq
    const reloadSeqAtStart = _reloadSeq

    try {
        const response = await http.get(`/api/folder/r/${repoId}/tree`)
        if (seq !== _countsSeq || reloadSeqAtStart !== _reloadSeq) return

        // Another explorer tab is active: the next render of the folders tab fetches fresh counts
        if (!document.getElementById("rootFolderList")) return

        if (!_patchAssetCounts(response.data)) {
            _renderTree(response.data, repoId)
        }
    } catch (error) {
        if (seq !== _countsSeq || reloadSeqAtStart !== _reloadSeq) return

        console.error("Failed to refresh folder counts", error)
        showErrorSnackBar("Failed to refresh folder counts")
    }
}

function _renderTree(treeData, repoId) {
    const container = document.getElementById("rootFolderList")
    if (!container) return

    // Snapshot only now: expansion or focus may have changed while the fetch was in flight (a
    // dialog returns focus to a menu control as its operation completes)
    const expandedIds = _getExpandedFolderIds()
    const focusedId = _getFocusedTreeControlId()

    container.innerHTML = ""
    container.appendChild(_renderRootNode(treeData, repoId))
    _ensureAddControl(treeData, repoId)

    // Alpine's mutation observer initializes the new subtree; an explicit `initTree` would
    // initialize every component twice, duplicating listeners
    if (window.htmx) {
        htmx.process(container)
    }

    // Built after the tab settled, so the fragment hydration of search triggers missed it
    bindSearchTriggers(container)

    _restoreExpandedState(expandedIds)

    // The highlight follows the displayed results, so the latest scope applies even if a
    // navigation changed it during the fetch
    applyViewedFolderScope()

    if (focusedId) {
        document.getElementById(focusedId)?.focus()
    }
}

/**
 * Returns false as soon as a folder has no row. Rows missing from the JSON are left alone: folder
 * operations always reload the whole tree.
 */
function _patchAssetCounts(folder) {
    if (!folder.isRoot) {
        const el = document.getElementById(`folder-count-${folder.id}`)
        if (!el) return false

        setAssetCount(el, folder.numOfAssets)
    }

    return folder.children.every(_patchAssetCounts)
}

/**
 * Builds the "Add folder" control on the first render; its host lives outside the tree, so
 * rebuilds leave it alone.
 */
function _ensureAddControl(rootFolder, repoId) {
    const hostEl = document.getElementById("folderActions")
    if (!hostEl || hostEl.childElementCount > 0) return
    // Without Organize the host stays hidden
    if (!context.holds(Const.permission.organize)) return

    hostEl.appendChild(
        buildModalTriggerCtrl({
            triggerId: "addFolderBtn",
            label: "Add folder",
            iconClass: "fas fa-plus",
            url: `/htmx/folder/r/${repoId}/dialogs/add-folder`,
            vals: { parentId: rootFolder.id },
            buttonClass: "action-button small",
        }),
    )
    hostEl.hidden = false

    if (window.htmx) {
        htmx.process(hostEl)
    }
}

// ─── snapshot helpers ────────────────────────────────────────────────────────

function _getExpandedFolderIds() {
    const ids = new Set()
    document
        .querySelectorAll(
            `#rootFolderList .folder[${Const.attributes.expanded}]`,
        )
        .forEach((el) => ids.add(el.dataset.folderId))
    return ids
}

/** The `.folder` nodes in document order (controls and children containers carry the ID too) */
function _getFolderNodes() {
    return document.querySelectorAll(
        `#rootFolderList .folder[${Const.attributes.folderId}]`,
    )
}

function _getFocusedTreeControlId() {
    const activeEl = document.activeElement
    const container = document.getElementById("rootFolderList")

    return container?.contains(activeEl) && activeEl.id ? activeEl.id : null
}

/**
 * Re-expands surviving branches. Document order decides a parent before its children, and a branch
 * is restored only under an expanded parent, so whatever moved beneath a collapsed parent comes
 * back collapsed and that parent's next click reveals one level, as the model's reset invariant
 * requires.
 */
function _restoreExpandedState(expandedIds) {
    _getFolderNodes().forEach((el) => {
        if (!expandedIds.has(el.dataset.folderId)) return

        const folder = new Folder(el)
        if (folder.parent()?.isExpanded()) {
            folder.expand()
        }
    })
}

// ─── DOM builders ────────────────────────────────────────────────────────────

/**
 * The root is always expanded; its icon and name search the root. In Triage, which describes a
 * folder rather than searching it, the root is the whole repository, so both do nothing.
 */
function _renderRootNode(folder, repoId) {
    const folderEl = document.createElement("div")
    folderEl.classList.add("folder", "root")
    folderEl.id = `folder-${folder.id}`
    folderEl.setAttribute(Const.attributes.numOfChildren, folder.numOfChildren)
    folderEl.setAttribute(Const.attributes.folderId, folder.id)
    // No parent attribute: the model walks parents until there is none
    folderEl.setAttribute(Const.attributes.isRoot, "true")
    folderEl.setAttribute(Const.attributes.expanded, "true")

    folderEl.appendChild(_buildRootControls(folder, repoId))
    folderEl.appendChild(_buildChildrenDiv(folder, repoId, true, 0))

    return folderEl
}

function _buildRootControls(folder, repoId) {
    const controlsEl = document.createElement("div")
    controlsEl.classList.add("controls", "dropzone")
    controlsEl.setAttribute(Const.attributes.folderId, folder.id)

    const iconEl = document.createElement("i")
    iconEl.id = `folder-icon-${folder.id}`
    iconEl.className = "fas fa-folder folder-icon"
    _makeSearchTrigger(iconEl, folder.id)

    const nameEl = _buildFolderNameEl(folder, "/")

    const menuCtrlEl = _buildMenuCtrl(folder, repoId, "Root")

    // ⋯ menu button | icon | folder-name (no asset count on the root row)
    controlsEl.appendChild(menuCtrlEl)
    controlsEl.appendChild(iconEl)
    controlsEl.appendChild(nameEl)

    return controlsEl
}

/** `depth` is 1 for direct children of the root */
function _renderFolderNode(folder, repoId, depth) {
    const folderEl = document.createElement("div")
    folderEl.classList.add("folder")
    folderEl.id = `folder-${folder.id}`
    folderEl.setAttribute(Const.attributes.numOfChildren, folder.numOfChildren)
    folderEl.setAttribute(Const.attributes.folderId, folder.id)
    folderEl.setAttribute(Const.attributes.parentFolderId, folder.parentId)
    folderEl.style.setProperty("--depth", depth)

    folderEl.appendChild(_buildFolderControls(folder, repoId))
    folderEl.appendChild(_buildChildrenDiv(folder, repoId, false, depth))

    return folderEl
}

function _buildFolderControls(folder, repoId) {
    const controlsEl = document.createElement("div")
    controlsEl.classList.add("controls", "drag-drop", "dropzone")
    controlsEl.setAttribute(Const.attributes.folderId, folder.id)

    const nameEl = _buildFolderNameEl(folder, folder.name)
    _anchorDialogsTo(nameEl)
    const iconCtrlEl = _buildFolderIconCtrl(folder)

    const menuCtrlEl = _buildMenuCtrl(folder, repoId, folder.name)

    // Dotted guide from the ⋯ button to the icon; its width is the row's indent
    const traceEl = document.createElement("span")
    traceEl.className = "trace"

    // ⋯ menu button | trace | icon | folder-name | asset count
    controlsEl.appendChild(menuCtrlEl)
    controlsEl.appendChild(traceEl)
    controlsEl.appendChild(iconCtrlEl)
    controlsEl.appendChild(nameEl)
    controlsEl.appendChild(
        buildAssetCountEl(`folder-count-${folder.id}`, folder.numOfAssets),
    )

    return controlsEl
}

/**
 * A non-root folder's icon button. Its IDs are stable across rebuilds, for focus restoration, a
 * leaf's dialog anchor and the model's glyph updates. A branch's toggles expansion and never
 * navigates, with `aria-expanded` kept by the model; a leaf's is a search trigger, as the name is.
 */
function _buildFolderIconCtrl(folder) {
    const isBranch = folder.numOfChildren > 0

    const ctrlEl = document.createElement("button")
    ctrlEl.type = "button"
    ctrlEl.id = `expand-folder-children-${folder.id}`
    ctrlEl.className = "expand-ctrl"
    ctrlEl.setAttribute(Const.attributes.folderId, folder.id)

    const iconEl = document.createElement("i")
    iconEl.id = `folder-icon-${folder.id}`
    iconEl.className = `fas ${isBranch ? "fa-folder-plus" : "fa-folder"} folder-icon`
    iconEl.setAttribute("aria-hidden", "true")
    ctrlEl.appendChild(iconEl)

    if (isBranch) {
        ctrlEl.setAttribute("aria-label", `Subfolders of ${folder.name}`)
        ctrlEl.setAttribute("aria-controls", `children-${folder.id}`)
        ctrlEl.setAttribute("aria-expanded", "false")
        ctrlEl.title = "Show subfolders. Double-click to show all levels"
        _bindBranchGestures(ctrlEl, folder.id)
    } else {
        ctrlEl.setAttribute("aria-label", `Open folder ${folder.name}`)
        _makeSearchTrigger(ctrlEl, folder.id)
        _anchorDialogsTo(ctrlEl)
    }

    return ctrlEl
}

/**
 * Single- and double-click on a branch control.
 *
 * A pointer double-click arrives as click (`detail` 1), click (`detail` 2), then `dblclick`. The
 * first click toggles at once, with no single-click delay. Every later click of the sequence is
 * ignored, since a second toggle would undo the first. `dblclick` then acts on the state captured
 * before the first click: a branch that started collapsed opens every level, one that started
 * expanded stays closed.
 *
 * Keyboard activation sends a click with `detail` 0 and no `dblclick`, so each is a plain toggle.
 * The captured state belongs to this control and is cleared once used, so a control replaced by a
 * rebuild between the clicks acts on nothing.
 */
function _bindBranchGestures(ctrlEl, folderId) {
    let startedExpanded = null

    ctrlEl.addEventListener("click", (event) => {
        event.preventDefault()
        event.stopPropagation()

        if (!ctrlEl.isConnected || event.detail > 1) return

        const folder = Folder.find(folderId)
        if (!folder) return

        const expanded = folder.isExpanded()
        startedExpanded = event.detail === 1 ? expanded : null

        if (expanded) {
            folder.collapse()
        } else {
            folder.expand()
        }
    })

    ctrlEl.addEventListener("dblclick", (event) => {
        event.preventDefault()
        event.stopPropagation()

        const wasExpanded = startedExpanded
        startedExpanded = null

        if (!ctrlEl.isConnected || wasExpanded === null) return

        const folder = Folder.find(folderId)
        if (!folder) return

        if (wasExpanded) {
            folder.collapse()
        } else {
            folder.expandAll()
        }
    })
}

function _buildFolderNameEl(folder, label) {
    const el = document.createElement("span")
    el.id = `folderName-${folder.id}`
    el.className = "folder-name"
    _makeSearchTrigger(el, folder.id)
    el.textContent = label
    return el
}

/**
 * Navigating to a folder is a search: the element declares its one parameter and
 * `search-results/search-triggers.js` combines it with the current search and decides what the
 * click does outside Browse.
 */
function _makeSearchTrigger(el, folderId) {
    el.setAttribute("data-app-search", "click")
    el.setAttribute("data-app-search-folder-id", folderId)
}

/**
 * Makes `el` the anchor of the popover dialog its click requests in Triage. The ID is stable, so a
 * rebuild re-anchors an open dialog.
 */
function _anchorDialogsTo(el) {
    el.style.anchorName = `--${el.id}`
    el.dataset.appDialogAnchor = el.id
}

/**
 * The folder's ⋯ menu cell. The trigger ID is stable across rebuilds, since folder dialogs return
 * focus to it. Search here is offered to every account but not on the root, which is no filter;
 * the folder's actions follow, and a menu with no action is the empty cell.
 */
function _buildMenuCtrl(folder, repoId, folderName) {
    const searchHere = folder.isRoot
        ? []
        : [
              {
                  label: "Search here",
                  run: () => openSearchPanel({ folderId: folder.id }),
              },
          ]
    const folderActions = _folderActions(folder).map(
        ({ label, dialog, vals }) => ({
            label,
            url: `/htmx/folder/r/${repoId}/dialogs/${dialog}`,
            vals,
        }),
    )
    const separator =
        searchHere.length > 0 && folderActions.length > 0
            ? [{ separator: true }]
            : []
    const actions = [...searchHere, ...separator, ...folderActions]

    if (actions.length === 0) {
        return buildNoMenuCell()
    }

    return buildContextMenuCtrl({
        triggerId: `folderMenuCtrl-${folder.id}`,
        panelId: `menu-${folder.id}`,
        ariaLabel: `Actions for folder ${folderName}`,
        entityAttr: Const.attributes.folderId,
        entityId: folder.id,
        actions,
    })
}

/** The folder's actions the account holds the Permissions of: all need Organize */
function _folderActions(folder) {
    if (!context.holds(Const.permission.organize)) {
        return []
    }

    const actions = [
        {
            label: "Add folder",
            dialog: "add-folder",
            vals: { parentId: folder.id },
        },
    ]

    if (!folder.isRoot) {
        actions.push({
            label: "Rename",
            dialog: "rename-folder",
            vals: { id: folder.id },
        })
        // Deleting recycles the assets in the folder and its subfolders, which takes Recycle
        if (
            folder.numOfAssets === 0 ||
            context.holds(Const.permission.recycle)
        ) {
            actions.push({
                label: "Delete",
                dialog: "delete-folder",
                vals: { id: folder.id },
            })
        }
    }

    return actions
}

function _buildChildrenDiv(folder, repoId, isRoot, depth) {
    const childrenEl = document.createElement("div")
    childrenEl.className = "children"
    childrenEl.id = `children-${folder.id}`
    childrenEl.setAttribute(Const.attributes.folderId, folder.id)

    if (!isRoot) {
        childrenEl.style.display = "none"
    }

    folder.children.forEach((child) => {
        childrenEl.appendChild(_renderFolderNode(child, repoId, depth + 1))
    })

    return childrenEl
}
