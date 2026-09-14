/**
 * JSON-driven Location list renderer: the album list's shape (`album-list.js`) with one level of
 * nesting.
 *
 * Public API:
 *   reloadLocationList(repoId) — fetch every category and Location from the server, snapshot the
 *   expanded categories and the focused control, re-render, restore both, and show the right add
 *   controls: the top "Add location" and "Add category" buttons normally, the centered "Add your
 *   first location" while the list is empty.
 *   refreshLocationCounts(repoId) — fetch the list and patch only the asset counts in place; falls
 *   back to a full render when the list gained a row the DOM does not have.
 *   setViewedLocation(locationId) — mark the Location whose results are displayed (green icon).
 *   expandLocationCategory(categoryId) — reveal a category's Locations (the add listener uses it
 *   for the category a new Location went into).
 *   focusAddLocationControlIfFocusLost() — move focus to whichever add control is visible.
 *
 * The list endpoint returns the rows in path order (a category directly followed by its Locations,
 * top-level Locations interleaved by name). Top-level Locations and categories are rendered as
 * siblings in that order; a category is a node holding its row and a children container
 * (`#location-children-<id>`) with its Locations, collapsed on first render. A category with
 * Locations wraps its icon and name in one native button (`#location-expand-<id>`) that expands
 * and collapses the children; the CSS in the tab template draws a +/− badge on its icon. The
 * category holds no assets, so it is neither a drop target nor a search trigger, and a category
 * with no Locations is plain: no button, no badge. A category row shows its ⋯ menu (Rename,
 * Delete), the icon and the name. A Location row shows its ⋯ menu (Rename, Delete, Move to
 * category), an icon, the name and its asset count (`.asset-count`, directly after the name, empty
 * for zero; `common/asset-count.js`); icon and name are search triggers for the Location and the
 * row's `.controls` is a drop zone for assets (`dragdrop/locations.js`). Locations are pointers
 * only: nothing here moves or changes an asset.
 *
 * Expansion is presentation state: it survives every rebuild of the list within the page (the
 * snapshot is taken immediately before the DOM is replaced) and is not persisted across page loads.
 */
import { closeOpenContextMenu } from "../alpine/components/context-menu.js"
import { Const } from "../constants.js"
import { http } from "../http/client.js"
import { buildAssetCountEl, setAssetCount } from "./asset-count.js"
import {
    buildContextMenuCtrl,
    buildModalTriggerCtrl,
} from "./context-menu-markup.js"
import { showErrorSnackBar } from "./snackbar.js"
import { bindSearchTriggers } from "../search-results/search-triggers.js"

const SCOPE_MARKER = Const.attributes.viewedScope
const EXPANDED = Const.attributes.expanded
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
 * row's count in place, so expansion, focus and open menus are untouched. Nothing is fetched while
 * another explorer tab is active: the next render of the Locations tab brings fresh counts. A full
 * reload started meanwhile carries fresh counts itself, so this one stands down.
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
 * Records the Location whose results are displayed and marks its row with `data-viewed-scope`,
 * which the CSS in `views/htmx/locations.scala.html` colors green. Null clears the marker. Like
 * the viewed folder scope, this follows the displayed results, never the search parameters. A
 * viewed Location inside a collapsed category stays hidden until the category is expanded.
 */
export function setViewedLocation(locationId) {
    _viewedLocationId = locationId || null
    _applyViewedLocation()
}

/**
 * Reveals the Locations of the category rendered as `#location-<categoryId>`. Nothing happens for
 * an ID the list does not hold, a Location, or a category with no Locations.
 */
