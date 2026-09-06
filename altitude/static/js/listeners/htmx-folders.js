import { showErrorSnackBar } from "../common/snackbar.js"
import {
    getRequestPath,
    getResponseStatus,
    isRequestSuccessful,
} from "../common/htmx-events.js"

/**
 * Reports a failed folder HTMX request: the folders tab load, or a folder menu action loading its
 * inline dialog into the menu panel (the operations the dialogs submit are settled by the dialog
 * listeners before this runs). Folder expansion and the context menus need no request at all:
 * the tree renderer (`common/folder-tree.js`) builds both.
 */
export function handleFolderAfterRequest({ event }) {
    if (isRequestSuccessful(event)) {
        return
    }

    const requestPath = getRequestPath(event)
    showErrorSnackBar(
        `Error for request to ${requestPath}. HTTP ${getResponseStatus(event)}`,
    )
}

export function isFolderRequest({ app, requestPath }) {
    return requestPath.startsWith(`/htmx/folder/r/${app.context.getRepoId()}/`)
}
