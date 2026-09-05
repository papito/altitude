import { registerFolderListeners } from "./folders.js"
import { registerPeopleListeners } from "./people.js"
import { registerAssetListeners } from "./assets.js"
import { registerHtmxAndSearchListeners } from "./htmx-search.js"
import { registerModalListeners } from "./modal.js"

export function registerAppEventListeners(app) {
    registerFolderListeners(app)
    registerPeopleListeners(app)
    registerAssetListeners(app)
    registerHtmxAndSearchListeners(app)
    registerModalListeners(app)
}
