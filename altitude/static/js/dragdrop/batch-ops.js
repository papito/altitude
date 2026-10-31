import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { suppressClickAfterRelease } from "../search-results/click-suppression.js"
import { dragged, dragMoveListener } from "./helpers.js"

/**
 * The footer's Move button drags the whole selection; the selected thumbnails dim while it does.
 * A click on the button (no drag) shows its hint through `$tooltip` (`includes/batch_ops`).
 */
export function bindBatchOpsDragDrop() {
    interact("#batchOps button.drag-drop").draggable({
        inertia: true,
        autoScroll: true,

        listeners: {
            move: dragMoveListener,
            start: () => {
                Alpine.store(Const.state.selectedAssets).setDragging(true)

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
