/**
 * A docked panel: where it sits against what it is docked to (its Dock side: left, right, top or
 * bottom), how much of the width or the height it takes (its share), the divider that drags and
 * steps that share, the Dock side menu that picks the side, and the state remembered per browser.
 * Two docks are made from it, the explorer's against the results (`common/explorer-dock.js`) and
 * the Detail panel's against Asset detail's media (`search-results/detail-panel.js`); each gives
 * the factory what differs:
 *
 * - `storage`: `{ key, field }`, the localStorage key holding `{ side?, widthShare, heightShare }`,
 *   the object itself or, with `field`, that field of it, the rest of the object being someone
 *   else's and left as it is. `side` is absent until a pick.
 * - `pickAttribute`: the data attribute of a pick, `data-app-dock-side`: a click on an element
 *   carrying it, anywhere on the page, docks the panel on the side it names. The options of a
 *   compact choice menu (`common/context-menu-markup.js`) carry it, and the factory keeps that
 *   menu true to the side: the current option is checked and the trigger shows its glyph and
 *   names the side.
 * - `layout`: `{ sideAttribute, shareProperty }`, written on the container: the side as an
 *   attribute and the share as a percentage custom property, which the stylesheet lays the grid
 *   out from.
 * - `container`: `() => Element | null`, the element laid out, or null while it is not on the page.
 * - `sizeEl`: `() => Element | null`, the element whose size along the axis the shares are
 *   percentages of.
 * - `divider`: `() => Element | null`, the separator.
 * - `defaults`: `{ side: () => string, widthShare, heightShare }`, the side until a pick and the
 *   share of each axis until a drag; the width share serves left and right, the height share top
 *   and bottom, so a switch within an axis keeps the size.
 * - `minimums`: `(vertical) => { panel, content }` in px, the least the panel and what it is
 *   docked to may be squeezed to along the axis, as they stand in the current viewport.
 * - `redockOnOrientationChange`: whether a change of the viewport's orientation lays the default
 *   side out again while nothing is picked, for a default that follows it.
 * - `orientsDivider`: whether the divider's orientation class (`vertical` for a left or right dock,
 *   `horizontal` for top or bottom) is written on each side change; the explorer's divider is laid
 *   out by `html[data-dock-side]` instead, before any script runs.
 * - `onSideChange`: `(side) => void`, after every side change, by a pick or by the viewport.
 *
 * The divider (a `role="separator"`) is dragged with the pointer and moved with the arrow keys
 * along its axis, 1 % a step (Shift, 5 %), Home and End take it to the bounds, and a double-click
 * or Enter returns the axis to its default share. The share the panel is laid out with is bounded
 * by the minimums as they stand in the current viewport, on every write and on `resize`; the
 * remembered share is the user's own, bounded when the user sets it, never by a visit in a
 * smaller window.
 */
import { Const } from "../constants.js"

const KEY_STEP = 1
const KEY_STEP_LARGE = 5

// The keys that move the divider along each side's axis: the one toward the panel's edge, which
// shrinks it, then the one away from it, which grows it
const AXIS_KEYS = {
    [Const.dockSides.left]: ["ArrowLeft", "ArrowRight"],
    [Const.dockSides.right]: ["ArrowRight", "ArrowLeft"],
    [Const.dockSides.top]: ["ArrowUp", "ArrowDown"],
    [Const.dockSides.bottom]: ["ArrowDown", "ArrowUp"],
}

/** Whether `side` divides the height (the divider runs across) rather than the width */
export function isVerticalAxis(side) {
    return side === Const.dockSides.top || side === Const.dockSides.bottom
}

