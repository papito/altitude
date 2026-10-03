import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { createModalStore } from "../common/modal.js"
import { totalLabel } from "../common/total-label.js"
import { createSelectedAssetsStore } from "../search-results/selection.js"
import { createSearchParamsStore } from "./search-params.js"

export function initializeFrontendStores() {
    Alpine.store(Const.context.repoId, "")
    Alpine.store(Const.context.gridMetadataFields, new Set())

    // The selected asset IDs (search-results/selection.js)
    Alpine.store(Const.state.selectedAssets, createSelectedAssetsStore())

    // The results total in the toolbar; a capped one was counted only up to the server's cap
    Alpine.store(Const.state.resultsTotal, {
        count: 0,
        isCapped: false,

        set(n, isCapped = false) {
            this.count = n
            this.isCapped = isCapped
        },

        // A capped total says only that there are more than the cap, which removing assets does not change
        decrement(n = 1) {
            if (!this.isCapped) {
                this.count = Math.max(0, this.count - n)
            }
        },

        get label() {
            return totalLabel(this.count, this.isCapped)
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
