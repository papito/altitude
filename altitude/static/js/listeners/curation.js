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
 * Opens Curation on the Sequence `?curate=` names, over the page's first results; the first search drops
 * the parameter from the URL, so a reload shows the grid. Without a Curate Permission the parameter is
 * ignored, since the server would refuse Curation.
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
 * Where Asset detail resumes when Curation closes: the asset it left or, when that was recycled, the
 * nearest remaining cell, next before previous; null when none is left. Read before the recycled cells
 * leave the grid.
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
 * The Detail panel's Curation link opens Curation in place on a plain click, keeping Asset detail's
 * place; Asset detail comes back there when Curation closes, through its cell as a click would, so
 * stepping goes on from it. A modified or middle click is left to the browser's new tab
 * (`openCurationInLocation`).
 */
export function registerCurationListeners(app) {
    // Asset detail's place while Curation is open over it
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
     * The server refuses a selection over the cap too, but the generic refusal report gives only the
     * path and status, so the reason is told here
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
     * The selection is reset however Curation ended, or the next selection would be additive. The grid
     * keeps its scroll: recycled frames leave it as Recycle takes them, a cleared Sequence's group loses
     * its Curate button, and kept frames stay under their old header until the next search. The map has
     * no cells, so it searches again.
     */
    document.body.addEventListener(Const.events.curationClosed, (event) => {
        const { recycledIds, clearedSequenceIds } = event.detail
        const detail =
            resumeDetail && detailAfterCuration(resumeDetail, recycledIds)
        resumeDetail = null

        Alpine.store(Const.state.selectedAssets).reset()

        if (recycledIds.length > 0 || clearedSequenceIds.length > 0) {
            if (Alpine.store(Const.state.searchParams).showsMap()) {
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
