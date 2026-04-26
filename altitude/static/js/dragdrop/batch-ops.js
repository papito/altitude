import { Const } from "../constants.js"
import { dragged, dragMoveListener } from "../common/dragon-drop.js"

export function bindBatchOpsDragDrop({ Alpine }) {
    const dragHandleEl = document.querySelector("#batchOps button.drag-drop")
    if (!dragHandleEl) {
        return
    }

    interact("#batchOps button.drag-drop").draggable({
        inertia: true,
        autoScroll: true,

        listeners: {
            move: dragMoveListener,
            start: () => {
                const selectedAssetsStore = Alpine.store(
                    Const.state.selectedAssets,
                )

                selectedAssetsStore.items.forEach((asset) => {
                    asset.drag()
                })
            },
            end: (event) => {
                const selectedAssetsStore = Alpine.store(
                    Const.state.selectedAssets,
                )

                selectedAssetsStore.items.forEach((asset) => {
                    asset.drop()
                })

                dragged(event)
            },
        },
    })
}

