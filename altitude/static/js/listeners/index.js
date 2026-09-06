import { registerFolderListeners } from "./folders.js"
import { registerAlbumListeners } from "./albums.js"
import { registerPeopleListeners } from "./people.js"
import { registerAssetListeners } from "./assets.js"
import { registerHtmxAndSearchListeners } from "./htmx-search.js"
import { registerDialogListeners } from "./dialogs.js"

export function registerAppEventListeners(app) {
    registerFolderListeners(app)
    registerAlbumListeners(app)
    registerPeopleListeners(app)
    registerAssetListeners(app)
    registerHtmxAndSearchListeners(app)
    registerDialogListeners(app)
}
