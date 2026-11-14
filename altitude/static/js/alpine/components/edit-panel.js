import { channelPaths, percentLabel } from "../../common/histogram.js"
import { Const } from "../../constants.js"
import {
    addCurvePoint,
    aspectLabel,
    isOffCurveBox,
    isRemovableCurvePoint,
    moveCurvePoint,
    removeCurvePoint,
    withCurve,
} from "../../edit/adjust.js"
import { connectEditPanelView } from "../../edit/edit-panel.js"
import { editedSize, frameSize, isPortrait } from "../../edit/geometry.js"
import {
    ASPECTS,
    defaults,
    formatSlider,
    isIdentityCurve,
    SECTIONS,
    sectionChanged,
    SLIDERS,
} from "../../edit/recipe.js"
import { curveLut } from "../../edit/tone.js"

/**
 * The Edit panel's controls (`includes/edit_panel`, `x-data="editPanel"`): the tabs, the sliders, the aspect chips, the
 * curve editor, the section resets, Undo and Redo, and the Histogram and its Clipping blink. The Edit, its session and
 * the stage live in the coordinator (`edit/edit-panel.js`), which this component connects to as it starts: the
 * coordinator syncs a snapshot into it (`sync`), and the component asks it for every change, worked out from the Edit
 * shown (`edit/adjust.js`). The component's state is what its bindings read: a copy of the Edit shown, the save status,
 * whether there is anything to undo or redo, the Histogram's paths and the tools' states.
 *
 * A slider counts as one change on release, or on Enter in its number field (a value left unentered goes back as the
 * field loses focus or on Escape); a double-click on its label or its track resets it. A curve point counts as one
 * change on release; a click adds one, a drag moves it, a drag well off the box or a double-click removes it.
 */

// The tab shown, kept for the page's life so each asset's panel opens on the last one
let shownTab = SECTIONS[0].id

// How near a point of the curve a press is to take it, in CSS pixels
const CURVE_HIT_RADIUS = 12
// The curve's points, drawn as dots of this radius, in CSS pixels
const CURVE_POINT_RADIUS = 4.5

const STATUS_TEXT = {
    idle: "",
    saving: "Saving…",
    saved: "Saved · the original is untouched",
    failed: "Could not save",
    conflict: "Changed elsewhere · showing its latest Edit",
}

// The keys that step a range (`input[type=range]`), whose changes are saved once the key rests (`commitSoon`)
const RANGE_KEYS = [
    "ArrowLeft",
    "ArrowRight",
    "ArrowUp",
    "ArrowDown",
    "PageUp",
    "PageDown",
    "Home",
    "End",
]

// A tone curve point moved with the arrows: a step of x or y, and with Shift
const CURVE_KEY_STEP = 0.01
const CURVE_KEY_STEP_LARGE = 0.05

