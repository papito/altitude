import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { thumbnailsOf } from "./cells.js"

/**
 * SELECTION
 *
 * The selection is a reactive `Set` of asset IDs in the `selectedAssets` store, the one place it
 * changes. Each change paints every thumbnail of the asset (a Location grouping holds an asset once
 * per Location), so the DOM never has to be asked what is selected. The DOM is not reactive, so cells
 * entering or leaving the grid bump a counter (`noteGridChange`) for group headers to recount.
 *
 * A click on a thumbnail's checkmark, or a Shift-click on its image, toggles its asset; a plain click
 * on the image opens its detail.
 */

const SELECTED_CLASS = "selected"
const DRAGGED_CLASS = "masked"

export function createSelectedAssetsStore() {
    return {
        ids: new Set(),

        // Bumped whenever cells enter or leave the grid
        gridVersion: 0,

        get isEmpty() {
            return this.ids.size === 0
        },

        get size() {
            return this.ids.size
        },

        contains(id) {
            return this.ids.has(id)
        },

        toArray() {
            return Array.from(this.ids)
        },

        select(id) {
            if (this.ids.has(id)) {
                return
            }

            this.ids.add(id)
            paint(id, SELECTED_CLASS, true)
        },

        deselect(id) {
            if (!this.ids.delete(id)) {
                return
            }

            paint(id, SELECTED_CLASS, false)
        },

        toggle(id) {
            if (this.ids.has(id)) {
                this.deselect(id)
            } else {
                this.select(id)
            }
        },

        reset() {
            this.ids.forEach((id) => paint(id, SELECTED_CLASS, false))
            this.ids.clear()
        },

        /** Dims every selected thumbnail while the selection is being dragged as a batch */
        setDragging(dragging) {
            this.ids.forEach((id) => paint(id, DRAGGED_CLASS, dragging))
        },

        noteGridChange() {
            this.gridVersion++
        },
    }
}

/** Paints every thumbnail box of the asset */
function paint(id, className, on) {
    thumbnailsOf(id).forEach((thumbnailEl) =>
        thumbnailEl.classList.toggle(className, on),
    )
}

/**
 * Binds the grid's one click listener. `#assets` is replaced on every search, so this runs once per
 * displayed grid; the guard keeps a re-hydration of the same grid from binding a second one.
 */
export function bindAssetSelection({ assetsElement }) {
    if (assetsElement.dataset.appSelectionBound === "true") {
        return
    }

    assetsElement.dataset.appSelectionBound = "true"

    assetsElement.addEventListener("click", (event) => {
        const target = event.target instanceof Element ? event.target : null
        const assetId = target?.closest(`[${Const.attributes.assetId}]`)
            ?.dataset.assetId

        if (!assetId) {
            return
        }

        if (
            target.closest(".checkmark") ||
            (target.matches("img") && event.shiftKey)
        ) {
            Alpine.store(Const.state.selectedAssets).toggle(assetId)
        }
    })
}
