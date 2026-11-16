import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * Fills a batch dialog's hidden `assetIds` field from the selection on its first render. A validation re-render keeps
 * the submitted value: the modal traps focus, so the selection cannot have changed meanwhile.
 */
export function fillSelectionField(fragmentEl) {
    const assetIdsEl = fragmentEl.querySelector('input[name="assetIds"]')
    if (assetIdsEl && !assetIdsEl.value) {
        assetIdsEl.value = Alpine.store(Const.state.selectedAssets)
            .toArray()
            .join(",")
    }
}
