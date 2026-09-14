import { Const } from "../constants.js"
import { isInMapPanel } from "../map/map-panel.js"

/**
 * The map view's document-level wiring.
 *
 * The crowded-pin panel's grid is requested as a plain grid while the store says map, so the
 * bookmarkable URL the server pushes back for it says `layout=grid` - the wrong page to reopen.
 * The URL is a projection of the store and is never read back, so it is corrected here, before
 * htmx applies it, for responses to requests issued from the panel: the layout is put back to map.
 * Everything else about the URL (the area, the sort, the scope) is the server's.
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
