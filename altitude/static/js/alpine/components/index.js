import { Alpine } from "../../lib/alpine.esm.min.js"
import { contextMenu } from "./context-menu.js"
import { initDateGroupSelectable } from "./date-group-selectable.js"

// Referenced by name from Twirl templates
window.initDateGroupSelectable = initDateGroupSelectable

// Referenced as `x-data="contextMenu"` by the folder tree and album list renderers
Alpine.data("contextMenu", contextMenu)
