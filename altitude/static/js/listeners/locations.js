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
 * A single-asset drop escalates to a batch when that asset is among the selected ones. A list reload
 * restores the expanded categories; an add also reveals the new Location's category, while a move
 * leaves a collapsed category collapsed.
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

            // Its assets may now be in no Location, one of the Triage summary's counts
            app.reloadTriageSummary()

            await reloadLocationList(repoId())
            // Deleting the last row hides the top button the dialog returned focus to
            focusAddLocationControlIfFocusLost()
            showSuccessSnackBar(`"${name}" deleted`)

            // The search goes on without the deleted Location, keeping its other filters
            const searchParams = Alpine.store(Const.state.searchParams)
            if (searchParams.names("locationId", id)) {
                searchParams.replaceId("locationId", id, null)
                runSearch()
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

    // The dialog's selection field is filled when it is hydrated (fragments/add-to-location.js)
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

    document.body.addEventListener(
        Const.events.assetsAddedToLocation,
        (event) => {
            // `added` is the server's count; without it, the selection the dialog submitted
            app.assetActions.reportAddedToLocation({
                ...event.detail,
                added: event.detail.added ?? selectedAssets().size,
            })
            selectedAssets().reset()
            app.reloadLocationCounts()
        },
    )
}

/** Falls back to the ID when the Locations tab is not shown */
function locationName(id) {
    return document.getElementById(`locationName-${id}`)?.textContent ?? id
}
