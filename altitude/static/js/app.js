import { Alpine } from "./lib/alpine.esm.min.js"
import focus from "./lib/alpine-focus.esm.js"
import { context } from "./context.js"
import { FrontendApp } from "./frontend-app.js"

// `x-trap` for the modal hosts; plugins must be registered before Alpine starts
Alpine.plugin(focus)

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
