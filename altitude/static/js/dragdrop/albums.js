import { Const } from "../constants.js"
import { dropzoneListeners } from "../common/dragon-drop.js"

/**
 * Album rows accept assets: a single thumbnail, or the batch mover when assets are selected.
 * Dropping adds pointers to the album and leaves the assets where they are, so nothing in the
 * grid moves. Albums themselves are not draggable: they are a flat list with nothing to move into.
 */
export function bindAlbumDragDrop({ dispatch }) {
    interact("#albumList .dropzone").dropzone({
        accept: "#assets .drag-drop, #batchOps .drag-drop",
        overlap: 0.2,

        ...dropzoneListeners((event) => {
            const draggableElement = event.relatedTarget
            const albumId = event.target.getAttribute(Const.attributes.albumId)
            const assetId = draggableElement.getAttribute(
                Const.attributes.assetId,
            )
            const isBatchMover =
                draggableElement.parentNode.classList.contains("batch-mover")

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
