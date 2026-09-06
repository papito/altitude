import { Const } from "../constants.js"
import { Folder } from "../models/folder.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "../common/snackbar.js"
import { allowHttpStatuses, getHttpErrorMessage, http } from "../http/client.js"

export function createAssetActions({
    Alpine,
    context,
    reloadNav,
    reloadFolderCounts,
}) {
    // Every successful mutation refreshes both the nav counts and the folder tree counts
    function refreshCounts() {
        reloadNav()
        reloadFolderCounts()
    }

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

    /*
     * Marks cells as having a move in flight. The triage marker stays
     * hidden for the duration (see the `.move-pending` rule in
     * search_results.scala.html) so that on a successful move it is never
     * repainted between the drop and `removeTriageStyling()` deleting it.
     */
    function setMovePending(assetIds, isPending) {
        for (const assetId of assetIds) {
            const cellEl = htmx.find(`#asset-${assetId}`)
            if (!cellEl) {
                continue
            }

            cellEl.classList.toggle("move-pending", isPending)
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

    async function moveAssets({ folderId, assetIds }) {
        const newParentFolder = new Folder(folderId)
        const payload = { assetIds, folderId }

        // Runs in the same synchronous turn as the drop, before the drag
        // handler drops `.dragging` off the cell - so it never flashes back.
        setMovePending(assetIds, true)

        try {
            await http.put(`/api/asset/r/${context.getRepoId()}/move`, payload)

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

            refreshCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error moving assets: ${getHttpErrorMessage(error)}`,
            )
        } finally {
            // On success the marker element is already gone; on failure this
            // brings it back, since the asset is still triaged.
            setMovePending(assetIds, false)
        }
    }

    async function recycleAssets({ assetIds }) {
        const payload = { assetIds }

        try {
            await http.delete(`/api/asset/r/${context.getRepoId()}/move`, {
                data: payload,
            })

            const successMessage = `${
                assetIds.length > 1 ? "Assets" : "Asset"
            } moved to the trash bin`
            showSuccessSnackBar(successMessage)

            removeAssetsFromGrid(assetIds)

            if (shouldResetSelectedAssets(assetIds)) {
                Alpine.store(Const.state.selectedAssets).reset()
            }

            refreshCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error moving assets to trash: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    async function purgeAssets({ assetIds }) {
        const payload = { assetIds }

        try {
            await http.delete(`/api/asset/r/${context.getRepoId()}/purge`, {
                data: payload,
            })

            const successMessage = `${
                assetIds.length > 1 ? "Assets" : "Asset"
            } permanently deleted`
            showSuccessSnackBar(successMessage)

            removeAssetsFromGrid(assetIds)
            Alpine.store(Const.state.selectedAssets).reset()
            refreshCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error purging assets: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    async function restoreAssets({ assetIds }) {
        const payload = { assetIds }

        try {
            const response = await http.put(
                `/api/asset/r/${context.getRepoId()}/restore`,
                payload,
                {
                    validateStatus: allowHttpStatuses(409),
                },
            )

            if (response.status === 409) {
                showWarningSnackBar(
                    "Cannot restore: a non-recycled asset with the same content already exists",
                )
                return
            }

            const successMessage = `${
                assetIds.length > 1 ? "Assets" : "Asset"
            } restored`
            showSuccessSnackBar(successMessage)

            removeAssetsFromGrid(assetIds)
            Alpine.store(Const.state.selectedAssets).reset()
            refreshCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error restoring assets: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    return {
        moveAssets,
        recycleAssets,
        purgeAssets,
        restoreAssets,
    }
}
