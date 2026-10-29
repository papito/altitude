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
 */
const PREFIX = "appSearch"
const EVENT_KEY = "appSearch"
const BOUND_KEY = "appSearchBound"

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

            // Folder navigation is meaningless in triage and trash - those views span every folder
            if (isFolderNavigationBlocked(params)) {
                event.stopPropagation()
                return
            }

            runSearch({ params })
        })
    })
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

function isFolderNavigationBlocked(params) {
    if (!("folderId" in params)) {
        return false
    }

    const currentView = Alpine.store(Const.state.currentView)

    return currentView.isTriageView() || currentView.isTrashBinView()
}
