/**
 * Builds each server-rendered `[data-choice-menu]` host of a fragment into a trigger and a popover
 * panel. The nav's host is `hx-preserve`, so a nav reload keeps the built one; the guard keeps a
 * re-hydration of the same DOM from building twice.
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
