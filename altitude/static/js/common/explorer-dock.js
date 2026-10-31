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
 * builds from the page's markup), which this module keeps true to the side: the current option is
 * checked and the trigger shows its glyph and names the side, as a server re-render would show a
 * toolbar menu's choice. Each side change, by a pick or by the viewport, is
 * `Const.events.dockSideChanged` on `document.body`, with the new side in `detail.side`.
 *
 * The divider (`#explorerDivider`, a `role="separator"`) is dragged with the pointer and moved with
 * the arrow keys along its axis; a double-click or Enter returns the axis to its default share. The
 * share the page is laid out with is bounded by the minimums `core.css` declares for the explorer
 * and the results, as they stand in the current viewport; the remembered share is the user's own,
 * bounded when the user sets it, never by a visit in a smaller window. The adapter
 * `js/design-tweaks.js` passes `explorerShare` and `setExplorerShare` to the design tweaks panel
 * as an extra control, so the size is explored like a token.
 */
import { Const } from "../constants.js"

// The default shares; static/js/explorer-dock-boot.js, which cannot import, repeats both
export const DEFAULT_WIDTH_SHARE = 25
export const DEFAULT_HEIGHT_SHARE = 35
const KEY_STEP = 1
const KEY_STEP_LARGE = 5

// The keys that move the divider along each side's axis: the one toward the explorer's edge,
// which shrinks it, then the one away from it, which grows it
const AXIS_KEYS = {
    [Const.dockSides.left]: ["ArrowLeft", "ArrowRight"],
    [Const.dockSides.right]: ["ArrowRight", "ArrowLeft"],
    [Const.dockSides.top]: ["ArrowUp", "ArrowDown"],
    [Const.dockSides.bottom]: ["ArrowDown", "ArrowUp"],
}

const html = document.documentElement
const portraitQuery = window.matchMedia("(orientation: portrait)")

// The remembered state; `side` is null until a pick
const state = { side: null, widthShare: null, heightShare: null }

// Whether the page has a dock to drive: not in the trash bin, and not on a page without the explorer
let active = false

/** Lays the main page's dock on for `view` and binds its controls; the trash bin shows no explorer, so nothing */
export function initExplorerDock(view) {
    if (view === Const.views.trashbin) {
        return
    }

    Object.assign(state, rememberedState())
    active = true

    applyEffectiveSide()

    // A rotation re-docks the explorer only while nothing is picked
    portraitQuery.addEventListener("change", () => {
        if (state.side === null) {
            applyEffectiveSide()
            announceSideChange()
        }
    })

    // The bounds are percentages of `main`'s size, so a resized window moves them: the laid-out
    // share is bounded again, the remembered one left alone
    window.addEventListener("resize", () => applyShare())

    document.body.addEventListener("click", (event) => {
        const pickEl = event.target.closest?.("[data-app-dock-side]")
        if (pickEl) {
            setDockSide(pickEl.dataset.appDockSide)
        }
    })

    bindDivider(document.getElementById("explorerDivider"))
}

/** Docks the explorer on `side` from now on: remembered, laid out and announced; the side already picked is nothing */
export function setDockSide(side) {
    if (!Object.values(Const.dockSides).includes(side) || side === state.side) {
        return
    }

    state.side = side
    remember()
    applyEffectiveSide()
    announceSideChange()
}

/** The side the explorer sits on, picked or by the viewport, as the page is laid out */
export function effectiveSide() {
    return html.dataset.dockSide ?? Const.dockSides.left
}

/** Whether `side` divides the height (the divider runs across) rather than the width */
export function isVerticalAxis(side) {
    return side === Const.dockSides.top || side === Const.dockSides.bottom
}

/** Whether the page has a dock to size: not in the trash bin, and not on a page without the explorer */
export function hasExplorerDock() {
    return active
}

/** The explorer's share, in percent, of the width (a left or right dock) or of the height (top or bottom), as remembered */
export function explorerShare() {
    const vertical = isVerticalAxis(effectiveSide())
    const share = vertical ? state.heightShare : state.widthShare
    return share ?? defaultShare(vertical)
}

/** Gives the explorer `percent` of its axis, within the bounds, and remembers it, as a drag would */
export function setExplorerShare(percent) {
    if (!active) {
        return
    }

    const bounds = shareBounds()
    storeShare(clampShare(percent, bounds))
    applyShare(bounds)
    remember()
}

