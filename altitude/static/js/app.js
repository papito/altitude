import { Alpine } from "./lib/alpine.esm.min.js"
import { context } from "./context.js"
import { FrontendApp } from "./frontend-app.js"

// Templates reach Alpine and the context by name (`$store` bindings, `window.ctx.setRepoId`)
window.Alpine = Alpine
window.ctx = context

import "./alpine/components/index.js"

let frontendApp = null

export function initApp() {
    console.debug("Initializing...")

    if (!frontendApp) {
        frontendApp = new FrontendApp({ context })
    }

    return frontendApp.start()
}

export function getApp() {
    return frontendApp
}
