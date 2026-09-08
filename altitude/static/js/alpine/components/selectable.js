import { Const } from "../../constants.js"

/**
 * Announces that what is selected in the results grid, or which cells are in it, has changed. Date
 * group headers hold a checkbox over their day's cells and recount on it
 * (js/alpine/components/date-group-selectable.js); nothing else listens.
 *
 * Every path that changes a selection goes through `toggle()` or `deselect()` below and announces
 * itself. The grid's own comings and goings do not, so cells appended by continuous scroll and cells
 * removed by a batch operation are announced by the code that puts them there or takes them away.
 */
export function notifyGridSelectionChanged() {
    document.body.dispatchEvent(
        new CustomEvent(Const.events.gridSelectionChanged),
    )
}

export function initSelectable(id) {
    return {
        id: id,
        selected: false,
        dragged: false,

        init() {},

        toggle() {
            if (this.selected) {
                Alpine.store(Const.state.selectedAssets).items.delete(id)
            } else {
                Alpine.store(Const.state.selectedAssets).items.set(id, this)
            }

            this.selected = !this.selected
            notifyGridSelectionChanged()
        },

        /**
         * This is technically called from @click.shift, but in Alpine 3.13.10,
         * this does not seem work - the regular click still fires.
         *
         * The shift modifier can be retrieved from the original event, which we do here.
         *
         * In the future, if Alpine supports @click.shift properly, we can change this to
         * @click.shift="toggle" in the template.
         */
        toggleViaAsset(originalEvent) {
            if (originalEvent.shiftKey) {
                this.toggle()
            }
        },

        deselect() {
            if (this.selected) {
                this.selected = false
                Alpine.store(Const.state.selectedAssets).items.delete(id)
                notifyGridSelectionChanged()
            }
        },
        drag() {
            this.dragged = true
        },
        drop() {
            this.dragged = false
        },
    }
}
