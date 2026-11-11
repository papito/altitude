import { Alpine } from "../lib/alpine.esm.min.js"
import { requestCuration } from "../alpine/components/curation.js"
import { isModalActive } from "../common/modal.js"
import { showWarningSnackBar } from "../common/snackbar.js"
import { Const } from "../constants.js"
import { context } from "../context.js"
import { assetIdOf } from "../search-results/cells.js"
import { removeCurateButtons } from "../search-results/date-groups.js"
import { runSearch } from "../search-results/search.js"

const CURATE_PARAM = "curate"

/**
 * Curation on the Sequence the browser's URL names (`?curate=`, the Detail panel's Curation link), over the page's first
 * results. The first search replaces the URL without the parameter, so a reload shows the grid. An ID that is no Sequence's
 * opens on Curation's own too-few-frames state. Opening Curation takes a Curate Permission, so without one the
 * parameter is left alone, as the server would refuse every request of Curation.
 */
export function openCurationInLocation(app) {
    const sequenceId = new URLSearchParams(window.location.search).get(
        CURATE_PARAM,
    )

    if (
        sequenceId &&
        context.holds(Const.permission.curateOwn, Const.permission.curateAll)
    ) {
        app.dispatch(Const.events.curateRequested, { sequenceId })
    }
}

/**
 * What Asset detail comes back on when Curation closes: the asset it left, or, when Curation recycled
 * that asset, the nearest cell still in the grid, the next one before the previous one. Null when the
 * asset is gone and no cell is left to go to. Read before the recycled cells leave the grid.
 */
function detailAfterCuration({ assetId, cellEl }, recycledIds) {
    const recycled = new Set(recycledIds)

    if (!recycled.has(assetId)) {
        return { assetId, cellEl }
    }

    const nearestCell = (direction) => {
        let el = cellEl?.[direction]

        while (
            el &&
            !(el.classList.contains("cell") && !recycled.has(assetIdOf(el)))
        ) {
            el = el[direction]
        }

        return el ?? null
    }

    const neighbourEl =
        nearestCell("nextElementSibling") ??
        nearestCell("previousElementSibling")

    return neighbourEl
        ? { assetId: assetIdOf(neighbourEl), cellEl: neighbourEl }
        : null
}

/**
 * Curation's events: opening it, the counts after each mutation in it, and the grid beneath when it
 * closes. A view that opens it dispatches `Const.events.curateRequested` with no detail for the queue,
 * `{ sequenceId }` for one Sequence or `{ assetIds }` for a selection; the batch ops' Curate goes through
 * `Const.events.batchCurateRequested`, which holds a selection over the cap back. Exit and Esc close it, through
 * `closeModal` (`common/modal.js`); the `curation` component announces that.
 *
 * The Detail panel's Curation link opens in place on a plain click, with Asset detail's place in the
 * `resumeDetail` of the request, and Asset detail comes back on it when Curation closes, through its
 * cell as a click would, so stepping goes on from there. A click with a modifier, or a middle click,
 * is left to the browser: the link's new tab (`openCurationInLocation`).
 */
export function registerCurationListeners(app) {
    // Asset detail's place while Curation is open over it; each open of Curation sets or clears it
    let resumeDetail = null

    document.body.addEventListener("click", (event) => {
        const linkEl = event.target.closest?.(
            `[${Const.attributes.curateSequence}]`,
        )

        if (
            !linkEl ||
            event.ctrlKey ||
            event.metaKey ||
            event.shiftKey ||
            event.altKey
        ) {
            return
        }

        event.preventDefault()
        app.dispatch(Const.events.curateRequested, {
            sequenceId: linkEl.getAttribute(Const.attributes.curateSequence),
            resumeDetail: {
                assetId: linkEl
                    .closest(`[${Const.attributes.appAssetId}]`)
                    .getAttribute(Const.attributes.appAssetId),
                cellEl: app.searchDetailCoordinator.currentCellEl(),
            },
        })
    })

    document.body.addEventListener(Const.events.curateRequested, (event) => {
        const { resumeDetail: place = null, ...entry } = event.detail ?? {}
        resumeDetail = place
        requestCuration(entry)

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
     * The selection is reset whatever happened in Curation: a set opened on a selection is done with
     * when Curation closes, resolved or not, and a selection left standing would make the next one
     * additive. The grid keeps its scroll position, and with nothing changed nothing else moves.
     * Otherwise the frames still recycled leave it as the grid's own Recycle takes them, with the
     * group counts and the total, a group whose Sequence was cleared loses its Curate button, and
     * kept frames stay under their old header until the next search. The map layout has no cells,
     * so it runs the search again. Asset detail then comes back if Curation was opened over it.
     */
    document.body.addEventListener(Const.events.curationClosed, (event) => {
        const { recycledIds, clearedSequenceIds } = event.detail
        const detail =
            resumeDetail && detailAfterCuration(resumeDetail, recycledIds)
        resumeDetail = null

        Alpine.store(Const.state.selectedAssets).reset()

        if (recycledIds.length > 0 || clearedSequenceIds.length > 0) {
            if (
                Alpine.store(Const.state.searchParams).layout ===
                Const.search.layout.map
            ) {
                runSearch()
            } else {
                app.assetActions.removeAssetsFromGrid(recycledIds)
                removeCurateButtons(clearedSequenceIds)
            }
        }

        // Another dialog may have taken over from Curation
        if (detail && !isModalActive()) {
            htmx.ajax(
                "GET",
                `/htmx/asset/r/${app.context.getRepoId()}/modals/asset-detail/${detail.assetId}`,
                {
                    source: detail.cellEl?.querySelector("img[hx-get]"),
                    target: "#imageDetailModalContent",
                    swap: "innerHTML",
                },
            )
        }
    })
}
