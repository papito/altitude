import { reloadAlbumList } from "../common/album-list.js"
import { reloadFolderTree } from "../common/folder-tree.js"

/**
 * The explorer tabs' list hosts: `data-app-fragment="folder-tree"` (`htmx/folders.scala.html`) and
 * `data-app-fragment="album-list"` (`htmx/albums.scala.html`) arrive empty and are rendered
 * client-side from the JSON endpoints as soon as the tab has settled.
 */
export function hydrateFolderTreeFragment({ context }) {
    reloadFolderTree(context.getRepoId())
}

export function hydrateAlbumListFragment({ context }) {
    reloadAlbumList(context.getRepoId())
}
