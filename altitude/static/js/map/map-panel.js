import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { totalLabel } from "../common/total-label.js"
import { runSearch } from "../search-results/search.js"

/**
 * The map view's crowded-pin panel: the ordinary results grid for a map area, over the map's right
 * edge. The area is the store's `bbox` filter, so the panel is a plain search into `#mapPanelContent`
 * (infinite scroll, selection, detail and drag work unchanged) and `layout=map&bbox=` is bookmarkable.
 * The map's own requests ignore `bbox`, so the map behind keeps plotting the whole search. The panel
 * asks for the grid layout with no grouping for its request alone (`transient`) while the store keeps
 * saying map, and issues it from the panel so the URL pushed back can be recognised and corrected.
 */

/** `bbox` is `"s,w,n,e"` */
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

/** Clearing the map area re-renders the map without the panel; returns whether one was open */
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

export function isInMapPanel(el) {
    return Boolean(el?.closest?.("#mapPanel"))
}

export function setMapPanelCount(total, isCapped) {
    const countEl = document.getElementById("mapPanelCount")
    if (countEl) {
        const noun = total === 1 && !isCapped ? "item" : "items"
        countEl.textContent = `${totalLabel(total, isCapped)} ${noun} here`
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

export function currentBbox() {
    return Alpine.store(Const.state.searchParams).bbox
}
