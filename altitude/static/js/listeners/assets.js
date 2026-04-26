import { Const } from "../constants.js"

export function registerAssetListeners(app) {
    document.body.addEventListener(Const.events.assetMoved, (event) => {
        const assetId = event.detail.assetId
        const folderId = event.detail.folderId
        const selectedAssetsStore = app.Alpine.store(Const.state.selectedAssets)

        if (!selectedAssetsStore.isEmpty && selectedAssetsStore.contains(assetId)) {
            app.dispatch(Const.events.batchAssetsMoved, { folderId })
            return
        }

        app.moveAssets({ folderId, assetIds: [assetId] })
    })

    document.body.addEventListener(Const.events.batchAssetsMoved, (event) => {
        const folderId = event.detail.folderId
        const selectedAssetsStore = app.Alpine.store(Const.state.selectedAssets)

        app.moveAssets({
            folderId,
            assetIds: Array.from(selectedAssetsStore.items.keys()),
        })
    })

    document.body.addEventListener(Const.events.assetTrashed, (event) => {
        const assetId = event.detail.assetId
        const selectedAssetsStore = app.Alpine.store(Const.state.selectedAssets)

        if (!selectedAssetsStore.isEmpty && selectedAssetsStore.contains(assetId)) {
            app.dispatch(Const.events.batchAssetsRecycled)
            return
        }

        app.recycleAssets({ assetIds: [assetId] })
    })

    document.body.addEventListener(Const.events.batchAssetsRecycled, () => {
        const selectedAssetsStore = app.Alpine.store(Const.state.selectedAssets)

        app.recycleAssets({
            assetIds: Array.from(selectedAssetsStore.items.keys()),
        })
    })

    document.body.addEventListener(Const.events.batchAssetsPurged, () => {
        const selectedAssetsStore = app.Alpine.store(Const.state.selectedAssets)

        app.purgeAssets({
            assetIds: Array.from(selectedAssetsStore.items.keys()),
        })
    })

    document.body.addEventListener(Const.events.batchAssetsRestored, () => {
        const selectedAssetsStore = app.Alpine.store(Const.state.selectedAssets)

        app.restoreAssets({
            assetIds: Array.from(selectedAssetsStore.items.keys()),
        })
    })

    document.body.addEventListener(Const.events.deselectAll, () => {
        const selectedAssetsStore = app.Alpine.store(Const.state.selectedAssets)

        selectedAssetsStore.items.forEach((asset) => {
            asset.deselect()
        })
        selectedAssetsStore.items.clear()
    })
}

