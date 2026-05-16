import { focusFragmentElement } from "./helpers.js"

export function hydratePersonNameEditorFragment({ fragmentEl }) {
    focusFragmentElement(
        fragmentEl,
        fragmentEl.dataset.appAutofocusSelector,
        fragmentEl.dataset.appSelectOnFocus === "true",
    )
}
