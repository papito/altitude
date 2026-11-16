import { Const } from "../constants.js"
import { isInMapPanel } from "../map/map-panel.js"

/**
 * The crowded-pin panel's grid is requested as a plain grid while the store says map, so the URL the
 * server pushes back says `layout=grid`, the wrong page to reopen. The layout is put back to map here,
 * before htmx applies it; the rest of the URL is the server's.
 */
export function registerMapListeners() {
    document.addEventListener("htmx:before:history:update", (event) => {
        const { history, sourceElement } = event.detail

        if (!isInMapPanel(sourceElement)) {
            return
        }

        const url = new URL(history.path, window.location.href)
        url.searchParams.set("layout", Const.search.layout.map)
        history.path = url.pathname + url.search + url.hash
    })
}
