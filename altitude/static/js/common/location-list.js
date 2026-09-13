/**
 * JSON-driven Location list renderer: the album list's shape (`album-list.js`) with one level of
 * nesting.
 *
 * Public API:
 *   reloadLocationList(repoId) — fetch every category and Location from the server, re-render, restore
 *   focus, and show the right add controls: the top "Add location" and "Add category" buttons
 *   normally, the centered "Add your first location" while the list is empty.
 *   refreshLocationCounts(repoId) — fetch the list and patch only the asset counts in place; falls
 *   back to a full render when the list gained a row the DOM does not have.
 *   setViewedLocation(locationId) — mark the Location whose results are displayed (green icon).
 *   focusAddLocationControlIfFocusLost() — move focus to whichever add control is visible.
 *
 * The list endpoint returns the rows in path order (a category directly followed by its Locations,
 * top-level Locations interleaved by name), so the list is rendered in that order as it comes: a
 * category row, then each of its Locations indented one step. A category row shows its ⋯ menu (Rename,
 * Delete), an icon and the name; it holds no assets, so it is neither a drop target nor a search
 * trigger. A Location row shows its ⋯ menu (Rename, Delete, Move to category), its asset count
 * (`.asset-count`, empty for zero; `common/asset-count.js`), an icon and the name; icon and name
 * are search triggers for the Location and the row's `.controls` is a drop zone for assets
 * (`dragdrop/locations.js`). Locations are pointers only: nothing here moves or changes an asset.
 */
import { Const } from "../constants.js"
import { http } from "../http/client.js"
import {
    buildAssetCountEl,
    setAssetCount,
    sizeCountColumn,
} from "./asset-count.js"
import {
    buildContextMenuCtrl,
    buildDialogTriggerCtrl,
} from "./context-menu-markup.js"
import { showErrorSnackBar } from "./snackbar.js"
import { bindSearchTriggers } from "../search-results/search-triggers.js"

const COUNT_COLUMN_VARIABLE = "--location-count-column"
const SCOPE_MARKER = Const.attributes.viewedScope
const CATEGORY_KIND = "category"

// ─── public ─────────────────────────────────────────────────────────────────

// Monotonic reload counter: if another reload starts while a fetch is in flight, the older
// response is discarded so a stale list is never rendered
let _reloadSeq = 0

export async function reloadLocationList(repoId) {
    const seq = ++_reloadSeq

    try {
        const response = await http.get(`/api/location/r/${repoId}/list`)
        if (seq !== _reloadSeq) return

        _render(response.data, repoId)
    } catch (error) {
        // A newer reload superseded this one - let it report its own outcome
        if (seq !== _reloadSeq) return

        console.error("Failed to load locations", error)
        showErrorSnackBar("Failed to load locations")
    }
}

// Same guard for count refreshes, which never replace the DOM unless they have to
let _countsSeq = 0

/**
 * Re-fetches the list after a membership change or an asset mutation and patches each Location
 * row's count in place, so focus and open menus are untouched. Nothing is fetched while another
 * explorer tab is active: the next render of the Locations tab brings fresh counts. A full reload
 * started meanwhile carries fresh counts itself, so this one stands down.
 */
export async function refreshLocationCounts(repoId) {
    if (!document.getElementById("locationList")) return

    const seq = ++_countsSeq
    const reloadSeqAtStart = _reloadSeq

    try {
        const response = await http.get(`/api/location/r/${repoId}/list`)
        if (seq !== _countsSeq || reloadSeqAtStart !== _reloadSeq) return

        const container = document.getElementById("locationList")
        if (!container) return

        if (_patchAssetCounts(response.data)) {
            sizeCountColumn(container, COUNT_COLUMN_VARIABLE)
        } else {
            _render(response.data, repoId)
        }
    } catch (error) {
        if (seq !== _countsSeq || reloadSeqAtStart !== _reloadSeq) return

        console.error("Failed to refresh location counts", error)
        showErrorSnackBar("Failed to refresh location counts")
    }
}

let _viewedLocationId = null

/**
 * Records the Location whose results are displayed and marks its row with `data-viewed-scope`,
 * which the CSS in `views/htmx/locations.scala.html` colors green. Null clears the marker. Like
 * the viewed folder scope, this follows the displayed results, never the search parameters.
 */
export function setViewedLocation(locationId) {
    _viewedLocationId = locationId || null
    _applyViewedLocation()
}

/**
 * Moves focus to whichever add control is visible, if focus was lost. Used after a rebuild that a
 * dialog triggered: adding the first Location hides the empty-state button that opened the dialog,
 * and deleting the last row hides the top button its dialog returns focus to.
 */
