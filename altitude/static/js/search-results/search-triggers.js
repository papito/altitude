import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { datasetKeySuffixToDetailKey } from "../fragments/helpers.js"
import { runSearch } from "./search.js"

/**
 * Declarative search triggers, hydrated centrally the same way `data-app-fragment` roots are.
 *
 * An element with `data-app-search="<DOM event>"` runs a search when that event fires. The
 * parameters it contributes are its `data-app-search-<param>="<value>"` literals, e.g.
 * `data-app-search-person-id="abc"`, or the sort and grouping an option of a choice menu carries;
 * an empty literal clears the parameter.
 *
 * Everything else - the folder you are in, the sort you picked - comes from the `searchParams`
 * store, so markup never has to spell out a whole search.
 *
 * The hydrator also owns the one rule on where a trigger's event goes in the current view
 * (`routeOf`), so it holds for every explorer row and for the Results title's crumbs alike:
 *
 * - In the Trash, which spans every folder and has no explorer, a trigger carrying a folder does
 *   nothing.
 * - In Triage, a trigger naming a folder, a Person or a Location asks the Triage scope route
 *   (`GET /htmx/triage/r/:repoId/dialogs/scope`) instead. A folder holds no triaged asset, so it is
 *   always described by the popover dialog the route answers, except the root folder, the whole
 *   repository, which does nothing. A Person or a Location with triaged assets is a 204, and the
 *   trigger then searches as anywhere else (`runTriggerSearch`, called by `listeners/dialogs.js`);
 *   one without them is the dialog. The request is an open request of the popover host like any
 *   other (`common/modal.js`): it is issued from the trigger, so the dialog is placed against the
 *   anchor the trigger names in `data-app-dialog-anchor`, and an open the user replaced or dismissed
 *   meanwhile neither shows its dialog nor searches. An empty value names nothing and searches, so
 *   the Results title's × and the Triage summary's total still clear a scope there.
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
    // The last cell's continuation, read by the infinite-scroll observer (js/search-results/infinite-scroll.js)
    "appSearchAfter",
])

/** Binds every search trigger in `root` (and `root` itself). Idempotent, so re-hydration is safe. */
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

/**
 * Runs the search `triggerEl` declares, as its event would have outside Triage: what a 204 from the
 * Triage scope route asks for. A source that is no search trigger is left alone.
 */
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

/** The `data-app-search-<param>` literals of `triggerEl`, keyed by parameter name */
function searchParamsOf(triggerEl) {
    const params = {}

    Object.entries(triggerEl.dataset).forEach(([key, value]) => {
        if (!key.startsWith(PREFIX) || RESERVED_KEYS.has(key)) {
            return
        }

        params[datasetKeySuffixToDetailKey(key.slice(PREFIX.length))] = value
    })

    return params
}

/**
 * Where the event of a trigger asking for `params` goes in the current view: `Route.search`,
 * `Route.nothing`, or `{ scope }`, the one explorer scope the Triage scope route is asked about
 * (`{ folderId }`, `{ personId }` or `{ locationId }`). See the module comment.
 */
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

/**
 * Whether `folderId` is the repository's root folder, the one tree node marked `data-is-root`. The
 * Results title has no crumb for the root, so the tree's root row is the one trigger naming it.
 */
function isRootFolder(folderId) {
    return (
        document.getElementById(`folder-${folderId}`)?.dataset.isRoot === "true"
    )
}

/**
 * Asks the Triage scope route about `scope` from `triggerEl`, into the popover host: a dialog opens
 * against the trigger's anchor, and a 204 searches (`runTriggerSearch`). A failure is reported as
 * any failed dialog open is.
 */
function requestScopeDialog(triggerEl, scope) {
    htmx.ajax("GET", `/htmx/triage/r/${window.ctx.getRepoId()}/dialogs/scope`, {
        source: triggerEl,
        target: "#popoverContent",
        swap: "innerHTML",
        values: scope,
    })
}
