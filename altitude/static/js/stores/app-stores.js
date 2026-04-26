import { Const } from "../constants.js"

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
            document.body.dispatchEvent(new CustomEvent(Const.events.deselectAll))
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

    Alpine.store(Const.state.searchUrl, {
        url: null,

        set(url) {
            this.url = url
        },
    })

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
}

