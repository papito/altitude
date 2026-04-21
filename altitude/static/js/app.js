import { Alpine } from "./lib/alpine.esm.min.js"
import { Const } from "./constants.js"
import { context } from "./context.js"

window.Alpine = Alpine
window.ctx = context

import "./alpine/components/index.js"

export function initApp() {
    /**
     * Initialize the Selectable engine - keeps track of selected assets
     * for batch operations like delete, move, etc.
     */
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

    /**
     * Reactive results-total counter. Seeded by the search_results template
     * on every new search/sort/page load; decremented/incremented by
     * assetService when assets are moved or recycled.
     */
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

    Alpine.store(Const.state.shadowResults, {
        items: [],
        page: 1,
        totalPages: 0,
        currentAssetId: null,

        reset(page, totalPages) {
            this.items = []
        },
        prepend(newItems) {
            this.items = newItems.concat(this.items)
        },
        append(newItems) {
            this.items = this.items.concat(newItems)
        },
    })

    Alpine.store(Const.state.currentView, {
        name: null,

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

    Alpine.start()
}

/**
 * Not used for now, but might be useful in the future.
 * Get us the Alpine proxy object for the given element.
 */
export function getAlpineProxyObj(el) {
    // https://github.com/alpinejs/alpine/discussions/2375
    return Alpine.mergeProxies(el._x_dataStack)
}