export function expandLocationCategory(categoryId) {
    const nodeEl = _findCategoryNode(categoryId)
    if (nodeEl) {
        _setCategoryExpanded(nodeEl, true)
    }
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

/**
 * Replaces the rendered list with `locations`, preserving the expanded categories, focus and the
 * viewed-Location marker.
 */
function _render(locations, repoId) {
    const container = document.getElementById("locationList")
    if (!container) return

    // Snapshot expansion and focus only now, immediately before the DOM is replaced: both may have
    // changed while the fetch was in flight (a category toggled; a dialog returning focus to a
    // menu control as its operation completes), and an older snapshot would undo that
    const expandedIds = _getExpandedCategoryIds(container)
    const focusedId = _getFocusedListControlId(container)

    container.replaceChildren(..._buildNodes(locations, repoId))
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

    // A category that no longer exists is absent, and one that lost its last Location has no
    // children to reveal, which `_setCategoryExpanded` ignores
    expandedIds.forEach(expandLocationCategory)

    _applyViewedLocation()

    // Keep keyboard focus on the rebuilt copy of the control that had it, if it still exists
    if (focusedId) {
        document.getElementById(focusedId)?.focus()
    }
}

/**
 * Builds the add controls into their hosts on the first render after the tab loads. "Add location"
 * and "Add category" open separate modals. The hosts (`#locationActions`, `#noLocations`)
 * decide which controls are shown.
 */
function _ensureAddControls(repoId) {
    const actionsEl = document.getElementById("locationActions")
    if (actionsEl.childElementCount > 0) return

    actionsEl.appendChild(
        buildModalTriggerCtrl({
            triggerId: "addLocationBtn",
            label: "Add location",
            buttonClass: "action-button small",
            iconClass: "fas fa-plus",
            url: `/htmx/location/r/${repoId}/dialogs/add-location`,
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
            buttonClass: "action-button",
            iconClass: "fas fa-plus",
            url: `/htmx/location/r/${repoId}/dialogs/add-location`,
        }),
    )

    if (window.htmx) {
        htmx.process(actionsEl)
        htmx.process(noLocationsEl)
    }
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

/** The category node rendered as `#location-<id>`, or null for anything else */
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
 * The one place a category's state changes: the `data-expanded` flag (which the CSS reads for the
 * badge and the group spacing), the visibility of its children, and the `aria-expanded` and
 * tooltip of its button move together. A category with no Locations has no children container
 * and no button, and nothing to change.
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
 * Closes a category. Hiding the Locations hides their menu triggers too, so an open menu among
 * them is closed first, and focus that was inside moves to the category's button rather than
 * being left in hidden content.
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
 * Click on a category's button. A pointer double-click arrives as click (`detail` 1), click
 * (`detail` 2), then `dblclick`: the first click toggles at once and every later click of the
 * sequence is ignored, so a double- or triple-click toggles exactly once, as a folder branch's
 * first click does. Keyboard activation (Enter, Space) dispatches a click with `detail` 0 and is
 * always a single toggle. Categories are one level deep, so there is no recursive action and no
 * `dblclick` handler. A control replaced by a rebuild between the clicks is detached and ignores
 * its events.
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
 * The list's top-level nodes in the order the rows came: a category node for each category, holding
 * the Locations that name it as theirs, and a `.top-level` row for every other Location. Grouping
 * by category ID rather than by adjacency keeps the nesting right whatever the order.
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

/**
 * A category node: its row, then, when it has Locations, the children container holding their rows
 * (`.child`, indented by one step), hidden until the category is expanded. The node is the
 * `.location.category` element, which carries `data-expanded` while open.
 */
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
        // ⋯ menu button | icon | name: nothing to expand, so the icon and name are plain
        controlsEl.appendChild(iconEl)
        controlsEl.appendChild(nameEl)
    }
    nodeEl.appendChild(controlsEl)

    if (children.length > 0) {
        const childrenEl = document.createElement("div")
        childrenEl.className = "children"
        childrenEl.id = `location-children-${category.id}`
        childrenEl.setAttribute(Const.attributes.locationId, category.id)
        // Collapsed on first render; a rebuild restores the previous state afterwards
        childrenEl.style.display = "none"
        children.forEach((child) => {
            childrenEl.appendChild(_buildLocationRow(child, repoId, true))
        })
        nodeEl.appendChild(childrenEl)
    }

    return nodeEl
}

/**
 * A category's expand control: a native button (Enter and Space work, no page jump) wrapping the
 * icon and the name, so clicking either toggles. It keeps the stable ID `location-expand-<id>`
 * (focus restoration after a rebuild, focus recovery after a collapse), names the category, points
 * `aria-controls` at the children container and carries `aria-expanded`, kept in step by
 * `_setCategoryExpanded`. The icon is decorative; the CSS draws the +/− badge on it.
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

/**
 * A Location row: `.top-level`, or `.child` under a category with `--depth: 1` and a `.trace` cell,
 * so the CSS in the tab template indents it like a folder. The row is the drop target for assets.
 */
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
    iconEl.id = `location-icon-${location.id}`
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
 * Opening a Location is a search like any other: the element declares the one parameter it knows
 * about and `search-results/search-triggers.js` combines it with the rest of the current search.
 */
function _makeSearchTrigger(el, locationId) {
    el.setAttribute("data-app-search", "click")
    el.setAttribute("data-app-search-location-id", locationId)
}

/**
 * The row's ⋯ menu cell (see `buildContextMenuCtrl`): Rename and Delete for both kinds, plus Move
 * to category for a Location, each loading a separate modal.
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
