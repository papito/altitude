import { Const } from "../constants.js"
import { suppressClickAfterRelease } from "../search-results/click-suppression.js"
import {
    dragged,
    dragMoveListener,
    dropzoneListeners,
    isBatchMoverElement,
} from "./helpers.js"

export function bindFolderDragDrop({ dispatch }) {
    interact("#rootFolderList .drag-drop").draggable({
        // The ⋯ trigger with its menu and the icon control are controls, not drag handles; a
        // drag starting on the icon would break its single/double-click expansion gestures
        ignoreFrom: ".menu-ctrl, .expand-ctrl",
        inertia: true,
        autoScroll: {
            container: document.querySelector("#explorerPanelContent"),
        },

        listeners: {
            move: dragMoveListener,
            // The release's click lands on the folder's name, moved under the pointer, and would
            // search (or warn in Triage) as if the row had been clicked. Armed at start rather
            // than in `end`: with inertia, a flick's `end` fires after the browser's click.
            start: suppressClickAfterRelease,
            end: dragged,
        },
    })

    interact("#rootFolderList .dropzone").dropzone({
        accept: "#rootFolderList .drag-drop, #assets .drag-drop, .batch-ops .drag-drop",
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
            const isBatchMover = isBatchMoverElement(draggableElement)
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
