import { Const } from "../constants.js"
import { Folder } from "../models/folder.js"
import { showErrorSnackBar } from "../common/snackbar.js"

export function handleFolderBeforeRequest({ app, event }) {
    const requestPath = event.detail.pathInfo.requestPath

    if (!isFolderRequest({ app, requestPath })) {
        return false
    }

    if (requestPath === `/htmx/folder/r/${app.context.getRepoId()}/children`) {
        const url = new URL(
            "https://dummy.com" + event.detail.pathInfo.finalRequestPath,
        )
        const folderId = url.searchParams.get("parentId")
        const folder = new Folder(folderId)

        if (folder.isRoot) {
            return true
        }

        if (folder.isExpanded()) {
            folder.collapse()
            event.preventDefault()
            return true
        }

        if (folder.numOfChildren() === 0) {
            event.preventDefault()
            const currentView = app.Alpine.store(Const.state.currentView)
            if (currentView.isTriageView() || currentView.isTrashBinView()) {
                return true
            }

            folder.folderNameEl().click()
            return true
        }

        folder.expand()
        return true
    }

    return true
}

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
        requestPath === `/htmx/folder/r/${app.context.getRepoId()}/children` ||
        requestPath === `/htmx/folder/r/${app.context.getRepoId()}/add`
    ) {
        const folder = new Folder(
            event.target.getAttribute(Const.attributes.folderId),
        )
        folder.expand()
        return true
    }

    return true
}

export function isFolderRequest({ app, requestPath }) {
    return requestPath.startsWith(`/htmx/folder/r/${app.context.getRepoId()}/`)
}
