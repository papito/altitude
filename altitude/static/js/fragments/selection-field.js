import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * The batch dialogs (Add to album, Add to location) submit the selection the server knows nothing of in a hidden
 * `assetIds` field (named by `Api.Field.ASSET_IDS` server-side), filled here from the `selectedAssets` store as a
 * comma-separated list on the dialog's first render. A validation replacement re-renders the field with the submitted
 * value, which is kept: the modal traps focus, so the selection cannot have changed meanwhile.
 */
export function fillSelectionField(fragmentEl) {
    const assetIdsEl = fragmentEl.querySelector('input[name="assetIds"]')
    if (assetIdsEl && !assetIdsEl.value) {
        assetIdsEl.value = Alpine.store(Const.state.selectedAssets)
            .toArray()
            .join(",")
    }
}
