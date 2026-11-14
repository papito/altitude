import { registerFolderListeners } from "./folders.js"
import { registerAlbumListeners } from "./albums.js"
import { registerLocationListeners } from "./locations.js"
import { registerPeopleListeners } from "./people.js"
import { registerAssetListeners } from "./assets.js"
import { registerCurationListeners } from "./curation.js"
import { registerTriageListeners } from "./triage.js"
import { registerSearchListeners } from "./search.js"
import { registerMapListeners } from "./map.js"
import { registerHtmxRequestListeners } from "./htmx-requests.js"
import { registerDialogListeners } from "./dialogs.js"
import { registerAccountListeners } from "./account.js"
import { registerEditListeners } from "./edit.js"

export function registerAppEventListeners(app) {
    registerFolderListeners(app)
    registerAlbumListeners(app)
    registerLocationListeners(app)
    registerPeopleListeners(app)
    registerAssetListeners(app)
    registerCurationListeners(app)
    registerTriageListeners(app)
    registerSearchListeners(app)
    registerMapListeners()
    registerHtmxRequestListeners(app)
    registerDialogListeners(app)
    registerAccountListeners()
    registerEditListeners(app)
}
