/**
 * JSON-driven folder tree renderer.
 *
 * Public API:
 *   reloadFolderTree(repoId) — fetch the full tree from the server, snapshot
 *   currently-expanded folder IDs, re-render, restore expanded state.
 *
 * The DOM structure produced mirrors what the old Twirl templates generated so
 * that the existing Folder JS model, CSS, drag-and-drop wiring, and HTMX
 * context-menu loading all continue to work without modification.
 */
import { Folder } from "../models/folder.js"
import { http } from "../http/client.js"
import { showErrorSnackBar } from "./snackbar.js"

// ─── public ─────────────────────────────────────────────────────────────────

// Monotonic reload counter: if another reload starts while a fetch is in
// flight, the older response is discarded so a stale tree is never rendered.
let _reloadSeq = 0

export async function reloadFolderTree(repoId) {
    const seq = ++_reloadSeq
    const expandedIds = _getExpandedFolderIds()

    try {
        const response = await http.get(`/api/folder/r/${repoId}/tree`)
        if (seq !== _reloadSeq) return

        const treeData = response.data

        const container = document.getElementById("rootFolderList")
        if (!container) return

        // Tear down and rebuild
        container.innerHTML = ""
        container.appendChild(_renderRootNode(treeData, repoId))

        // Let HTMX and Alpine wire up the new elements
        if (window.htmx) {
            htmx.process(container)
        }
        if (window.Alpine) {
            window.Alpine.initTree(container)
        }

        // Restore previously-expanded folders (best-effort; silently skip
        // folders that no longer exist after the mutation).
        _restoreExpandedState(expandedIds)
    } catch (error) {
        // A newer reload superseded this one - let it report its own outcome
        if (seq !== _reloadSeq) return

        console.error("Failed to load folder tree", error)
        showErrorSnackBar("Failed to load folder tree")
    }
}

// ─── snapshot helpers ────────────────────────────────────────────────────────

function _getExpandedFolderIds() {
    const ids = new Set()
    document
        .querySelectorAll("#rootFolderList .folder[alt-expanded]")
        .forEach((el) => {
            const id = el.getAttribute("alt-folder-id")
            if (id) ids.add(id)
        })
    return ids
}

function _restoreExpandedState(expandedIds) {
    expandedIds.forEach((id) => {
        try {
            const folder = new Folder(id)
            if (!folder.isRoot && !folder.isExpanded()) {
                folder.expand()
            }
        } catch (_) {
            // folder was deleted or moved — skip silently
        }
    })
}

// ─── DOM builders ────────────────────────────────────────────────────────────

/**
 * Build the root folder node.
 * The root folder is always shown as expanded and has a plain folder icon that
 * navigates to the root (same behaviour as the old template).
 */
function _renderRootNode(folder, repoId) {
    const folderEl = document.createElement("div")
    folderEl.classList.add("folder", "root")
    folderEl.id = `folder-${folder.id}`
    folderEl.setAttribute("alt-num-of-children", folder.numOfChildren)
    folderEl.setAttribute("alt-folder-id", folder.id)
    folderEl.setAttribute("alt-parent-folder-id", folder.id) // root is its own parent
    folderEl.setAttribute("alt-is-root", "true")
    folderEl.setAttribute("alt-expanded", "true")

    folderEl.appendChild(_buildRootControls(folder, repoId))
    folderEl.appendChild(_buildMenuDiv(folder))
    folderEl.appendChild(_buildChildrenDiv(folder, repoId, true))

    return folderEl
}

function _buildRootControls(folder, repoId) {
    const controlsEl = document.createElement("div")
    controlsEl.classList.add("controls", "dropzone")
    controlsEl.setAttribute("alt-folder-id", folder.id)

    // Clickable folder icon → navigate to root (same as clicking the name)
    const iconEl = document.createElement("i")
    iconEl.id = `folder-icon-${folder.id}`
    iconEl.className = "fas fa-folder"
    iconEl.setAttribute("hx-target", "#content")
    iconEl.setAttribute("hx-trigger", "click")
    iconEl.setAttribute("hx-swap", "innerHTML")
    iconEl.setAttribute(
        "x-on:click",
        "if ($store.currentView.isTriageView() || $store.currentView.isTrashBinView()) { $event.stopPropagation(); $event.preventDefault(); return false; }",
    )
    iconEl.setAttribute(
        "hx-get",
        `/htmx/search/r/${repoId}?folderId=${folder.id}&newSearch=true`,
    )

    // Folder name
    const nameEl = _buildFolderNameEl(folder, repoId, "/ Root")

    // ⋯ menu button (leftmost column)
    const menuCtrlEl = _buildMenuCtrl(folder, repoId)

    controlsEl.appendChild(menuCtrlEl)
    controlsEl.appendChild(iconEl)
    controlsEl.appendChild(nameEl)

    return controlsEl
}

/**
 * Build a non-root folder node.
 */
