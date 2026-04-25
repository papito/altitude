import { Alpine } from "./lib/alpine.esm.min.js"
import { context } from "./context.js"
import { FrontendApp } from "./frontend-app.js"

window.Alpine = Alpine
window.ctx = context

import "./alpine/components/index.js"

let frontendApp = null

export function initApp() {
    console.debug("Initializing...")

    if (!frontendApp) {
        frontendApp = new FrontendApp({ Alpine, context })
    }

    return frontendApp.start()
}

export function getApp() {
    return frontendApp
}
