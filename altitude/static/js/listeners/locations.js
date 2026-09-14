import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import {
    expandLocationCategory,
    focusAddLocationControlIfFocusLost,
    reloadLocationList,
} from "../common/location-list.js"
import { showSuccessSnackBar } from "../common/snackbar.js"
import { runSearch } from "../search-results/search.js"

/**
 * Location events: the dialogs' outcomes (add, add category, rename, move and delete reload the
 * list; the Add to location dialog announces its memberships) and membership changes from
 * drag/drop and the batch footer. A single-asset drop escalates to a batch when that asset is
 * among the selected ones, as folder moves and album drops do. The reload restores the expanded
 * categories; an add also reveals the category the new Location went into, while a move leaves a
 * collapsed category collapsed, as moving a folder under a collapsed parent does.
 */
export function registerLocationListeners(app) {
    const selectedAssets = () => Alpine.store(Const.state.selectedAssets)
    const repoId = () => app.context.getRepoId()

    document.body.addEventListener(
        Const.events.locationAdded,
        async (event) => {
            await reloadLocationList(repoId())
            // The server names the category in the success detail (null at the top level)
            expandLocationCategory(event.detail?.categoryId)
            // The first Location hides the empty-state button that opened the dialog
            focusAddLocationControlIfFocusLost()
        },
    )

    document.body.addEventListener(Const.events.categoryAdded, async () => {
        await reloadLocationList(repoId())
    })

    document.body.addEventListener(Const.events.locationRenamed, async () => {
        await reloadLocationList(repoId())
    })

    document.body.addEventListener(Const.events.locationMoved, async () => {
        await reloadLocationList(repoId())
    })

    document.body.addEventListener(
        Const.events.locationDeleted,
        async (event) => {
            // Capture the name before the list is rebuilt
            const id = event.detail.id
            const name = locationName(id)

            await reloadLocationList(repoId())
            // Deleting the last row hides the top button the dialog returned focus to
            focusAddLocationControlIfFocusLost()
            showSuccessSnackBar(`"${name}" deleted`)

            // The displayed results belonged to the deleted Location: go back to the whole repository
            if (app.context.getCurrentLocationId() === id) {
                runSearch({ params: { locationId: null } })
            }
        },
    )

    document.body.addEventListener(
        Const.events.assetAddedToLocation,
        (event) => {
            const { locationId, assetId } = event.detail

            if (selectedAssets().contains(assetId)) {
                app.dispatch(Const.events.batchAssetsAddedToLocation, {
                    locationId,
                })
                return
            }

            app.assetActions.addAssetsToLocation({
                locationId,
                assetIds: [assetId],
            })
        },
    )

    document.body.addEventListener(
        Const.events.batchAssetsAddedToLocation,
        (event) => {
            app.assetActions.addAssetsToLocation({
                locationId: event.detail.locationId,
                assetIds: selectedAssets().toArray(),
            })
        },
    )

    document.body.addEventListener(
        Const.events.batchAssetsRemovedFromLocation,
        () => {
            app.assetActions.removeAssetsFromLocation({
                locationId: app.context.getCurrentLocationId(),
                assetIds: selectedAssets().toArray(),
            })
        },
    )

    // The footer's "Add to location" opens the dialog for the selection in the modal host; the
    // hidden selection field is filled when the dialog is hydrated (js/fragments/add-to-location.js)
    document.body.addEventListener(
        Const.events.batchAddToLocationRequested,
        () => {
            htmx.ajax(
                "GET",
                `/htmx/location/r/${repoId()}/dialogs/add-to-location`,
                { swap: "innerHTML", target: "#modalContent" },
            )
        },
    )

    // The dialog's success: the server added the selection to the chosen Location (already
    // present assets skipped), so the selection is done with and the counts move on
    document.body.addEventListener(
        Const.events.assetsAddedToLocation,
        (event) => {
            // `added` is the server's count (the dialog operation merges it into the detail); a
            // detail that did not arrive falls back to the selection the dialog submitted
            app.assetActions.reportAddedToLocation({
                ...event.detail,
                added: event.detail.added ?? selectedAssets().size,
            })
            selectedAssets().reset()
            app.reloadLocationCounts()
        },
    )
}

/** The rendered name of a row, when the Locations tab is displayed; the ID is the fallback */
function locationName(id) {
    return document.getElementById(`locationName-${id}`)?.textContent ?? id
}
