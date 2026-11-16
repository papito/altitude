import { fillSelectionField } from "./selection-field.js"

/**
 * The Add to location dialog. The success detail names the chosen Location by its bare name (not
 * the option's `Category › Name` label) so the snackbar can name it after the dialog is gone. The
 * detail is read when the request is issued, so it follows the select rather than being written on
 * submit.
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
