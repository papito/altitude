/**
 * Lays the main page out before its first paint: the Dock side and the explorer's share are written
 * on <html> from what this browser remembers, so the modules, which run later, never move the
 * explorer under the user. A plain blocking script, loaded by views/index.scala.html with the
 * storage key in its `data-key`; js/common/explorer-dock.js owns the same key from then on and
 * writes the two the same way.
 *
 * Nothing remembered, or nothing readable, docks the explorer by the viewport's proportions: a
 * portrait viewport puts it at the bottom, any other on the left. A plain script imports nothing,
 * so the sides and the two default shares below mirror `Const.dockSides` and the module's
 * `DEFAULT_WIDTH_SHARE` / `DEFAULT_HEIGHT_SHARE`.
 */
;(() => {
    "use strict"

    const SIDES = ["left", "right", "top", "bottom"]
    const WIDTH_SHARE = 25
    const HEIGHT_SHARE = 35

    let remembered = {}
    try {
        const key = document.currentScript?.dataset.key ?? "explorerDock"
        const parsed = JSON.parse(localStorage.getItem(key) ?? "null")
        if (typeof parsed === "object" && parsed !== null) {
            remembered = parsed
        }
    } catch {
        // Storage unavailable or unreadable: the defaults below
    }

    const side = SIDES.includes(remembered.side)
        ? remembered.side
        : window.matchMedia("(orientation: portrait)").matches
          ? "bottom"
          : "left"

    const vertical = side === "top" || side === "bottom"
    const rememberedShare = vertical
        ? remembered.heightShare
        : remembered.widthShare
    const share = Number.isFinite(rememberedShare)
        ? rememberedShare
        : vertical
          ? HEIGHT_SHARE
          : WIDTH_SHARE

    document.documentElement.dataset.dockSide = side
    document.documentElement.style.setProperty("--explorer-share", `${share}%`)
})()
