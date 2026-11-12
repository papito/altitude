import { Alpine } from "./lib/alpine.esm.min.js"
import { importUploads } from "./alpine/components/import-uploads.js"
import { registerImportStatus } from "./import-status.js"
import { startNavPage } from "./nav-page.js"

/**
 * The Import page. It starts as every page with the nav outside the main app does (`startNavPage`:
 * Alpine for what `html_common` renders and for the nav's menus, the htmx request rule and the
 * dialog wiring), with its own component registered first: the upload sections' (`importUploads`,
 * whose uploads go through the axios client for their progress). Then it registers what keeps the
 * Import history live: the status feed's messages, the history's reads and the feed's end after
 * reconnects that keep failing (`import-status.js`).
 */
Alpine.data("importUploads", importUploads)

startNavPage()
registerImportStatus()
