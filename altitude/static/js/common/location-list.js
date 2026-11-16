/**
 * JSON-driven Location list: the album list's shape with one level of nesting. The endpoint returns
 * rows in path order; a category node holds its row and a children container with its Locations,
 * collapsed on first render. A category holds no assets, so it is neither a drop target nor a
 * search trigger; a category with no Locations has no expand button. A Location's icon and name
 * are search triggers and its row a drop zone. Locations are pointers only: nothing here moves or
 * changes an asset.
 *
 * Expansion survives every rebuild within the page and is not persisted across page loads.
 */
import { closeOpenContextMenu } from "../alpine/components/context-menu.js"
import { Const } from "../constants.js"
import { context } from "../context.js"
import { http } from "../http/client.js"
import { buildAssetCountEl, setAssetCount } from "./asset-count.js"
import {
    buildContextMenuCtrl,
    buildModalTriggerCtrl,
    buildNoMenuCell,
} from "./context-menu-markup.js"
import { ModalHost } from "./modal.js"
import { showErrorSnackBar } from "./snackbar.js"
import { bindSearchTriggers } from "../search-results/search-triggers.js"

const SCOPE_MARKER = Const.attributes.viewedScope
const EXPANDED = Const.attributes.expanded
const CATEGORY_KIND = "category"

// ─── public ─────────────────────────────────────────────────────────────────

