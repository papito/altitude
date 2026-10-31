import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { suppressClickAfterRelease } from "../search-results/click-suppression.js"
import {
    dragged,
    dragMoveListener,
    setFixedPositionWhileDragging,
} from "./helpers.js"

/**
 * The batch ops' Move button drags the whole selection, from either copy (`includes/batch_ops`: the search toolbar's
 * and the footer's); the selected thumbnails dim while it does. A click on the button (no drag) shows its hint through
 * `$tooltip`.
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
                // explorer; fixed, it is drawn over everything, as a dragged thumbnail is. A placeholder holds its
                // place in the row, so the buttons after it stay put
                setFixedPositionWhileDragging(event)

                // The release's click lands on the button, moved under the pointer, and would
                // show the hint as if it had been clicked. Armed at start rather than in `end`:
                // with inertia, a flick's `end` fires after the browser's click.
                suppressClickAfterRelease()
            },
            end: (event) => {
                Alpine.store(Const.state.selectedAssets).setDragging(false)
                dragged(event)
            },
        },
    })
}
