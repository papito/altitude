import { fillSelectionField } from "./selection-field.js"

/**
 * The Add to location modal dialog (`htmx/add_to_location_dialog.scala.html`,
 * `data-app-dialog-kind="add-to-location"`): the form's hidden `assetIds` field carries the
 * selection (`fillSelectionField`).
 *
 * The success event's detail names the chosen Location - its ID, and its bare name from the
 * option's `data-name`, as a drop's report names it, rather than the option's `Category › Name`
 * label - so the listener can name it in the snackbar after the dialog is gone, whichever explorer
 * tab is displayed: the detail is read when the request is issued, so it is kept in step with the
 * select rather than written on submit. The server adds how many assets it `added`.
 */
export function hydrateAddToLocationFragment({ fragmentEl }) {
    fillSelectionField(fragmentEl)

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
