/**
 * The Edit panel's changes to an Edit (`altitude/static/js/edit/adjust.js`): the crop kept inside the image as the
 * geometry changes, a quarter turn and a flip carried into the new frame, the aspect lock, the section resets, the Level
 * tool's and the rotating drag's Straighten, and the curve editor's rules on its points. Run by `make test-js`.
 */
import { test } from "node:test"
import assert from "node:assert/strict"

import * as adjust from "../../altitude/static/js/edit/adjust.js"
import {
    editedSize,
    frameSize,
    outline,
    validRect,
} from "../../altitude/static/js/edit/geometry.js"
import {
    defaults,
    isIdentity,
    MIN_CURVE_SPACING,
    normalize,
} from "../../altitude/static/js/edit/recipe.js"

const UPRIGHT = { width: 4000, height: 3000 }

function quadOf(recipe) {
    const { width, height } = frameSize(UPRIGHT.width, UPRIGHT.height, recipe)
    return outline(width, height, recipe)
}

test("A fitted crop re-fits inside the image as Straighten changes, and goes back to none at 0", () => {
    const straightened = adjust.withSlider(defaults(), "straighten", 5, UPRIGHT)
    assert.equal(straightened.straighten, 5)
    assert.notEqual(straightened.crop, null)
    assert.equal(straightened.cropFitted, true)
    assert.ok(validRect(straightened.crop, quadOf(straightened)))
    // The largest rectangle at the frame's own ratio
    const { width, height } = editedSize(
        UPRIGHT.width,
        UPRIGHT.height,
        straightened,
    )
    assert.ok(Math.abs(width / height - 4 / 3) < 0.01)

    const back = adjust.withSlider(straightened, "straighten", 0, UPRIGHT)
    assert.equal(back.crop, null)
    assert.ok(isIdentity(back))
})

test("A crop set by hand shrinks around its center only as much as Straighten needs", () => {
    const set = adjust.withCrop(
        defaults(),
        { x: 0.3, y: 0.3, w: 0.4, h: 0.4 },
        UPRIGHT,
    )
    assert.equal(set.cropFitted, false)
    assert.deepEqual(set.crop, { x: 0.3, y: 0.3, w: 0.4, h: 0.4 })

    // A small turn leaves a small centered crop as it is
    const turned = adjust.withSlider(set, "straighten", 3, UPRIGHT)
    assert.deepEqual(turned.crop, set.crop)

    // A crop to the frame's edges has to shrink, about its own center
    const wide = adjust.withCrop(
        defaults(),
        { x: 0, y: 0, w: 1, h: 1 },
        UPRIGHT,
    )
    assert.equal(wide.crop, null)
    const corner = adjust.withCrop(
        defaults(),
        { x: 0, y: 0, w: 0.5, h: 0.5 },
        UPRIGHT,
    )
    const shrunk = adjust.withSlider(corner, "straighten", 10, UPRIGHT)
    assert.ok(shrunk.crop.w < 0.5 && shrunk.crop.h < 0.5)
    assert.ok(validRect(shrunk.crop, quadOf(shrunk)))
    assert.ok(Math.abs(shrunk.crop.x + shrunk.crop.w / 2 - 0.25) < 0.001)
})

test("A slider's drag works from its start, so a crop it shrank grows back as the drag returns", () => {
    const start = adjust.withCrop(
        defaults(),
        { x: 0.05, y: 0.05, w: 0.5, h: 0.5 },
        UPRIGHT,
    )
    const drag = adjust.sliderDrag(start, "straighten", UPRIGHT)
    const turned = drag(12)
    assert.ok(turned.crop.w < start.crop.w)
    assert.deepEqual(drag(0), start)

    // Applied step by step to the Edit shown, the crop would ratchet smaller
    const stepped = adjust.withSlider(turned, "straighten", 0, UPRIGHT)
    assert.ok(stepped.crop.w < start.crop.w)
})

test("Without keeping the crop inside, Straighten leaves a set crop alone and a fitted one whole", () => {
    const loose = adjust.withKeepInside(defaults(), false, UPRIGHT)
    const turned = adjust.withSlider(loose, "straighten", 10, UPRIGHT)
    assert.equal(turned.crop, null)

    const back = adjust.withKeepInside(turned, true, UPRIGHT)
    assert.notEqual(back.crop, null)
    assert.ok(validRect(back.crop, quadOf(back)))
})

