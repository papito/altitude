/**
 * The explorer's dock on the main page, written on <html> as `data-dock-side` and
 * `--explorer-share`; `explorer-dock-boot.js` writes them first, before the first paint, from the
 * same remembered state. Until a pick, the side follows the viewport (bottom in portrait, else
 * left) and a rotation re-docks it. Across the width the explorer is never narrower than its header.
 */
import { Const } from "../constants.js"
import { createDock, isVerticalAxis } from "./dock.js"

export { isVerticalAxis }

// The default shares; static/js/explorer-dock-boot.js, which cannot import, repeats both
export const DEFAULT_WIDTH_SHARE = 25
export const DEFAULT_HEIGHT_SHARE = 35

const html = document.documentElement
const portraitQuery = window.matchMedia("(orientation: portrait)")

const dock = createDock({
    storage: { key: Const.localStore.explorerDock },
    pickAttribute: Const.attributes.dockSide,
    layout: {
        sideAttribute: "data-dock-side",
        shareProperty: "--explorer-share",
    },
    container: () => html,
    sizeEl: () => document.querySelector("main"),
    divider: () => document.getElementById("explorerDivider"),
    defaults: {
        side: () =>
            portraitQuery.matches
                ? Const.dockSides.bottom
                : Const.dockSides.left,
        widthShare: DEFAULT_WIDTH_SHARE,
        heightShare: DEFAULT_HEIGHT_SHARE,
    },
    minimums: explorerMinimums,
    redockOnOrientationChange: true,
    onSideChange: (side) =>
        document.body.dispatchEvent(
            new CustomEvent(Const.events.dockSideChanged, { detail: { side } }),
        ),
})

/** The trash bin shows no explorer */
export function initExplorerDock(view) {
    if (view === Const.views.trashbin) {
        return
    }

    dock.start()
    dock.layOut()
    dock.bindDivider(document.getElementById("explorerDivider"))
}

export function setDockSide(side) {
    dock.setSide(side)
}

/** Picked or by the viewport */
export function effectiveSide() {
    return dock.side()
}

/** False in the trash bin and on a page without the explorer */
export function hasExplorerDock() {
    return dock.isActive()
}

export function explorerShare() {
    return dock.share()
}

export function setExplorerShare(percent) {
    dock.setShare(percent)
}

/**
 * The `core.css` tokens, registered as lengths so their computed values are in px; across the width,
 * at least the header's natural width
 */
function explorerMinimums(vertical) {
    const styles = getComputedStyle(html)
    const token = (name) => parseFloat(styles.getPropertyValue(name))

    return {
        panel: vertical
            ? token("--explorer-min-height")
            : Math.max(token("--explorer-min-width"), headerMinContentWidth()),
        content: token(
            vertical ? "--content-min-height" : "--content-min-width",
        ),
    }
}

/** Measured by sizing the header to min-content for the read, which costs a layout */
function headerMinContentWidth() {
    const headerEl = document.getElementById("explorerHeader")
    if (!headerEl) {
        return 0
    }

    const previous = headerEl.style.width
    headerEl.style.width = "min-content"
    const width = headerEl.getBoundingClientRect().width
    headerEl.style.width = previous
    return width
}
