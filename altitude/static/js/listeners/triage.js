import { Const } from "../constants.js"

/**
 * The Triage summary (`#triageSummary` in the explorer's header, `index.scala.html`): how many assets are in triage, how
 * many have Unknown people and how many no Location points at. The page renders its host in Triage alone and the host
 * loads the summary once (`hx-trigger="load"`); a tab load leaves it alone, and this module reloads it after a change it
 * counts:
 *
 * - `peopleChanged`, announced by every asset mutation (`refreshCounts` in `assets/asset-actions.js`), every change to
 *   a Person (a merge, a hide, a show, a discard, a rename) and every Face correction, which can each make a Person
 *   Unknown or named, or take an asset out of triage;
 * - every reload of the nav and of the Location counts (`FrontendApp.reloadNav`, `reloadLocationCounts`), and the
 *   deletion of a Location (`listeners/locations.js`), which can leave its assets in no Location.
 */
export function registerTriageListeners(app) {
    document.body.addEventListener(Const.events.peopleChanged, () => {
        app.reloadTriageSummary()
    })
}

// Whether a reload is already asked for in the running task
let reloadPending = false

/**
 * Reloads the Triage summary, outside Triage (no host) doing nothing.
 *
 * One change reaches here several times in one task: `refreshCounts` reloads the nav and the Location counts and
 * announces `peopleChanged`, each of which asks. So the request waits for a microtask, after that task, and every ask
 * made meanwhile joins it: one change, one request. The request is issued from the host, whose requests take turns
 * (htmx's default `hx-sync`, `queue first`): a reload asked for while one is in flight is queued, issued when that one
 * has been swapped in, so it reads the counts as they are then, and a further ask joins the queued one. A response is
 * never swapped in after a newer one.
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
