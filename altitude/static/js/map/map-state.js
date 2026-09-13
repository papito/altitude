import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * What the map view remembers between renders, in memory only.
 *
 * The results fragment is re-swapped on every search, the map with it. A change of sort, a panel
 * opening or closing, or a switch to the grid and back should not throw the user's viewport away,
 * so the last center and zoom are kept here, keyed by the scope they were looked at in: the view,
 * folder, person, album, Location and text of the search (the parameters that change *what* is
 * plotted). The sort, layout, grouping, paging and the map area (`bbox`, the panel's scope, which
 * the map ignores) do not key it. A new scope starts from the server's bounds again.
 *
 * The displayed map also registers its live view here, for the pin editor of the Add location
 * dialog to open where the user is looking (js/fragments/location-editor.js).
 */

const SCOPE_PARAMS = [
    "view",
    "folderId",
    "personId",
    "albumId",
    "locationId",
    "q",
]

// The last view per scope fingerprint
const rememberedViews = new Map()

// The live view of the map on screen, or null while no map is displayed
let displayedView = null

/** The fingerprint of the scope the search parameters now ask for */
export function currentScopeFingerprint() {
    const params = Alpine.store(Const.state.searchParams)

    return JSON.stringify(SCOPE_PARAMS.map((name) => params[name] ?? null))
}

export function rememberMapView(fingerprint, { center, zoom }) {
    rememberedViews.set(fingerprint, { center, zoom })
}

/** `{ center: [lat, lng], zoom }` last looked at in the scope, or null */
export function rememberedMapView(fingerprint) {
    return rememberedViews.get(fingerprint) ?? null
}

/** Registers the view of the map on screen (`null` when it is removed) */
export function setDisplayedMapView(view) {
    displayedView = view
}

/** `{ center: [lat, lng], zoom }` of the map on screen, or null when none is displayed */
export function getDisplayedMapView() {
    return displayedView
}
