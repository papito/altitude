import { Const } from "../constants.js"
import { Folder } from "../models/folder.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "../common/snackbar.js"

export function registerFolderListeners(app) {
    document.body.addEventListener(Const.events.folderMoved, (event) => {
        const movedFolderId = event.detail.movedFolderId
        const newParentId = event.detail.newParentId

        if (newParentId === movedFolderId) {
            return
        }

        const movedFolder = new Folder(movedFolderId)
        const newParent = new Folder(newParentId)
        const oldParent = movedFolder.parent()

        htmx.ajax(
            "put",
            `/htmx/folder/r/${app.context.getRepoId()}/move?movedFolderId=${movedFolderId}&newParentId=${newParentId}`,
            {
                swap: "none",
                handler: (response) => {
                    const status = response["htmx-internal-data"].xhr.status

                    if (status === 200) {
                        movedFolder.closeContextMenu()
                        movedFolder.clearChildren()

                        newParent.incrementNumOfChildren()
                        oldParent.decrementNumOfChildren()

                        showSuccessSnackBar(
                            `Folder ${movedFolder.name()} moved into "${newParent.name()}"`,
                        )

                        if (newParent.isExpanded()) {
                            newParent.addChild(movedFolder)
                            movedFolder.collapse()
                        } else {
                            movedFolder.remove()
                        }

                        newParent.updateVisualState()
                        oldParent.updateVisualState()
                    } else if (status === 409) {
                        showWarningSnackBar(
                            response["htmx-internal-data"].xhr.responseText,
                        )
                    } else {
                        showErrorSnackBar(
                            `Error moving folder "${movedFolder.name()}". Status: ${status}`,
                        )
                    }
                },
            },
        )
    })

    document.body.addEventListener(Const.events.folderAdded, (event) => {
        const parentFolder = new Folder(event.detail.parentId)
        parentFolder.incrementNumOfChildren()
        parentFolder.updateVisualState()
    })

    document.body.addEventListener(Const.events.folderDeleted, (event) => {
        const folder = new Folder(event.detail.id)
        const parent = folder.parent()

        parent.decrementNumOfChildren()
        parent.updateVisualState()

        showSuccessSnackBar(`Folder "${folder.name()}" deleted`)
        folder.remove()
    })
}

