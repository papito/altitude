import { Const } from "../constants.js"
import { dropzoneListeners, isBatchMoverElement } from "./helpers.js"

/**
 * Location rows accept a thumbnail or the batch mover. A drop adds the assets to the Location without
 * moving them, so the grid stays as it is. Category rows hold no assets and take no drops; no row is
 * draggable.
 */
export function bindLocationDragDrop({ dispatch }) {
    interact("#locationList .dropzone").dropzone({
        accept: "#assets .drag-drop, .batch-ops .drag-drop",
        overlap: 0.2,

        ...dropzoneListeners((event) => {
            const draggableElement = event.relatedTarget
            const locationId = event.target.getAttribute(
                Const.attributes.locationId,
            )
            const assetId = draggableElement.getAttribute(
                Const.attributes.assetId,
            )
            const isBatchMover = isBatchMoverElement(draggableElement)

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
