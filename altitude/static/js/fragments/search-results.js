import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { setViewedAlbum } from "../common/album-list.js"
import { setViewedLocation } from "../common/location-list.js"
import { setViewedFolderScope } from "../common/viewed-folder-scope.js"
import { isInMapPanel, setMapPanelCount } from "../map/map-panel.js"
import { disposeMapView } from "../map/map-view.js"
import { bindBoxSelection } from "../search-results/box-selection.js"
import { bindFilteredTitle } from "../search-results/filtered-title.js"
import { bindInfiniteScroll } from "../search-results/infinite-scroll.js"
import { bindLazyImages } from "../search-results/lazy-images.js"
import { bindMetadataVisibility } from "../search-results/metadata-visibility.js"
import { bindPageWindow } from "../search-results/page-window.js"
import { bindStickyOffsets } from "../search-results/sticky-offsets.js"
import { bindAssetSelection } from "../search-results/selection.js"
import { ensureViewSettingsControl } from "../search-results/view-settings-control.js"

/**
 * The search results fragment, re-swapped into `#content` on every search. Each grid behaviour is its
 * own module under `js/search-results/`; this runs them in order. In map layout the fragment holds the
 * map shell instead of `#assets`.
 *
 * The crowded-pin map panel loads the same fragment into `#mapPanelContent`: there it leaves the
 * displayed map, its total and the viewed scope alone, reports its count to the panel, scrolls in the
 * panel, and gets no box selection (the gesture is bound to the `#content` pane).
 */
export function hydrateSearchResultsFragment({ fragmentEl }) {
    const contentElement = document.getElementById("content")
    const assetsElement = fragmentEl.querySelector("#assets")
    const total = Number(fragmentEl.dataset.resultsTotal || 0)
    const isTotalCapped = fragmentEl.dataset.resultsTotalCapped === "true"
    const scrollElement = isInMapPanel(fragmentEl)
        ? document.getElementById("mapPanelContent")
        : contentElement

    Alpine.store(Const.state.selectedAssets).reset()
    scrollElement?.scrollTo({ top: 0, behavior: "auto" })

    if (isInMapPanel(fragmentEl)) {
        setMapPanelCount(total, isTotalCapped)
    } else {
        // The previous fragment is gone, and with it the map it held, if any
        disposeMapView()

        Alpine.store(Const.state.resultsTotal).set(total, isTotalCapped)
        syncViewedScope(fragmentEl)
        ensureViewSettingsControl(fragmentEl)
        bindStickyOffsets({ fragmentEl })
        bindFilteredTitle({ fragmentEl })

        // The toolbar's Curate button shows the number of Sequences in the queue
        if (fragmentEl.dataset.resultsView === Const.views.triage) {
            Alpine.store(Const.state.sequencesToCurate).refresh()
        }

        // Also discards the previous grid's controller, and any box it was still drawing
        bindBoxSelection({ assetsElement, contentElement })
    }

    if (!assetsElement) {
        return
    }

    bindAssetSelection({ assetsElement })
    bindInfiniteScroll({ assetsElement })
    bindPageWindow({ assetsElement, scrollElement })
    bindLazyImages({ assetsElement })
    bindMetadataVisibility({ assetsElement })
}

/**
 * Highlights the displayed results' scope in the folder tree, album list and Location list. The
 * fragment carries the scope the server resolved, not what the search store now asks for, so a
 * superseded or failed navigation never moves the highlight. Triage and trash results have no scope.
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
