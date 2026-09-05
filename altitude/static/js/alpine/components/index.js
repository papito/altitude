import { Alpine } from "../../lib/alpine.esm.min.js"
import { folderMenu } from "./folder-menu.js"
import { initSelectable } from "./selectable.js"

// Referenced by name from Twirl templates
window.initSelectable = initSelectable

// Referenced as `x-data="folderMenu"` by the folder tree renderer
Alpine.data("folderMenu", folderMenu)
