import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { dragged, dragMoveListener } from "./helpers.js"

/** The footer's Move button drags the whole selection; the selected thumbnails dim while it does */
export function bindBatchOpsDragDrop() {
    interact("#batchOps button.drag-drop").draggable({
        inertia: true,
        autoScroll: true,

        listeners: {
            move: dragMoveListener,
            start: () => {
                Alpine.store(Const.state.selectedAssets).setDragging(true)
            },
            end: (event) => {
                Alpine.store(Const.state.selectedAssets).setDragging(false)
                dragged(event)
            },
        },
    })
}