test("An aspect lock re-fits a fitted crop to its ratio, and a set crop takes it about its center", () => {
    const square = adjust.withAspect(defaults(), "1:1", UPRIGHT)
    const size = editedSize(UPRIGHT.width, UPRIGHT.height, square)
    assert.equal(size.width, size.height)
    assert.equal(size.height, 3000)

    const set = adjust.withCrop(
        defaults(),
        { x: 0.2, y: 0.2, w: 0.4, h: 0.4 },
        UPRIGHT,
    )
    const wide = adjust.withAspect(set, "16:9", UPRIGHT)
    const wideSize = editedSize(UPRIGHT.width, UPRIGHT.height, wide)
    assert.ok(Math.abs(wideSize.width / wideSize.height - 16 / 9) < 0.01)
    assert.ok(Math.abs(wide.crop.x + wide.crop.w / 2 - 0.4) < 0.001)
    assert.ok(Math.abs(wide.crop.y + wide.crop.h / 2 - 0.4) < 0.001)
})

test("Swapping the lock's orientation turns its ratio portrait, and Free swaps a set crop's sides", () => {
    const landscape = adjust.withAspect(defaults(), "3:2", UPRIGHT)
    const portrait = adjust.swappedOrientation(landscape, UPRIGHT)
    assert.equal(portrait.portrait, true)
    const size = editedSize(UPRIGHT.width, UPRIGHT.height, portrait)
    assert.ok(Math.abs(size.width / size.height - 2 / 3) < 0.01)

    const free = adjust.withCrop(
        adjust.withAspect(defaults(), "free", UPRIGHT),
        { x: 0.25, y: 0.25, w: 0.5, h: 0.25 },
        UPRIGHT,
    )
    const swapped = adjust.swappedOrientation(free, UPRIGHT)
    const before = editedSize(UPRIGHT.width, UPRIGHT.height, free)
    const after = editedSize(UPRIGHT.width, UPRIGHT.height, swapped)
    assert.ok(
        Math.abs(after.width / after.height - before.height / before.width) <
            0.01,
    )
})

test("A quarter turn carries the crop and the perspective into the turned frame, and four come back", () => {
    let edit = adjust.withCrop(
        {
            ...defaults(),
            perspectiveH: 20,
            perspectiveV: -10,
            keepInside: false,
        },
        { x: 0.1, y: 0.2, w: 0.3, h: 0.4 },
        UPRIGHT,
    )
    const right = adjust.rotated(edit, 1, UPRIGHT)
    assert.equal(right.rotate, 90)
    assert.deepEqual(right.crop, { x: 0.4, y: 0.1, w: 0.4, h: 0.3 })
    assert.equal(right.perspectiveH, 10)
    assert.equal(right.perspectiveV, 20)
    assert.deepEqual(adjust.rotated(right, -1, UPRIGHT), edit)

    for (let i = 0; i < 4; i++) {
        edit = adjust.rotated(edit, 1, UPRIGHT)
    }
    assert.equal(edit.rotate, 0)
    assert.deepEqual(edit.crop, { x: 0.1, y: 0.2, w: 0.3, h: 0.4 })
})

test("With one flip on, a turn to the right steps the stored turn back, as the mirror reverses it", () => {
    const flippedEdit = adjust.flipped(defaults(), "h", UPRIGHT)
    assert.equal(flippedEdit.flipH, true)
    assert.equal(adjust.rotated(flippedEdit, 1, UPRIGHT).rotate, 270)
    const both = adjust.flipped(flippedEdit, "v", UPRIGHT)
    assert.equal(adjust.rotated(both, 1, UPRIGHT).rotate, 90)
})

test("A flip mirrors the crop and changes the sign of Straighten and that axis's keystone", () => {
    const edit = adjust.withCrop(
        {
            ...defaults(),
            straighten: 2,
            perspectiveH: 30,
            perspectiveV: 15,
            keepInside: false,
        },
        { x: 0.1, y: 0.2, w: 0.3, h: 0.4 },
        UPRIGHT,
    )
    const h = adjust.flipped(edit, "h", UPRIGHT)
    assert.deepEqual(h.crop, { x: 0.6, y: 0.2, w: 0.3, h: 0.4 })
    assert.equal(h.straighten, -2)
    assert.equal(h.perspectiveH, -30)
    assert.equal(h.perspectiveV, 15)
    const v = adjust.flipped(edit, "v", UPRIGHT)
    assert.deepEqual(v.crop, { x: 0.1, y: 0.4, w: 0.3, h: 0.4 })
    assert.equal(v.perspectiveV, -15)
})

