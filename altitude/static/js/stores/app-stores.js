import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { totalLabel } from "../common/total-label.js"
import { createSelectedAssetsStore } from "../search-results/selection.js"
import { createSearchParamsStore } from "./search-params.js"
import { createSequencesToCurateStore } from "./sequences-to-curate.js"
import { registerHtmlCommonBindings } from "./html-common.js"

export function initializeFrontendStores() {
    Alpine.store(Const.context.repoId, "")
    Alpine.store(Const.context.gridMetadataFields, new Set())

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

    // Every search request is built from it
    Alpine.store(Const.state.searchParams, createSearchParamsStore())

    Alpine.store(Const.state.sequencesToCurate, createSequencesToCurateStore())

    // Written once at page load from `searchParams.view`: the view changes only by navigating to a new page
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

    registerHtmlCommonBindings()
}
