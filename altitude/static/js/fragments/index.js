import { findFragmentRoots } from "./helpers.js"
import { hydrateModalFragment } from "./modal.js"
import { hydrateInlineDialogFragment } from "./inline-dialog.js"
import { hydrateLocationEditorFragment } from "./location-editor.js"
import { hydrateImageDetailFragment } from "./image-detail.js"
import { hydratePersonNameEditorFragment } from "./person-name-editor.js"
import { hydrateSearchResultsFragment } from "./search-results.js"
import { hydrateMapViewFragment } from "../map/map-view.js"
import {
    hydrateAlbumListFragment,
    hydrateFolderTreeFragment,
    hydrateLocationListFragment,
} from "./explorer.js"
import { bindDialogOpeners } from "./dialog-openers.js"
import { bindSearchTriggers } from "../search-results/search-triggers.js"

export function hydrateAppFragments({ root, app }) {
    findFragmentRoots(root, "person-name-editor").forEach((fragmentEl) => {
        hydratePersonNameEditorFragment({ fragmentEl })
    })

    findFragmentRoots(root, "modal").forEach((fragmentEl) => {
        hydrateModalFragment({ fragmentEl })
    })

    // After the modal hydrator: the editor's map is sized once its host is displayed
    findFragmentRoots(root, "location-editor").forEach((fragmentEl) => {
        hydrateLocationEditorFragment({ fragmentEl })
    })

    findFragmentRoots(root, "inline-dialog").forEach((fragmentEl) => {
        hydrateInlineDialogFragment({ fragmentEl, context: app.context })
    })

    findFragmentRoots(root, "image-detail").forEach((fragmentEl) => {
        hydrateImageDetailFragment({
            fragmentEl,
            coordinator: app.searchDetailCoordinator,
        })
    })

    findFragmentRoots(root, "search-results").forEach((fragmentEl) => {
        hydrateSearchResultsFragment({ fragmentEl })
    })

    // After the results hydrator, which disposed of the previous map with the previous fragment
    findFragmentRoots(root, "map-view").forEach((fragmentEl) => {
        hydrateMapViewFragment({ fragmentEl })
    })

    findFragmentRoots(root, "folder-tree").forEach(() => {
        hydrateFolderTreeFragment({ context: app.context })
    })

    findFragmentRoots(root, "album-list").forEach(() => {
        hydrateAlbumListFragment({ context: app.context })
    })

    findFragmentRoots(root, "location-list").forEach(() => {
        hydrateLocationListFragment({ context: app.context })
    })

    bindDialogOpeners({ root, hydrate: (el) => app.hydrateFragments(el) })
    bindSearchTriggers(root)
}
