import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { Folder } from "../models/folder.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "../common/snackbar.js"
import { getHttpErrorMessage, http } from "../http/client.js"
import { cellsOf } from "../search-results/cells.js"
import { decrementDateGroupOf } from "../search-results/date-groups.js"

/** An asset whose content was imported again after it was recycled stays in the trash; the server names both */
export function reportRestore({ restored, duplicates }) {
    reportLeftInTrash({
        doneMessage: `${
            restored.length > 1 ? `${restored.length} assets` : "Asset"
        } restored`,
        doneCount: restored.length,
        duplicates,
        notDone: "not restored",
    })
}

/**
 * Wherever assets leave the trash (a restore, a move), those whose content is live again stay
 * there: a success with none, else a warning naming how many stayed, after what was done if
 * anything was
 */
function reportLeftInTrash({ doneMessage, doneCount, duplicates, notDone }) {
    if (duplicates.length === 0) {
        showSuccessSnackBar(doneMessage)
        return
    }

    const skippedMessage = `${duplicates.length} ${notDone}: an asset with the same content exists`
    showWarningSnackBar(
        doneCount > 0 ? `${doneMessage}; ${skippedMessage}` : skippedMessage,
    )
}

export function createAssetActions({
    context,
    reloadNav,
    reloadFolderCounts,
    reloadAlbumCounts,
    reloadLocationCounts,
}) {
    const selectedAssets = () => Alpine.store(Const.state.selectedAssets)

    // After every successful mutation. Album and Location counts too: recycling drops an asset from
    // its albums and Locations
    function refreshCounts() {
        reloadNav()
        reloadFolderCounts()
        reloadAlbumCounts()
        reloadLocationCounts()
        Alpine.store(Const.state.sequencesToCurate).refresh()
        document.body.dispatchEvent(new CustomEvent(Const.events.peopleChanged))
    }

    // An asset can have several cells (a Location grouping shows it under each of its Locations)
    function removeTriageStyling(assetIds) {
        for (const assetId of assetIds) {
            for (const cellEl of cellsOf(assetId)) {
                delete cellEl.dataset.isTriaged
                cellEl.querySelector(".triage-marker")?.remove()
            }
        }
    }

    /*
     * While a move is in flight the triage marker stays hidden (`.move-pending` in
     * search_results.css), so a successful move never repaints it between the drop and
     * `removeTriageStyling()` deleting it.
     */
    function setMovePending(assetIds, isPending) {
        for (const assetId of assetIds) {
            for (const cellEl of cellsOf(assetId)) {
                cellEl.classList.toggle("move-pending", isPending)
            }
        }
    }

    /**
     * The one place cells leave the grid. Each cell leaves its group's header count, but the footer
     * total counts assets, so it drops by one per asset that had a cell.
     */
    function removeAssetsFromGrid(assetIds) {
        let removedCount = 0

        for (const assetId of assetIds) {
            const cellEls = cellsOf(assetId)
            if (cellEls.length === 0) {
                continue
            }

            for (const cellEl of cellEls) {
                decrementDateGroupOf(cellEl)
                cellEl.remove()
            }
            removedCount++
        }

        if (removedCount > 0) {
            selectedAssets().noteGridChange()
            Alpine.store(Const.state.resultsTotal).decrement(removedCount)
        }
    }

    function shouldRemoveFromGrid(destinationFolderId) {
        // A move takes an asset out of Triage and out of the Trash alike
        const currentView = Alpine.store(Const.state.currentView)
        if (currentView.isTriageView() || currentView.isTrashBinView()) {
            return true
        }

        const viewedFolderId = context.getCurrentFolderId()
        if (!viewedFolderId) {
            return false
        }

        const destFolder = Folder.find(destinationFolderId)
        if (!destFolder) {
            console.debug(
                `Destination folder ${destinationFolderId} not in DOM, assuming outside viewed subtree`,
            )
            return true
        }

        return !destFolder.isDescendantOrSelf(viewedFolderId)
    }

    function shouldResetSelectedAssets(assetIds) {
        return (
            assetIds.length > 1 ||
            (assetIds.length === 1 && selectedAssets().contains(assetIds[0]))
        )
    }

    /**
     * A move out of the trash answers as a restore does (`{restored, duplicates}`): an asset whose
     * content was imported again after it was recycled stays in the trash, so its cell stays too,
     * and stays selected
     */
    async function moveAssets({ folderId, assetIds }) {
        const newParentFolderName = Folder.find(folderId)?.name() ?? folderId
        const payload = { assetIds, folderId }

        // In the drop's synchronous turn, before the drag handler drops `.dragging` off the cell,
        // so the marker never flashes back
        setMovePending(assetIds, true)

        try {
            const response = await http.put(
                `/api/asset/r/${context.getRepoId()}/move`,
                payload,
            )
            const { duplicates } = response.data
            const moved = assetIds.filter((id) => !duplicates.includes(id))

            reportLeftInTrash({
                doneMessage: `${
                    moved.length > 1 ? "Assets" : "Asset"
                } moved to folder "${newParentFolderName}"`,
                doneCount: moved.length,
                duplicates,
                notDone: "not moved",
            })

            removeTriageStyling(moved)

            if (shouldRemoveFromGrid(folderId)) {
                removeAssetsFromGrid(moved)
            }

            // The duplicates stay in the trash and stay selected, to be dealt with next
            if (duplicates.length > 0) {
                moved.forEach((id) => selectedAssets().deselect(id))
            } else if (shouldResetSelectedAssets(assetIds)) {
                selectedAssets().reset()
            }

            refreshCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error moving assets: ${getHttpErrorMessage(error)}`,
            )
        } finally {
            // On failure this brings the marker back, since the asset is still triaged
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
                selectedAssets().reset()
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
            selectedAssets().reset()
            refreshCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error purging assets: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    /**
     * An asset whose content was imported again after it was recycled stays in the trash, so only
     * the restored cells leave the grid.
     */
    async function restoreAssets({ assetIds }) {
        const payload = { assetIds }

        try {
            const response = await http.put(
                `/api/asset/r/${context.getRepoId()}/restore`,
                payload,
            )
            reportRestore(response.data)

            removeAssetsFromGrid(response.data.restored)
            selectedAssets().reset()
            refreshCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error restoring assets: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    function albumName(albumId) {
        return (
            document.getElementById(`albumName-${albumId}`)?.textContent ??
            "album"
        )
    }

    /**
     * The server skips assets already in the album, so nothing `added` is a warning. The dialog
     * passes `name`: the album's row is not rendered under another explorer tab, nor yet for an
     * album the dialog has just created.
     */
    function reportAddedToAlbum({ albumId, added, name }) {
        const label = name ?? albumName(albumId)

        if (added === 0) {
            showWarningSnackBar(`Already in album "${label}"`)
            return
        }

        showSuccessSnackBar(
            `${added > 1 ? `${added} assets` : "Asset"} added to album "${label}"`,
        )
    }

    /** Albums only point at assets, so the grid does not change */
    async function addAssetsToAlbum({ albumId, assetIds }) {
        const payload = { albumId, assetIds }

        try {
            const response = await http.put(
                `/api/album/r/${context.getRepoId()}/assets`,
                payload,
            )
            reportAddedToAlbum({ albumId, added: response.data.added })

            if (shouldResetSelectedAssets(assetIds)) {
                selectedAssets().reset()
            }

            reloadAlbumCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error adding assets to album: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    /** Removes the pointers only: the assets leave the displayed album results and nothing else */
    async function removeAssetsFromAlbum({ albumId, assetIds }) {
        const payload = { albumId, assetIds }

        try {
            await http.delete(`/api/album/r/${context.getRepoId()}/assets`, {
                data: payload,
            })

            showSuccessSnackBar(
                `${assetIds.length > 1 ? "Assets" : "Asset"} removed from album "${albumName(albumId)}"`,
            )

            removeAssetsFromGrid(assetIds)
            selectedAssets().reset()
            reloadAlbumCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error removing assets from album: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    function locationName(locationId) {
        return (
            document.getElementById(`locationName-${locationId}`)
                ?.textContent ?? "location"
        )
    }

    /** As `reportAddedToAlbum`; the dialog passes `name` from its select */
    function reportAddedToLocation({ locationId, added, name }) {
        const label = name ?? locationName(locationId)

        if (added === 0) {
            showWarningSnackBar(`Already in location "${label}"`)
            return
        }

        showSuccessSnackBar(
            `${added > 1 ? `${added} assets` : "Asset"} added to location "${label}"`,
        )
    }

    /** Locations only point at assets, so the grid does not change */
    async function addAssetsToLocation({ locationId, assetIds }) {
        const payload = { locationId, assetIds }

        try {
            const response = await http.put(
                `/api/location/r/${context.getRepoId()}/assets`,
                payload,
            )
            reportAddedToLocation({ locationId, added: response.data.added })

            if (shouldResetSelectedAssets(assetIds)) {
                selectedAssets().reset()
            }

            reloadLocationCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error adding assets to location: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    /** Removes the pointers only: the assets leave the displayed Location results and nothing else */
    async function removeAssetsFromLocation({ locationId, assetIds }) {
        const payload = { locationId, assetIds }

        try {
            await http.delete(`/api/location/r/${context.getRepoId()}/assets`, {
                data: payload,
            })

            showSuccessSnackBar(
                `${assetIds.length > 1 ? "Assets" : "Asset"} removed from location "${locationName(locationId)}"`,
            )

            removeAssetsFromGrid(assetIds)
            selectedAssets().reset()
            reloadLocationCounts()
        } catch (error) {
            showErrorSnackBar(
                `Error removing assets from location: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    return {
        refreshCounts,
        removeAssetsFromGrid,
        moveAssets,
        recycleAssets,
        purgeAssets,
        restoreAssets,
        addAssetsToAlbum,
        reportAddedToAlbum,
        removeAssetsFromAlbum,
        addAssetsToLocation,
        reportAddedToLocation,
        removeAssetsFromLocation,
    }
}