export function editPanel() {
    // The coordinator's actions, out of the reactive object
    let panel = null
    // The last Histogram's luminance bins, which the curve's backdrop draws, and the curve's drag in progress
    let luminanceBins = null
    let curveDrag = null
    let themeListener = null
    // Paints the curve as its canvas gets a size: Alpine shows a tab on the next frame, after the change that shows it
    let curveObserver = null
    // The last Histogram synced, so a sync that brings the same one redraws nothing
    let lastHistogram = null
    // A slider's drag (`dragOf`: each value from the Edit it started on) and how it is being moved: "pointer" or "key"
    let slideDrag = null
    let slideVia = "pointer"
    let slideKeyTimer = 0

    return {
        ready: false,
        refusal: "",
        recipe: defaults(),
        upright: null,
        status: "idle",
        canUndo: false,
        canRedo: false,
        cropToolOpen: false,
        levelOn: false,
        splitOn: false,
        clippingBlink: false,
        autoBusy: false,
        histogram: null,
        crushedLabel: "",
        blownLabel: "",
        // Bumped as the curve's backdrop changes, so the curve is drawn again
        histogramVersion: 0,
        // The curve point the keys act on, and whether the curve editor has focus, which rings that point
        curveIndex: 0,
        curveFocused: false,
        tab: shownTab,
        sections: SECTIONS.map(({ id, label }) => ({ id, label })),
        aspects: ASPECTS,

        init() {
            panel = connectEditPanelView(this)
            this.$nextTick(() => {
                const canvas = this.$refs.curve
                if (canvas && "ResizeObserver" in window) {
                    curveObserver = new ResizeObserver(() =>
                        this.paintCurve(this.recipe.curve),
                    )
                    curveObserver.observe(canvas)
                }
            })
            themeListener = () => this.histogramVersion++
            document.body.addEventListener(
                Const.events.themeChanged,
                themeListener,
            )
        },

        destroy() {
            panel?.disconnect()
            panel = null
            curveObserver?.disconnect()
            document.body.removeEventListener(
                Const.events.themeChanged,
                themeListener,
            )
        },

        /** The coordinator's snapshot: each field written only when it changed, so a field being typed in keeps its text */
        sync(snapshot) {
            const same = (a, b) => JSON.stringify(a) === JSON.stringify(b)
            for (const key of [
                "ready",
                "refusal",
                "status",
                "canUndo",
                "canRedo",
                "cropToolOpen",
                "levelOn",
                "splitOn",
                "clippingBlink",
                "autoBusy",
            ]) {
                if (this[key] !== snapshot[key]) {
                    this[key] = snapshot[key]
                }
            }
            if (snapshot.recipe && !same(this.recipe, snapshot.recipe)) {
                this.recipe = snapshot.recipe
            }
            if (!same(this.upright, snapshot.upright)) {
                this.upright = snapshot.upright
            }
            if (snapshot.histogram !== lastHistogram) {
                lastHistogram = snapshot.histogram
                luminanceBins = snapshot.histogram?.luminance ?? null
                this.histogram = snapshot.histogram
                    ? channelPaths(snapshot.histogram)
                    : null
                this.histogramVersion++
            }
            this.crushedLabel = percentLabel(snapshot.fractions?.crushed)
            this.blownLabel = percentLabel(snapshot.fractions?.blown)
        },

        // The tabs

        isTab(id) {
            return this.tab === id
        },

        tabIndexOf(id) {
            return this.isTab(id) ? 0 : -1
        },

        selectTab(id) {
            this.tab = id
            shownTab = id
        },

        /** The arrows move between the tabs, Home and End to the first and the last, as the ARIA tabs pattern has it */
        tabKey(event) {
            const ids = this.sections.map((section) => section.id)
            const index = ids.indexOf(this.tab)
            const next = {
                ArrowRight: (index + 1) % ids.length,
                ArrowLeft: (index - 1 + ids.length) % ids.length,
                Home: 0,
                End: ids.length - 1,
            }[event.key]
            if (next === undefined) {
                return
            }
            event.preventDefault()
            this.selectTab(ids[next])
            this.$nextTick(() =>
                document.getElementById(`editTab-${ids[next]}`)?.focus(),
            )
        },

        sectionChanged(id) {
            return sectionChanged(this.recipe, id)
        },

        anyChanged() {
            return this.sections.some((section) =>
                this.sectionChanged(section.id),
            )
        },

        edited() {
            return this.anyChanged()
        },

        resetSection(id) {
            panel?.change("resetSection", id)
        },

        resetAll() {
            panel?.change("resetAll")
        },

        revert() {
            panel?.revert()
        },

        // The sliders

        /** The specs of the sliders whose keys are listed, space-separated, in their order */
        slidersOf(keys) {
            return keys.split(" ").map((key) => SLIDERS[key])
        },

        sliderValue(key) {
            return this.recipe[key]
        },

        /** The number field's value: the slider's, as `formatSlider` writes it */
        fieldValue(key) {
            return formatSlider(key, this.recipe[key])
        },

        isChanged(key) {
            return this.recipe[key] !== 0
        },

        hasAuto(key) {
            return key === "straighten"
        },

        /** A changed slider's class, which bolds its label */
        sliderClass(key) {
            return this.isChanged(key) ? "changed" : ""
        },

        /**
         * The track's fill to the thumb (`--edit-slider-fill`) and, for a slider with a gradient track (its `track` in
         * `SLIDERS`), the track itself (`--edit-slider-track`, its `--edit-track-*` token), written inline
         */
        sliderStyle(key) {
            const { min, max, track } = SLIDERS[key]
            const fill = ((this.recipe[key] - min) / (max - min)) * 100
            const gradient = track
                ? ` --edit-slider-track: var(--edit-track-${track});`
                : ""
            return `--edit-slider-fill: ${fill}%;${gradient}`
        },

        /**
         * A slider dragged: shown at once, saved on release; the coordinator works the change out (`adjust.withSlider`),
         * keeping the crop true to Straighten and the perspective
         */
        slide(key, event) {
            if (!panel?.recipe()) {
                return
            }
            if (slideDrag?.key !== key) {
                slideDrag = { key, to: panel.dragOf(key) }
            }
            if (slideDrag.to) {
                panel.preview(slideDrag.to(Number(event.target.value)))
            }
        },

        /** A press on the track or the thumb: the slider moves by the pointer */
        slidePointer() {
            slideVia = "pointer"
            slideDrag = null
        },

        /** A key that steps the range: the slider moves by the keys, whose changes are saved once they rest */
        slideKey(event) {
            if (RANGE_KEYS.includes(event.key)) {
                slideVia = "key"
            }
        },

        /**
         * The range's change: saved a moment later, once the keys rest (a held key is one step) or, from the pointer, past
         * a double-click's reset; a drag by the keys keeps its start Edit until they rest
         */
        endSlide() {
            const recipe = panel?.recipe()
            if (!recipe) {
                return
            }
            panel.commitSoon(recipe, slideVia === "key" ? "key" : "click")
            clearTimeout(slideKeyTimer)
            if (slideVia === "key") {
                slideKeyTimer = setTimeout(() => (slideDrag = null), 400)
            } else {
                slideDrag = null
            }
        },

        resetSlider(key) {
            panel?.change("slider", key, 0)
        },

        /** Enter in a slider's number field sets it, as one step */
        setFromField(key, event) {
            const value = Number(event.target.value)
            if (event.target.value.trim() === "" || Number.isNaN(value)) {
                this.revertField(key, event)
                return
            }
            panel?.change("slider", key, value)
            event.target.value = this.fieldValue(key)
        },

        /** A number field's value not entered goes back to the slider's */
        revertField(key, event) {
            event.target.value = this.fieldValue(key)
            if (event.type === "keydown") {
                event.target.blur()
            }
        },

        autoStraighten() {
            panel?.autoStraighten()
        },

        // Crop & rotate

        isAspect(aspect) {
            return this.recipe.aspect === aspect
        },

        /** A chip's label in the lock's orientation: "3:2" or "2:3" */
        aspectLabelOf(aspect) {
            if (!this.upright) {
                return aspectLabel(aspect, false)
            }
            const { width, height } = frameSize(
                this.upright.width,
                this.upright.height,
                this.recipe,
            )
            return aspectLabel(aspect, isPortrait(this.recipe, width, height))
        },

        setAspect(aspect) {
            panel?.change("aspect", aspect)
        },

        swapOrientation() {
            panel?.change("swap")
        },

        rotate(direction) {
            panel?.change("rotate", direction)
        },

        flip(axis) {
            panel?.change("flip", axis)
        },

        setKeepInside(on) {
            panel?.change("keepInside", on)
        },

        /** The crop's size in the original's pixels */
        cropSizeText() {
            if (!this.upright) {
                return ""
            }
            const { width, height } = editedSize(
                this.upright.width,
                this.upright.height,
                this.recipe,
            )
            return `${width} × ${height} px`
        },

        toggleCropTool() {
            panel?.toggleCropTool()
        },

        toggleLevel() {
            panel?.toggleLevel()
        },

        // The header

        undo() {
            panel?.undo()
        },

        redo() {
            panel?.redo()
        },

        toggleSplit() {
            panel?.toggleSplit()
        },

        statusText() {
            return STATUS_TEXT[this.status] ?? ""
        },

        // The Histogram

        histogramPath(channel) {
            return this.histogram?.[channel] ?? ""
        },

        toggleClippingBlink() {
            panel?.toggleClippingBlink()
        },

        // The curve editor

        curveChanged() {
            return !isIdentityCurve(this.recipe.curve)
        },

        resetCurve() {
            const recipe = panel?.recipe()
            if (recipe) {
                panel.commit(
                    withCurve(recipe, [
                        [0, 0],
                        [1, 1],
                    ]),
                )
            }
        },

        /**
         * Draws the curve over the luminance Histogram of the Edit shown, in the colors its stylesheet gives the canvas
         * (`color` the curve and its points, `border-color` the grid, `outline-color` the Histogram), so the script holds
         * no literal; nothing while the canvas is not laid out (another tab is shown)
         */
        drawCurve() {
            // Read here, so the effect follows them; a tab not shown yet is painted as it is (`curveObserver`)
            const points = this.recipe.curve
            void this.histogramVersion
            void this.curveIndex
            void this.curveFocused
            this.paintCurve(points)
        },

        paintCurve(points) {
            const canvas = this.$refs.curve
            if (!canvas || !canvas.clientWidth) {
                return
            }
            const ratio = window.devicePixelRatio || 1
            const size = Math.round(canvas.clientWidth * ratio)
            canvas.width = size
            canvas.height = size
            const context = canvas.getContext("2d")
            const style = getComputedStyle(canvas)
            context.clearRect(0, 0, size, size)

            if (luminanceBins) {
                const tallest = Math.max(1, ...luminanceBins)
                context.beginPath()
                context.moveTo(0, size)
                luminanceBins.forEach((bin, i) => {
                    const y = size - (bin / tallest) * size * 0.9
                    context.lineTo((i / luminanceBins.length) * size, y)
                    context.lineTo(((i + 1) / luminanceBins.length) * size, y)
                })
                context.lineTo(size, size)
                context.fillStyle = style.outlineColor
                context.fill()
            }

            context.strokeStyle = style.borderColor
            context.lineWidth = ratio
            context.beginPath()
            for (let i = 1; i < 4; i++) {
                context.moveTo((i * size) / 4, 0)
                context.lineTo((i * size) / 4, size)
                context.moveTo(0, (i * size) / 4)
                context.lineTo(size, (i * size) / 4)
            }
            context.moveTo(0, size)
            context.lineTo(size, 0)
            context.stroke()

            const lut = curveLut(points, 256)
            context.strokeStyle = style.color
            context.lineWidth = 1.6 * ratio
            context.beginPath()
            lut.forEach((y, i) => {
                const x = (i / (lut.length - 1)) * size
                const top = (1 - y) * size
                if (i === 0) {
                    context.moveTo(x, top)
                } else {
                    context.lineTo(x, top)
                }
            })
            context.stroke()

            context.fillStyle = style.color
            points.forEach(([x, y], index) => {
                context.beginPath()
                context.arc(
                    x * size,
                    (1 - y) * size,
                    CURVE_POINT_RADIUS * ratio,
                    0,
                    2 * Math.PI,
                )
                context.fill()
                // The point the keys act on, while the editor has focus
                if (this.curveFocused && index === this.selectedCurvePoint()) {
                    context.lineWidth = ratio
                    context.beginPath()
                    context.arc(
                        x * size,
                        (1 - y) * size,
                        2 * CURVE_POINT_RADIUS * ratio,
                        0,
                        2 * Math.PI,
                    )
                    context.stroke()
                }
            })
        },

        /** The point the keys act on, within the curve's points */
        selectedCurvePoint() {
            return Math.min(this.curveIndex, this.recipe.curve.length - 1)
        },

        /** The curve editor's name for a screen reader: the point the keys act on, where it is, and the keys */
        curveLabel() {
            const index = this.selectedCurvePoint()
            const [x, y] = this.recipe.curve[index]
            return (
                `Tone curve, point ${index + 1} of ${this.recipe.curve.length}: input ${x.toFixed(3)}, output ` +
                `${y.toFixed(3)}. Arrows move it, [ and ] pick another, + adds one, Delete removes it`
            )
        },

        /**
         * The curve editor's keys: the arrows move the point the keys act on (Shift, further), within its neighbours,
         * saved once they rest; [ and ] pick the point before or after; + (or Insert) adds one halfway to the next, on
         * the curve; Delete or Backspace removes an inner one
         */
        curveKey(event) {
            const recipe = panel?.recipe()
            if (
                !recipe ||
                !this.ready ||
                event.ctrlKey ||
                event.metaKey ||
                event.altKey
            ) {
                return
            }
            const curve = recipe.curve
            const index = this.selectedCurvePoint()
            const step = event.shiftKey ? CURVE_KEY_STEP_LARGE : CURVE_KEY_STEP
            const move = {
                ArrowLeft: [-step, 0],
                ArrowRight: [step, 0],
                ArrowUp: [0, step],
                ArrowDown: [0, -step],
            }[event.key]
            if (move) {
                const [x, y] = curve[index]
                panel.commitSoon(
                    withCurve(
                        recipe,
                        moveCurvePoint(curve, index, x + move[0], y + move[1]),
                    ),
                    "key",
                )
            } else if (event.key === "[" || event.key === "]") {
                this.curveIndex = Math.max(
                    0,
                    Math.min(
                        curve.length - 1,
                        index + (event.key === "]" ? 1 : -1),
                    ),
                )
            } else if (event.key === "+" || event.key === "Insert") {
                const next = Math.min(index + 1, curve.length - 1)
                const from = next === index ? index - 1 : index
                const x = (curve[from][0] + curve[next][0]) / 2
                const lut = curveLut(curve, 1001)
                const added = addCurvePoint(curve, x, lut[Math.round(x * 1000)])
                if (added) {
                    this.curveIndex = added.index
                    panel.commit(withCurve(recipe, added.curve))
                }
            } else if (event.key === "Delete" || event.key === "Backspace") {
                if (isRemovableCurvePoint(curve, index)) {
                    this.curveIndex = index - 1
                    panel.commit(
                        withCurve(recipe, removeCurvePoint(curve, index)),
                    )
                }
            } else {
                return
            }
            event.preventDefault()
        },

        /** The pointer's place over the curve's box, x right and y up, in fractions of it, and the box's width */
        curvePoint(event) {
            const box = this.$refs.curve.getBoundingClientRect()
            return {
                x: (event.clientX - box.left) / box.width,
                y: 1 - (event.clientY - box.top) / box.height,
                width: box.width,
            }
        },

        /** The index of the point within reach of the pointer, or -1 */
        nearestCurvePoint({ x, y, width }) {
            let nearest = -1
            let distance = CURVE_HIT_RADIUS / width
            this.recipe.curve.forEach(([px, py], index) => {
                const d = Math.hypot(px - x, py - y)
                if (d < distance) {
                    distance = d
                    nearest = index
                }
            })
            return nearest
        },

        /** A press takes the point within reach, or adds one, and drags it */
        curveDown(event) {
            const recipe = panel?.recipe()
            if (!recipe || event.button !== 0 || !this.ready) {
                return
            }
            const at = this.curvePoint(event)
            let index = this.nearestCurvePoint(at)
            let curve = recipe.curve
            if (index < 0) {
                const added = addCurvePoint(curve, at.x, at.y)
                if (!added) {
                    return
                }
                curve = added.curve
                index = added.index
                panel.preview(withCurve(recipe, curve))
            }
            curveDrag = { index, remove: false }
            this.curveIndex = index
            event.target.setPointerCapture(event.pointerId)
        },

        /** A drag moves its point between its neighbors; well off the box, an inner point is marked to go */
        curveMove(event) {
            const recipe = panel?.recipe()
            if (!curveDrag || !recipe) {
                return
            }
            const at = this.curvePoint(event)
            curveDrag.remove =
                isRemovableCurvePoint(recipe.curve, curveDrag.index) &&
                isOffCurveBox(at.x, at.y)
            panel.preview(
                withCurve(
                    recipe,
                    moveCurvePoint(recipe.curve, curveDrag.index, at.x, at.y),
                ),
            )
        },

        /** The release saves the curve as one step, without the point dragged off the box */
        curveUp() {
            const recipe = panel?.recipe()
            if (!curveDrag || !recipe) {
                curveDrag = null
                return
            }
            const { index, remove } = curveDrag
            curveDrag = null
            panel.commit(
                remove
                    ? withCurve(recipe, removeCurvePoint(recipe.curve, index))
                    : recipe,
            )
        },

        /** A double-click on an inner point removes it */
        curveDoubleClick(event) {
            const recipe = panel?.recipe()
            if (!recipe) {
                return
            }
            const index = this.nearestCurvePoint(this.curvePoint(event))
            if (isRemovableCurvePoint(recipe.curve, index)) {
                panel.commit(
                    withCurve(recipe, removeCurvePoint(recipe.curve, index)),
                )
            }
        },
    }
}
