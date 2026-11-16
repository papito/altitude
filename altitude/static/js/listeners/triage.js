import { Const } from "../constants.js"

/**
 * The Triage summary, rendered in Triage alone, loads once and is reloaded after any change it counts.
 * `peopleChanged` covers asset mutations, Person changes and Face corrections, any of which can make a
 * Person Unknown or named or take an asset out of triage.
 */
export function registerTriageListeners(app) {
    document.body.addEventListener(Const.events.peopleChanged, () => {
        app.reloadTriageSummary()
    })
}

// Whether a reload is already asked for in the running task
let reloadPending = false

/**
 * Does nothing outside Triage. One change asks several times in one task (`refreshCounts` reloads the
 * nav and the Location counts and announces `peopleChanged`), so the asks join one microtask: one
 * change, one request. The host's requests queue (htmx's default `hx-sync`), so a reload asked while
 * one is in flight reads the counts after it lands, and no response is swapped in after a newer one.
 */
export function reloadTriageSummary() {
    if (reloadPending || !document.getElementById("triageSummary")) {
        return
    }

    reloadPending = true

    queueMicrotask(() => {
        reloadPending = false

        const hostEl = document.getElementById("triageSummary")
        htmx.ajax("GET", hostEl.getAttribute("hx-get"), {
            source: hostEl,
            target: hostEl,
            swap: "innerHTML",
        })
    })
}
