import { showErrorSnackBar } from "../common/snackbar.js"
import {
    getRequestPath,
    getResponseStatus,
    isRequestSuccessful,
} from "../common/htmx-events.js"

/**
 * Reports a failed explorer HTMX request: a folders or albums tab load, or a menu action loading
 * its inline dialog into the menu panel (the operations the dialogs submit are settled by the
 * dialog listeners before this runs). Expansion and the context menus need no request at all:
 * the renderers (`common/folder-tree.js`, `common/album-list.js`) build both.
 */
export function handleExplorerAfterRequest({ event }) {
    if (isRequestSuccessful(event)) {
        return
    }

    const requestPath = getRequestPath(event)
    showErrorSnackBar(
        `Error for request to ${requestPath}. HTTP ${getResponseStatus(event)}`,
    )
}

export function isExplorerRequest({ app, requestPath }) {
    const repoId = app.context.getRepoId()

    return (
        requestPath.startsWith(`/htmx/folder/r/${repoId}/`) ||
        requestPath.startsWith(`/htmx/album/r/${repoId}/`)
    )
}