// A reload started while a fetch is in flight discards the older response, so a stale list is
// never rendered
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
 * Patches each Location row's count in place, leaving expansion, focus and open menus alone; a
 * Location the DOM lacks means a full render. Fetches nothing while another explorer tab is active,
 * and stands down if a full reload started meanwhile.
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

        if (!_patchAssetCounts(response.data)) {
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
 * Marks the Location whose results are displayed (null clears it). Like the viewed folder scope,
 * it follows the displayed results, never the search parameters.
 */
export function setViewedLocation(locationId) {
    _viewedLocationId = locationId || null
    _applyViewedLocation()
}

/** Reveals a category's Locations; a no-op for anything but a category with Locations */
export function expandLocationCategory(categoryId) {
    const nodeEl = _findCategoryNode(categoryId)
    if (nodeEl) {
        _setCategoryExpanded(nodeEl, true)
    }
}

/**
 * Moves focus to whichever add control is visible, if focus was lost: adding the first Location
 * hides the empty-state button, and deleting the last row hides the top buttons.
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

function _render(locations, repoId) {
    const container = document.getElementById("locationList")
    if (!container) return

    // Snapshot only now: expansion or focus may have changed while the fetch was in flight (a
    // dialog returns focus to a menu control as its operation completes)
    const expandedIds = _getExpandedCategoryIds(container)
    const focusedId = _getFocusedListControlId(container)

    container.replaceChildren(..._buildNodes(locations, repoId))
    _ensureAddControls(repoId)
    _showAddControl(locations.length === 0)

    // Alpine's mutation observer initializes the new rows; an explicit `initTree` would
    // initialize every component twice
    if (window.htmx) {
        htmx.process(container)
    }

    // Built after the tab settled, so the fragment hydration of search triggers missed it
    bindSearchTriggers(container)

    expandedIds.forEach(expandLocationCategory)

    _applyViewedLocation()

    if (focusedId) {
        document.getElementById(focusedId)?.focus()
    }
}

/**
 * Builds the add controls on the first render. "Add location" opens the general modal, with room
 * for its map editor.
 */
function _ensureAddControls(repoId) {
    const actionsEl = document.getElementById("locationActions")
    if (actionsEl.childElementCount > 0) return
    // Without Organize both hosts stay empty
    if (!context.holds(Const.permission.organize)) return

    actionsEl.appendChild(
        buildModalTriggerCtrl({
            triggerId: "addLocationBtn",
            label: "Add location",
            buttonClass: "action-button small",
            iconClass: "fas fa-plus",
            url: `/htmx/location/r/${repoId}/dialogs/add-location`,
            host: ModalHost.general,
        }),
    )
    actionsEl.appendChild(
        buildModalTriggerCtrl({
            triggerId: "addCategoryBtn",
            label: "Add category",
            iconClass: "fas fa-plus",
            url: `/htmx/location/r/${repoId}/dialogs/add-category`,
            buttonClass: "action-button small",
        }),
    )

    const noLocationsEl = document.getElementById("noLocations")
    noLocationsEl.appendChild(
        buildModalTriggerCtrl({
            triggerId: "addFirstLocationBtn",
            label: "Add your first location",
            buttonClass: "action-button primary",
            iconClass: "fas fa-plus",
            url: `/htmx/location/r/${repoId}/dialogs/add-location`,
            host: ModalHost.general,
        }),
    )

    if (window.htmx) {
        htmx.process(actionsEl)
        htmx.process(noLocationsEl)
    }
}

function _showAddControl(isEmpty) {
    document.getElementById("locationActions").hidden = isEmpty
    document.getElementById("noLocations").hidden = !isEmpty
}

/**
 * Returns false as soon as a Location has no row. Rows missing from the JSON are left alone:
 * Location operations always reload the whole list.
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

// ─── snapshot helpers ────────────────────────────────────────────────────────

function _getExpandedCategoryIds(container) {
    return [...container.querySelectorAll(`.location.category[${EXPANDED}]`)]
        .map((el) => el.dataset.locationId)
        .filter(Boolean)
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

// ─── expansion ──────────────────────────────────────────────────────────────

function _findCategoryNode(categoryId) {
    const el = categoryId
        ? document.getElementById(`location-${categoryId}`)
        : null

    return el?.classList.contains("category") ? el : null
}

function _isExpanded(nodeEl) {
    return nodeEl.getAttribute(EXPANDED) === "true"
}

/**
 * The one place a category's state changes, so `data-expanded` (read by the CSS), the children's
 * visibility and the button's `aria-expanded` and tooltip move together.
 */
function _setCategoryExpanded(nodeEl, expanded) {
    const id = nodeEl.dataset.locationId
    const childrenEl = document.getElementById(`location-children-${id}`)
    const ctrlEl = document.getElementById(`location-expand-${id}`)
    if (!childrenEl || !ctrlEl) return

    if (expanded) {
        nodeEl.setAttribute(EXPANDED, "true")
    } else {
        nodeEl.removeAttribute(EXPANDED)
    }

    childrenEl.style.display = expanded ? "" : "none"
    ctrlEl.setAttribute("aria-expanded", String(expanded))
    ctrlEl.title = expanded ? "Hide locations" : "Show locations"
}

/**
 * Hiding the Locations hides their menu triggers too, so an open menu among them is closed first,
 * and focus inside moves to the category's button rather than staying in hidden content.
 */
function _collapseCategory(nodeEl) {
    const id = nodeEl.dataset.locationId
    const childrenEl = document.getElementById(`location-children-${id}`)
    if (!childrenEl) return

    closeOpenContextMenu({
        reason: `category ${_nameOf(nodeEl)} collapsed`,
        within: childrenEl,
    })

    const focusWasInside = childrenEl.contains(document.activeElement)

    _setCategoryExpanded(nodeEl, false)

    if (focusWasInside) {
        document.getElementById(`location-expand-${id}`)?.focus()
    }

    console.debug(`Collapsed category ${_nameOf(nodeEl)}`)
}

/**
 * Only the first click of a pointer sequence (`detail` 1) toggles, so a double- or triple-click
 * toggles once; keyboard activation (`detail` 0) always toggles. Categories are one level deep, so
 * there is no `dblclick` action.
 */
function _bindExpandGesture(ctrlEl, categoryId) {
    ctrlEl.addEventListener("click", (event) => {
        event.preventDefault()

        if (!ctrlEl.isConnected || event.detail > 1) return

        const nodeEl = _findCategoryNode(categoryId)
        if (!nodeEl) return

        if (_isExpanded(nodeEl)) {
            _collapseCategory(nodeEl)
        } else {
            _setCategoryExpanded(nodeEl, true)
            console.debug(`Expanded category ${_nameOf(nodeEl)}`)
        }
    })
}

function _nameOf(nodeEl) {
    return (
        document.getElementById(`locationName-${nodeEl.dataset.locationId}`)
            ?.textContent ?? nodeEl.dataset.locationId
    )
}

// ─── DOM builders ────────────────────────────────────────────────────────────

/**
 * The top-level nodes in row order. Locations are grouped by category ID rather than by adjacency,
 * so the nesting is right whatever the order.
 */
function _buildNodes(locations, repoId) {
    const categoryIds = new Set(
        locations
            .filter((location) => location.kind === CATEGORY_KIND)
            .map((location) => location.id),
    )
    const childrenOf = new Map()
    locations.forEach((location) => {
        if (
            location.kind !== CATEGORY_KIND &&
            categoryIds.has(location.categoryId)
        ) {
            const siblings = childrenOf.get(location.categoryId) ?? []
            siblings.push(location)
            childrenOf.set(location.categoryId, siblings)
        }
    })

    const nodes = []
    locations.forEach((location) => {
        if (location.kind === CATEGORY_KIND) {
            nodes.push(
                _buildCategoryNode(
                    location,
                    childrenOf.get(location.id) ?? [],
                    repoId,
                ),
            )
        } else if (!categoryIds.has(location.categoryId)) {
            nodes.push(_buildLocationRow(location, repoId, false))
        }
    })

    return nodes
}

function _buildCategoryNode(category, children, repoId) {
    const nodeEl = document.createElement("div")
    nodeEl.className = "location category"
    nodeEl.id = `location-${category.id}`
    nodeEl.setAttribute(Const.attributes.locationId, category.id)
    nodeEl.setAttribute(Const.attributes.kind, category.kind)

    const controlsEl = document.createElement("div")
    controlsEl.className = "controls"
    controlsEl.appendChild(_buildMenuCtrl(category, repoId))

    const iconEl = _buildIconEl(category, "fa-layer-group")
    const nameEl = _buildNameEl(category)

    if (children.length > 0) {
        // ⋯ menu button | expand button (icon | name)
        controlsEl.appendChild(_buildExpandCtrl(category, iconEl, nameEl))
    } else {
        // ⋯ menu button | icon | name
        controlsEl.appendChild(iconEl)
        controlsEl.appendChild(nameEl)
    }
    nodeEl.appendChild(controlsEl)

    if (children.length > 0) {
        const childrenEl = document.createElement("div")
        childrenEl.className = "children"
        childrenEl.id = `location-children-${category.id}`
        childrenEl.setAttribute(Const.attributes.locationId, category.id)
        childrenEl.style.display = "none"
        children.forEach((child) => {
            childrenEl.appendChild(_buildLocationRow(child, repoId, true))
        })
        nodeEl.appendChild(childrenEl)
    }

    return nodeEl
}

/**
 * A button wrapping the icon and the name, so clicking either toggles. Its ID is stable across
 * rebuilds for focus restoration. The CSS draws the +/− badge on the icon.
 */
function _buildExpandCtrl(category, iconEl, nameEl) {
    const ctrlEl = document.createElement("button")
    ctrlEl.type = "button"
    ctrlEl.id = `location-expand-${category.id}`
    ctrlEl.className = "expand-ctrl"
    ctrlEl.setAttribute(Const.attributes.locationId, category.id)
    ctrlEl.setAttribute("aria-label", `Locations in ${category.name}`)
    ctrlEl.setAttribute("aria-controls", `location-children-${category.id}`)
    ctrlEl.setAttribute("aria-expanded", "false")
    ctrlEl.title = "Show locations"

    iconEl.setAttribute("aria-hidden", "true")
    ctrlEl.appendChild(iconEl)
    ctrlEl.appendChild(nameEl)
    _bindExpandGesture(ctrlEl, category.id)

    return ctrlEl
}

/** A `.child` row has `--depth: 1` and a `.trace` cell, so `locations.css` indents it like a folder */
function _buildLocationRow(location, repoId, isChild) {
    const rowEl = document.createElement("div")
    rowEl.className = `location ${isChild ? "child" : "top-level"}`
    rowEl.id = `location-${location.id}`
    rowEl.setAttribute(Const.attributes.locationId, location.id)
    rowEl.setAttribute(Const.attributes.kind, location.kind)
    if (isChild) {
        rowEl.setAttribute(Const.attributes.categoryId, location.categoryId)
        rowEl.style.setProperty("--depth", 1)
    }

    const controlsEl = document.createElement("div")
    controlsEl.className = "controls dropzone"
    controlsEl.setAttribute(Const.attributes.locationId, location.id)

    const iconEl = _buildIconEl(location, "fa-map-marker-alt")
    const nameEl = _buildNameEl(location)
    _makeSearchTrigger(iconEl, location.id)
    _makeSearchTrigger(nameEl, location.id)

    // ⋯ menu button | [trace] | icon | name | asset count
    controlsEl.appendChild(_buildMenuCtrl(location, repoId))
    if (isChild) {
        const traceEl = document.createElement("span")
        traceEl.className = "trace"
        controlsEl.appendChild(traceEl)
    }
    controlsEl.appendChild(iconEl)
    controlsEl.appendChild(nameEl)
    controlsEl.appendChild(
        buildAssetCountEl(
            `location-count-${location.id}`,
            location.numOfAssets,
        ),
    )

    rowEl.appendChild(controlsEl)
    return rowEl
}

function _buildIconEl(location, glyphClass) {
    const iconEl = document.createElement("i")
    iconEl.id = `locationIcon-${location.id}`
    iconEl.className = `fas ${glyphClass} location-icon`
    return iconEl
}

function _buildNameEl(location) {
    const nameEl = document.createElement("span")
    nameEl.id = `locationName-${location.id}`
    nameEl.className = "location-name"
    nameEl.textContent = location.name
    return nameEl
}

/**
 * Opening a Location is a search: the element declares its one parameter and
 * `search-results/search-triggers.js` combines it with the current search. The element also
 * anchors the scope dialog its click may open in Triage; its ID is stable, so a rebuild re-anchors
 * an open dialog.
 */
function _makeSearchTrigger(el, locationId) {
    el.setAttribute("data-app-search", "click")
    el.setAttribute("data-app-search-location-id", locationId)
    el.style.anchorName = `--${el.id}`
    el.dataset.appDialogAnchor = el.id
}

/**
 * The row's ⋯ menu cell. The trigger ID is stable, since dialogs return focus to it. Every action
 * needs Organize.
 */
function _buildMenuCtrl(location, repoId) {
    if (!context.holds(Const.permission.organize)) {
        return buildNoMenuCell()
    }

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
