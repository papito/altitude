import { Alpine } from "./lib/alpine.esm.min.js"
import { Const } from "./constants.js"
import { context } from "./context.js"

window.Alpine = Alpine;
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
        }
    })


    Alpine.start();
}
