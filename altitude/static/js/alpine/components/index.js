import { Alpine } from "../../lib/alpine.esm.min.js"
import { contextMenu } from "./context-menu.js"
import { curation } from "./curation.js"
import { dateRange } from "./date-range.js"
import { faceCombobox } from "./face-combobox.js"
import { filterCombobox } from "./filter-combobox.js"
import { initDateGroupSelectable } from "./date-group-selectable.js"
import { localTime } from "./local-time.js"
import { peopleTab } from "./people-tab.js"
import { searchPanel } from "./search-panel.js"
import { viewSettings } from "./view-settings.js"

// Referenced by name from Twirl templates
window.initDateGroupSelectable = initDateGroupSelectable

// Referenced as `x-data="contextMenu"` by the folder tree and album list renderers
Alpine.data("contextMenu", contextMenu)

// Referenced as `x-data="localTime"` by htmx/account_info_dialog.scala.html, which the nav's account trigger opens
Alpine.data("localTime", localTime)

// Referenced as `x-data="viewSettings"` by htmx/view_settings_dialog.scala.html
Alpine.data("viewSettings", viewSettings)

// Referenced as `x-data="peopleTab"` by htmx/people.scala.html
Alpine.data("peopleTab", peopleTab)

// Referenced as `x-data="curation"` by htmx/curation.scala.html
Alpine.data("curation", curation)

// Referenced as `x-data="faceCombobox"` by htmx/detail_panel_people.scala.html
Alpine.data("faceCombobox", faceCombobox)

// Referenced as `x-data="searchPanel"`, `x-data="filterCombobox"` and `x-data="dateRange"` by
// htmx/search_panel.scala.html, the advanced search panel's form and its fields, which the nav loads on every page
// (nav-page.js registers them too)
Alpine.data("searchPanel", searchPanel)
Alpine.data("filterCombobox", filterCombobox)
Alpine.data("dateRange", dateRange)
