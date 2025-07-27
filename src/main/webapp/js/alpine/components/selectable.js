import { Const } from "../../constants.js"

export function initSelectable(id) {
    return {
        id: id,
        selected: false,

        init() {
        },

        toggle(){
            if (this.selected) {
                Alpine.store(Const.state.selectedAssets).items.delete(id)
            } else {
                Alpine.store(Const.state.selectedAssets).items.set(id, this)
            }

            this.selected = !this.selected
        },

        /**
         * This is technically called from @click.shift, but as of Alpine 3.13.10,
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

        deselect(){
            if (this.selected) {
                this.selected = false
                Alpine.store(Const.state.selectedAssets).items.delete(id)
            }
        }

    }
}
