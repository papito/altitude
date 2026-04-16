import { Folder } from "../models/folder.js"
 import { Const } from "../constants.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "../common/snackbar.js"

class AssetService {
    reloadNav() {
        htmx.ajax("GET", `/htmx/nav/r/${window.ctx.getRepoId()}`, {
            swap: "innerHTML",
            target: "nav",
        })
    }

    /**
     * Remove triage styling (marker) from assets after they are moved to a folder.
     * This is called after a successful move operation to update the UI.
     */
    removeTriageStyling(assetIds) {
        for (const assetId of assetIds) {
            const cellEl = htmx.find(`#asset-${assetId}`)
            if (cellEl) {
                cellEl.removeAttribute("alt-is-triaged")

                const marker = cellEl.querySelector(".triage-marker")
                if (marker) {
                    marker.remove()
                }
            }
        }
    }

    /**
     * Remove the given assets from the result grid and decrement the results
     * counter by the number of assets actually removed.
     */
    removeAssetsFromGrid(assetIds) {
        let removedCount = 0
        for (const assetId of assetIds) {
            const el = htmx.find(`#asset-${assetId}`)
            if (el) {
                el.remove()
                removedCount++
            }
        }

        if (removedCount > 0) {
            Alpine.store(Const.state.resultsTotal).decrement(removedCount)
        }
    }

    /**
     * Returns true if the destination folder is outside the currently viewed
     * folder's subtree, meaning moved assets should be removed from the grid.
     *
     * If no folder filter is active (browsing everything), returns false (no removal).
     * If in triage view, always returns true (triage items moved to folders should disappear).
     */
    shouldRemoveFromGrid(destinationFolderId) {
        // If we're in triage view, moving assets to any folder removes them from triage
        if (Alpine.store(Const.state.currentView).isTriageView()) {
            return true
        }

        const viewedFolderId = window.ctx.getCurrentFolderId()
        if (!viewedFolderId) {
            // If we're in triage view, moving assets to any folder removes them from triage
            if (Alpine.store(Const.state.currentView).isTriageView()) {
                return true
            }

            // No folder filter active — asset stays visible regardless
            return false
        }

        try {
            const destFolder = new Folder(destinationFolderId)
            return !destFolder.isDescendantOrSelf(viewedFolderId)
        } catch (e) {
            // Destination folder element not found in sidebar DOM — it's outside
            // the currently expanded tree, so conservatively assume it's outside
            // the viewed subtree.
            console.debug(
                `Destination folder ${destinationFolderId} not in DOM, assuming outside viewed subtree`,
            )
            return true
        }
    }

    moveAssetFromResultSetUtil(event, response, successMessage) {
        const status = response["htmx-internal-data"].xhr.status
        const assetId = event.detail["assetId"]

        if (status === 200) {
            // Remove the asset from the DOM and decrement the reactive counter
            htmx.find(`#asset-${assetId}`).remove()
            Alpine.store(Const.state.resultsTotal).decrement(1)

            showSuccessSnackBar(successMessage)

            this.reloadNav()
        } else if (status === 409) {
            const message = response["htmx-internal-data"].xhr.responseText
            showWarningSnackBar(message)
        } else {
            showErrorSnackBar(
                `Error performing operation on ${assetId}: ${status}`,
            )
        }
    }

    moveAssets({ folderId, assetIds }) {
        const newParentFolder = new Folder(folderId)

        const payload = {
            assetIds: assetIds,
            folderId: folderId,
        }

        fetch(`/api/asset/r/${window.ctx.getRepoId()}/move`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    showErrorSnackBar(`Error  + ${response.statusText}`)
                    return
                }

                const successMessage = `${assetIds.length > 1 ? "Assets" : "Asset"} moved to folder "${newParentFolder.name()}"`
                showSuccessSnackBar(successMessage)

                // Remove triage styling from moved assets (they are no longer in triage)
                this.removeTriageStyling(assetIds)

                // Remove assets from the grid if the destination folder is outside
                // the currently viewed folder's subtree (respecting ancestry)
                if (this.shouldRemoveFromGrid(folderId)) {
                    this.removeAssetsFromGrid(assetIds)
                }

                // reset the selected assets store, but only if we moved multiple assets, or if the moved asset was part of a selection
                if (
                    assetIds.length > 1 ||
                    (assetIds.length === 1 &&
                        Alpine.store(Const.state.selectedAssets).contains(
                            assetIds[0],
                        ))
                ) {
                    Alpine.store(Const.state.selectedAssets).reset()
                }

                this.reloadNav()
            })
            .catch((response) => {
                showErrorSnackBar(
                    `Error moving  asset: ${response.status}, ${response.statusText}`,
                )
            })
    }

    recycleAssets({ assetIds }) {
        const payload = {
            assetIds: assetIds,
        }

        fetch(`/api/asset/r/${window.ctx.getRepoId()}/move`, {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    showErrorSnackBar(`Error  + ${response.statusText}`)
                    return
                }
                const successMessage = `${assetIds.length > 1 ? "Assets" : "Asset"} moved to the trash bin`
                showSuccessSnackBar(successMessage)

                // Always remove recycled assets from the grid — the trash bin
                // is never inside any folder's subtree
                this.removeAssetsFromGrid(assetIds)

                // reset the selected assets store, but only if we moved multiple assets, or if the moved asset was part of a selection
                if (
                    assetIds.length > 1 ||
                    (assetIds.length === 1 &&
                        Alpine.store(Const.state.selectedAssets).contains(
                            assetIds[0],
                        ))
                ) {
                    Alpine.store(Const.state.selectedAssets).reset()
                }

                this.reloadNav()
            })
            .catch((response) => {
                showErrorSnackBar(
                    `Error moving  asset: ${response.status}, ${response.statusText}`,
                )
            })
    }

    purgeAssets({ assetIds }) {
        const payload = {
            assetIds: assetIds,
        }

        fetch(`/api/asset/r/${window.ctx.getRepoId()}/purge`, {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    showErrorSnackBar(`Error purging assets: ${response.statusText}`)
                    return
                }
                const successMessage = `${assetIds.length > 1 ? "Assets" : "Asset"} permanently deleted`
                showSuccessSnackBar(successMessage)

                this.removeAssetsFromGrid(assetIds)
                Alpine.store(Const.state.selectedAssets).reset()
                this.reloadNav()
            })
            .catch((response) => {
                showErrorSnackBar(
                    `Error purging assets: ${response.status}, ${response.statusText}`,
                )
            })
    }

    restoreAssets({ assetIds }) {
        const payload = {
            assetIds: assetIds,
        }

        fetch(`/api/asset/r/${window.ctx.getRepoId()}/restore`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    if (response.status === 409) {
                        showWarningSnackBar("Cannot restore: a non-recycled asset with the same content already exists")
                    } else {
                        showErrorSnackBar(`Error restoring assets: ${response.statusText}`)
                    }
                    return
                }
                const successMessage = `${assetIds.length > 1 ? "Assets" : "Asset"} restored`
                showSuccessSnackBar(successMessage)

                this.removeAssetsFromGrid(assetIds)
                Alpine.store(Const.state.selectedAssets).reset()
                this.reloadNav()
            })
            .catch((response) => {
                showErrorSnackBar(
                    `Error restoring assets: ${response.status}, ${response.statusText}`,
                )
            })
    }
}

const assetService = new AssetService()
export default assetService
