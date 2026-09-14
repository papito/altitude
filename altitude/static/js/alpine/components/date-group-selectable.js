import { Alpine } from "../../lib/alpine.esm.min.js"
import { Const } from "../../constants.js"

/**
 * The checkbox of a group header (`.result-group`, a day or a Location,
 * `htmx/results_grid_grouped.scala.html`): it selects or deselects that group's cells as a set, and
 * shows how much of the group is selected - unchecked, the indeterminate dash, or checked.
 *
 * The set is the group's *loaded* cells. A group's header count is its match count across every page,
 * loaded or not, so a group still scrolling in stays indeterminate however many of its loaded cells
 * are selected; selecting a group never requests the pages it has not reached yet.
 *
 * A group owns no element of its own: its cells are the header's following siblings, up to the next
 * header or the end of the grid, which is what lets a group continued onto the next cursor page repeat
 * no header and simply append cells. So the set is read off the DOM each time rather than held.
 *
 * Everything here is derived from the `selectedAssets` store: the counts read its reactive set and
 * its grid version, so the box's `x-effect` repaints on every selection change and every time
 * cells are appended or removed, with no listener of its own.
 */
export function initDateGroupSelectable() {
    return {
        get store() {
            return Alpine.store(Const.state.selectedAssets)
        },

        /** Checked only when the whole day is selected - which a day still scrolling in never is */
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
         * Writes the state into the checkbox. It is the box's `x-effect`, so every change repaints
         * it, and its `change` handler calls it again after `toggle()`: a click that changes
         * nothing re-runs no effect, and the browser's own toggle would otherwise stand.
         *
         * The click is not cancelled on purpose. The browser restores a cancelled checkbox's
         * `checked` and `indeterminate` to their pre-click values once the click is dispatched -
         * after the microtask Alpine runs its effects in - so a `@click.prevent` box would show the
         * state from before the click.
         */
        paint(checkboxEl) {
            checkboxEl.checked = this.allSelected
            checkboxEl.indeterminate = this.someSelected
        },

        /** Selects the whole loaded day, or deselects it when all of it is already selected */
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

        /**
         * The day's match count across every page, which the header carries as a number and the grid
         * decrements as cells leave it (js/search-results/date-groups.js)
         */
        totalCount() {
            return (
                Number(this.$root.querySelector(".count")?.dataset.count) || 0
            )
        },

        /**
         * The asset IDs of the day's loaded cells, in grid order. Reading the store's grid version
         * first is what makes a reactive consumer re-run this when cells come or go.
         */
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
