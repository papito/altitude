import { dragged, dragMoveListener } from "../common/dragon-drop.js"
import { Const } from "../constants.js"

interact("#batchOps button.drag-drop").draggable({
    inertia: true,
    autoScroll: true,

    listeners: {
        move: dragMoveListener,
        start: function(event) {
            const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)
            // Mark affected assets as being dragged
            selectedAssetsStore.items.forEach((asset) => {
                asset.drag()
            })
        },
        end: function(event) {
            const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)
            // Unmark affected assets as being dragged
            selectedAssetsStore.items.forEach((asset) => {
                asset.drop()
            })
            dragged(event)
        },
    },
})
