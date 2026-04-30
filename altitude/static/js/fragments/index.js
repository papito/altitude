import { findFragmentRoots } from "./helpers.js"
import { hydrateModalFragment } from "./modal.js"
import { hydrateImageDetailFragment } from "./image-detail.js"
import { hydratePersonNameEditorFragment } from "./person-name-editor.js"
import { hydrateSearchResultsFragment } from "./search-results.js"

export function hydrateAppFragments({ root, app }) {

    findFragmentRoots(root, "person-name-editor").forEach((fragmentEl) => {
        hydratePersonNameEditorFragment({ fragmentEl })
    })

    findFragmentRoots(root, "modal").forEach((fragmentEl) => {
        hydrateModalFragment({
            fragmentEl,
            context: app.context,
            dispatch: app.dispatch.bind(app),
            closeFolderContextMenu: app.closeFolderContextMenu.bind(app),
        })
    })

    findFragmentRoots(root, "image-detail").forEach((fragmentEl) => {
        hydrateImageDetailFragment({
            fragmentEl,
            Alpine: app.Alpine,
            dispatch: app.dispatch.bind(app),
        })
    })

    findFragmentRoots(root, "search-results").forEach((fragmentEl) => {
        hydrateSearchResultsFragment({ fragmentEl, app })
    })
}

