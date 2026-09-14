import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { runSearch } from "../search-results/search.js"

/**
 * The crowded-pin panel of the map view (`#mapPanel` in `htmx/map_view.scala.html`): the ordinary
 * results grid for a map area, laid over the map's right edge.
 *
 * The area is the `bbox` search filter, a real parameter of the store, so the panel's grid is a
 * plain search run into `#mapPanelContent`: infinite scroll, cursor continuation, selection, the
 * detail modal and drag to a Location row all work in it unchanged, and the URL is bookmarkable
 * (`layout=map&bbox=` reopens the panel: the map hydrator calls `openMapPanel` for the area the
 * results fragment carries). The map endpoints and the map layout's own count and bounds ignore
 * `bbox`, so the map behind the panel keeps plotting the whole search.
 *
 * The panel's request asks for the grid layout and no grouping for that one request only
 * (`transient`), while the store keeps saying map; its continuations do the same by reading the
 * layout and grouping off the fragment (js/search-results/infinite-scroll.js). It is issued from
 * the panel element, so the URL the server pushes back for it can be recognised and kept saying
 * `layout=map` (js/listeners/map.js).
 *
 * Closing the panel, its × or Escape, and the toolbar's "Map area ×" chip all clear `bbox` through
 * the search funnel, which re-renders the map without a panel. "Show only these in the grid"
 * switches the layout and keeps the area, so the grid opens scoped to it, with the chip to clear it.
 */

/** Opens the panel for `bbox` (`"s,w,n,e"`) and runs the search into it */
export function openMapPanel({ bbox }) {
    const panelEl = document.getElementById("mapPanel")
    if (!panelEl) {
        return
    }

    panelEl.hidden = false
    bindPanelControls(panelEl)

    return runSearch({
        params: { bbox },
        transient: {
            layout: Const.search.layout.grid,
            groupBy: null,
            groupDirection: null,
        },
        target: "#mapPanelContent",
        source: panelEl,
    })
}

/** Closes the panel by clearing the map area, which re-renders the map. Returns whether one was open. */
export function closeMapPanel() {
    if (!isMapPanelOpen()) {
        return false
    }

    runSearch({ params: { bbox: null } })

    return true
}

export function isMapPanelOpen() {
    const panelEl = document.getElementById("mapPanel")

    return Boolean(panelEl && !panelEl.hidden)
}

/** Whether `el` is inside the panel: its grid is hydrated as the panel's (js/fragments/search-results.js) */
export function isInMapPanel(el) {
    return Boolean(el?.closest?.("#mapPanel"))
}

/** Writes the panel's heading from the results it just received */
export function setMapPanelCount(total) {
    const countEl = document.getElementById("mapPanelCount")
    if (countEl) {
        countEl.textContent = `${total} ${total === 1 ? "item" : "items"} here`
    }
}

function bindPanelControls(panelEl) {
    if (panelEl.dataset.appMapPanelBound === "true") {
        return
    }

    panelEl.dataset.appMapPanelBound = "true"

    panelEl
        .querySelector("#mapPanelClose")
        .addEventListener("click", () => closeMapPanel())

    panelEl
        .querySelector("#mapPanelShowInGrid")
        .addEventListener("click", () => {
            runSearch({ params: { layout: Const.search.layout.grid } })
        })
}

/** The map area the store holds, or null */
export function currentBbox() {
    return Alpine.store(Const.state.searchParams).bbox
}
