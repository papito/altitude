import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { suppressClickAfterRelease } from "../search-results/click-suppression.js"
import {
    dragged,
    dragMoveListener,
    setFixedPositionWhileDragging,
} from "./helpers.js"

/**
 * The batch ops' Move button (the toolbar's or the footer's copy) drags the whole selection, whose thumbnails dim
 * meanwhile. A plain click shows its hint (`$tooltip`).
 */
export function bindBatchOpsDragDrop() {
    interact(".batch-ops button.batch-mover").draggable({
        inertia: true,
        autoScroll: true,

        listeners: {
            move: dragMoveListener,
            start: (event) => {
                Alpine.store(Const.state.selectedAssets).setDragging(true)

                // The toolbar's copy sits in the scrolling results pane, which would clip it on its way to the
                // explorer; fixed, it is drawn over everything, and a placeholder keeps the buttons after it in place
                setFixedPositionWhileDragging(event)

                // The release's click lands on the button, now under the pointer, and would show the hint.
                // Armed at start: with inertia, a flick's `end` fires after the browser's click.
                suppressClickAfterRelease()
            },
            end: (event) => {
                Alpine.store(Const.state.selectedAssets).setDragging(false)
                dragged(event)
            },
        },
    })
}
