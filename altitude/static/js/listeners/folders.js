import { Const } from "../constants.js"
import { Folder } from "../models/folder.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "../common/snackbar.js"
import { allowHttpStatuses, getHttpErrorMessage, http } from "../http/client.js"

export function registerFolderListeners(app) {
    document.body.addEventListener(Const.events.folderMoved, async (event) => {
        const movedFolderId = event.detail.movedFolderId
        const newParentId = event.detail.newParentId

        if (newParentId === movedFolderId) {
            return
        }

        const movedFolder = new Folder(movedFolderId)
        const newParent = new Folder(newParentId)
        const oldParent = movedFolder.parent()

        try {
            const response = await http.put(
                `/htmx/folder/r/${app.context.getRepoId()}/move?movedFolderId=${movedFolderId}&newParentId=${newParentId}`,
                null,
                {
                    validateStatus: allowHttpStatuses(409),
                },
            )

            if (response.status === 200) {
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
            } else if (response.status === 409) {
                showWarningSnackBar(response.data)
            } else {
                showErrorSnackBar(
                    `Error moving folder "${movedFolder.name()}". Status: ${response.status}`,
                )
            }
        } catch (error) {
            showErrorSnackBar(
                `Error moving folder "${movedFolder.name()}": ${getHttpErrorMessage(error)}`,
            )
        }
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
