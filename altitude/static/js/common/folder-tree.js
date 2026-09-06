/**
 * JSON-driven folder tree renderer.
 *
 * Public API:
 *   reloadFolderTree(repoId) — fetch the full tree from the server, snapshot
 *   currently-expanded folder IDs and the focused tree control, re-render,
 *   restore expanded state, the viewed folder scope, and focus.
 *
 * The DOM structure produced mirrors what the old Twirl templates generated so
 * that the existing Folder JS model, CSS, and drag-and-drop wiring all continue
 * to work without modification.
 *
 * Each non-root row's icon is a button. On a branch (a folder with child
 * folders) it expands and collapses: single-click reveals the direct children or
 * closes the branch, double-click on a collapsed branch reveals every level (see
 * `_bindBranchGestures`). On a leaf it navigates, as the name does. The folder
 * model (`models/folder.js`) owns the expansion state the control changes.
 * Expansion needs no request: the tree endpoint returns every folder.
 *
 * Each folder's context menu is built here too, as a native `popover="auto"`
 * panel next to its ⋯ trigger, so opening a menu needs no request. The panel
 * holds its actions and an empty dialog host: an action loads its dialog into
 * the host through HTMX, and the panel then shows the dialog in place of the
 * actions. The `folderMenu` Alpine component (`alpine/components/folder-menu.js`)
 * places the panel, switches it between the two, and dismisses it.
 *
 * Indentation is not structural: every non-root `.folder` carries a `--depth`
 * CSS custom property, and its `.controls` row holds a `.trace` cell (between
 * the ⋯ menu button and the icon) whose width is derived from `--depth`. The
 * trace both indents the icon/name and draws the dotted guide back to the ⋯
 * button, which stays flush left at every depth.
 */
import { Const } from "../constants.js"
import { Folder } from "../models/folder.js"
import { http } from "../http/client.js"
import { showErrorSnackBar } from "./snackbar.js"
import { applyViewedFolderScope } from "./viewed-folder-scope.js"

// ─── public ─────────────────────────────────────────────────────────────────

// Monotonic reload counter: if another reload starts while a fetch is in
// flight, the older response is discarded so a stale tree is never rendered.
let _reloadSeq = 0

