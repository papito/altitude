import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { literalsOf } from "../fragments/helpers.js"
import { runSearch } from "./search.js"

/**
 * Declarative search triggers. An element with `data-app-search="<DOM event>"` runs a search when that
 * event fires, contributing its `data-app-search-<param>` literals (an empty one clears the
 * parameter); the `searchParams` store supplies the rest, so markup never spells out a whole search.
 *
 * `routeOf` is the one rule on where a trigger's event goes in the current view:
 * - In the Trash, which has no explorer, a trigger carrying a folder does nothing.
 * - In Triage, a trigger naming a folder, a Person or a Location asks the Triage scope route instead.
 *   A folder holds no triaged asset, so it always gets the dialog, except the root folder, which does
 *   nothing. A Person or a Location with triaged assets is a 204 and then searches; one without them
 *   gets the dialog. An empty value names nothing and searches, so a scope can still be cleared.
 * - Everything else searches.
 */
const PREFIX = "appSearch"
const EVENT_KEY = "appSearch"
const BOUND_KEY = "appSearchBound"

// The parameters whose value names what the explorer lists: a folder, a Person or a Location
const EXPLORER_SCOPE_PARAMS = ["folderId", "personId", "locationId"]

// Where a trigger's event goes (`routeOf`), besides the scope dialog
const Route = {
    search: "search",
    nothing: "nothing",
}

// Attributes that configure the trigger rather than naming a search parameter
const RESERVED_KEYS = new Set([
    EVENT_KEY,
    BOUND_KEY,
    // The last cell's continuation cursor, read by infinite scroll
    "appSearchAfter",
])

/** Binds every search trigger in `root` and `root` itself; idempotent, so re-hydration is safe */
export function bindSearchTriggers(root) {
    triggerElements(root).forEach((triggerEl) => {
        if (triggerEl.dataset[BOUND_KEY] === "true") {
            return
        }

        triggerEl.dataset[BOUND_KEY] = "true"

        triggerEl.addEventListener(triggerEl.dataset[EVENT_KEY], (event) => {
            event.preventDefault()

            const params = searchParamsOf(triggerEl)
            const route = routeOf(params)

            if (route === Route.search) {
                runSearch({ params })
                return
            }

            event.stopPropagation()

            if (route !== Route.nothing) {
                requestScopeDialog(triggerEl, route.scope)
            }
        })
    })
}

/** Runs the search `triggerEl` declares, as its event would outside Triage: what a 204 from the scope route asks for */
export function runTriggerSearch(triggerEl) {
    if (!triggerEl?.dataset[EVENT_KEY]) {
        return
    }

    runSearch({ params: searchParamsOf(triggerEl) })
}

function triggerElements(root) {
    if (!(root instanceof Element || root instanceof Document)) {
        return []
    }

    const selector = "[data-app-search]"
    const elements = []

    if (root instanceof Element && root.matches(selector)) {
        elements.push(root)
    }

    elements.push(...root.querySelectorAll(selector))

    return elements
}

function searchParamsOf(triggerEl) {
    return literalsOf(triggerEl, PREFIX, RESERVED_KEYS)
}

/** `Route.search`, `Route.nothing`, or `{ scope }`, the one explorer scope to ask the Triage scope route about */
function routeOf(params) {
    const currentView = Alpine.store(Const.state.currentView)

    if (currentView.isTrashBinView()) {
        return "folderId" in params ? Route.nothing : Route.search
    }

    const scopeParam = EXPLORER_SCOPE_PARAMS.find((param) => params[param])

    if (!currentView.isTriageView() || !scopeParam) {
        return Route.search
    }

    const id = params[scopeParam]

    if (scopeParam === "folderId" && isRootFolder(id)) {
        return Route.nothing
    }

    return { scope: { [scopeParam]: id } }
}

/** The Results title has no crumb for the root folder, so the tree's root row is the one trigger naming it */
function isRootFolder(folderId) {
    return (
        document.getElementById(`folder-${folderId}`)?.dataset.isRoot === "true"
    )
}

/**
 * Asks the Triage scope route about `scope`, issued from `triggerEl` so the dialog opens against its
 * anchor and an open the user replaced or dismissed meanwhile neither shows nor searches.
 */
function requestScopeDialog(triggerEl, scope) {
    htmx.ajax("GET", `/htmx/triage/r/${window.ctx.getRepoId()}/dialogs/scope`, {
        source: triggerEl,
        target: "#popoverContent",
        swap: "innerHTML",
        values: scope,
    })
}
