/**
 * Applies the remembered theme before the first paint, so a page never flashes the stylesheet's theme. A plain
 * blocking script with the storage key in its `data-key`; js/common/theme.js owns the key from then on and writes
 * the two the same way.
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
