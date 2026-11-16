import { bindAssetDragDrop } from "./assets.js"
import { bindBatchOpsDragDrop } from "./batch-ops.js"
import { bindPeopleDragDrop } from "./people.js"
import { bindFolderDragDrop } from "./folders.js"
import { bindAlbumDragDrop } from "./albums.js"
import { bindLocationDragDrop } from "./locations.js"
import { Const } from "../constants.js"
import { context } from "../context.js"

export function bindAppDragDrop(app) {
    // Recompute dropzone rects on every move rather than at drag start: with autoScroll, a zone that
    // scrolls into view mid-drag would otherwise keep its stale rect and never take the drop
    interact.dynamicDrop(true)

    const dispatch = app.dispatch.bind(app)

    // A drag is bound only where the account may do what its drop does: Organize for moves, album and
    // Location adds and People merges; for the trash, recycling in this view (the server checks each asset)
    const organizes = context.holds(Const.permission.organize)
    const recycles = context.recyclesHere()

    if (organizes || recycles) {
        bindAssetDragDrop({ dispatch, toTrash: recycles })
    }
    if (organizes) {
        bindBatchOpsDragDrop()
        bindPeopleDragDrop({ dispatch })
        bindFolderDragDrop({ dispatch })
        bindAlbumDragDrop({ dispatch })
        bindLocationDragDrop({ dispatch })
    }
}
