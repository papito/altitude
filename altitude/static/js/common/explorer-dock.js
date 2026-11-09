/**
 * The explorer's Dock side on the main page (`views/index.scala.html`): where the explorer sits
 * against the results, left, right, top or bottom, and how much of the width or the height it
 * takes. Both are written on <html>, `data-dock-side` and `--explorer-share`, which
 * `static/css/pages/index.css` lays the page's grid out from; `static/js/explorer-dock-boot.js`
 * writes them first, before the first paint, from the same remembered state
 * (`Const.localStore.explorerDock`: `{ side?, widthShare, heightShare }`).
 *
 * Until this browser has a pick, the side follows the viewport's proportions, portrait at the
 * bottom and anything else on the left, and a rotation re-docks it; a pick holds from then on. A
 * pick comes from a `[data-app-dock-side]` control: the options of the Dock side menu of the
 * explorer's header (`#dockSideMenuHost`, a compact choice menu `common/context-menu-markup.js`
 * builds from the page's markup). Each side change, by a pick or by the viewport, is
 * `Const.events.dockSideChanged` on `document.body`, with the new side in `detail.side`.
 *
 * The mechanics, the remembered state, the menu's upkeep and the divider (`#explorerDivider`) with
 * its drag and keys, are `common/dock.js`'s; this module is the explorer's configuration of it.
 * The share the page is laid out with is bounded by the minimums `core.css` declares for the
 * explorer and the results, as they stand in the current viewport, and across the width the
 * explorer is never narrower than its header. The adapter `js/design-tweaks.js` passes
 * `explorerShare` and `setExplorerShare` to the design tweaks panel as an extra control, so the
 * size is explored like a token.
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

/** Lays the main page's dock on for `view` and binds its controls; the trash bin shows no explorer, so nothing */
export function initExplorerDock(view) {
    if (view === Const.views.trashbin) {
        return
    }

    dock.start()
    dock.layOut()
    dock.bindDivider(document.getElementById("explorerDivider"))
}

/** Docks the explorer on `side` from now on: remembered, laid out and announced; the side already picked is nothing */
export function setDockSide(side) {
    dock.setSide(side)
}

/** The side the explorer sits on, picked or by the viewport, as the page is laid out */
export function effectiveSide() {
    return dock.side()
}

/** Whether the page has a dock to size: not in the trash bin, and not on a page without the explorer */
export function hasExplorerDock() {
    return dock.isActive()
}

/** The explorer's share, in percent, of the width (a left or right dock) or of the height (top or bottom), as remembered */
export function explorerShare() {
    return dock.share()
}

/** Gives the explorer `percent` of its axis, within the bounds, and remembers it, as a drag would */
export function setExplorerShare(percent) {
    dock.setShare(percent)
}

/**
 * The least the explorer and the results may be squeezed to along the axis, in px: the `core.css`
 * tokens, registered as lengths so their computed values are in px, and across the width the
 * explorer's is its header's natural width when that is larger, as the page's grid lays it out
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

/**
 * The explorer header's natural width, the tabs and the Dock side control laid out in full: measured by sizing the
 * header to its min-content for the moment of the read, which costs a layout, as reading the tokens does
 */
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
