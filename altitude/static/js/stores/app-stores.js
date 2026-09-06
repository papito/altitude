import { Const } from "../constants.js"
import { createModalStore } from "../common/modal.js"
import { createSearchParamsStore } from "./search-params.js"

export function initializeFrontendStores({ Alpine }) {
    Alpine.store(Const.state.selectedAssets, {
        items: new Map(),

        get isEmpty() {
            return this.items.size === 0
        },

        get size() {
            return this.items.size
        },

        reset() {
            document.body.dispatchEvent(
                new CustomEvent(Const.events.deselectAll),
            )
        },

        contains(id) {
            return this.items.has(id)
        },
    })

    Alpine.store(Const.state.resultsTotal, {
        count: 0,

        set(n) {
            this.count = n
        },

        increment(n = 1) {
            this.count += n
        },

        decrement(n = 1) {
            this.count = Math.max(0, this.count - n)
        },
    })

    // The whole search parameter set; every search request is built from it (search-results/search.js)
    Alpine.store(Const.state.searchParams, createSearchParamsStore())

    Alpine.store(Const.state.shadowResults, {
        items: [],
        page: 1,
        totalPages: 0,
        currentAssetId: null,

        replace(items, page, totalPages) {
            this.items = items
            this.page = page
            this.totalPages = totalPages
        },

        reset(page = 1, totalPages = 0) {
            this.items = []
            this.page = page
            this.totalPages = totalPages
            this.currentAssetId = null
        },

        prepend(newItems) {
            this.items = newItems.concat(this.items)
        },

        append(newItems) {
            this.items = this.items.concat(newItems)
        },
    })

    /**
     * The view as a behaviour, for `x-show` / `:class` bindings. Derived from `searchParams.view`,
     * which is the parameter itself, and written once at page load - the view only ever changes by
     * navigating to a new page.
     */
    Alpine.store(Const.state.currentView, {
        view: null,

        setViewName(view) {
            this.view = view
        },

        isDefaultViewView() {
            return this.view === Const.views.repository
        },

        isTriageView() {
            return this.view === Const.views.triage
        },

        isTrashBinView() {
            return this.view === Const.views.trashbin
        },
    })

    Alpine.store(Const.state.imageDetailLoading, {
        value: false,
    })

    Alpine.store(Const.state.modal, createModalStore())
}
