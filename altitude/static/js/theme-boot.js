/**
 * Shows a page in the remembered theme before its first paint, so a page never flashes the stylesheet's
 * theme first. A plain blocking script, loaded by views/includes/header_common.scala.html with the storage
 * key in its `data-key`; js/common/theme.js owns the same key from then on and writes the two the same way.
 *
 * Nothing remembered, or nothing readable, leaves the stylesheet's theme.
 */
;(() => {
    "use strict"

    let theme = null
    try {
        theme = localStorage.getItem(
            document.currentScript?.dataset.key ?? "theme",
        )
    } catch {
        // Storage unavailable: the stylesheet's theme
    }

    if (theme === "light" || theme === "dark") {
        document.documentElement.style.colorScheme = theme
        document.documentElement.dataset.theme = theme
    }
})()
