import { findFragmentRoots } from "./helpers.js"
import { hydrateModalFragment } from "./modal.js"
import { hydrateInlineDialogFragment } from "./inline-dialog.js"
import { hydrateImageDetailFragment } from "./image-detail.js"
import { hydratePersonNameEditorFragment } from "./person-name-editor.js"
import { hydrateSearchResultsFragment } from "./search-results.js"
import { bindSearchTriggers } from "../search-results/search-triggers.js"

export function hydrateAppFragments({ root, app }) {
    findFragmentRoots(root, "person-name-editor").forEach((fragmentEl) => {
        hydratePersonNameEditorFragment({ fragmentEl })
    })

    findFragmentRoots(root, "modal").forEach((fragmentEl) => {
        hydrateModalFragment({
            fragmentEl,
            context: app.context,
            dispatch: app.dispatch.bind(app),
        })
    })

    findFragmentRoots(root, "inline-dialog").forEach((fragmentEl) => {
        hydrateInlineDialogFragment({ fragmentEl })
    })

    findFragmentRoots(root, "image-detail").forEach((fragmentEl) => {
        hydrateImageDetailFragment({
            fragmentEl,
            coordinator: app.searchDetailCoordinator,
        })
    })

    findFragmentRoots(root, "search-results").forEach((fragmentEl) => {
        hydrateSearchResultsFragment({ fragmentEl, app })
    })

    bindSearchTriggers(root)
}