function defaultShare(vertical) {
    return vertical ? DEFAULT_HEIGHT_SHARE : DEFAULT_WIDTH_SHARE
}

/** Keeps `share` as the current axis's, in memory only */
function storeShare(share) {
    if (isVerticalAxis(effectiveSide())) {
        state.heightShare = share
    } else {
        state.widthShare = share
    }
}

/** Writes the picked side, else the viewport's, on <html>, with that axis's share, and shows it on the menu */
function applyEffectiveSide() {
    const side =
        state.side ??
        (portraitQuery.matches ? Const.dockSides.bottom : Const.dockSides.left)
    html.dataset.dockSide = side
    applyShare()
    syncDockControl(side)
}

/**
 * The Dock side menu follows the effective side: the option of `side` is the checked one and the
 * compact trigger shows its glyph and names it, "Dock side: Left". The options are found wherever
 * they stand, in the host before the control is built or in its panel after.
 */
function syncDockControl(side) {
    let checkedEl = null
    document.querySelectorAll("[data-app-dock-side]").forEach((optionEl) => {
        const checked = optionEl.dataset.appDockSide === side
        optionEl.setAttribute("aria-checked", String(checked))
        if (checked) {
            checkedEl = optionEl
        }
    })

    const triggerEl = document.getElementById("dockSideMenuBtn")
    const glyphEl = checkedEl?.querySelector(".glyph")
    if (!triggerEl || !glyphEl) {
        return
    }

    triggerEl.replaceChildren(glyphEl.cloneNode(true))
    const name = triggerEl.closest("[data-choice-menu]")?.dataset.choiceMenu
    const label = `${name}: ${checkedEl.textContent.trim()}`
    triggerEl.setAttribute("aria-label", label)
    triggerEl.title = label
}

/**
 * Lays the page out with the current axis's share, within `bounds` (the current ones when not
 * given), and writes it on the separator; the remembered share is not touched
 */
function applyShare(bounds = shareBounds()) {
    const share = clampShare(explorerShare(), bounds)
    html.style.setProperty("--explorer-share", `${share}%`)
    writeDividerState(bounds, share)
}

function announceSideChange() {
    document.body.dispatchEvent(
        new CustomEvent(Const.events.dockSideChanged, {
            detail: { side: effectiveSide() },
        }),
    )
}

/**
 * The share's bounds on the current axis: the explorer's minimum, and all but the results'
 * minimum and the divider, as percentages of `main`'s size along the axis. The minimums are
 * `core.css` tokens registered as lengths, so their computed values are in px. Reading them
 * forces a layout, so a drag takes the bounds once, when it starts.
 */
function shareBounds() {
    const vertical = isVerticalAxis(effectiveSide())
    const mainEl = document.querySelector("main")
    const dividerEl = document.getElementById("explorerDivider")
    const size = vertical ? mainEl.offsetHeight : mainEl.offsetWidth
    const dividerSize = vertical
        ? dividerEl.offsetHeight
        : dividerEl.offsetWidth

    if (!size) {
        return { min: 0, max: 100 }
    }

    const styles = getComputedStyle(html)
    const explorerMin = parseFloat(
        styles.getPropertyValue(
            vertical ? "--explorer-min-height" : "--explorer-min-width",
        ),
    )
    const contentMin = parseFloat(
        styles.getPropertyValue(
            vertical ? "--content-min-height" : "--content-min-width",
        ),
    )

    const min = (explorerMin / size) * 100
    const max = ((size - dividerSize - contentMin) / size) * 100

    // A viewport too small for both minimums: the explorer keeps its own
    return { min, max: Math.max(min, max) }
}

function clampShare(percent, { min, max }) {
    const share = Math.min(max, Math.max(min, percent))
    return Math.round(share * 10) / 10
}

/**
 * The divider: a pointer drag sets the share from the pointer's position in `main`, measured from
 * the edge the explorer sits on, within the bounds taken as the drag starts; the arrow keys along
 * the axis move the divider the way they point, Home and End take it to the bounds; Enter and a
 * double-click reset the axis's default. The separator's state follows.
 */
