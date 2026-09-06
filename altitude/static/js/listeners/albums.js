import { Const } from "../constants.js"
import {
    focusAddAlbumControlIfFocusLost,
    reloadAlbumList,
} from "../common/album-list.js"
import { showSuccessSnackBar } from "../common/snackbar.js"
import { runSearch } from "../search-results/search.js"

/**
 * Album events: the dialogs' outcomes (add, rename, delete reload the list) and membership changes
 * from drag/drop and the batch footer. A single-asset drop escalates to a batch when that asset is
 * among the selected ones, as folder moves do.
 */
export function registerAlbumListeners(app) {
    document.body.addEventListener(Const.events.albumAdded, async () => {
        await reloadAlbumList(app.context.getRepoId())
        // The first album hides the empty-state button that opened the dialog
        focusAddAlbumControlIfFocusLost()
    })

    document.body.addEventListener(Const.events.albumRenamed, async () => {
        await reloadAlbumList(app.context.getRepoId())
    })

    document.body.addEventListener(Const.events.albumDeleted, async (event) => {
        // Capture the name before the list is rebuilt
        const id = event.detail.id
        const name =
            document.getElementById(`albumName-${id}`)?.textContent ?? id

        await reloadAlbumList(app.context.getRepoId())
        // Deleting the last album hides the top button the dialog returned focus to
        focusAddAlbumControlIfFocusLost()
        showSuccessSnackBar(`Album "${name}" deleted`)

        // The displayed results belonged to the deleted album: go back to the whole repository
        if (app.context.getCurrentAlbumId() === id) {
            runSearch({ params: { albumId: null } })
        }
    })

    document.body.addEventListener(Const.events.assetAddedToAlbum, (event) => {
        const { albumId, assetId } = event.detail
        const selectedAssetsStore = app.Alpine.store(Const.state.selectedAssets)

        if (
            !selectedAssetsStore.isEmpty &&
            selectedAssetsStore.contains(assetId)
        ) {
            app.dispatch(Const.events.batchAssetsAddedToAlbum, { albumId })
            return
        }

        app.assetActions.addAssetsToAlbum({ albumId, assetIds: [assetId] })
    })

    document.body.addEventListener(
        Const.events.batchAssetsAddedToAlbum,
        (event) => {
            const selectedAssetsStore = app.Alpine.store(
                Const.state.selectedAssets,
            )

            app.assetActions.addAssetsToAlbum({
                albumId: event.detail.albumId,
                assetIds: Array.from(selectedAssetsStore.items.keys()),
            })
        },
    )

    document.body.addEventListener(
        Const.events.batchAssetsRemovedFromAlbum,
        () => {
            const selectedAssetsStore = app.Alpine.store(
                Const.state.selectedAssets,
            )

            app.assetActions.removeAssetsFromAlbum({
                albumId: app.context.getCurrentAlbumId(),
                assetIds: Array.from(selectedAssetsStore.items.keys()),
            })
        },
    )
}
