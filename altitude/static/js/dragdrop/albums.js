import { Const } from "../constants.js"
import { dropzoneListeners, isBatchMoverElement } from "./helpers.js"

/**
 * Album rows accept a thumbnail or the batch mover. A drop adds the assets to the album without
 * moving them, so the grid stays as it is. Albums are not draggable: a flat list has nowhere to move.
 */
export function bindAlbumDragDrop({ dispatch }) {
    interact("#albumList .dropzone").dropzone({
        accept: "#assets .drag-drop, .batch-ops .drag-drop",
        overlap: 0.2,

        ...dropzoneListeners((event) => {
            const draggableElement = event.relatedTarget
            const albumId = event.target.getAttribute(Const.attributes.albumId)
            const assetId = draggableElement.getAttribute(
                Const.attributes.assetId,
            )
            const isBatchMover = isBatchMoverElement(draggableElement)

            if (assetId) {
                console.debug(`Adding asset ${assetId} to album ${albumId}`)
                dispatch(Const.events.assetAddedToAlbum, { albumId, assetId })
            }

            if (isBatchMover) {
                console.debug(`Batch adding assets to album ${albumId}`)
                dispatch(Const.events.batchAssetsAddedToAlbum, { albumId })
            }
        }),
    })
}
