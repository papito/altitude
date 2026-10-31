import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import {
    focusAddAlbumControlIfFocusLost,
    reloadAlbumList,
} from "../common/album-list.js"
import { showSuccessSnackBar } from "../common/snackbar.js"
import { runSearch } from "../search-results/search.js"

/**
 * Album events: the dialogs' outcomes (add, rename, delete reload the list; the Add to album
 * dialog announces its memberships) and membership changes from drag/drop and the batch ops. A
 * single-asset drop escalates to a batch when that asset is among the selected ones, as folder
 * moves do.
 */
export function registerAlbumListeners(app) {
    const selectedAssets = () => Alpine.store(Const.state.selectedAssets)

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

        if (selectedAssets().contains(assetId)) {
            app.dispatch(Const.events.batchAssetsAddedToAlbum, { albumId })
            return
        }

        app.assetActions.addAssetsToAlbum({ albumId, assetIds: [assetId] })
    })

    document.body.addEventListener(
        Const.events.batchAssetsAddedToAlbum,
        (event) => {
            app.assetActions.addAssetsToAlbum({
                albumId: event.detail.albumId,
                assetIds: selectedAssets().toArray(),
            })
        },
    )

    document.body.addEventListener(
        Const.events.batchAssetsRemovedFromAlbum,
        () => {
            app.assetActions.removeAssetsFromAlbum({
                albumId: app.context.getCurrentAlbumId(),
                assetIds: selectedAssets().toArray(),
            })
        },
    )

    // The batch ops' "Add to album" opens the dialog for the selection in the modal host; the
    // hidden selection field is filled when the dialog is hydrated (js/fragments/add-to-album.js)
    document.body.addEventListener(
        Const.events.batchAddToAlbumRequested,
        () => {
            htmx.ajax(
                "GET",
                `/htmx/album/r/${app.context.getRepoId()}/dialogs/add-to-album`,
                { swap: "innerHTML", target: "#modalContent" },
            )
        },
    )

    // The dialog's success: the server added the selection to the chosen album, or to the album
    // it created (`created`), skipping assets already in it, so the selection is done with. A new
    // album needs its row, so the list is reloaded; otherwise only the counts move on
    document.body.addEventListener(Const.events.assetsAddedToAlbum, (event) => {
        // `added` is the server's count (the dialog operation merges it into the detail); a
        // detail that did not arrive falls back to the selection the dialog submitted
        app.assetActions.reportAddedToAlbum({
            ...event.detail,
            added: event.detail.added ?? selectedAssets().size,
        })
        selectedAssets().reset()

        if (event.detail.created) {
            reloadAlbumList(app.context.getRepoId())
        } else {
            app.reloadAlbumCounts()
        }
    })
}
