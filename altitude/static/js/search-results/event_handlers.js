import { Const } from "../constants.js"

import assetService from "../service/assetService.js"

document.body.addEventListener(Const.events.assetMoved, (event) => {
    const assetId = event.detail["assetId"]
    const newParentFolderId = event.detail["folderId"]

    // if we dragged one asset, and there are multiple selected assets, we need to do a batch move
    if (
        !Alpine.store(Const.state.selectedAssets).isEmpty &&
        Alpine.store(Const.state.selectedAssets).contains(assetId)
    ) {
        const batchMovedEvent = new CustomEvent(Const.events.batchAssetsMoved, {
            detail: {
                folderId: newParentFolderId,
            },
        })

        document.body.dispatchEvent(batchMovedEvent)
        return
    }

    assetService.moveAssets({
        folderId: newParentFolderId,
        assetIds: [assetId],
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

document.body.addEventListener(Const.events.assetTrashed, (event) => {
    const assetId = event.detail["assetId"]

    // if we dragged one asset, and there are multiple selected assets, we need to do a batch move
    if (
        !Alpine.store(Const.state.selectedAssets).isEmpty &&
        Alpine.store(Const.state.selectedAssets).contains(assetId)
    ) {
        const batchMovedEvent = new CustomEvent(
            Const.events.batchAssetsRecycled,
        )
        document.body.dispatchEvent(batchMovedEvent)
        return
    }

    assetService.recycleAssets({ assetIds: [assetId] })
})

document.body.addEventListener(Const.events.batchAssetsRecycled, (event) => {
    const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)

    console.debug(`Batch recycling ${selectedAssetsStore.size} assets`)

    assetService.recycleAssets({
        assetIds: Array.from(selectedAssetsStore.items.keys()),
    })
})

document.body.addEventListener(Const.events.batchAssetsPurged, (event) => {
    const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)

    console.debug(`Batch purging ${selectedAssetsStore.size} assets`)

    assetService.purgeAssets({
        assetIds: Array.from(selectedAssetsStore.items.keys()),
    })
})

document.body.addEventListener(Const.events.batchAssetsRestored, (event) => {
    const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)

    console.debug(`Batch restoring ${selectedAssetsStore.size} assets`)

    assetService.restoreAssets({
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
