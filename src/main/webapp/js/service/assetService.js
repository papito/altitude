import { Folder } from "../models/folder.js"
import { showErrorSnackBar, showSuccessSnackBar, showWarningSnackBar } from "../common/snackbar.js"

class AssetService {
    moveAssetFromResultSetUtil(event, response, successMessage) {
        const status = response["htmx-internal-data"].xhr.status
        const assetId = event.detail["assetId"]

        if (status === 200) {
            // Remove the asset from the DOM
            htmx.find(`#asset-${assetId}`).remove()

            // Decrement the counter in the search control bar
            const resultsTotalElement = htmx.find("#searchControl .results-total")
            const currentTotal = parseInt(resultsTotalElement.textContent)
            resultsTotalElement.textContent = currentTotal - 1

            showSuccessSnackBar(successMessage)

            // reload the navigation bar - it is sensitive to changes, especially if the user is moving assets around
            htmx.ajax("GET", `/htmx/nav/r/${window.ctx.getRepoId()}`, {
                swap: "innerHTML",
                target: "nav",
            })
        } else if (status === 409) {
            const message = response["htmx-internal-data"].xhr.responseText
            showWarningSnackBar(message)
        } else {
            showErrorSnackBar(`Error performing operation on ${assetId}: ${status}`)
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

                // reset the selected assets store
                Alpine.store(Const.state.selectedAssets).reset()

                // removeAssetFromResultSetUtil(event, response, successMessage)
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
                const successMessage = `${assetIds.size > 0 ? "Assets" : "Asset"} moved to the trash bin"`
                showSuccessSnackBar(successMessage)

                // reset the selected assets store
                Alpine.store(Const.state.selectedAssets).reset()

                // removeAssetFromResultSetUtil(event, response, successMessage)
            })
            .catch((response) => {
                showErrorSnackBar(
                    `Error moving  asset: ${response.status}, ${response.statusText}`,
                )
            })
    }
}

const assetService = new AssetService()
export default assetService
