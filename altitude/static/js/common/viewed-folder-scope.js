/**
 * The viewed folder scope: the folder whose assets, together with those of its whole subtree,
 * the displayed search results belong to. The folder tree colors that folder's icon and every
 * descendant's icon green (see the CSS in `views/htmx/folders.scala.html`).
 *
 * The scope follows the results that are actually displayed. `fragments/search-results.js` sets
 * it, when the results fragment is hydrated, from the metadata the server puts on the fragment
 * after combining the request with the browser URL. So a failed, cancelled, or superseded
 * navigation leaves the previous scope in place; sorting keeps it because the server carries the
 * folder over; and the next results page changes nothing because it is not a fragment. Expansion
 * never touches it. A tree rebuild reapplies the latest scope (`common/folder-tree.js`), so a
 * navigation that completed while a tree request was pending wins over the tree's older state.
 */
import { Const } from "../constants.js"

const SCOPE_MARKER = Const.attributes.viewedScope

let scope = { repoId: null, folderId: null }

/**
 * Records the scope of the displayed results and shows it in the tree. `folderId` is null when
 * the results have no folder scope.
 */
export function setViewedFolderScope({ repoId, folderId }) {
    scope = { repoId, folderId: folderId || null }
    console.debug(`Viewed folder scope: ${scope.folderId ?? "none"}`)
    applyViewedFolderScope()
}

/**
 * Clears the previous marker and marks the viewed folder's node with `alt-viewed-scope`. The
 * node's descendants are DOM descendants, so one CSS rule colors the whole subtree from this
 * single marker: root and leaves, hidden descendants without expanding them, and any folder later
 * added to or moved within the subtree by its new position. Ancestors and unrelated branches are
 * outside the node and keep their colors. Nothing is marked when the results have no folder
 * scope, belong to another repository, or name a folder no longer in the tree (its parent is not
 * selected in its place).
 */
export function applyViewedFolderScope() {
    const container = document.getElementById("rootFolderList")

    if (!container) {
        return
    }

    container
        .querySelectorAll(`[${SCOPE_MARKER}]`)
        .forEach((el) => el.removeAttribute(SCOPE_MARKER))

    if (!scope.folderId || scope.repoId !== window.ctx.getRepoId()) {
        return
    }

    document
        .getElementById(`folder-${scope.folderId}`)
        ?.setAttribute(SCOPE_MARKER, "true")
}
