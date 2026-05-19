import { Const } from "../constants.js"
import { Folder } from "../models/folder.js"
import { showErrorSnackBar } from "../common/snackbar.js"

/**
 * Before a folder HTMX request fires, handle context-menu toggle logic:
 * if the clicked folder's menu is already open, close it and cancel the request.
 * Otherwise close all other open menus before proceeding.
 */
export function handleFolderBeforeRequest({ app, event }) {
    const requestPath = event.detail.pathInfo.requestPath

    if (!isFolderRequest({ app, requestPath })) {
        return false
    }

    if (
        requestPath === `/htmx/folder/r/${app.context.getRepoId()}/context-menu`
    ) {
        const folderId = event.detail.target.getAttribute(
            Const.attributes.folderId,
        )
        const folder = new Folder(folderId)

        if (folder.isMenuExpanded()) {
            folder.closeContextMenu()
            event.preventDefault()
        } else {
            document
                .querySelectorAll("#rootFolderList .menu")
                .forEach((menuEl) => {
                    Folder.closeContextMenu(menuEl)
                })
        }

        return true
    }

    return true
}

/**
 * After a folder HTMX request completes, handle context-menu show logic.
 * Expand/collapse is handled entirely by the JS DOM builder (folder-tree.js)
 * click handlers now, so only the context-menu case is intercepted here.
 */
export function handleFolderAfterRequest({ app, event }) {
    const requestPath = event.detail.pathInfo.requestPath
    const status = event.detail.xhr.status

    if (!isFolderRequest({ app, requestPath })) {
        return false
    }

    if (event.detail.successful === false) {
        showErrorSnackBar(`Error for request to ${requestPath}. HTTP ${status}`)
        return true
    }

    if (
        requestPath === `/htmx/folder/r/${app.context.getRepoId()}/context-menu`
    ) {
        const folder = new Folder(
            event.target.getAttribute(Const.attributes.folderId),
        )
        folder.showContextMenu()
        return true
    }

    return true
}

export function isFolderRequest({ app, requestPath }) {
    return requestPath.startsWith(`/htmx/folder/r/${app.context.getRepoId()}/`)
}
