import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { createModalStore } from "../common/modal.js"
import { createSelectedAssetsStore } from "../search-results/selection.js"
import { createSearchParamsStore } from "./search-params.js"

export function initializeFrontendStores() {
    Alpine.store(Const.context.repoId, "")
    Alpine.store(Const.context.gridMetadataFields, new Set())

    // The selected asset IDs (search-results/selection.js)
    Alpine.store(Const.state.selectedAssets, createSelectedAssetsStore())

    Alpine.store(Const.state.resultsTotal, {
        count: 0,

        set(n) {
            this.count = n
        },

        decrement(n = 1) {
            this.count = Math.max(0, this.count - n)
        },
    })

    // The whole search parameter set; every search request is built from it (search-results/search.js)
    Alpine.store(Const.state.searchParams, createSearchParamsStore())

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