export async function reloadFolderTree(repoId) {
    const seq = ++_reloadSeq

    try {
        const response = await http.get(`/api/folder/r/${repoId}/tree`)
        if (seq !== _reloadSeq) return

        const treeData = response.data

        const container = document.getElementById("rootFolderList")
        if (!container) return

        // Snapshot expansion and focus only now, immediately before the DOM is replaced: both may
        // have changed while the fetch was in flight (a branch expanded or collapsed; a dialog
        // returning focus to a folder menu control as its operation completes), and an older
        // snapshot would undo that
        const expandedIds = _getExpandedFolderIds()
        const focusedId = _getFocusedTreeControlId()

        // Tear down and rebuild
        container.innerHTML = ""
        container.appendChild(_renderRootNode(treeData, repoId))

        // Let HTMX wire up the new elements. Alpine needs no call: its mutation observer
        // initializes the appended subtree, and an explicit `initTree` here would initialize
        // every component a second time, duplicating their listeners.
        if (window.htmx) {
            htmx.process(container)
        }

        _restoreExpandedState(expandedIds)

        // The highlight follows the displayed results, not the tree: the latest scope is applied
        // even if a navigation changed it while this request was pending, and it is independent
        // of the expansion snapshot
        applyViewedFolderScope()

        // Keep keyboard focus on the rebuilt copy of the control that had it, if it still exists
        if (focusedId) {
            document.getElementById(focusedId)?.focus()
        }
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
        .querySelectorAll(
            `#rootFolderList .folder[${Const.attributes.expanded}]`,
        )
        .forEach((el) => {
            const id = el.getAttribute(Const.attributes.folderId)
            if (id) ids.add(id)
        })
    return ids
}

/**
 * Folder tree nodes in document order: the `.folder` elements carrying a folder ID (the controls
 * and children containers carry the ID too, but are not nodes).
 */
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
 * Re-expands the surviving branches that were expanded before the rebuild. Nodes are visited in
 * document order, so a parent is decided before its children, and a branch is restored only when
 * its parent is expanded: whatever moved beneath a collapsed parent is normalized to collapsed,
 * and that parent's next single-click reveals one level, as the model's reset invariant requires.
 * Root is always expanded; deleted folders are simply absent, and a folder that lost its last
 * child is no longer a branch, which `expand()` ignores. The model keeps the icon and
 * `aria-expanded` in step.
 */
function _restoreExpandedState(expandedIds) {
    _getFolderNodes().forEach((el) => {
        const id = el.getAttribute(Const.attributes.folderId)
        if (!expandedIds.has(id)) return

        const folder = new Folder(id)
        if (!folder.isRoot && folder.parent().isExpanded()) {
            folder.expand()
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
    folderEl.appendChild(_buildChildrenDiv(folder, repoId, true, 0))

    return folderEl
}

function _buildRootControls(folder, repoId) {
    const controlsEl = document.createElement("div")
    controlsEl.classList.add("controls", "dropzone")
    controlsEl.setAttribute("alt-folder-id", folder.id)

    // Clickable folder icon → navigate to root (same as clicking the name)
    const iconEl = document.createElement("i")
    iconEl.id = `folder-icon-${folder.id}`
    iconEl.className = "fas fa-folder folder-icon"
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
    const menuCtrlEl = _buildMenuCtrl(folder, repoId, "Root")

    controlsEl.appendChild(menuCtrlEl)
    controlsEl.appendChild(iconEl)
    controlsEl.appendChild(nameEl)

    return controlsEl
}

/**
 * Build a non-root folder node.
 * `depth` is 1 for direct children of the root; the CSS derives the row's
 * indent (the `.trace` width) from it.
 */
function _renderFolderNode(folder, repoId, depth) {
    const folderEl = document.createElement("div")
    folderEl.classList.add("folder")
    folderEl.id = `folder-${folder.id}`
    folderEl.setAttribute("alt-num-of-children", folder.numOfChildren)
    folderEl.setAttribute("alt-folder-id", folder.id)
    folderEl.setAttribute("alt-parent-folder-id", folder.parentId)
    folderEl.style.setProperty("--depth", depth)

    folderEl.appendChild(_buildFolderControls(folder, repoId))
    folderEl.appendChild(_buildChildrenDiv(folder, repoId, false, depth))

    return folderEl
}

function _buildFolderControls(folder, repoId) {
    const controlsEl = document.createElement("div")
    controlsEl.classList.add("controls", "drag-drop", "dropzone")
    controlsEl.setAttribute("alt-folder-id", folder.id)

    const nameEl = _buildFolderNameEl(folder, repoId, folder.name)
    const iconCtrlEl = _buildFolderIconCtrl(folder, nameEl)

    // ⋯ menu button (leftmost column)
    const menuCtrlEl = _buildMenuCtrl(folder, repoId, folder.name)

    // Dotted guide from the ⋯ button to the icon; its width is the row's indent
    const traceEl = document.createElement("span")
    traceEl.className = "trace"

    // ⋯ menu button | trace | icon | folder-name
    controlsEl.appendChild(menuCtrlEl)
    controlsEl.appendChild(traceEl)
    controlsEl.appendChild(iconCtrlEl)
    controlsEl.appendChild(nameEl)

    return controlsEl
}

/**
 * Build a non-root folder's icon control (pure JS, no HTMX): a native button, so Enter and Space
 * activate it and it never submits a form or jumps the page. It keeps the stable IDs
 * `expand-folder-children-<id>` (focus restoration after a rebuild, focus recovery after a
 * collapse) and `folder-icon-<id>` (the model's glyph updates); the icon itself is decorative.
 *
 * A branch's control toggles expansion and never navigates: it names the folder, points
 * `aria-controls` at the children container, and carries `aria-expanded`, which the model keeps
 * synchronized. A leaf's control navigates to the folder, exactly as the name does, and so is
 * blocked in the triage and trash views like the name.
 */
function _buildFolderIconCtrl(folder, nameEl) {
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
        ctrlEl.addEventListener("click", (event) => {
            event.preventDefault()
            event.stopPropagation()

            const currentView = window.Alpine?.store(Const.state.currentView)
            if (
                !currentView?.isTriageView() &&
                !currentView?.isTrashBinView()
            ) {
                nameEl.click()
            }
        })
    }

    return ctrlEl
}

/**
 * Single- and double-click on a branch control.
 *
 * A pointer double-click arrives as click (`detail` 1), click (`detail` 2), then `dblclick`. The
 * first click acts immediately, so a collapsed branch opens one level with no single-click delay.
 * The second click must not act: two ordinary toggles would close what the first opened, and the
 * double-click would then find the branch collapsed and expand it, the reverse of what was meant.
 * So every click after the first of a pointer sequence (`detail` 2 and higher, a triple-click's
 * third included) is ignored, and `dblclick` applies the recursive action from the state captured
 * before the first click, never from the state or glyph after it: a branch that started collapsed
 * opens every level; one that started expanded was closed by the first click and stays closed.
 *
 * Keyboard activation (Enter, Space) dispatches a click with `detail` 0 and never a `dblclick`,
 * so each is an independent single-click action. The captured state is local to this control and
 * cleared once used, so a control replaced by a tree rebuild between the clicks has nothing to act
 * on, and a detached control ignores its events. Both gestures stop propagation and the default
 * action: a branch control never navigates.
 */
function _bindBranchGestures(ctrlEl, folderId) {
    let startedExpanded = null

    ctrlEl.addEventListener("click", (event) => {
        event.preventDefault()
        event.stopPropagation()

        if (!ctrlEl.isConnected || event.detail > 1) return

        const folder = _findFolder(folderId)
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

        const folder = _findFolder(folderId)
        if (!folder) return

        if (wasExpanded) {
            folder.collapse()
        } else {
            folder.expandAll()
        }
    })
}

function _findFolder(folderId) {
    try {
        return new Folder(folderId)
    } catch (_) {
        // the node was removed by a tree rebuild mid-interaction
        return null
    }
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

/**
 * Build the ⋯ menu cell: the trigger button and the native popover panel it
 * opens, holding the actions and the dialog host. All keep stable IDs
 * (`folderMenuCtrl-<id>`, `menu-<id>`, `menuDialog-<id>`): tree rebuilds
 * restore focus by ID, the actions target the dialog host, and the folder
 * dialogs declare the trigger as their return-focus control. The panel is
 * focusable (`tabindex="-1"`) so a click on non-interactive dialog content
 * keeps focus inside it, and so a dialog can rest focus on the panel itself.
 *
 * The cell is the `folderMenu` component's root, so the panel is a DOM child
 * of the cell and never of another folder's panel; see the component for why
 * that matters.
 */
function _buildMenuCtrl(folder, repoId, folderName) {
    const menuCtrlEl = document.createElement("div")
    menuCtrlEl.className = "menu-ctrl"
    menuCtrlEl.setAttribute("x-data", "folderMenu")
    menuCtrlEl.setAttribute("x-on:focusout", "handleFocusOut")

    const panelId = `menu-${folder.id}`
    const dialogId = `menuDialog-${folder.id}`

    const btnEl = document.createElement("button")
    btnEl.type = "button"
    btnEl.id = `folderMenuCtrl-${folder.id}`
    btnEl.setAttribute("alt-folder-id", folder.id)
    btnEl.setAttribute("popovertarget", panelId)
    btnEl.setAttribute("aria-label", `Actions for folder ${folderName}`)
    btnEl.setAttribute("x-ref", "trigger")
    btnEl.textContent = "⋯"

    const panelEl = document.createElement("div")
    panelEl.className = "folder-menu"
    panelEl.id = panelId
    panelEl.setAttribute("popover", "auto")
    panelEl.setAttribute("tabindex", "-1")
    panelEl.setAttribute("alt-folder-id", folder.id)
    panelEl.setAttribute("x-ref", "panel")
    panelEl.setAttribute("x-on:beforetoggle", "handleBeforeToggle")
    panelEl.setAttribute("x-on:toggle", "handleToggle")
    panelEl.setAttribute("x-on:htmx:before:request", "handleBeforeRequest")
    panelEl.setAttribute("x-on:htmx:after:request", "handleAfterRequest")
    panelEl.setAttribute("x-on:htmx:after:settle", "handleAfterSettle")

    const actionsEl = document.createElement("div")
    actionsEl.className = "actions"
    actionsEl.setAttribute("x-ref", "actions")
    _buildMenuActions(folder, repoId, dialogId).forEach((actionEl) => {
        actionsEl.appendChild(actionEl)
    })

    const dialogEl = document.createElement("div")
    dialogEl.className = "dialog"
    dialogEl.id = dialogId
    dialogEl.setAttribute("x-ref", "dialog")
    dialogEl.hidden = true

    panelEl.appendChild(actionsEl)
    panelEl.appendChild(dialogEl)

    menuCtrlEl.appendChild(btnEl)
    menuCtrlEl.appendChild(panelEl)
    return menuCtrlEl
}

/**
 * The menu's action buttons, in their established order. Each one requests
 * its dialog into the panel's own dialog host; the root folder can only gain
 * children, so it offers Add folder alone.
 */
function _buildMenuActions(folder, repoId, dialogId) {
    const actions = [
        {
            label: "Add folder",
            dialog: "add-folder",
            vals: { parentId: folder.id },
        },
    ]

    if (!folder.isRoot) {
        actions.push(
            {
                label: "Rename",
                dialog: "rename-folder",
                vals: { id: folder.id },
            },
            {
                label: "Delete",
                dialog: "delete-folder",
                vals: { id: folder.id },
            },
        )
    }

    return actions.map(({ label, dialog, vals }) => {
        const actionEl = document.createElement("button")
        actionEl.type = "button"
        actionEl.setAttribute(
            "hx-get",
            `/htmx/folder/r/${repoId}/dialogs/${dialog}`,
        )
        actionEl.setAttribute("hx-target", `#${dialogId}`)
        actionEl.setAttribute("hx-swap", "innerHTML")
        actionEl.setAttribute("hx-trigger", "click")
        actionEl.setAttribute("hx-vals", JSON.stringify(vals))
        actionEl.textContent = label
        return actionEl
    })
}

function _buildChildrenDiv(folder, repoId, isRoot, depth) {
    const childrenEl = document.createElement("div")
    childrenEl.className = "children"
    childrenEl.id = `children-${folder.id}`
    childrenEl.setAttribute("alt-folder-id", folder.id)

    // Root starts expanded; non-root starts collapsed
    if (!isRoot) {
        childrenEl.style.display = "none"
    }

    folder.children.forEach((child) => {
        childrenEl.appendChild(_renderFolderNode(child, repoId, depth + 1))
    })

    return childrenEl
}
