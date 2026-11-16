/**
 * The album list, rendered from JSON: the flat counterpart of the folder tree. Albums only point at
 * assets, so nothing here moves or changes an asset.
 */
import { Const } from "../constants.js"
import { context } from "../context.js"
import { http } from "../http/client.js"
import { buildAssetCountEl, setAssetCount } from "./asset-count.js"
import {
    buildContextMenuCtrl,
    buildModalTriggerCtrl,
    buildNoMenuCell,
} from "./context-menu-markup.js"
import { showErrorSnackBar } from "./snackbar.js"
import { bindSearchTriggers } from "../search-results/search-triggers.js"

const SCOPE_MARKER = Const.attributes.viewedScope

// ─── public ─────────────────────────────────────────────────────────────────

// A reload started while a fetch is in flight discards the older response, so a stale list never renders
let _reloadSeq = 0

export async function reloadAlbumList(repoId) {
    const seq = ++_reloadSeq

    try {
        const response = await http.get(`/api/album/r/${repoId}/list`)
        if (seq !== _reloadSeq) return

        _render(response.data, repoId)
    } catch (error) {
        // Superseded: the newer reload reports its own outcome
        if (seq !== _reloadSeq) return

        console.error("Failed to load albums", error)
        showErrorSnackBar("Failed to load albums")
    }
}

// Same guard for count refreshes, which never replace the DOM unless they have to
let _countsSeq = 0

/**
 * Patches the counts in place, so focus and open menus are untouched. Skipped under another explorer
 * tab, whose switch back renders fresh counts; stands down for a full reload started meanwhile.
 */
export async function refreshAlbumCounts(repoId) {
    if (!document.getElementById("albumList")) return

    const seq = ++_countsSeq
    const reloadSeqAtStart = _reloadSeq

    try {
        const response = await http.get(`/api/album/r/${repoId}/list`)
        if (seq !== _countsSeq || reloadSeqAtStart !== _reloadSeq) return

        const container = document.getElementById("albumList")
        if (!container) return

        if (!_patchAssetCounts(response.data)) {
            _render(response.data, repoId)
        }
    } catch (error) {
        if (seq !== _countsSeq || reloadSeqAtStart !== _reloadSeq) return

        console.error("Failed to refresh album counts", error)
        showErrorSnackBar("Failed to refresh album counts")
    }
}

let _viewedAlbumId = null

/** Like the viewed folder scope, this follows the displayed results, never the search parameters */
export function setViewedAlbum(albumId) {
    _viewedAlbumId = albumId || null
    _applyViewedAlbum()
}

/**
 * For a rebuild a dialog triggered: adding the first album hides the empty-state button that opened
 * the dialog, and deleting the last hides the top button its dialog returns focus to.
 */
export function focusAddAlbumControlIfFocusLost() {
    // A control hidden after it took focus can keep it for a while; that counts as lost too
    const active = document.activeElement
    if (active && active !== document.body && !active.closest("[hidden]")) {
        return
    }

    document
        .querySelector(
            "#albumActions:not([hidden]) button, #noAlbums:not([hidden]) button",
        )
        ?.focus()
}

// ─── rendering ──────────────────────────────────────────────────────────────

function _render(albums, repoId) {
    const container = document.getElementById("albumList")
    if (!container) return

    // Snapshot focus only now, immediately before the DOM is replaced: a dialog may have returned
    // it to a menu control while the fetch was in flight
    const focusedId = _getFocusedListControlId(container)

    container.replaceChildren(
        ...albums.map((album) => _buildAlbumRow(album, repoId)),
    )
    _ensureAddControls(repoId)
    _showAddControl(albums.length === 0)

    // Alpine's mutation observer initializes the appended rows; an explicit `initTree` would
    // initialize every component a second time
    if (window.htmx) {
        htmx.process(container)
    }

    // Built after the tab settled, so the fragment hydration that binds other search triggers missed it
    bindSearchTriggers(container)

    _applyViewedAlbum()

    if (focusedId) {
        document.getElementById(focusedId)?.focus()
    }
}

