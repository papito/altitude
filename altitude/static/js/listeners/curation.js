import { Alpine } from "../lib/alpine.esm.min.js"
import { requestCuration } from "../alpine/components/curation.js"
import { showWarningSnackBar } from "../common/snackbar.js"
import { Const } from "../constants.js"
import { removeCurateButtons } from "../search-results/date-groups.js"
import { runSearch } from "../search-results/search.js"

/**
 * Curation's events: opening it, the counts after each mutation in it, and the grid beneath when it
 * closes. A view that opens it dispatches `Const.events.curateRequested` with no detail for the queue,
 * `{ sequenceId }` for one Sequence or `{ assetIds }` for a selection; the batch ops' Curate goes through
 * `Const.events.batchCurateRequested`, which holds a selection over the cap back. Exit and Esc close it, through
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

    /**
     * The server refuses a selection over the cap too, but the generic report of a refused request
     * says only its path and status, so this is what tells the user why. The button carries the cap.
     */
    document.body.addEventListener(
        Const.events.batchCurateRequested,
        (event) => {
            const assetIds = Alpine.store(Const.state.selectedAssets).toArray()
            const maxSelection = Number(event.target.dataset.maxSelection)

            if (assetIds.length > maxSelection) {
                showWarningSnackBar(
                    `Curation compares at most ${maxSelection} images at a time`,
                )
                return
            }

            app.dispatch(Const.events.curateRequested, { assetIds })
        },
    )

    document.body.addEventListener(Const.events.curationChanged, () => {
        app.assetActions.refreshCounts()
    })

    /**
     * The grid keeps its scroll position, and with nothing changed its selection too. Otherwise the
     * frames still recycled leave it as the grid's own Recycle takes them, with the group counts and
     * the total, a group whose Sequence was cleared loses its Curate button, and kept frames stay
     * under their old header until the next search. The map layout has no cells, so it runs the
     * search again.
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
        removeCurateButtons(clearedSequenceIds)
        Alpine.store(Const.state.selectedAssets).reset()
    })
}