export function focusAddLocationControlIfFocusLost() {
    // A control hidden after it took focus can keep it for a while; that counts as lost too
    const active = document.activeElement
    if (active && active !== document.body && !active.closest("[hidden]")) {
        return
    }

    document
        .querySelector(
            "#locationActions:not([hidden]) button, #noLocations:not([hidden]) button",
        )
        ?.focus()
}

// ─── rendering ──────────────────────────────────────────────────────────────

/** Replaces the rendered list with `locations`, preserving focus and the viewed-Location marker. */
function _render(locations, repoId) {
    const container = document.getElementById("locationList")
    if (!container) return

    // Snapshot focus only now, immediately before the DOM is replaced: a dialog may have returned
    // it to a menu control while the fetch was in flight
    const focusedId = _getFocusedListControlId(container)

    container.replaceChildren(
        ...locations.map((location) => _buildRow(location, repoId)),
    )
    sizeCountColumn(container, COUNT_COLUMN_VARIABLE)
    _ensureAddControls(repoId)
    _showAddControl(locations.length === 0)

    // Let HTMX wire up the new elements. Alpine's mutation observer initializes the appended
    // rows; an explicit `initTree` would initialize every component a second time
    if (window.htmx) {
        htmx.process(container)
    }

    // The list is built after the Locations tab has already settled, so it is not covered by the
    // fragment hydration that binds every other search trigger
    bindSearchTriggers(container)

    _applyViewedLocation()

    // Keep keyboard focus on the rebuilt copy of the control that had it, if it still exists
    if (focusedId) {
        document.getElementById(focusedId)?.focus()
    }
}

/**
 * Builds the add controls into their hosts on the first render after the tab loads. "Add location"
 * requests its modal into the modal host (the dialog holds a map, so it is not an inline one);
 * "Add category" and the empty-state button are built here rather than in the tab template because
 * they are context menu components like the rows' menus. The hosts (`#locationActions`,
 * `#noLocations`) only decide which is shown.
 */
function _ensureAddControls(repoId) {
    const actionsEl = document.getElementById("locationActions")
    if (actionsEl.childElementCount > 0) return

    actionsEl.appendChild(
        _buildAddLocationButton({
            id: "addLocationBtn",
            label: "Add location",
            buttonClass: "action-button small",
            repoId,
        }),
    )
    actionsEl.appendChild(
        buildDialogTriggerCtrl({
            triggerId: "addCategoryBtn",
            panelId: "addCategoryMenu",
            dialogId: "addCategoryDialog",
            label: "Add category",
            iconClass: "fas fa-plus",
            url: `/htmx/location/r/${repoId}/dialogs/add-category`,
            buttonClass: "action-button small",
        }),
    )

    const noLocationsEl = document.getElementById("noLocations")
    noLocationsEl.appendChild(
        _buildAddLocationButton({
            id: "addFirstLocationBtn",
            label: "Add your first location",
            buttonClass: "action-button",
            repoId,
        }),
    )

    if (window.htmx) {
        htmx.process(actionsEl)
        htmx.process(noLocationsEl)
    }
}

/** A button requesting the Add location modal into the modal host; the dialog returns focus to it on close */
function _buildAddLocationButton({ id, label, buttonClass, repoId }) {
    const btnEl = document.createElement("button")
    btnEl.type = "button"
    btnEl.id = id
    btnEl.className = buttonClass
    btnEl.setAttribute(
        "hx-get",
        `/htmx/location/r/${repoId}/dialogs/add-location`,
    )
    btnEl.setAttribute("hx-target", "#modalContent")
    btnEl.setAttribute("hx-swap", "innerHTML")
    btnEl.setAttribute("hx-trigger", "click")

    const iconEl = document.createElement("i")
    iconEl.className = "fas fa-plus"
    iconEl.setAttribute("aria-hidden", "true")
    const labelEl = document.createElement("span")
    labelEl.textContent = label
    btnEl.appendChild(iconEl)
    btnEl.appendChild(labelEl)

    return btnEl
}

/** With no rows the centered empty-state button is the only control; otherwise the top buttons show */
function _showAddControl(isEmpty) {
    document.getElementById("locationActions").hidden = isEmpty
    document.getElementById("noLocations").hidden = !isEmpty
}

/**
 * Writes each Location's count into its rendered row. Returns false as soon as a Location has no
 * row, leaving the caller to render in full; rows with no Location in the JSON are left alone,
 * since Location operations always reload the whole list. Categories carry no count.
 */
function _patchAssetCounts(locations) {
    return locations
        .filter((location) => location.kind !== CATEGORY_KIND)
        .every((location) => {
            const el = document.getElementById(`location-count-${location.id}`)
            if (!el) return false

            setAssetCount(el, location.numOfAssets)
            return true
        })
}

