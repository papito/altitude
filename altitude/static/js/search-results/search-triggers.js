import { Alpine } from "../lib/alpine.esm.min.js"
import { showWarningSnackBar } from "../common/snackbar.js"
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
 * The hydrator also owns the one rule on where a trigger may not search (`blockedSearchOf`):
 * Triage is not for finding results, so a trigger naming a folder, a Person or a Location warns
 * there instead of searching, and a trigger carrying a folder does nothing in the Trash. Every
 * explorer row reaches a search through a trigger, so the rule holds for all of them.
 */
const PREFIX = "appSearch"
const EVENT_KEY = "appSearch"
const BOUND_KEY = "appSearchBound"

// The parameters whose value names what the explorer lists: a folder, a Person or a Location
const EXPLORER_SCOPE_PARAMS = ["folderId", "personId", "locationId"]

const NO_SEARCH_IN_TRIAGE_MESSAGE =
    "You can manage items here but not perform search - use Browse for that"

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

            const blocked = blockedSearchOf(params)

            if (blocked) {
                event.stopPropagation()

                if (blocked.warns) {
                    showWarningSnackBar(NO_SEARCH_IN_TRIAGE_MESSAGE)
                }

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

/**
 * Whether the current view refuses the search `params` asks for: `null` when it may run, and
 * otherwise whether the refusal is explained to the user (`warns`).
 *
 * - The Trash spans every folder and has no explorer, so a trigger carrying a folder does nothing
 *   there, silently.
 * - Triage manages folders, People and Locations but does not search them: a trigger naming one
 *   warns. An empty value names nothing and passes, so the Results title's × still clears the
 *   scope of a Triage URL that carries one.
 */
function blockedSearchOf(params) {
    const currentView = Alpine.store(Const.state.currentView)

    if (currentView.isTrashBinView()) {
        return "folderId" in params ? { warns: false } : null
    }

    if (
        currentView.isTriageView() &&
        EXPLORER_SCOPE_PARAMS.some((param) => params[param])
    ) {
        return { warns: true }
    }

    return null
}
