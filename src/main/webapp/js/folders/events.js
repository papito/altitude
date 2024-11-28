import {Const} from "/js/constants.js";
import {Folder} from "/js/models.js";
import {context} from "/js/context.js";
import {showSuccessSnackBar, showErrorSnackBar, showWarningSnackBar} from "/js/common/snackbar.js"

/**
 * A folder is dragon dropped in UI BUT not yet removed on the server-side
 */
document.body.addEventListener(Const.events.folderMoved, (event) => {
    const movedFolderId = event.detail['movedFolderId']
    const movedFolder = new Folder(movedFolderId)
    const newParentId = event.detail['newParentId']
    const newParent = new Folder(newParentId)
    const oldParent = movedFolder.parent()

    console.debug("Folder " + movedFolder.name() + " dragon dropped from " + oldParent.name() + " into " + newParent.name())

    // The server does check this, but since we remove the moved folder from the DOM, we must short-circuit
    if (newParentId === movedFolderId) {
        return
    }

    function handler(response)  {
        const status = response['htmx-internal-data'].xhr.status

        if (status === 200) {
            // Regardless of the target folder state, we should de-clutter the visual state of the moved folder
            movedFolder.closeContextMenu()
            movedFolder.clearChildren()

            newParent.incrementNumOfChildren()
            oldParent.decrementNumOfChildren()

            if (newParent.isExpanded()) {
                // target folder is expanded, so append the moved folder
                newParent.addChild(movedFolder)
                movedFolder.collapse()
            } else {
                console.debug("Target folder not expanded - removing the folder being moved.")
                // target folder is not expanded, so just make the moved folder disappear
                showSuccessSnackBar("Folder \"" + movedFolder.name() + "\" moved to \"" + newParent.name() + '"')
                movedFolder.remove()
            }

            newParent.updateVisualState()
            oldParent.updateVisualState()
        } else if (status === 409) {
            const message = response['htmx-internal-data'].xhr.responseText
            showWarningSnackBar(message)
        } else {
            console.debug(response['htmx-internal-data'].xhr.responseText)
            showErrorSnackBar("Error moving folder \"" + movedFolder.name() + "\". Status: " + status)
        }
    }

    htmx.ajax(
        "POST",
        `/htmx/folder/r/${context.repoId}/move`, {
            "swap": "none",
            "values": {...event.detail},
            "handler": handler
        })
})

/**
 * A folder is added on the server
 */
document.body.addEventListener(Const.events.folderAdded, (event) => {
    const parentFolder = new Folder(event.detail["parentId"])
    console.debug("Folder added event received for parent folder " + parentFolder.name())

    // Update the parent folder's number of children as we are not refreshing
    // the tree HTML, to avoid aggressive UI changes that might confuse the user
    parentFolder.incrementNumOfChildren()
    parentFolder.updateVisualState()
})

/**
 * A folder is deleted on the server
 */
document.body.addEventListener(Const.events.folderDeleted, (event) => {
    const folder = new Folder(event.detail["id"])
    console.debug("Folder deleted event received for folder " + folder.name())

    // Update the parent folder's number of children as we are not refreshing
    // the tree HTML, avoid aggressive UI changes that might confuse the user
    const parent = folder.parent()
    console.debug("Parent folder is " + parent.name())
    parent.decrementNumOfChildren()
    parent.updateVisualState()

    showSuccessSnackBar("Folder \"" + folder.name() + "\" deleted")

    // Without major commotion, pluck the folder from the DOM
    folder.remove()
})
