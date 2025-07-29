import interact from "https://cdn.interactjs.io/v1.9.20/interactjs/index.js"
import {
    dragged,
    dragMoveListener,
    setFixedPositionWhileDragging,
} from "../common/dragon-drop.js"
import { Const } from "../constants.js"

interact("#assets .drag-drop").draggable({
    inertia: true,
    autoScroll: true,

    listeners: {
        move: dragMoveListener,
        /**
         * This a custom function that, in addition to setting the display as "fixed",
         * makes the image smaller while dragging, for better UX.
         * Normally, we would just use the common setFixedPositionWhileDragging() function.
         */
        start: function (event) {
            setFixedPositionWhileDragging(event)

            let target = event.target
            let position = target.getBoundingClientRect()

            let imgElement = target.querySelector("img")
            if (imgElement) {
                imgElement.setAttribute(
                    Const.attributes.originalWidth,
                    imgElement.clientWidth,
                )
                imgElement.style.width = "45px"
                const yOffset = event.clientY - position.top
                target.style.top = position.top + yOffset + "px"
            }
        },
        end: dragged,
    },
})

interact("#trash").dropzone({
    accept: "#assets .drag-drop, #batchOps .drag-drop",
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

        const trashedFolderId = draggableElement.getAttribute(
            Const.attributes.folderId,
        )
        const trashedAssetId = draggableElement.getAttribute(
            Const.attributes.assetId,
        )

        // Check if this is a batch mover (i.e. multiple selected assets)
        // If so, this operation uses state store to get the list of selected assets
        const isBatchMover = draggableElement.parentNode.classList.contains("batch-mover")

        if (trashedFolderId) {
            console.debug(`Trashed folder ${trashedFolderId}`)
            const trashedFolderEvent = new CustomEvent(
                Const.events.folderTrashed,
                {
                    detail: {
                        trashedFolderId: trashedFolderId,
                    },
                },
            )

            document.body.dispatchEvent(trashedFolderEvent)
        }

        if (trashedAssetId) {
            console.debug(`Trashed asset ${trashedAssetId}`)
            const trashedAssetEvent = new CustomEvent(
                Const.events.assetTrashed,
                {
                    detail: {
                        assetId: trashedAssetId,
                    },
                },
            )

            document.body.dispatchEvent(trashedAssetEvent)
        }

        if (isBatchMover) {
            console.debug(`Batch recycling assets`)
            const batchTrashedEvent = new CustomEvent(
                Const.events.batchAssetsTrashed,
            )
            document.body.dispatchEvent(batchTrashedEvent)
        }
    },

    ondropdeactivate: function (event) {
        event.target.classList.remove("drop-active")
        event.target.classList.remove("drop-target")
    },
})
