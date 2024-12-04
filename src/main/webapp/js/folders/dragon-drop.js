import {dragged, dragMoveListener} from "/js/common/dragon-drop.js";

import interact from "https://cdn.interactjs.io/v1.9.20/interactjs/index.js"
import {Const} from "/js/constants.js";

interact('#rootFolderList .drag-drop').draggable({
    inertia: true,
    autoScroll: true,

    listeners: {
        move: dragMoveListener,
        end: dragged,
    }
})

// enable a draggable to be dropped into this
interact('#rootFolderList .dropzone').dropzone({
    accept: '.drag-drop',
    overlap: 0.50,

    ondropactivate: function (event) {
        event.target.classList.add('drop-active')
    },
    ondragenter: function (event) {
        const draggableElement = event.relatedTarget
        const dropzoneElement = event.target

        dropzoneElement.classList.add('drop-target')
        draggableElement.classList.add('can-drop')
    },
    ondragleave: function (event) {
        event.target.classList.remove('drop-target')
        event.relatedTarget.classList.remove('can-drop')
    },
    ondrop: function (event) {
        const draggableElement = event.relatedTarget
        const dropzoneElement = event.target

        dropzoneElement.classList.remove('drop-active')
        dropzoneElement.classList.remove('drop-target')
        draggableElement.classList.remove('can-drop')

        const movedFolderId = draggableElement.getAttribute("folder-id")
        const movedAssetId = draggableElement.getAttribute("asset-id")
        const newParentFolderId = dropzoneElement.getAttribute("folder-id")

        if (movedFolderId) {
            console.debug(`Moved folder ${movedFolderId} to ${newParentFolderId}`)
            const movedFolderEvent = new CustomEvent(
                Const.events.folderMoved, {
                    detail: {
                        movedFolderId: movedFolderId,
                        newParentId: newParentFolderId,
                    }});

            document.body.dispatchEvent(movedFolderEvent)
        }

        if (movedAssetId) {
            console.debug(`Moved asset ${movedAssetId} to ${newParentFolderId}`)
            const movedAssetEvent = new CustomEvent(
                Const.events.assetMoved, {
                    detail: {
                        movedAssetId: movedAssetId,
                        newParentId: newParentFolderId,
                    }});

            document.body.dispatchEvent(movedAssetEvent)
        }
    },

    ondropdeactivate: function (event) {
        event.target.classList.remove('drop-active')
        event.target.classList.remove('drop-target')
    }
})
