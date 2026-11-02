import { Alpine } from "../lib/alpine.esm.min.js"
import { requestCuration } from "../alpine/components/curation.js"
import { Const } from "../constants.js"
import { runSearch } from "../search-results/search.js"

/**
 * Curation's events: opening it, the counts after each mutation in it, and the grid beneath when it
 * closes. A view that opens it dispatches `Const.events.curateRequested` with no detail for the queue,
 * `{ sequenceId }` for one Sequence or `{ assetIds }` for a selection. Exit and Esc close it, through
 * `closeModal` (`common/modal.js`); the `curation` component announces that.
 */
export function registerCurationListeners(app) {
    document.body.addEventListener(Const.events.curateRequested, (event) => {
        requestCuration(event.detail ?? {})

        htmx.ajax("GET", `/htmx/curation/r/${app.context.getRepoId()}`, {
            target: "#curationModalContent",
            swap: "innerHTML",
        })
    })

    document.body.addEventListener(Const.events.curationChanged, () => {
        app.assetActions.refreshCounts()
    })

    /**
     * The grid keeps its scroll position, and with nothing changed its selection too. Otherwise the
     * frames still recycled leave it as the grid's own Recycle takes them, with the group counts and
     * the total, and kept frames stay under their old header until the next search. The map layout
     * has no cells, so it runs the search again.
     */
    document.body.addEventListener(Const.events.curationClosed, (event) => {
        const { recycledIds, clearedSequenceIds } = event.detail

        if (recycledIds.length === 0 && clearedSequenceIds.length === 0) {
            return
        }

        if (
            Alpine.store(Const.state.searchParams).layout ===
            Const.search.layout.map
        ) {
            runSearch()
            return
        }

        app.assetActions.removeAssetsFromGrid(recycledIds)
        Alpine.store(Const.state.selectedAssets).reset()
    })
}