export function createDock({
    storage,
    pickAttribute,
    layout,
    container,
    sizeEl,
    divider,
    defaults,
    minimums,
    redockOnOrientationChange = false,
    orientsDivider = false,
    onSideChange = () => {},
}) {
    // The remembered state; `side` is null until a pick
    const state = { side: null, widthShare: null, heightShare: null }

    // Whether the dock drives a panel: `start` was called
    let active = false

    // The dividers bound so far: a divider that comes with each fragment is bound once per fragment
    const boundDividers = new WeakSet()

    /** Reads the remembered state and binds the page-wide controls: the picks, the orientation, the window's size */
    function start() {
        Object.assign(state, rememberedState())
        active = true

        if (redockOnOrientationChange) {
            // A rotation re-docks the panel only while nothing is picked
            window
                .matchMedia("(orientation: portrait)")
                .addEventListener("change", () => {
                    if (state.side === null) {
                        applyEffectiveSide()
                        onSideChange(side())
                    }
                })
        }

        // The bounds are percentages of the size element, so a resized window moves them: the
        // laid-out share is bounded again, the remembered one left alone
        window.addEventListener("resize", () => applyShare())

        document.body.addEventListener("click", (event) => {
            const pickEl = event.target.closest?.(`[${pickAttribute}]`)
            if (pickEl) {
                setSide(pickEl.getAttribute(pickAttribute))
            }
        })
    }

    /** Lays the container out with the effective side and its axis's share, and shows the side on the menu */
    function layOut() {
        applyEffectiveSide()
    }

    /** Docks the panel on `side` from now on: remembered, laid out and announced; the side already picked is nothing */
    function setSide(newSide) {
        if (
            !Object.values(Const.dockSides).includes(newSide) ||
            newSide === state.side
        ) {
            return
        }

        state.side = newSide
        remember()
        applyEffectiveSide()
        onSideChange(side())
    }

    /** The side the panel sits on, picked or by default, as the container is laid out */
    function side() {
        return (
            container()?.getAttribute(layout.sideAttribute) ?? defaults.side()
        )
    }

    function isActive() {
        return active
    }

    /** The panel's share, in percent, of the width (a left or right dock) or of the height (top or bottom), as remembered */
    function share() {
        const vertical = isVerticalAxis(side())
        const remembered = vertical ? state.heightShare : state.widthShare
        return remembered ?? defaultShare(vertical)
    }

    /** Gives the panel `percent` of its axis, within the bounds, and remembers it, as a drag would */
    function setShare(percent) {
        if (!active) {
            return
        }

        const bounds = shareBounds()
        storeShare(clampShare(percent, bounds))
        applyShare(bounds)
        remember()
    }

    function defaultShare(vertical) {
        return vertical ? defaults.heightShare : defaults.widthShare
    }

    /** Keeps `value` as the current axis's share, in memory only */
    function storeShare(value) {
        if (isVerticalAxis(side())) {
            state.heightShare = value
        } else {
            state.widthShare = value
        }
    }

    /** Writes the picked side, else the default, on the container with that axis's share, orients the divider and shows it on the menu */
    function applyEffectiveSide() {
        const el = container()
        if (!el) {
            return
        }

        const effective = state.side ?? defaults.side()
        el.setAttribute(layout.sideAttribute, effective)

        if (orientsDivider) {
            const dividerEl = divider()
            const vertical = isVerticalAxis(effective)
            dividerEl?.classList.toggle("vertical", !vertical)
            dividerEl?.classList.toggle("horizontal", vertical)
        }

        applyShare()
        syncDockControl(effective)
    }

    /**
     * The Dock side menu follows the effective side: the option of `effective` is the checked one
     * and the compact trigger shows its glyph and names it, "Dock side: Left". The options are found
     * wherever they stand, in the host before the control is built or in its panel after.
     */
    function syncDockControl(effective) {
        let checkedEl = null
        document.querySelectorAll(`[${pickAttribute}]`).forEach((optionEl) => {
            const checked = optionEl.getAttribute(pickAttribute) === effective
            optionEl.setAttribute("aria-checked", String(checked))
            if (checked) {
                checkedEl = optionEl
            }
        })

        const hostEl = checkedEl?.closest("[data-choice-menu]")
        const triggerEl = hostEl?.querySelector('[aria-haspopup="menu"]')
        const glyphEl = checkedEl?.querySelector(".glyph")
        if (!triggerEl || !glyphEl) {
            return
        }

        triggerEl.replaceChildren(glyphEl.cloneNode(true))
        const label = `${hostEl.dataset.choiceMenu}: ${checkedEl.textContent.trim()}`
        triggerEl.setAttribute("aria-label", label)
        triggerEl.title = label
    }

    /**
     * Lays the container out with the current axis's share, within `bounds` (the current ones when
     * not given), and writes it on the separator; the remembered share is not touched
     */
    function applyShare(bounds = shareBounds()) {
        const el = container()
        if (!el) {
            return
        }

        const laidOut = clampShare(share(), bounds)
        el.style.setProperty(layout.shareProperty, `${laidOut}%`)
        writeDividerState(bounds, laidOut)
    }

    /**
     * The share's bounds on the current axis: the panel's minimum, and all but the content's
     * minimum and the divider, as percentages of the size element along the axis. Reading the
     * minimums forces a layout, so a drag takes the bounds once, when it starts.
     */
    function shareBounds() {
        const vertical = isVerticalAxis(side())
        const mainEl = sizeEl()
        const dividerEl = divider()
        const size = mainEl
            ? vertical
                ? mainEl.offsetHeight
                : mainEl.offsetWidth
            : 0

        if (!size) {
            return { min: 0, max: 100 }
        }

        const dividerSize = dividerEl
            ? vertical
                ? dividerEl.offsetHeight
                : dividerEl.offsetWidth
            : 0
        const { panel, content } = minimums(vertical)

        const min = (panel / size) * 100
        const max = ((size - dividerSize - content) / size) * 100

        // A viewport too small for both minimums: the panel keeps its own
        return { min, max: Math.max(min, max) }
    }

    function clampShare(percent, { min, max }) {
        const bounded = Math.min(max, Math.max(min, percent))
        return Math.round(bounded * 10) / 10
    }

    /**
     * Binds the divider: a pointer drag sets the share from the pointer's position in the size
     * element, measured from the edge the panel sits on, within the bounds taken as the drag
     * starts; the arrow keys along the axis move the divider the way they point, Home and End take
     * it to the bounds; Enter and a double-click reset the axis's default. The separator's state
     * follows. A divider already bound is left alone.
     */
    function bindDivider(dividerEl) {
        if (!dividerEl || boundDividers.has(dividerEl)) {
            return
        }
        boundDividers.add(dividerEl)

        // The bounds of the drag in progress, null between drags
        let dragBounds = null

        dividerEl.addEventListener("pointerdown", (event) => {
            if (event.button !== 0) {
                return
            }
            dragBounds = shareBounds()
            dividerEl.setPointerCapture(event.pointerId)
            dividerEl.dataset.dragging = ""
            // The pointer down focuses the separator by itself (it is in the tab order), so the
            // arrow keys work right after the drag; a focus the script gave would draw the focus
            // ring, which a pointer's focus does not
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
            const [towardPanel, awayFromPanel] = AXIS_KEYS[side()]
            const step = event.shiftKey ? KEY_STEP_LARGE : KEY_STEP

            switch (event.key) {
                case towardPanel:
                    setShare(share() - step)
                    break
                case awayFromPanel:
                    setShare(share() + step)
                    break
                case "Home":
                    setShare(0)
                    break
                case "End":
                    setShare(100)
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

    /** The share the pointer at `event` asks for: its distance from the panel's edge of the size element, in percent */
    function shareAtPointer(event) {
        const rect = sizeEl().getBoundingClientRect()

        switch (side()) {
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
        setShare(defaultShare(isVerticalAxis(side())))
    }

    /** The separator's orientation and value, for assistive technology: `laidOut` is the share laid out, within `bounds` */
    function writeDividerState({ min, max }, laidOut) {
        const dividerEl = divider()
        if (!dividerEl || !active) {
            return
        }

        // A separator's orientation is the divider's own: a left or right dock's runs top to bottom
        dividerEl.setAttribute(
            "aria-orientation",
            isVerticalAxis(side()) ? "horizontal" : "vertical",
        )
        dividerEl.setAttribute("aria-valuemin", String(Math.round(min)))
        dividerEl.setAttribute("aria-valuemax", String(Math.round(max)))
        dividerEl.setAttribute("aria-valuenow", String(Math.round(laidOut)))
    }

    /** The stored object under the key, or nothing where it is missing or unreadable */
    function storedObject() {
        let saved = null
        try {
            saved = JSON.parse(localStorage.getItem(storage.key) ?? "null")
        } catch {
            saved = null
        }
        return typeof saved === "object" && saved !== null ? saved : {}
    }

    /** The remembered dock state, or nothing where it is missing or unreadable; a share counts only as a finite number */
    function rememberedState() {
        const stored = storedObject()
        const saved = storage.field ? stored[storage.field] : stored
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

    /** Writes the dock state under the key, in its field when it has one, leaving the rest of the stored object as it is */
    function remember() {
        const saved = {
            widthShare: state.widthShare ?? defaults.widthShare,
            heightShare: state.heightShare ?? defaults.heightShare,
        }
        if (state.side !== null) {
            saved.side = state.side
        }

        const stored = storage.field
            ? { ...storedObject(), [storage.field]: saved }
            : saved
        localStorage.setItem(storage.key, JSON.stringify(stored))
    }

    return {
        start,
        layOut,
        bindDivider,
        setSide,
        side,
        share,
        setShare,
        isActive,
    }
}
