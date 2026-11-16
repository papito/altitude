import { Alpine } from "./lib/alpine.esm.min.js"
import { importUploads } from "./alpine/components/import-uploads.js"
import { watchFolderForm } from "./alpine/components/watch-folder-form.js"
import { registerImportStatus } from "./import-status.js"
import { registerWatchFolders } from "./import/watch-folders.js"
import { startNavPage } from "./nav-page.js"

/**
 * The Import page: its components registered before `startNavPage()`, then what keeps the Import
 * history and the Watch folders' cards live.
 */
Alpine.data("importUploads", importUploads)
Alpine.data("watchFolderForm", watchFolderForm)

startNavPage()
registerImportStatus()
registerWatchFolders()
