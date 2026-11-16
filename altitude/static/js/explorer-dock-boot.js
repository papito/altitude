/**
 * Writes the Dock side and the explorer's share on <html> before the first paint, from what this
 * browser remembers, so the modules, which run later, never move the explorer under the user. With
 * nothing remembered a portrait viewport docks it at the bottom, any other on the left.
 *
 * A plain blocking script imports nothing, so the sides and default shares mirror `Const.dockSides`
 * and `common/explorer-dock.js`'s `DEFAULT_WIDTH_SHARE` / `DEFAULT_HEIGHT_SHARE`.
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
