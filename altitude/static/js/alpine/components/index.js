import { Alpine } from "../../lib/alpine.esm.min.js"
import { contextMenu } from "./context-menu.js"
import { curation } from "./curation.js"
import { dateRange } from "./date-range.js"
import { editPanel } from "./edit-panel.js"
import { faceCombobox } from "./face-combobox.js"
import { filterCombobox } from "./filter-combobox.js"
import { initDateGroupSelectable } from "./date-group-selectable.js"
import { localTime } from "./local-time.js"
import { peopleTab } from "./people-tab.js"
import { searchPanel } from "./search-panel.js"
import { viewSettings } from "./view-settings.js"

// Referenced by name from Twirl templates
window.initDateGroupSelectable = initDateGroupSelectable

Alpine.data("contextMenu", contextMenu)

Alpine.data("localTime", localTime)

Alpine.data("viewSettings", viewSettings)

Alpine.data("peopleTab", peopleTab)

Alpine.data("curation", curation)

Alpine.data("faceCombobox", faceCombobox)

Alpine.data("editPanel", editPanel)

// The advanced search panel's, which the nav loads on every page; nav-page.js registers them
// for the pages outside the app
Alpine.data("searchPanel", searchPanel)
Alpine.data("filterCombobox", filterCombobox)
Alpine.data("dateRange", dateRange)
