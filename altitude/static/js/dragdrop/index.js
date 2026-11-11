import { bindAssetDragDrop } from "./assets.js"
import { bindBatchOpsDragDrop } from "./batch-ops.js"
import { bindPeopleDragDrop } from "./people.js"
import { bindFolderDragDrop } from "./folders.js"
import { bindAlbumDragDrop } from "./albums.js"
import { bindLocationDragDrop } from "./locations.js"
import { Const } from "../constants.js"
import { context } from "../context.js"

export function bindAppDragDrop(app) {
    // Recalculate dropzone rects on every dragmove instead of caching them at
    // drag start. Required for autoScroll to work correctly: without this,
    // elements that scroll into view during a drag are never recognised as
    // valid drop targets because interact.js is still using their stale
    // (pre-scroll) bounding rectangles.
    interact.dynamicDrop(true)

    const dispatch = app.dispatch.bind(app)

    // A drag is bound only where the account holds what its drop does: Organize for every move, add
    // to an album or a Location, folder move and People merge, and for the trash what recycling takes
    // in this view (Recycle in Browse, a Curate Permission in Triage; the server checks each asset)
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