function _renderFolderNode(folder, repoId) {
    const folderEl = document.createElement("div")
    folderEl.classList.add("folder")
    folderEl.id = `folder-${folder.id}`
    folderEl.setAttribute("alt-num-of-children", folder.numOfChildren)
    folderEl.setAttribute("alt-folder-id", folder.id)
    folderEl.setAttribute("alt-parent-folder-id", folder.parentId)

    folderEl.appendChild(_buildFolderControls(folder, repoId))
    folderEl.appendChild(_buildMenuDiv(folder))
    folderEl.appendChild(_buildChildrenDiv(folder, repoId, false))

    return folderEl
}

function _buildFolderControls(folder, repoId) {
    const controlsEl = document.createElement("div")
    controlsEl.classList.add("controls", "drag-drop", "dropzone")
    controlsEl.setAttribute("alt-folder-id", folder.id)

    // Expand / collapse icon (pure-JS click handler — no HTMX)
    const expandLinkEl = document.createElement("a")
    expandLinkEl.href = "#"
    expandLinkEl.id = `expand-folder-children-${folder.id}`
    expandLinkEl.setAttribute("alt-folder-id", folder.id)

    const iconEl = document.createElement("i")
    iconEl.id = `folder-icon-${folder.id}`
    iconEl.className =
        folder.numOfChildren > 0 ? "fas fa-folder-plus" : "fas fa-folder"
    expandLinkEl.appendChild(iconEl)

    const nameEl = _buildFolderNameEl(folder, repoId, folder.name)

    expandLinkEl.addEventListener("click", (e) => {
        e.preventDefault()
        e.stopPropagation()
        try {
            const f = new Folder(folder.id)
            if (f.isExpanded()) {
                f.collapse()
            } else if (f.numOfChildren() === 0) {
                // Leaf folder: navigate to it (same as clicking the name)
                const currentView = window.Alpine?.store("currentView")
                if (
                    !currentView?.isTriageView() &&
                    !currentView?.isTrashBinView()
                ) {
                    nameEl.click()
                }
            } else {
                f.expand()
            }
        } catch (_) {
            /* element may have been removed mid-interaction */
        }
    })

    // ⋯ menu button (leftmost column)
    const menuCtrlEl = _buildMenuCtrl(folder, repoId)

    controlsEl.appendChild(menuCtrlEl)
    controlsEl.appendChild(expandLinkEl)
    controlsEl.appendChild(nameEl)

    return controlsEl
}

function _buildFolderNameEl(folder, repoId, label) {
    const el = document.createElement("span")
    el.id = `folderName-${folder.id}`
    el.className = "folder-name"
    el.setAttribute("x-data", "")
    el.setAttribute(
        ":class",
        "{ 'disabled': $store.currentView.isTriageView() || $store.currentView.isTrashBinView() }",
    )
    el.setAttribute(
        "x-on:click",
        "if ($store.currentView.isTriageView() || $store.currentView.isTrashBinView()) { $event.stopPropagation(); $event.preventDefault(); return false; }",
    )
    el.setAttribute("hx-target", "#content")
    el.setAttribute("hx-trigger", "click")
    el.setAttribute("hx-swap", "innerHTML")
    el.setAttribute(
        "hx-get",
        `/htmx/search/r/${repoId}?folderId=${folder.id}&newSearch=true`,
    )
    el.textContent = label
    return el
}

function _buildMenuCtrl(folder, repoId) {
    const menuCtrlEl = document.createElement("div")
    menuCtrlEl.className = "menu-ctrl"

    const btnEl = document.createElement("a")
    btnEl.href = "#"
    btnEl.setAttribute("alt-folder-id", folder.id)
    btnEl.setAttribute(
        "hx-get",
        `/htmx/folder/r/${repoId}/context-menu`,
    )
    btnEl.setAttribute("hx-swap", "innerHTML")
    btnEl.setAttribute("hx-target", `#menu-${folder.id}`)
    btnEl.setAttribute("hx-vals", JSON.stringify({ folderId: folder.id }))
    btnEl.setAttribute("hx-trigger", "click")
    btnEl.textContent = "⋯"

    menuCtrlEl.appendChild(btnEl)
    return menuCtrlEl
}

function _buildMenuDiv(folder) {
    const menuEl = document.createElement("div")
    menuEl.className = "menu"
    menuEl.id = `menu-${folder.id}`
    menuEl.setAttribute("alt-folder-id", folder.id)
    return menuEl
}

function _buildChildrenDiv(folder, repoId, isRoot) {
    const childrenEl = document.createElement("div")
    childrenEl.className = "children"
    childrenEl.id = `children-${folder.id}`
    childrenEl.setAttribute("alt-folder-id", folder.id)

    // Root starts expanded; non-root starts collapsed
    if (!isRoot) {
        childrenEl.style.display = "none"
    }

    folder.children.forEach((child) => {
        childrenEl.appendChild(_renderFolderNode(child, repoId))
    })

    return childrenEl
}

