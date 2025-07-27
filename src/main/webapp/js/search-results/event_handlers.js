import { Const } from "../constants.js"
import { Folder } from "../models/folder.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "../common/snackbar.js"

function removeAssetFromResultSetUtil(event, response, successMessage) {
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

document.body.addEventListener(Const.events.assetMoved, (event) => {
    const newParentFolderId = event.detail["folderId"]
    const newParentFolder = new Folder(newParentFolderId)

    function assetMovedHandler(response) {
        const successMessage = `Asset moved to folder "${newParentFolder.name()}"`
        removeAssetFromResultSetUtil(event, response, successMessage)
    }

    htmx.ajax("PUT", `/htmx/asset/r/${window.ctx.getRepoId()}/move`, {
        swap: "none",
        values: { ...event.detail },
        handler: assetMovedHandler,
    })
})

document.body.addEventListener(Const.events.assetTrashed, (event) => {
    function assetTrashedHandler(response) {
        const successMessage = "Asset moved to the trash bin"
        removeAssetFromResultSetUtil(event, response, successMessage)
    }

    htmx.ajax("DELETE", `/htmx/asset/r/${window.ctx.getRepoId()}/move`, {
        swap: "none",
        values: { ...event.detail },
        handler: assetTrashedHandler,
    })
})

document.body.addEventListener(Const.events.viewSettingChanged, (event) => {
    const fieldName = event.detail["fieldName"]
    const checked = event.detail["checked"]

    // show/hide this metadata field in the grid
    document
        .querySelectorAll(".metadata > div." + fieldName)
        .forEach((div) => (div.style.display = checked ? "block" : "none"))

    // show/hide the metadata container, depending on the number of fields selected
    const showFields = window.ctx.getGridMetadataFields()

    // No metadata fields selected? Hide the metadata container
    if (showFields.size === 0) {
        document
            .querySelectorAll("#assets .metadata")
            .forEach((div) => (div.style.display = "none"))
    }

    // If there is ONE metadata field selected, show the metadata container
    // (if there is more than one field selected, the metadata container is already shown)
    if (showFields.size === 1) {
        document
            .querySelectorAll("#assets .metadata")
            .forEach((div) => (div.style.display = "grid"))
    }
})

document.body.addEventListener(Const.events.deselectAll, () => {
    const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)
    selectedAssetsStore.items.forEach((asset) => {
        asset.deselect()
    })
    selectedAssetsStore.items.clear()
})

document.body.addEventListener(Const.events.toggleAsset, () => {
    console.log("toggleAsset event received")
})
