/**
 * JSON-driven folder tree renderer.
 *
 * Public API:
 *   reloadFolderTree(repoId) — fetch the full tree from the server, snapshot
 *   currently-expanded folder IDs and the focused tree control, re-render,
 *   restore expanded state and focus.
 *
 * The DOM structure produced mirrors what the old Twirl templates generated so
 * that the existing Folder JS model, CSS, and drag-and-drop wiring all continue
 * to work without modification.
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

        // Snapshot focus only now: it may have moved into the tree while the fetch was in flight
        // (a dialog returning focus to a folder menu control as its operation completes)
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

        // Restore previously-expanded folders (best-effort; silently skip
        // folders that no longer exist after the mutation).
        _restoreExpandedState(expandedIds)

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
        .querySelectorAll("#rootFolderList .folder[alt-expanded]")
        .forEach((el) => {
            const id = el.getAttribute("alt-folder-id")
            if (id) ids.add(id)
        })
    return ids
}

function _getFocusedTreeControlId() {
    const activeEl = document.activeElement
    const container = document.getElementById("rootFolderList")

    return container?.contains(activeEl) && activeEl.id ? activeEl.id : null
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
    const menuCtrlEl = _buildMenuCtrl(folder, repoId, folder.name)

    // Dotted guide from the ⋯ button to the icon; its width is the row's indent
    const traceEl = document.createElement("span")
    traceEl.className = "trace"

    // ⋯ menu button | trace | icon | folder-name
    controlsEl.appendChild(menuCtrlEl)
    controlsEl.appendChild(traceEl)
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
