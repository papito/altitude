import { showErrorSnackBar } from "../common/snackbar.js"
import {
    getRequestPath,
    getResponseStatus,
    isRequestSuccessful,
} from "../common/htmx-events.js"

/**
 * Reports a failed folder HTMX request. The folder requests still issued through HTMX are the
 * folders tab load and the dialog opens from a folder menu; the latter are settled by the modal
 * listeners before this runs, so in practice this covers the tab. Folder expansion and the context
 * menus need no request at all: the tree renderer (`common/folder-tree.js`) builds both.
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
