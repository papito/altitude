import { reloadAlbumList } from "../common/album-list.js"
import { reloadFolderTree } from "../common/folder-tree.js"
import { reloadLocationList } from "../common/location-list.js"

/** The explorer tabs' list hosts arrive empty and are rendered client-side from the JSON endpoints */
export function hydrateFolderTreeFragment({ context }) {
    reloadFolderTree(context.getRepoId())
}

export function hydrateAlbumListFragment({ context }) {
    reloadAlbumList(context.getRepoId())
}

export function hydrateLocationListFragment({ context }) {
    reloadLocationList(context.getRepoId())
}