function _getFocusedListControlId(container) {
    const activeEl = document.activeElement

    return container.contains(activeEl) && activeEl.id ? activeEl.id : null
}

function _applyViewedLocation() {
    const container = document.getElementById("locationList")
    if (!container) return

    container
        .querySelectorAll(`[${SCOPE_MARKER}]`)
        .forEach((el) => el.removeAttribute(SCOPE_MARKER))

    if (!_viewedLocationId) return

    document
        .getElementById(`location-${_viewedLocationId}`)
        ?.setAttribute(SCOPE_MARKER, "true")
}

// ─── DOM builders ────────────────────────────────────────────────────────────

/**
 * One row. A category is `.category`; a Location is `.top-level` or, under a category, `.child` with
 * `--depth: 1` and a `.trace` cell, so the CSS in the tab template indents it like a folder.
 */
function _buildRow(location, repoId) {
    const isCategory = location.kind === CATEGORY_KIND
    const isChild = !isCategory && Boolean(location.categoryId)

    const rowEl = document.createElement("div")
    rowEl.className = `location ${isCategory ? "category" : isChild ? "child" : "top-level"}`
    rowEl.id = `location-${location.id}`
    rowEl.setAttribute(Const.attributes.locationId, location.id)
    rowEl.setAttribute(Const.attributes.kind, location.kind)
    if (isChild) {
        rowEl.setAttribute(Const.attributes.categoryId, location.categoryId)
        rowEl.style.setProperty("--depth", 1)
    }

    const controlsEl = document.createElement("div")
    controlsEl.className = "controls"

    const iconEl = document.createElement("i")
    iconEl.id = `location-icon-${location.id}`
    iconEl.className = `fas ${isCategory ? "fa-layer-group" : "fa-map-marker-alt"} location-icon`

    const nameEl = document.createElement("span")
    nameEl.id = `locationName-${location.id}`
    nameEl.className = "location-name"
    nameEl.textContent = location.name

    controlsEl.appendChild(_buildMenuCtrl(location, repoId))

    if (isCategory) {
        // ⋯ menu button | icon | name
        controlsEl.appendChild(iconEl)
        controlsEl.appendChild(nameEl)
    } else {
        // ⋯ menu button | [trace] | asset count | icon | name; the row is the drop target for assets
        controlsEl.classList.add("dropzone")
        controlsEl.setAttribute(Const.attributes.locationId, location.id)
        _makeSearchTrigger(iconEl, location.id)
        _makeSearchTrigger(nameEl, location.id)

        if (isChild) {
            const traceEl = document.createElement("span")
            traceEl.className = "trace"
            controlsEl.appendChild(traceEl)
        }
        controlsEl.appendChild(
            buildAssetCountEl(
                `location-count-${location.id}`,
                location.numOfAssets,
            ),
        )
        controlsEl.appendChild(iconEl)
        controlsEl.appendChild(nameEl)
    }

    rowEl.appendChild(controlsEl)
    return rowEl
}

/**
 * Opening a Location is a search like any other: the element declares the one parameter it knows
 * about and `search-results/search-triggers.js` combines it with the rest of the current search.
 */
function _makeSearchTrigger(el, locationId) {
    el.setAttribute("data-app-search", "click")
    el.setAttribute("data-app-search-location-id", locationId)
}

/**
 * The row's ⋯ menu cell (see `buildContextMenuCtrl`): Rename and Delete for both kinds, plus Move
 * to category for a Location, each loading its inline dialog into the panel's dialog host.
 * `locationMenuCtrl-<id>` is the trigger the rename and move dialogs return focus to.
 */
function _buildMenuCtrl(location, repoId) {
    const isCategory = location.kind === CATEGORY_KIND
    const actions = [
        { label: "Rename", dialog: "rename-location" },
        { label: "Delete", dialog: "delete-location" },
    ]
    if (!isCategory) {
        actions.push({ label: "Move to category", dialog: "move-location" })
    }

    return buildContextMenuCtrl({
        triggerId: `locationMenuCtrl-${location.id}`,
        panelId: `locationMenu-${location.id}`,
        dialogId: `locationMenuDialog-${location.id}`,
        ariaLabel: `Actions for ${isCategory ? "category" : "location"} ${location.name}`,
        entityAttr: Const.attributes.locationId,
        entityId: location.id,
        actions: actions.map(({ label, dialog }) => ({
            label,
            url: `/htmx/location/r/${repoId}/dialogs/${dialog}`,
            vals: { id: location.id },
        })),
    })
}
