import { hydrateUploadFormFragments } from "./fragments/upload-form.js"
import { registerImportStatus } from "./import-status.js"
import { startNavPage } from "./nav-page.js"

/**
 * The Import page: its upload form, posted through axios for the upload's progress, and, as on every page with the nav
 * outside the main app (`startNavPage`), Alpine for what `html_common` renders and for the nav's Library menu, and the dialog
 * wiring of the New library dialog that menu opens; and the status feed's end after reconnects that
 * keep failing (`import-status.js`).
 */
startNavPage()
hydrateUploadFormFragments(document)
registerImportStatus()
