/**
 * A docked panel: its Dock side, its share of the width or height, the divider that drags and steps
 * that share, the Dock side menu, and the state remembered per browser. The explorer, the Detail
 * panel and the Edit panel (which sits opposite the Detail panel) are made from it. Options:
 *
 * - `storage`: `{ key, field? }`, the localStorage key of `{ side?, widthShare, heightShare }`, or
 *   of an object whose `field` holds it, the rest left alone. `side` is absent until a pick.
 * - `pickAttribute`: a click anywhere on an element carrying it docks the panel on the side it
 *   names; the factory keeps the compact Dock side menu's checked option and trigger in step.
 * - `followSide`: in place of `pickAttribute`, for a dock with no side of its own; no side is
 *   remembered, and the owner calls `layOut` when the followed side changes.
 * - `layout`: `{ sideAttribute, shareProperty }`, written on the container for the stylesheet.
 * - `container`, `sizeEl`, `divider`: element getters; the shares are percentages of `sizeEl`.
 * - `defaults`: `{ side, widthShare, heightShare }`; the width share serves left and right, the
 *   height share top and bottom, so a switch within an axis keeps the size.
 * - `minimums`: `(vertical) => { panel, content }` in px, as they stand in the current viewport.
 * - `redockOnOrientationChange`: a rotation lays the default side out again while nothing is picked.
 * - `orientsDivider`: writes the divider's orientation class on each side change; the explorer's is
 *   laid out by `html[data-dock-side]` instead, before any script runs.
 * - `onSideChange`: after every side change, by a pick or by the viewport.
 *
 * The divider drags, steps 1 % per arrow key (Shift, 5 %), goes to the bounds with Home and End, and
 * resets with Enter or a double-click. The laid-out share is bounded by the minimums on every write
 * and on `resize`; the remembered one only when the user sets it, never by a visit in a smaller window.
 */
import { Const } from "../constants.js"
import { readStoredObject, writeStoredFields } from "./stored-state.js"

const KEY_STEP = 1
const KEY_STEP_LARGE = 5

// Per side: the key toward the panel's edge, which shrinks it, then the one that grows it
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
    pickAttribute = null,
    followSide = null,
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
    // `side` is null until a pick
    const state = { side: null, widthShare: null, heightShare: null }

    let active = false

    // A divider that comes with each fragment is bound once per fragment
    const boundDividers = new WeakSet()

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

        // A resize moves the bounds: the laid-out share is bounded again, the remembered one left alone
        window.addEventListener("resize", () => applyShare())

        if (pickAttribute) {
            document.body.addEventListener("click", (event) => {
                const pickEl = event.target.closest?.(`[${pickAttribute}]`)
                if (pickEl) {
                    setSide(pickEl.getAttribute(pickAttribute))
                }
            })
        }
    }

    function layOut() {
        applyEffectiveSide()
    }

    function setSide(newSide) {
        if (
            followSide ||
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

    /** The effective side, as the container is laid out */
    function side() {
        return (
            container()?.getAttribute(layout.sideAttribute) ?? defaults.side()
        )
    }

    function isActive() {
        return active
    }

    /** The current axis's remembered share, in percent */
    function share() {
        const vertical = isVerticalAxis(side())
        const remembered = vertical ? state.heightShare : state.widthShare
        return remembered ?? defaultShare(vertical)
    }

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

    /** In memory only */
    function storeShare(value) {
        if (isVerticalAxis(side())) {
            state.heightShare = value
        } else {
            state.widthShare = value
        }
    }

    function applyEffectiveSide() {
        const el = container()
        if (!el) {
            return
        }

        const effective = followSide?.() ?? state.side ?? defaults.side()
        el.setAttribute(layout.sideAttribute, effective)

        if (orientsDivider) {
            const dividerEl = divider()
            const vertical = isVerticalAxis(effective)
            dividerEl?.classList.toggle("vertical", !vertical)
            dividerEl?.classList.toggle("horizontal", vertical)
        }

        applyShare()
        if (pickAttribute) {
            syncDockControl(effective)
        }
    }

    /** The options are found wherever they stand: in the host before the menu is built, or its panel after */
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

    /** The remembered share is not touched */
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
     * In percent of the size element: from the panel's minimum to all but the content's minimum and
     * the divider. Reading the minimums forces a layout, so a drag takes the bounds once, as it starts.
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

    function bindDivider(dividerEl) {
        if (!dividerEl || boundDividers.has(dividerEl)) {
            return
        }
        boundDividers.add(dividerEl)

        // Null between drags
        let dragBounds = null

        dividerEl.addEventListener("pointerdown", (event) => {
            if (event.button !== 0) {
                return
            }
            dragBounds = shareBounds()
            dividerEl.setPointerCapture(event.pointerId)
            dividerEl.dataset.dragging = ""
            // No `focus()`: the pointer down focuses the separator by itself, so the arrow keys work
            // after the drag, and a scripted focus would draw the focus ring
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

    /** The pointer's distance from the panel's edge of the size element, in percent */
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

    function writeDividerState({ min, max }, laidOut) {
        const dividerEl = divider()
        if (!dividerEl || !active) {
            return
        }

        // The divider's own orientation: a left or right dock's runs top to bottom
        dividerEl.setAttribute(
            "aria-orientation",
            isVerticalAxis(side()) ? "horizontal" : "vertical",
        )
        dividerEl.setAttribute("aria-valuemin", String(Math.round(min)))
        dividerEl.setAttribute("aria-valuemax", String(Math.round(max)))
        dividerEl.setAttribute("aria-valuenow", String(Math.round(laidOut)))
    }

    /** Keeps only a known side and finite shares */
    function rememberedState() {
        const stored = readStoredObject(storage.key)
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

    function remember() {
        const saved = {
            widthShare: state.widthShare ?? defaults.widthShare,
            heightShare: state.heightShare ?? defaults.heightShare,
        }
        if (state.side !== null) {
            saved.side = state.side
        }

        writeStoredFields(
            storage.key,
            storage.field ? { [storage.field]: saved } : saved,
        )
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
