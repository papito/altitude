import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * The Add to location modal dialog (`htmx/add_to_location_dialog.scala.html`,
 * `data-app-dialog-kind="add-to-location"`): the server knows nothing of the selection, so the
 * form's hidden `assetIds` field is filled here from the `selectedAssets` store, as a
 * comma-separated list. A validation replacement re-renders the field with the submitted value,
 * which is kept: the modal traps focus, so the selection cannot have changed meanwhile.
 *
 * The success event's detail names the chosen Location - its ID, and its bare name from the
 * option's `data-name`, as a drop's report names it, rather than the option's `Category › Name`
 * label - so the listener can name it in the snackbar after the dialog is gone, whichever explorer
 * tab is displayed: the detail is read when the request is issued, so it is kept in step with the
 * select rather than written on submit. The server adds how many assets it `added`.
 */
export function hydrateAddToLocationFragment({ fragmentEl }) {
    // The field is named by `Api.Field.ASSET_IDS` server-side
    const assetIdsEl = fragmentEl.querySelector('input[name="assetIds"]')
    if (assetIdsEl && !assetIdsEl.value) {
        assetIdsEl.value = Alpine.store(Const.state.selectedAssets)
            .toArray()
            .join(",")
    }

    const selectEl = fragmentEl.querySelector("select")
    if (!selectEl) {
        return
    }

    const writeDetail = () => {
        fragmentEl.dataset.appSuccessDetail = JSON.stringify({
            locationId: selectEl.value,
            name: selectEl.selectedOptions[0]?.dataset.name ?? null,
        })
    }

    writeDetail()
    selectEl.addEventListener("change", writeDetail)
}
