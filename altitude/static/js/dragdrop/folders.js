import { Const } from "../constants.js"
import {
    dragged,
    dragMoveListener,
    dropzoneListeners,
} from "../common/dragon-drop.js"

export function bindFolderDragDrop({ dispatch }) {
    interact("#rootFolderList .drag-drop").draggable({
        // The ⋯ trigger with its menu and the icon control are controls, not drag handles; a
        // drag starting on the icon would break its single/double-click expansion gestures
        ignoreFrom: ".menu-ctrl, .expand-ctrl",
        inertia: true,
        autoScroll: { container: document.querySelector("#explorer") },

        listeners: {
            move: dragMoveListener,
            end: dragged,
        },
    })

    interact("#rootFolderList .dropzone").dropzone({
        accept: "#rootFolderList .drag-drop, #assets .drag-drop, #batchOps .drag-drop",
        overlap: 0.2,

        ...dropzoneListeners((event) => {
            const draggableElement = event.relatedTarget
            const dropzoneElement = event.target
            const movedFolderId = draggableElement.getAttribute(
                Const.attributes.folderId,
            )
            const movedAssetId = draggableElement.getAttribute(
                Const.attributes.assetId,
            )
            const isBatchMover =
                draggableElement.parentNode.classList.contains("batch-mover")
            const newParentId = dropzoneElement.getAttribute(
                Const.attributes.folderId,
            )

            if (movedFolderId) {
                console.debug(`Moved folder ${movedFolderId} to ${newParentId}`)
                dispatch(Const.events.folderMoved, {
                    movedFolderId,
                    newParentId,
                })
            }

            if (movedAssetId) {
                console.debug(`Moved asset ${movedAssetId} to ${newParentId}`)
                dispatch(Const.events.assetMoved, {
                    assetId: movedAssetId,
                    folderId: newParentId,
                })
            }

            if (isBatchMover) {
                console.debug(`Batch moving assets to folder ${newParentId}`)
                dispatch(Const.events.batchAssetsMoved, {
                    folderId: newParentId,
                })
            }
        }),
    })
}
