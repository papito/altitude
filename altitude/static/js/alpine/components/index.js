import { Alpine } from "../../lib/alpine.esm.min.js"
import { contextMenu } from "./context-menu.js"
import { initDateGroupSelectable } from "./date-group-selectable.js"
import { peopleTab } from "./people-tab.js"
import { viewSettings } from "./view-settings.js"

// Referenced by name from Twirl templates
window.initDateGroupSelectable = initDateGroupSelectable

// Referenced as `x-data="contextMenu"` by the folder tree and album list renderers
Alpine.data("contextMenu", contextMenu)

// Referenced as `x-data="viewSettings"` by htmx/view_settings_dialog.scala.html
Alpine.data("viewSettings", viewSettings)

// Referenced as `x-data="peopleTab"` by htmx/people.scala.html
Alpine.data("peopleTab", peopleTab)