function bindDivider(dividerEl) {
    if (!dividerEl) {
        return
    }

    // The bounds of the drag in progress, null between drags
    let dragBounds = null

    dividerEl.addEventListener("pointerdown", (event) => {
        if (event.button !== 0) {
            return
        }
        dragBounds = shareBounds()
        dividerEl.setPointerCapture(event.pointerId)
        dividerEl.dataset.dragging = ""
        // The pointer down focuses the separator by itself (it is in the tab order), so the arrow
        // keys work right after the drag; a focus the script gave would draw the focus ring,
        // which a pointer's focus does not
    })

    dividerEl.addEventListener("pointermove", (event) => {
        if (dragBounds === null) {
            return
        }
        storeShare(clampShare(shareAtPointer(event), dragBounds))
        applyShare(dragBounds)
    })

    const endDrag = () => {
        if (dragBounds === null) {
            return
        }
        dragBounds = null
        delete dividerEl.dataset.dragging
        remember()
    }
    dividerEl.addEventListener("pointerup", endDrag)
    dividerEl.addEventListener("pointercancel", endDrag)

    dividerEl.addEventListener("dblclick", resetShare)

    dividerEl.addEventListener("keydown", (event) => {
        const [towardExplorer, awayFromExplorer] = AXIS_KEYS[effectiveSide()]
        const step = event.shiftKey ? KEY_STEP_LARGE : KEY_STEP

        switch (event.key) {
            case towardExplorer:
                setExplorerShare(explorerShare() - step)
                break
            case awayFromExplorer:
                setExplorerShare(explorerShare() + step)
                break
            case "Home":
                setExplorerShare(0)
                break
            case "End":
                setExplorerShare(100)
                break
            case "Enter":
                resetShare()
                break
            default:
                return
        }

        event.preventDefault()
    })
}

/** The share the pointer at `event` asks for: its distance from the explorer's edge of `main`, in percent */
function shareAtPointer(event) {
    const rect = document.querySelector("main").getBoundingClientRect()

    switch (effectiveSide()) {
        case Const.dockSides.right:
            return ((rect.right - event.clientX) / rect.width) * 100
        case Const.dockSides.top:
            return ((event.clientY - rect.top) / rect.height) * 100
        case Const.dockSides.bottom:
            return ((rect.bottom - event.clientY) / rect.height) * 100
        default:
            return ((event.clientX - rect.left) / rect.width) * 100
    }
}

function resetShare() {
    setExplorerShare(defaultShare(isVerticalAxis(effectiveSide())))
}

/** The separator's orientation and value, for assistive technology: `share` is the one laid out, within `bounds` */
function writeDividerState({ min, max }, share) {
    const dividerEl = document.getElementById("explorerDivider")
    if (!dividerEl || !active) {
        return
    }

    // A separator's orientation is the divider's own: a left or right dock's runs top to bottom
    dividerEl.setAttribute(
        "aria-orientation",
        isVerticalAxis(effectiveSide()) ? "horizontal" : "vertical",
    )
    dividerEl.setAttribute("aria-valuemin", String(Math.round(min)))
    dividerEl.setAttribute("aria-valuemax", String(Math.round(max)))
    dividerEl.setAttribute("aria-valuenow", String(Math.round(share)))
}

/** The remembered state, or nothing where it is missing or unreadable; a share counts only as a finite number */
function rememberedState() {
    let saved = null
    try {
        saved = JSON.parse(
            localStorage.getItem(Const.localStore.explorerDock) ?? "null",
        )
    } catch {
        saved = null
    }
    if (typeof saved !== "object" || saved === null) {
        return {}
    }

    const read = {}
    if (Object.values(Const.dockSides).includes(saved.side)) {
        read.side = saved.side
    }
    if (Number.isFinite(saved.widthShare)) {
        read.widthShare = saved.widthShare
    }
    if (Number.isFinite(saved.heightShare)) {
        read.heightShare = saved.heightShare
    }
    return read
}

function remember() {
    const saved = {
        widthShare: state.widthShare ?? DEFAULT_WIDTH_SHARE,
        heightShare: state.heightShare ?? DEFAULT_HEIGHT_SHARE,
    }
    if (state.side !== null) {
        saved.side = state.side
    }
    localStorage.setItem(Const.localStore.explorerDock, JSON.stringify(saved))
}
