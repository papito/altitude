import { Const } from "../constants.js"

import assetService from "../service/assetService.js"

document.body.addEventListener(Const.events.assetMoved, (event) => {
    const newParentFolderId = event.detail["folderId"]
    assetService.moveAssets({
        folderId: newParentFolderId,
        assetIds: [event.detail["assetId"]],
    })
})

document.body.addEventListener(Const.events.batchAssetsMoved, (event) => {
    const newParentFolderId = event.detail["folderId"]
    const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)

    console.debug(
        `Batch moving ${selectedAssetsStore.size} assets to folder ${newParentFolderId}`,
    )

    assetService.moveAssets({
        folderId: newParentFolderId,
        assetIds: Array.from(selectedAssetsStore.items.keys()),
    })
})

document.body.addEventListener(Const.events.batchAssetsRecycled, (event) => {
    const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)

    console.debug(
        `Batch recycling ${selectedAssetsStore.size}`,
    )

    assetService.recycleAssets({
        assetIds: Array.from(selectedAssetsStore.items.keys()),
    })
})

document.body.addEventListener(Const.events.assetTrashed, (event) => {
    assetService.recycleAssets({ assetIds: [event.detail["assetId"]] })
})

document.body.addEventListener(Const.events.batchAssetsTrashed, (event) => {
    const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)

    console.debug(`Batch recycling ${selectedAssetsStore.size} assets`)

    assetService.recycleAssets({
        assetIds: Array.from(selectedAssetsStore.items.keys()),
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
