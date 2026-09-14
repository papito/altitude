import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { thumbnailsOf } from "./cells.js"

/**
 * SELECTION
 *
 * What is selected in the results grid is a reactive `Set` of asset IDs in the `selectedAssets`
 * store, and nothing else: no per-cell component, no map of component proxies. The store is the
 * one place a selection changes, and each change paints the cells it concerns (`.selected` on the
 * thumbnail box, every `.drag-drop[data-asset-id]` of the asset - a Location grouping holds an
 * asset once per Location), so the DOM never has to be asked what is selected.
 *
 * The footer binds to the set (`x-show="!$store.selectedAssets.isEmpty"`, `x-text` of its size),
 * and a group header counts its group's cells against it (js/alpine/components/date-group-selectable.js).
 * Cells that enter or leave the grid are announced with `noteGridChange()`, which bumps a reactive
 * counter those headers read: the DOM is not reactive, and this is the one signal that stands in
 * for it.
 *
 * Every displayed grid gets one delegated click listener (`bindAssetSelection`): a click on a
 * thumbnail's checkmark toggles its asset, and a Shift-click on the thumbnail image does the same
 * (a plain click on the image opens its detail through htmx, `click[!event.shiftKey]`).
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

/** Paints every cell of the asset: the thumbnail box is the `.drag-drop` div carrying the asset ID (htmx/result_cell.scala.html) */
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
