import { Const } from "../../constants.js"

export function initSelectable() {
    return {
        selected: false,
        select(){
            const assetId = this.$el.parentElement.getAttribute('alt-asset-id')

            if (this.selected) {
                Alpine.store(Const.selectedAssets).ids.delete(assetId)
            } else {
                Alpine.store(Const.selectedAssets).ids.add(assetId)
            }

            this.selected = !this.selected
        }
    }
}
