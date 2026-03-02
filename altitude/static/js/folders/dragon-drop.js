import { dragged, dragMoveListener } from "../common/dragon-drop.js"
import { Const } from "../constants.js"

interact("#rootFolderList .drag-drop").draggable({
    inertia: true,
    autoScroll: true,

    listeners: {
        move: dragMoveListener,
        end: dragged,
    },
})

// enable a draggable to be dropped into this
interact("#rootFolderList .dropzone").dropzone({
    accept: "#rootFolderList .drag-drop, #assets .drag-drop, #batchOps .drag-drop",
    overlap: 0.2,

    ondropactivate: function (event) {
        event.target.classList.add("drop-active")
    },
    ondragenter: function (event) {
        const draggableElement = event.relatedTarget
        const dropzoneElement = event.target

        dropzoneElement.classList.add("drop-target")
        draggableElement.classList.add("can-drop")
    },
    ondragleave: function (event) {
        event.target.classList.remove("drop-target")
        event.relatedTarget.classList.remove("can-drop")
    },
    ondrop: function (event) {
        const draggableElement = event.relatedTarget
        const dropzoneElement = event.target

        dropzoneElement.classList.remove("drop-active")
        dropzoneElement.classList.remove("drop-target")
        draggableElement.classList.remove("can-drop")

        // If available, get the folderId of the moved element
        const movedFolderId = draggableElement.getAttribute(
            Const.attributes.folderId,
        )
        // If available, get the assetId of the moved element
        const movedAssetId = draggableElement.getAttribute(
            Const.attributes.assetId,
        )

        // Check if this is a batch mover (i.e. multiple selected assets)
        // If so, this operation uses state store to get the list of selected assets
        const isBatchMover =
            draggableElement.parentNode.classList.contains("batch-mover")

        // Get the folderId of the dropzone (the new parent folder)
        const newParentFolderId = dropzoneElement.getAttribute(
            Const.attributes.folderId,
        )

        if (movedFolderId) {
            console.debug(
                `Moved folder ${movedFolderId} to ${newParentFolderId}`,
            )
            const movedFolderEvent = new CustomEvent(Const.events.folderMoved, {
                detail: {
                    movedFolderId: movedFolderId,
                    newParentId: newParentFolderId,
                },
            })

            document.body.dispatchEvent(movedFolderEvent)
        }

        if (movedAssetId) {
            console.debug(`Moved asset ${movedAssetId} to ${newParentFolderId}`)
            const movedAssetEvent = new CustomEvent(Const.events.assetMoved, {
                detail: {
                    assetId: movedAssetId,
                    folderId: newParentFolderId,
                },
            })

            document.body.dispatchEvent(movedAssetEvent)
        }

        /**
         * If this is a batch mover, or if the moved asset is part of a selection of multiple assets
         */
        if (isBatchMover) {
            console.debug(`Batch moving assets to folder ${newParentFolderId}`)
            const batchMovedEvent = new CustomEvent(
                Const.events.batchAssetsMoved,
                {
                    detail: {
                        folderId: newParentFolderId,
                    },
                },
            )

            document.body.dispatchEvent(batchMovedEvent)
        }
    },

    ondropdeactivate: function (event) {
        event.target.classList.remove("drop-active")
        event.target.classList.remove("drop-target")
    },
})
