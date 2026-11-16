import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * What the map view remembers between renders. Each search re-swaps the map, so the last center and
 * zoom are kept per scope: the repository and the parameters that change what is plotted, not the
 * sort, layout, paging or `bbox`. A scope not looked at yet starts from the server's bounds. Views live
 * in `sessionStorage` (a reload keeps them, a new tab does not), the most recent ones up to a bound,
 * and are written only once moves pause or the page is left, since Leaflet reports each frame of a
 * resize as a move. Unusable storage leaves them in memory. The displayed map's live view is held here
 * too, for the Add location dialog's pin editor to open where the user is looking.
 */

const SCOPE_PARAMS = [
    "view",
    "folderId",
    "personId",
    "albumId",
    "locationId",
    "peopleMatch",
    "takenFrom",
    "takenTo",
    "importedFrom",
    "importedTo",
    "subset",
    "q",
]

const MAX_REMEMBERED_VIEWS = 50

const STORE_DELAY_MS = 500

// The last view per scope fingerprint, least recently looked at first; read from storage on first use
let rememberedViews = null

// The pending write, or null when storage is up to date
let storeTimer = null

let displayedView = null

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
