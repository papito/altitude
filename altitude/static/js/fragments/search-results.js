import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { setViewedAlbum } from "../common/album-list.js"
import { setViewedLocation } from "../common/location-list.js"
import { setViewedFolderScope } from "../common/viewed-folder-scope.js"
import { isInMapPanel, setMapPanelCount } from "../map/map-panel.js"
import { disposeMapView } from "../map/map-view.js"
import { bindBoxSelection } from "../search-results/box-selection.js"
import { bindInfiniteScroll } from "../search-results/infinite-scroll.js"
import { bindLazyImages } from "../search-results/lazy-images.js"
import { bindMetadataVisibility } from "../search-results/metadata-visibility.js"
import { bindAssetSelection } from "../search-results/selection.js"
import { ensureViewSettingsControl } from "../search-results/view-settings-control.js"

/**
 * The search results fragment (`data-app-fragment="search-results"`, `includes/search_results.scala.html`):
 * the controls and the first page of the grid, re-swapped into `#content` on every search. Each
 * behaviour of the grid is its own module under `js/search-results/`; this only runs them in order.
 *
 * In map layout the fragment holds the map shell instead of `#assets` (hydrated next, as the
 * `map-view` fragment), and the same fragment is what the crowded-pin panel loads into
 * `#mapPanelContent` (js/map/map-panel.js): that one is the panel's grid, so it leaves the
 * displayed map, its total and the viewed scope alone, reports its count to the panel's heading,
 * and gets no box selection (the gesture is bound to the `#content` pane).
 */
export function hydrateSearchResultsFragment({ fragmentEl }) {
    const contentElement = document.getElementById("content")
    const assetsElement = fragmentEl.querySelector("#assets")
    const total = Number(fragmentEl.dataset.resultsTotal || 0)

    Alpine.store(Const.state.selectedAssets).reset()

    if (isInMapPanel(fragmentEl)) {
        document
            .getElementById("mapPanelContent")
            ?.scrollTo({ top: 0, behavior: "auto" })
        setMapPanelCount(total)
    } else {
        contentElement?.scrollTo({ top: 0, behavior: "auto" })

        // The previous fragment is gone, and with it the map it held, if any
        disposeMapView()

        Alpine.store(Const.state.resultsTotal).set(total)
        syncViewedScope(fragmentEl)
        ensureViewSettingsControl(fragmentEl)

        // Also discards the previous grid's controller, and any box it was still drawing
        bindBoxSelection({ assetsElement, contentElement })
    }

    if (!assetsElement) {
        return
    }

    bindAssetSelection({ assetsElement })
    bindInfiniteScroll({ assetsElement })
    bindLazyImages({ assetsElement })
    bindMetadataVisibility({ assetsElement })
}

/**
 * The folder tree highlights the folder scope of the results now displayed, the album list the
 * album, and the Location list the Location. The fragment carries the scope the server resolved,
 * which is what is on screen - not what the search store now asks for, so a superseded or failed
 * navigation never moves the highlight. Triage and trash results have no folder, album or Location
 * scope, whatever is still in the search parameters.
 */
function syncViewedScope(fragmentEl) {
    const {
        resultsRepoId,
        resultsView,
        resultsFolderId,
        resultsAlbumId,
        resultsLocationId,
    } = fragmentEl.dataset
    const hasScope =
        resultsView !== Const.views.triage &&
        resultsView !== Const.views.trashbin

    setViewedFolderScope({
        repoId: resultsRepoId,
        folderId: hasScope ? resultsFolderId : null,
    })
    setViewedAlbum(hasScope ? resultsAlbumId : null)
    setViewedLocation(hasScope ? resultsLocationId : null)
}
