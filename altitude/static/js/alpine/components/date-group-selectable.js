import { Alpine } from "../../lib/alpine.esm.min.js"
import { Const } from "../../constants.js"

/**
 * The checkbox of a date group header (`htmx/results_grid_grouped.scala.html`): it selects or
 * deselects that day's cells as a set, and shows how much of the day is selected - unchecked, the
 * indeterminate dash, or checked.
 *
 * The set is the day's *loaded* cells. A day's header count is its match count across every page,
 * loaded or not, so a day still scrolling in stays indeterminate however many of its loaded cells
 * are selected; selecting a day never requests the pages it has not reached yet.
 *
 * A group owns no element of its own: its cells are the header's following siblings, up to the next
 * header or the end of the grid, which is what lets a day continued onto the next cursor page repeat
 * no header and simply append cells. So the set is read off the DOM each time rather than held.
 *
 * Selection is toggled through each cell's own `selectable` component, the same path a click on a
 * thumbnail's checkmark takes; nothing here writes to the `selectedAssets` store.
 */
export function initDateGroupSelectable() {
    // Kept out of the component's reactive state: this only coalesces work, and must not itself
    // schedule renders
    let recountQueued = false
    let onSelectionChanged = null

    return {
        loadedCount: 0,
        selectedCount: 0,

        init() {
            onSelectionChanged = () => this.scheduleRecount()
            document.body.addEventListener(
                Const.events.gridSelectionChanged,
                onSelectionChanged,
            )

            // The header precedes its cells, so their components are not initialized yet
            this.$nextTick(() => this.recount())
        },

        destroy() {
            document.body.removeEventListener(
                Const.events.gridSelectionChanged,
                onSelectionChanged,
            )
        },

        /** Checked only when the whole day is selected - which a day still scrolling in never is */
        get allSelected() {
            return (
                this.loadedCount > 0 &&
                this.selectedCount === this.loadedCount &&
                this.loadedCount >= this.totalCount()
            )
        },

        get someSelected() {
            return this.selectedCount > 0 && !this.allSelected
        },

        /**
         * Writes the state into the checkbox. It is the box's `x-effect`, so every recount repaints
         * it, and its `change` handler calls it again after `toggle()`: a click whose recount changes
         * neither count re-runs no effect, and the browser's own toggle would otherwise stand.
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
            const cells = this.cellComponents()
            const select = !cells.every((cell) => cell.selected)

            cells.forEach((cell) => {
                if (cell.selected !== select) {
                    cell.toggle()
                }
            })

            this.recount()
        },

        /**
         * One recount per batch: a box selection toggles every cell it caught one by one, and each
         * toggle announces itself
         */
        scheduleRecount() {
            if (recountQueued) {
                return
            }

            recountQueued = true

            queueMicrotask(() => {
                recountQueued = false
                this.recount()
            })
        },

        recount() {
            const cells = this.cellComponents()

            this.loadedCount = cells.length
            this.selectedCount = cells.filter((cell) => cell.selected).length
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

        /** The `selectable` components of the day's loaded cells, in grid order */
        cellComponents() {
            const components = []
            let el = this.$root.nextElementSibling

            while (el && !el.classList.contains("date-group")) {
                const component = selectableOf(el)

                if (component) {
                    components.push(component)
                }

                el = el.nextElementSibling
            }

            return components
        },
    }
}

/**
 * The `selectable` component of a cell. The thumbnail box carrying the asset ID is the component's
 * element - its `<img>` carries the same attribute, so the selector names the div, as box selection
 * does. A cell whose component has not initialized yet resolves to an enclosing scope instead, which
 * the shape check rejects.
 */
function selectableOf(cellEl) {
    const thumbnailEl = cellEl.querySelector?.(
        `div[${Const.attributes.assetId}]`,
    )

    if (!thumbnailEl) {
        return null
    }

    const component = Alpine.$data(thumbnailEl)

    return component && "selected" in component ? component : null
}
