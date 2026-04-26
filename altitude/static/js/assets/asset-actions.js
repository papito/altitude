import { Const } from "../constants.js"
import { Folder } from "../models/folder.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "../common/snackbar.js"

export function createAssetActions({ Alpine, context, reloadNav }) {
    function removeTriageStyling(assetIds) {
        for (const assetId of assetIds) {
            const cellEl = htmx.find(`#asset-${assetId}`)
            if (!cellEl) {
                continue
            }

            cellEl.removeAttribute("alt-is-triaged")

            const marker = cellEl.querySelector(".triage-marker")
            if (marker) {
                marker.remove()
            }
        }
    }

    function removeAssetsFromGrid(assetIds) {
        let removedCount = 0

        for (const assetId of assetIds) {
            const el = htmx.find(`#asset-${assetId}`)
            if (!el) {
                continue
            }

            el.remove()
            removedCount++
        }

        if (removedCount > 0) {
            Alpine.store(Const.state.resultsTotal).decrement(removedCount)
        }
    }

    function shouldRemoveFromGrid(destinationFolderId) {
        if (Alpine.store(Const.state.currentView).isTriageView()) {
            return true
        }

        const viewedFolderId = context.getCurrentFolderId()
        if (!viewedFolderId) {
            return false
        }

        try {
            const destFolder = new Folder(destinationFolderId)
            return !destFolder.isDescendantOrSelf(viewedFolderId)
        } catch {
            console.debug(
                `Destination folder ${destinationFolderId} not in DOM, assuming outside viewed subtree`,
            )
            return true
        }
    }

    function shouldResetSelectedAssets(assetIds) {
        return (
            assetIds.length > 1 ||
            (assetIds.length === 1 &&
                Alpine.store(Const.state.selectedAssets).contains(assetIds[0]))
        )
    }

    function moveAssets({ folderId, assetIds }) {
        const newParentFolder = new Folder(folderId)
        const payload = { assetIds, folderId }

        fetch(`/api/asset/r/${context.getRepoId()}/move`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    showErrorSnackBar(`Error moving assets: ${response.statusText}`)
                    return
                }

                const successMessage = `${
                    assetIds.length > 1 ? "Assets" : "Asset"
                } moved to folder "${newParentFolder.name()}"`
                showSuccessSnackBar(successMessage)

                removeTriageStyling(assetIds)

                if (shouldRemoveFromGrid(folderId)) {
                    removeAssetsFromGrid(assetIds)
                }

                if (shouldResetSelectedAssets(assetIds)) {
                    Alpine.store(Const.state.selectedAssets).reset()
                }

                reloadNav()
            })
            .catch((error) => {
                showErrorSnackBar(`Error moving assets: ${error}`)
            })
    }

    function recycleAssets({ assetIds }) {
        const payload = { assetIds }

        fetch(`/api/asset/r/${context.getRepoId()}/move`, {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    showErrorSnackBar(
                        `Error moving assets to trash: ${response.statusText}`,
                    )
                    return
                }

                const successMessage = `${
                    assetIds.length > 1 ? "Assets" : "Asset"
                } moved to the trash bin`
                showSuccessSnackBar(successMessage)

                removeAssetsFromGrid(assetIds)

                if (shouldResetSelectedAssets(assetIds)) {
                    Alpine.store(Const.state.selectedAssets).reset()
                }

                reloadNav()
            })
            .catch((error) => {
                showErrorSnackBar(`Error moving assets to trash: ${error}`)
            })
    }

    function purgeAssets({ assetIds }) {
        const payload = { assetIds }

        fetch(`/api/asset/r/${context.getRepoId()}/purge`, {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    showErrorSnackBar(`Error purging assets: ${response.statusText}`)
                    return
                }

                const successMessage = `${
                    assetIds.length > 1 ? "Assets" : "Asset"
                } permanently deleted`
                showSuccessSnackBar(successMessage)

                removeAssetsFromGrid(assetIds)
                Alpine.store(Const.state.selectedAssets).reset()
                reloadNav()
            })
            .catch((error) => {
                showErrorSnackBar(`Error purging assets: ${error}`)
            })
    }

    function restoreAssets({ assetIds }) {
        const payload = { assetIds }

        fetch(`/api/asset/r/${context.getRepoId()}/restore`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    if (response.status === 409) {
                        showWarningSnackBar(
                            "Cannot restore: a non-recycled asset with the same content already exists",
                        )
                    } else {
                        showErrorSnackBar(
                            `Error restoring assets: ${response.statusText}`,
                        )
                    }
                    return
                }

                const successMessage = `${
                    assetIds.length > 1 ? "Assets" : "Asset"
                } restored`
                showSuccessSnackBar(successMessage)

                removeAssetsFromGrid(assetIds)
                Alpine.store(Const.state.selectedAssets).reset()
                reloadNav()
            })
            .catch((error) => {
                showErrorSnackBar(`Error restoring assets: ${error}`)
            })
    }

    return {
        moveAssets,
        recycleAssets,
        purgeAssets,
        restoreAssets,
    }
}

