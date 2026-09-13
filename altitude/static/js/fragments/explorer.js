import { reloadAlbumList } from "../common/album-list.js"
import { reloadFolderTree } from "../common/folder-tree.js"
import { reloadLocationList } from "../common/location-list.js"

/**
 * The explorer tabs' list hosts: `data-app-fragment="folder-tree"` (`htmx/folders.scala.html`),
 * `data-app-fragment="album-list"` (`htmx/albums.scala.html`) and
 * `data-app-fragment="location-list"` (`htmx/locations.scala.html`) arrive empty and are rendered
 * client-side from the JSON endpoints as soon as the tab has settled.
 */
export function hydrateFolderTreeFragment({ context }) {
    reloadFolderTree(context.getRepoId())
}

export function hydrateAlbumListFragment({ context }) {
    reloadAlbumList(context.getRepoId())
}

export function hydrateLocationListFragment({ context }) {
    reloadLocationList(context.getRepoId())
}
