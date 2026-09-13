import { bindAssetDragDrop } from "./assets.js"
import { bindBatchOpsDragDrop } from "./batch-ops.js"
import { bindPeopleDragDrop } from "./people.js"
import { bindFolderDragDrop } from "./folders.js"
import { bindAlbumDragDrop } from "./albums.js"

export function bindAppDragDrop(app) {
    // Recalculate dropzone rects on every dragmove instead of caching them at
    // drag start. Required for autoScroll to work correctly: without this,
    // elements that scroll into view during a drag are never recognised as
    // valid drop targets because interact.js is still using their stale
    // (pre-scroll) bounding rectangles.
    interact.dynamicDrop(true)

    const dispatch = app.dispatch.bind(app)

    bindAssetDragDrop({ dispatch })
    bindBatchOpsDragDrop()
    bindPeopleDragDrop({ dispatch })
    bindFolderDragDrop({ dispatch })
    bindAlbumDragDrop({ dispatch })
}
