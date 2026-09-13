import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * Asset events: single-asset drops escalate to a batch when the asset is among the selected ones;
 * the batch events act on the whole selection; a purge of the recycle bin refreshes the counts.
 */
export function registerAssetListeners(app) {
    const selectedAssets = () => Alpine.store(Const.state.selectedAssets)

    document.body.addEventListener(Const.events.assetMoved, (event) => {
        const { assetId, folderId } = event.detail

        if (selectedAssets().contains(assetId)) {
            app.dispatch(Const.events.batchAssetsMoved, { folderId })
            return
        }

        app.assetActions.moveAssets({ folderId, assetIds: [assetId] })
    })

    document.body.addEventListener(Const.events.batchAssetsMoved, (event) => {
        app.assetActions.moveAssets({
            folderId: event.detail.folderId,
            assetIds: selectedAssets().toArray(),
        })
    })

    document.body.addEventListener(Const.events.assetTrashed, (event) => {
        const assetId = event.detail.assetId

        if (selectedAssets().contains(assetId)) {
            app.dispatch(Const.events.batchAssetsRecycled)
            return
        }

        app.assetActions.recycleAssets({ assetIds: [assetId] })
    })

    document.body.addEventListener(Const.events.batchAssetsRecycled, () => {
        app.assetActions.recycleAssets({
            assetIds: selectedAssets().toArray(),
        })
    })

    document.body.addEventListener(Const.events.batchAssetsPurged, () => {
        app.assetActions.purgeAssets({ assetIds: selectedAssets().toArray() })
    })

    document.body.addEventListener(Const.events.batchAssetsRestored, () => {
        app.assetActions.restoreAssets({
            assetIds: selectedAssets().toArray(),
        })
    })

    // Purging only touches recycled assets, which the counts already exclude, but every asset
    // mutation refreshes them so the rule has no exceptions to remember
    document.body.addEventListener(Const.events.trashPurged, () => {
        app.reloadNav()
        app.reloadFolderCounts()
        app.reloadAlbumCounts()
        app.reloadLocationCounts()
    })
}
