import { bindBatchOpsDragDrop } from "./batch-ops.js"
import { bindPeopleDragDrop } from "./people.js"
import { bindFolderDragDrop } from "./folders.js"

export function bindAppDragDrop(app) {
    bindBatchOpsDragDrop({ Alpine: app.Alpine })
    bindPeopleDragDrop({ dispatch: app.dispatch.bind(app) })
    bindFolderDragDrop({ dispatch: app.dispatch.bind(app) })
}
