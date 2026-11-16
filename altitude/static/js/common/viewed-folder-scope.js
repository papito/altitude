/**
 * The viewed folder scope: the folder whose subtree the displayed results belong to, which the
 * folder tree colors. It follows the results actually displayed: it is set as a results fragment is
 * hydrated, from metadata the server puts on it, so a failed, cancelled or superseded navigation
 * keeps the previous scope, and the next results page (not a fragment) changes nothing. A tree
 * rebuild reapplies the latest scope, so a navigation that completed during a tree request wins.
 */
import { Const } from "../constants.js"

const SCOPE_MARKER = Const.attributes.viewedScope

let scope = { repoId: null, folderId: null }

/** Records the displayed results' scope (`folderId` null for none) and shows it in the tree */
export function setViewedFolderScope({ repoId, folderId }) {
    scope = { repoId, folderId: folderId || null }
    console.debug(`Viewed folder scope: ${scope.folderId ?? "none"}`)
    applyViewedFolderScope()
}

/**
 * Moves the marker to the viewed folder's node. Its descendants are DOM descendants, so one CSS rule
 * colors the whole subtree from this single marker, hidden or later-added folders included. Nothing
 * is marked for no folder scope, another repository, or a folder no longer in the tree (its parent
 * is not marked instead).
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

    const nodeEl = document.getElementById(`folder-${scope.folderId}`)

    // Root's scope is the whole repository: marking it would color the whole tree
    if (!nodeEl || nodeEl.dataset.isRoot === "true") {
        return
    }

    nodeEl.setAttribute(SCOPE_MARKER, "true")
}
