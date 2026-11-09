import { Alpine } from "../../lib/alpine.esm.min.js"
import { contextMenu } from "./context-menu.js"
import { curation } from "./curation.js"
import { faceCombobox } from "./face-combobox.js"
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

// Referenced as `x-data="curation"` by htmx/curation.scala.html
Alpine.data("curation", curation)

// Referenced as `x-data="faceCombobox"` by htmx/detail_panel_people.scala.html
Alpine.data("faceCombobox", faceCombobox)