/** Built once, on the first render after the tab loads; `_showAddControl` decides which one shows */
function _ensureAddControls(repoId) {
    const actionsEl = document.getElementById("albumActions")
    if (actionsEl.childElementCount > 0) return
    // Adding an album is Organize's: without it both hosts stay empty
    if (!context.holds(Const.permission.organize)) return

    const url = `/htmx/album/r/${repoId}/dialogs/add-album`

    actionsEl.appendChild(
        buildModalTriggerCtrl({
            triggerId: "addAlbumBtn",
            label: "Add album",
            iconClass: "fas fa-plus",
            url,
            buttonClass: "action-button small",
        }),
    )

    const noAlbumsEl = document.getElementById("noAlbums")
    noAlbumsEl.appendChild(
        buildModalTriggerCtrl({
            triggerId: "addFirstAlbumBtn",
            label: "Add your first album",
            iconClass: "fas fa-plus",
            url,
            buttonClass: "action-button primary",
        }),
    )

    if (window.htmx) {
        htmx.process(actionsEl)
        htmx.process(noAlbumsEl)
    }
}

function _showAddControl(isEmpty) {
    document.getElementById("albumActions").hidden = isEmpty
    document.getElementById("noAlbums").hidden = !isEmpty
}

/**
 * False as soon as an album has no row, for a full render. Rows with no album in the JSON are left
 * alone, since album operations always reload the whole list.
 */
function _patchAssetCounts(albums) {
    return albums.every((album) => {
        const el = document.getElementById(`album-count-${album.id}`)
        if (!el) return false

        setAssetCount(el, album.numOfAssets)
        return true
    })
}

function _getFocusedListControlId(container) {
    const activeEl = document.activeElement

    return container.contains(activeEl) && activeEl.id ? activeEl.id : null
}

function _applyViewedAlbum() {
    const container = document.getElementById("albumList")
    if (!container) return

    container
        .querySelectorAll(`[${SCOPE_MARKER}]`)
        .forEach((el) => el.removeAttribute(SCOPE_MARKER))

    if (!_viewedAlbumId) return

    document
        .getElementById(`album-${_viewedAlbumId}`)
        ?.setAttribute(SCOPE_MARKER, "true")
}

// ─── DOM builders ────────────────────────────────────────────────────────────

function _buildAlbumRow(album, repoId) {
    const albumEl = document.createElement("div")
    albumEl.className = "album"
    albumEl.id = `album-${album.id}`
    albumEl.setAttribute(Const.attributes.albumId, album.id)

    // The assets' drop target
    const controlsEl = document.createElement("div")
    controlsEl.classList.add("controls", "dropzone")
    controlsEl.setAttribute(Const.attributes.albumId, album.id)

    const iconEl = document.createElement("i")
    iconEl.id = `album-icon-${album.id}`
    iconEl.className = "fas fa-images album-icon"
    _makeSearchTrigger(iconEl, album.id)

    const nameEl = document.createElement("span")
    nameEl.id = `albumName-${album.id}`
    nameEl.className = "album-name"
    nameEl.textContent = album.name
    _makeSearchTrigger(nameEl, album.id)

    controlsEl.appendChild(_buildMenuCtrl(album, repoId))
    controlsEl.appendChild(iconEl)
    controlsEl.appendChild(nameEl)
    controlsEl.appendChild(
        buildAssetCountEl(`album-count-${album.id}`, album.numOfAssets),
    )

    albumEl.appendChild(controlsEl)
    return albumEl
}

/** The element declares its one parameter; `search-triggers.js` combines it with the current search */
function _makeSearchTrigger(el, albumId) {
    el.setAttribute("data-app-search", "click")
    el.setAttribute("data-app-search-album-id", albumId)
}

/**
 * `albumMenuCtrl-<id>` is the trigger the rename dialog returns focus to. Both actions are
 * Organize's, so an account without it gets the empty cell.
 */
function _buildMenuCtrl(album, repoId) {
    if (!context.holds(Const.permission.organize)) {
        return buildNoMenuCell()
    }

    const actions = [
        { label: "Rename", dialog: "rename-album" },
        { label: "Delete", dialog: "delete-album" },
    ]

    return buildContextMenuCtrl({
        triggerId: `albumMenuCtrl-${album.id}`,
        panelId: `albumMenu-${album.id}`,
        ariaLabel: `Actions for album ${album.name}`,
        entityAttr: Const.attributes.albumId,
        entityId: album.id,
        actions: actions.map(({ label, dialog }) => ({
            label,
            url: `/htmx/album/r/${repoId}/dialogs/${dialog}`,
            vals: { id: album.id },
        })),
    })
}
