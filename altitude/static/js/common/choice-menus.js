/**
 * Builds every choice menu of a fragment: a `[data-choice-menu]` host the server renders with its
 * option buttons (the grid toolbar's Group and Sort, `includes/search_results.scala.html`) becomes
 * a trigger and a popover panel through `buildChoiceMenuCtrl`. The results fragment is re-swapped
 * on every search, so each one arrives as a fresh host; the guard keeps a re-hydration of the same
 * DOM from building twice. Alpine's mutation observer initializes the new component, and the
 * options' search triggers are bound by the same hydration pass (`search-triggers.js`).
 */
import { buildChoiceMenuCtrl } from "./context-menu-markup.js"

export function ensureChoiceMenus(root) {
    if (!(root instanceof Element || root instanceof Document)) {
        return
    }

    root.querySelectorAll("[data-choice-menu]").forEach((hostEl) => {
        if (hostEl.querySelector(".choice-menu-ctrl")) {
            return
        }

        buildChoiceMenuCtrl(hostEl)
    })
}
