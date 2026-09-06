import { Const } from "../constants.js"
import { Folder } from "../models/folder.js"
import { reloadFolderTree } from "../common/folder-tree.js"
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

        // Capture the folder name before the DOM is rebuilt
        const movedFolderNameEl = document.getElementById(
            `folderName-${movedFolderId}`,
        )
        const movedFolderName = movedFolderNameEl?.innerText ?? movedFolderId

        const newParentNameEl = document.getElementById(
            `folderName-${newParentId}`,
        )
        const newParentName = newParentNameEl?.innerText ?? newParentId

        try {
            const response = await http.put(
                `/htmx/folder/r/${app.context.getRepoId()}/move?movedFolderId=${movedFolderId}&newParentId=${newParentId}`,
                null,
                {
                    validateStatus: allowHttpStatuses(409),
                },
            )

            if (response.status === 200) {
                await reloadFolderTree(app.context.getRepoId())
                showSuccessSnackBar(
                    `Folder "${movedFolderName}" moved into "${newParentName}"`,
                )
            } else if (response.status === 409) {
                showWarningSnackBar(response.data)
            } else {
                showErrorSnackBar(
                    `Error moving folder "${movedFolderName}". Status: ${response.status}`,
                )
            }
        } catch (error) {
            showErrorSnackBar(
                `Error moving folder "${movedFolderName}": ${getHttpErrorMessage(error)}`,
            )
        }
    })

    document.body.addEventListener(Const.events.folderAdded, async (event) => {
        await reloadFolderTree(app.context.getRepoId())

        // Expand the parent so the newly added folder is visible
        const parentId = event.detail.parentId
        if (parentId) {
            try {
                const parent = new Folder(parentId)
                if (!parent.isRoot && !parent.isExpanded()) {
                    parent.expand()
                }
            } catch (_) {
                // parent not present in the rebuilt tree - nothing to reveal
            }
        }
    })

    document.body.addEventListener(
        Const.events.folderDeleted,
        async (event) => {
            // Capture name before the tree is rebuilt
            const id = event.detail.id
            const nameEl = document.getElementById(`folderName-${id}`)
            const name = nameEl?.innerText ?? id

            await reloadFolderTree(app.context.getRepoId())
            showSuccessSnackBar(`Folder "${name}" deleted`)
        },
    )

    document.body.addEventListener(Const.events.folderRenamed, async () => {
        await reloadFolderTree(app.context.getRepoId())
    })
}