test("A section's reset leaves the other sections and keeping the crop inside as they are", () => {
    const edit = normalize({
        ...defaults(),
        exposure: 1,
        contrast: 20,
        curve: [
            [0, 0],
            [0.5, 0.6],
            [1, 1],
        ],
        saturation: 30,
        rotate: 90,
        keepInside: false,
    })
    const light = adjust.resetSection(edit, "light", UPRIGHT)
    assert.equal(light.exposure, 0)
    assert.equal(light.contrast, 0)
    assert.deepEqual(light.curve, [
        [0, 0],
        [1, 1],
    ])
    assert.equal(light.saturation, 30)
    assert.equal(light.rotate, 90)

    const geometry = adjust.resetSection(edit, "cropRotate", UPRIGHT)
    assert.equal(geometry.rotate, 0)
    assert.equal(geometry.keepInside, false)
    assert.equal(geometry.exposure, 1)

    const all = adjust.resetAll(edit, UPRIGHT)
    assert.ok(isIdentity(all))
    assert.equal(all.keepInside, false)
})

test("The Level tool levels the line drawn or makes it plumb, whichever is nearer, and ignores a click", () => {
    // A line drawn 3° clockwise of level is turned back by 3°
    const dx = 100
    const dy = Math.tan((3 * Math.PI) / 180) * 100
    assert.ok(Math.abs(adjust.levelStraighten(0, dx, dy) + 3) < 1e-9)
    // Drawn right to left, the same line
    assert.ok(Math.abs(adjust.levelStraighten(0, -dx, -dy) + 3) < 1e-9)
    // A near-vertical line, 2° off plumb
    const plumb = adjust.levelStraighten(
        1,
        Math.tan((2 * Math.PI) / 180) * 100,
        100,
    )
    assert.ok(Math.abs(plumb - 3) < 1e-9)
    assert.equal(adjust.levelStraighten(0, 3, 4), null)
})

test("A drag around the crop's center turns Straighten by the pointer's angle, within its range", () => {
    const center = { x: 0, y: 0 }
    assert.ok(
        Math.abs(
            adjust.draggedStraighten(
                0,
                center,
                { x: 100, y: 0 },
                { x: 100, y: 10 },
            ) -
                (Math.atan2(10, 100) * 180) / Math.PI,
        ) < 1e-9,
    )
    assert.equal(
        adjust.draggedStraighten(
            40,
            center,
            { x: 100, y: 0 },
            { x: 0, y: 100 },
        ),
        45,
    )
    // Across the angle's wrap the change is the short way round
    const wrapped = adjust.draggedStraighten(
        0,
        center,
        { x: -100, y: -1 },
        { x: -100, y: 1 },
    )
    assert.ok(wrapped < 0 && wrapped > -2)
})

test("The curve takes a point in x order, refuses one too near another or past its most, and keeps its ends' x", () => {
    const identity = [
        [0, 0],
        [1, 1],
    ]
    const added = adjust.addCurvePoint(identity, 0.5, 0.6)
    assert.deepEqual(added, {
        curve: [
            [0, 0],
            [0.5, 0.6],
            [1, 1],
        ],
        index: 1,
    })
    assert.equal(adjust.addCurvePoint(added.curve, 0.51, 0.2), null)

    let full = identity
    for (let i = 1; full.length < 16; i++) {
        full = adjust.addCurvePoint(full, i * 0.06, 0.5).curve
    }
    assert.equal(adjust.addCurvePoint(full, 0.97, 0.5), null)

    // An inner point stays between its neighbors, a step away from each; an end keeps its x
    const moved = adjust.moveCurvePoint(added.curve, 1, 1.4, 0.75)
    assert.deepEqual(moved[1], [1 - MIN_CURVE_SPACING, 0.75])
    const end = adjust.moveCurvePoint(added.curve, 0, 0.3, 0.2)
    assert.deepEqual(end[0], [0, 0.2])
    // Every point lands on a step, so the server keeps it as sent
    const rounded = adjust.moveCurvePoint(added.curve, 1, 0.123456, 0.654321)
    assert.deepEqual(rounded[1], [0.123, 0.654])
})

test("An inner point is removed, an end never, and a drag well past the box removes", () => {
    const curve = [
        [0, 0],
        [0.5, 0.6],
        [1, 1],
    ]
    assert.deepEqual(adjust.removeCurvePoint(curve, 1), [
        [0, 0],
        [1, 1],
    ])
    assert.deepEqual(adjust.removeCurvePoint(curve, 0), curve)
    assert.equal(adjust.isRemovableCurvePoint(curve, 2), false)
    assert.equal(adjust.isOffCurveBox(0.5, 1.2), true)
    assert.equal(adjust.isOffCurveBox(0.5, 1.05), false)
})

test("An aspect chip reads its ratio by the lock's orientation", () => {
    assert.equal(adjust.aspectLabel("3:2", false), "3:2")
    assert.equal(adjust.aspectLabel("3:2", true), "2:3")
    assert.equal(adjust.aspectLabel("original", true), "Original")
    assert.equal(adjust.aspectLabel("free", false), "Free")
})
