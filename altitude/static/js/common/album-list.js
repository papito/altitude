/**
 * JSON-driven album list renderer: the flat counterpart of the folder tree (`folder-tree.js`).
 *
 * Public API:
 *   reloadAlbumList(repoId) — fetch every album from the server, re-render, restore focus, and
 *   show the right add control: the top "Add album" button normally, the centered empty-state
 *   button while there are no albums.
 *   refreshAlbumCounts(repoId) — fetch the list and patch only the asset counts in place; falls
 *   back to a full render when the list gained an album the DOM does not have.
 *   setViewedAlbum(albumId) — mark the album whose results are displayed (green icon).
 *
 * Each row shows: the album's ⋯ menu (Rename, Delete; built by `common/context-menu.js`), its
 * asset count (`.asset-count`, empty for zero; `common/asset-count.js`), an icon, and the name.
 * Icon and name are search triggers for the album; every row's `.controls` is a drop zone for
 * assets (`dragdrop/albums.js`). Albums are pointers only: nothing here moves or changes an asset.
 */
import { Const } from "../constants.js"
import { http } from "../http/client.js"
import {
    buildAssetCountEl,
    setAssetCount,
    sizeCountColumn,
} from "./asset-count.js"
import { buildContextMenuCtrl } from "./context-menu.js"
import { showErrorSnackBar } from "./snackbar.js"
import { bindSearchTriggers } from "../search-results/search-triggers.js"

const COUNT_COLUMN_VARIABLE = "--album-count-column"
const SCOPE_MARKER = Const.attributes.viewedScope

// ─── public ─────────────────────────────────────────────────────────────────

// Monotonic reload counter: if another reload starts while a fetch is in flight, the older
// response is discarded so a stale list is never rendered
let _reloadSeq = 0

export async function reloadAlbumList(repoId) {
    const seq = ++_reloadSeq

    try {
        const response = await http.get(`/api/album/r/${repoId}/list`)
        if (seq !== _reloadSeq) return

        _render(response.data, repoId)
    } catch (error) {
        // A newer reload superseded this one - let it report its own outcome
        if (seq !== _reloadSeq) return

        console.error("Failed to load albums", error)
        showErrorSnackBar("Failed to load albums")
    }
}

// Same guard for count refreshes, which never replace the DOM unless they have to
let _countsSeq = 0

/**
 * Re-fetches the list after a membership change or an asset mutation and patches each row's count
 * in place, so focus and open menus are untouched. Nothing is fetched while another explorer tab
 * is active: the next render of the albums tab brings fresh counts. A full reload started
 * meanwhile carries fresh counts itself, so this one stands down.
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

        if (_patchAssetCounts(response.data)) {
            sizeCountColumn(container, COUNT_COLUMN_VARIABLE)
        } else {
            _render(response.data, repoId)
        }
    } catch (error) {
        if (seq !== _countsSeq || reloadSeqAtStart !== _reloadSeq) return

        console.error("Failed to refresh album counts", error)
        showErrorSnackBar("Failed to refresh album counts")
    }
}

let _viewedAlbumId = null

/**
 * Records the album whose results are displayed and marks its row with `alt-viewed-scope`, which
 * the CSS in `views/htmx/albums.scala.html` colors green. Null clears the marker. Like the viewed
 * folder scope, this follows the displayed results, never the search parameters.
 */
export function setViewedAlbum(albumId) {
    _viewedAlbumId = albumId || null
    _applyViewedAlbum()
}

/**
 * Moves focus to whichever add control is visible, if focus was lost. Used after a rebuild that a
 * dialog triggered: adding the first album hides the empty-state button that opened the dialog,
 * and deleting the last album hides the top button its dialog returns focus to.
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

/** Replaces the rendered list with `albums`, preserving focus and the viewed-album marker. */
function _render(albums, repoId) {
    const container = document.getElementById("albumList")
    if (!container) return

    // Snapshot focus only now, immediately before the DOM is replaced: a dialog may have returned
    // it to a menu control while the fetch was in flight
    const focusedId = _getFocusedListControlId(container)

    container.replaceChildren(
        ...albums.map((album) => _buildAlbumRow(album, repoId)),
    )
    sizeCountColumn(container, COUNT_COLUMN_VARIABLE)
    _showAddControl(albums.length === 0)

    // Let HTMX wire up the new elements. Alpine's mutation observer initializes the appended
    // rows; an explicit `initTree` would initialize every component a second time
    if (window.htmx) {
        htmx.process(container)
    }

    // The list is built after the albums tab has already settled, so it is not covered by the
    // fragment hydration that binds every other search trigger
    bindSearchTriggers(container)

    _applyViewedAlbum()

    // Keep keyboard focus on the rebuilt copy of the control that had it, if it still exists
    if (focusedId) {
        document.getElementById(focusedId)?.focus()
    }
}

/** With no albums the centered empty-state button is the only control; otherwise the top button shows */
function _showAddControl(isEmpty) {
    document.getElementById("albumActions").hidden = isEmpty
    document.getElementById("noAlbums").hidden = !isEmpty
}

/**
 * Writes each album's count into its rendered row. Returns false as soon as an album has no row,
 * leaving the caller to render in full; rows with no album in the JSON are left alone, since
 * album operations always reload the whole list.
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

    // The row is the drop target for assets
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

    // ⋯ menu button | asset count | icon | album-name
    controlsEl.appendChild(_buildMenuCtrl(album, repoId))
    controlsEl.appendChild(
        buildAssetCountEl(`album-count-${album.id}`, album.numOfAssets),
    )
    controlsEl.appendChild(iconEl)
    controlsEl.appendChild(nameEl)

    albumEl.appendChild(controlsEl)
    return albumEl
}

/**
 * Opening an album is a search like any other: the element declares the one parameter it knows
 * about and `search-results/search-triggers.js` combines it with the rest of the current search.
 */
function _makeSearchTrigger(el, albumId) {
    el.setAttribute("data-app-search", "click")
    el.setAttribute("data-app-search-album-id", albumId)
}

/**
 * The album's ⋯ menu cell (see `buildContextMenuCtrl`): Rename and Delete, each loading its inline
 * dialog into the panel's dialog host. `albumMenuCtrl-<id>` is the trigger the rename dialog
 * returns focus to.
 */
function _buildMenuCtrl(album, repoId) {
    const actions = [
        { label: "Rename", dialog: "rename-album" },
        { label: "Delete", dialog: "delete-album" },
    ]

    return buildContextMenuCtrl({
        triggerId: `albumMenuCtrl-${album.id}`,
        panelId: `albumMenu-${album.id}`,
        dialogId: `albumMenuDialog-${album.id}`,
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
