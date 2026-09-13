import { registerFolderListeners } from "./folders.js"
import { registerAlbumListeners } from "./albums.js"
import { registerPeopleListeners } from "./people.js"
import { registerAssetListeners } from "./assets.js"
import { registerSearchListeners } from "./search.js"
import { registerHtmxRequestListeners } from "./htmx-requests.js"
import { registerDialogListeners } from "./dialogs.js"

export function registerAppEventListeners(app) {
    registerFolderListeners(app)
    registerAlbumListeners(app)
    registerPeopleListeners(app)
    registerAssetListeners(app)
    registerSearchListeners(app)
    registerHtmxRequestListeners(app)
    registerDialogListeners(app)
}
