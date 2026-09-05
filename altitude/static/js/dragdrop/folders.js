import { Const } from "../constants.js"
import { dragged, dragMoveListener } from "../common/dragon-drop.js"

export function bindFolderDragDrop({ dispatch }) {
    interact("#rootFolderList .drag-drop").draggable({
        // The ⋯ trigger and its menu are controls, not drag handles
        ignoreFrom: ".menu-ctrl",
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

        ondropactivate: (event) => {
            event.target.classList.add("drop-active")
        },

        ondragenter: (event) => {
            const draggableElement = event.relatedTarget
            const dropzoneElement = event.target

            dropzoneElement.classList.add("drop-target")
            draggableElement.classList.add("can-drop")
        },

        ondragleave: (event) => {
            event.target.classList.remove("drop-target")
            event.relatedTarget.classList.remove("can-drop")
        },

        ondrop: (event) => {
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

            dropzoneElement.classList.remove("drop-active")
            dropzoneElement.classList.remove("drop-target")
            draggableElement.classList.remove("can-drop")

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
        },

        ondropdeactivate: (event) => {
            event.target.classList.remove("drop-active")
            event.target.classList.remove("drop-target")
        },
    })
}
