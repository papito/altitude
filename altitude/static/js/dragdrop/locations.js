import { Const } from "../constants.js"
import { dropzoneListeners } from "./helpers.js"

/**
 * Location rows accept assets: a single thumbnail, or the batch mover when assets are selected.
 * Dropping adds pointers to the Location and leaves the assets where they are, so nothing in the
 * grid moves. Category rows are not drop targets (they hold no assets), and rows are not draggable.
 */
export function bindLocationDragDrop({ dispatch }) {
    interact("#locationList .dropzone").dropzone({
        accept: "#assets .drag-drop, #batchOps .drag-drop",
        overlap: 0.2,

        ...dropzoneListeners((event) => {
            const draggableElement = event.relatedTarget
            const locationId = event.target.getAttribute(
                Const.attributes.locationId,
            )
            const assetId = draggableElement.getAttribute(
                Const.attributes.assetId,
            )
            const isBatchMover =
                draggableElement.parentNode.classList.contains("batch-mover")

            if (assetId) {
                console.debug(
                    `Adding asset ${assetId} to location ${locationId}`,
                )
                dispatch(Const.events.assetAddedToLocation, {
                    locationId,
                    assetId,
                })
            }

            if (isBatchMover) {
                console.debug(`Batch adding assets to location ${locationId}`)
                dispatch(Const.events.batchAssetsAddedToLocation, {
                    locationId,
                })
            }
        }),
    })
}
