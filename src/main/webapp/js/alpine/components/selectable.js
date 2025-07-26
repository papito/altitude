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
        deselect(){
            if (this.selected) {
                this.selected = false
                Alpine.store(Const.state.selectedAssets).items.delete(id)
            }
        }

    }
}
