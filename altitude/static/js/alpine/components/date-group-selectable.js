import { Alpine } from "../../lib/alpine.esm.min.js"
import { Const } from "../../constants.js"

/**
 * The checkbox of a result group header (a day, a Location or a Sequence): it selects or deselects the
 * group's cells as a set and shows unchecked, indeterminate or checked.
 *
 * The set is the group's *loaded* cells, while the header's count spans every page, so a group still
 * scrolling in stays indeterminate; selecting a group never requests the pages it has not reached.
 * A group owns no element: its cells are the header's following siblings up to the next header, which
 * lets a group continued onto the next page append cells without repeating a header. So the set is read
 * off the DOM each time, and the store's `gridVersion` makes that reactive.
 */
export function initDateGroupSelectable() {
    return {
        get store() {
            return Alpine.store(Const.state.selectedAssets)
        },

        /** Checked only when the whole group is selected, which a group still scrolling in never is */
        get allSelected() {
            const loaded = this.loadedCount

            return (
                loaded > 0 &&
                this.selectedCount === loaded &&
                loaded >= this.totalCount()
            )
        },

        get someSelected() {
            return this.selectedCount > 0 && !this.allSelected
        },

        get loadedCount() {
            return this.cellIds().length
        },

        get selectedCount() {
            return this.cellIds().filter((id) => this.store.contains(id)).length
        },

        /**
         * The box's `x-effect`, also called after `toggle()`: a click that changes nothing re-runs no
         * effect, and the browser's own toggle would otherwise stand. The click is deliberately not
         * cancelled: the browser restores a cancelled checkbox after Alpine's effect microtask, so a
         * `@click.prevent` box would show the pre-click state.
         */
        paint(checkboxEl) {
            checkboxEl.checked = this.allSelected
            checkboxEl.indeterminate = this.someSelected
        },

        /** Selects the loaded group, or deselects it when all of it is already selected */
        toggle() {
            const ids = this.cellIds()
            const select = !ids.every((id) => this.store.contains(id))

            ids.forEach((id) => {
                if (select) {
                    this.store.select(id)
                } else {
                    this.store.deselect(id)
                }
            })
        },

        /** The group's match count across every page; the grid decrements it as cells leave */
        totalCount() {
            return (
                Number(this.$root.querySelector(".count")?.dataset.count) || 0
            )
        },

        /** Reading `gridVersion` first makes a reactive consumer re-run this when cells come or go */
        cellIds() {
            this.store.gridVersion

            const ids = []
            let el = this.$root.nextElementSibling

            while (el && !el.classList.contains("result-group")) {
                const id = el.querySelector?.(`[${Const.attributes.assetId}]`)
                    ?.dataset.assetId

                if (id) {
                    ids.push(id)
                }

                el = el.nextElementSibling
            }

            return ids
        },
    }
}
