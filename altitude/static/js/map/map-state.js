import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * What the map view remembers between renders.
 *
 * The results fragment is re-swapped on every search, the map with it. A change of sort, a panel
 * opening or closing, a switch to the grid and back, or a reload of the page should not throw the
 * user's viewport away, so the last center and zoom are kept, keyed by the scope they were looked at
 * in: the repository, and the view, folder, person, album, Location and text of the search (the
 * parameters that change *what* is plotted). The sort, layout, grouping, paging and the map area
 * (`bbox`, the panel's scope, which the map ignores) do not key it. A scope not looked at yet starts
 * from the server's bounds.
 *
 * The views live in `sessionStorage`, so they survive a reload of the tab but a new tab or window
 * starts fitted to the results; the most recently looked-at scopes are kept, up to a bound. Every
 * move is remembered in memory at once, but written to storage only once moves pause (or the page is
 * left): Leaflet reports each frame of a resize as a move. Storage that cannot be read or written (a
 * privacy mode, a full quota) leaves the views in memory only.
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

const MAX_REMEMBERED_VIEWS = 50

const STORE_DELAY_MS = 500

// The last view per scope fingerprint, least recently looked at first; read from storage on first use
let rememberedViews = null

// The pending write of the views to storage, or null when storage is up to date
let storeTimer = null

// The live view of the map on screen, or null while no map is displayed
let displayedView = null

/** The fingerprint of the scope the search parameters now ask for, in this repository */
export function currentScopeFingerprint() {
    const params = Alpine.store(Const.state.searchParams)

    return JSON.stringify([
        window.ctx.getRepoId(),
        ...SCOPE_PARAMS.map((name) => params[name] ?? null),
    ])
}

export function rememberMapView(fingerprint, { center, zoom }) {
    const views = rememberedMapViews()

    // Re-inserted, so the scope looked at last is the last one evicted
    views.delete(fingerprint)
    views.set(fingerprint, { center, zoom })
    if (views.size > MAX_REMEMBERED_VIEWS) {
        views.delete(views.keys().next().value)
    }

    clearTimeout(storeTimer)
    storeTimer = setTimeout(storeMapViews, STORE_DELAY_MS)
}

// A reload or navigation before the pending write is due still keeps the last view
window.addEventListener("pagehide", () => {
    if (storeTimer !== null) {
        storeMapViews()
    }
})

function storeMapViews() {
    clearTimeout(storeTimer)
    storeTimer = null

    try {
        sessionStorage.setItem(
            Const.sessionStore.mapViews,
            JSON.stringify(Object.fromEntries(rememberedMapViews())),
        )
    } catch (error) {
        console.warn("Could not store the map views", error)
    }
}

/** `{ center: [lat, lng], zoom }` last looked at in the scope, or null */
export function rememberedMapView(fingerprint) {
    return rememberedMapViews().get(fingerprint) ?? null
}

/** Registers the view of the map on screen (`null` when it is removed) */
export function setDisplayedMapView(view) {
    displayedView = view
}

/** `{ center: [lat, lng], zoom }` of the map on screen, or null when none is displayed */
export function getDisplayedMapView() {
    return displayedView
}

function rememberedMapViews() {
    if (rememberedViews) {
        return rememberedViews
    }

    rememberedViews = new Map()

    try {
        const saved = JSON.parse(
            sessionStorage.getItem(Const.sessionStore.mapViews) || "{}",
        )
        Object.entries(saved)
            .filter(([, view]) => isMapView(view))
            .forEach(([fingerprint, view]) =>
                rememberedViews.set(fingerprint, view),
            )
    } catch (error) {
        console.warn("Could not read the remembered map views", error)
    }

    return rememberedViews
}

/** A stored value is only trusted as a view when Leaflet can be set to it */
function isMapView(view) {
    return (
        Array.isArray(view?.center) &&
        view.center.length === 2 &&
        view.center.every(Number.isFinite) &&
        Number.isFinite(view.zoom)
    )
}
