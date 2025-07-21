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
    Alpine.store(Const.selectedAssets, {
        ids: new Set(),

        get isEmpty() {
            return this.ids.size === 0;
        },
    })

    Alpine.start();
}
