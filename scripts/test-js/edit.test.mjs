/**
 * The parity of the Edit panel's geometry and per-pixel math with the server's renderer: the
 * client modules (`altitude/static/js/edit/geometry.js`, `tone.js`, `recipe.js`), unchanged, against
 * the shared vectors the Scala renderer generated (`altitude/test/resources/edit/vectors.json`,
 * whose `_doc` describes each group), at the tolerances the file states. Run by `make test-js`
 * under Node's built-in runner (`node --test`), with no dependency.
 */
import { test } from "node:test"
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import { fileURLToPath } from "node:url"

import * as geometry from "../../altitude/static/js/edit/geometry.js"
import * as tone from "../../altitude/static/js/edit/tone.js"
import * as recipes from "../../altitude/static/js/edit/recipe.js"

const vectors = JSON.parse(
    readFileSync(
        fileURLToPath(
            new URL(
                "../../altitude/test/resources/edit/vectors.json",
                import.meta.url,
            ),
        ),
        "utf8",
    ),
)
const tolerance = vectors.tolerance

/** Asserts each number of `actual` is within `within` of `expected`'s, naming `what` */
function near(actual, expected, within, what) {
    const actuals = [actual].flat()
    const expecteds = [expected].flat()
    assert.equal(actuals.length, expecteds.length, `${what}: length`)
    actuals.forEach((value, i) =>
        assert.ok(
            Math.abs(value - expecteds[i]) <= within,
            `${what}[${i}]: ${value} is not within ${within} of ${expecteds[i]}`,
        ),
    )
}

test("the vectors are version 1", () => {
    assert.equal(vectors.version, 1)
    assert.equal(recipes.VERSION, 1)
})

test("every vector recipe is already normalized and is an identity exactly when it changes no pixel", () => {
    const all = [
        ...vectors.tone,
        ...vectors.saturation,
        ...vectors.geometry,
    ].map((entry) => entry.recipe)
    for (const recipe of all) {
        assert.deepEqual(recipes.normalize(recipe), recipe)
    }
    assert.ok(recipes.isIdentity(vectors.tone[0].recipe))
    assert.ok(!recipes.isIdentity(vectors.tone[1].recipe))
})

for (const entry of vectors.tone) {
    test(`tone: ${entry.name}`, () => {
        near(
            tone.whiteBalanceGains(entry.recipe),
            entry.gains,
            tolerance.tone,
            "gains",
        )
        const tables = tone.tables(entry.recipe)
        for (const { index, rgb } of entry.table) {
            near(
                tables.map((table) => table[index]),
                rgb,
                tolerance.tone,
                `table entry ${index}`,
            )
        }
        for (const { v, rgb } of entry.lookup) {
            near(
                tables.map((table) => tone.lookup(table, v)),
                rgb,
                tolerance.tone,
                `lookup at ${v}`,
            )
        }
    })
}

for (const entry of vectors.curves) {
    test(`curve through ${JSON.stringify(entry.points)}`, () => {
        const curve = tone.createCurve(entry.points)
        for (const [x, y] of entry.samples) {
            near(curve(x), y, tolerance.curves, `curve at ${x}`)
        }
    })
}

test("saturation and vibrance", () => {
    for (const entry of vectors.saturation) {
        near(
            tone.saturate(...entry.in, entry.recipe),
            entry.out,
            tolerance.saturation,
            `saturate ${JSON.stringify(entry.in)}`,
        )
    }
})

for (const entry of vectors.geometry) {
    test(`geometry: ${entry.name}`, () => {
        const [uprightWidth, uprightHeight] = entry.upright
        const { recipe } = entry
        const frame = geometry.frameSize(uprightWidth, uprightHeight, recipe)
        assert.deepEqual([frame.width, frame.height], entry.frame)

        const edited = geometry.editedSize(uprightWidth, uprightHeight, recipe)
        assert.deepEqual([edited.width, edited.height], entry.edited)

        near(
            geometry.outline(frame.width, frame.height, recipe).flat(),
            entry.outline.flat(),
            tolerance.geometry,
            "outline",
        )

        for (const point of entry.turned) {
            const turned = geometry.turn(
                ...point.in,
                uprightWidth,
                uprightHeight,
                recipe,
            )
            near(turned, point.out, tolerance.geometry, `turn ${point.in}`)
            // And back again, by the inverse and by the affine map the shader reads
            near(
                geometry.turnBack(
                    ...turned,
                    uprightWidth,
                    uprightHeight,
                    recipe,
                ),
                point.in,
                tolerance.geometry,
                `turn back ${point.in}`,
            )
            const [a, b, c, d, e, f] = geometry.frameToUpright(
                uprightWidth,
                uprightHeight,
                recipe,
            )
            near(
                [
                    (a * turned[0] + b * turned[1] + c) * uprightWidth,
                    (d * turned[0] + e * turned[1] + f) * uprightHeight,
                ],
                point.in,
                tolerance.geometry,
                `frame to upright ${point.in}`,
            )
        }

        for (const point of entry.warped) {
            near(
                geometry.warp(...point.in, frame.width, frame.height, recipe),
                point.out,
                tolerance.geometry,
                `warp ${point.in}`,
            )
        }

        const warp = geometry.createWarp(frame.width, frame.height, recipe)
        for (const point of entry.unwarped) {
            const source = geometry.unwarp(
                ...point.in,
                frame.width,
                frame.height,
                recipe,
            )
            if (point.out === null) {
                assert.equal(source, null, `unwarp ${point.in}`)
            } else {
                near(
                    source,
                    point.out,
                    tolerance.geometry,
                    `unwarp ${point.in}`,
                )
            }
            assert.equal(
                warp.covers(...point.in),
                point.covered,
                `covers ${point.in}`,
            )
        }
    })
}

test("the grain's hash", () => {
    for (const { x, y, h } of vectors.effects.hash) {
        near(tone.hash(x, y), h, tolerance.effects, `hash ${x}, ${y}`)
    }
})

test("the grain's value noise", () => {
    for (const { x, y, cell, noise } of vectors.effects.grain) {
        near(
            tone.grainNoise(x, y, cell),
            noise,
            tolerance.effects,
            `noise ${x}, ${y} over ${cell}`,
        )
    }
})

test("the vignette's weight", () => {
    for (const { x, y, width, height, weight } of vectors.effects.vignette) {
        near(
            tone.vignetteWeight(x, y, width, height),
            weight,
            tolerance.effects,
            `vignette ${x}, ${y} of ${width} × ${height}`,
        )
    }
})

test("output sizes", () => {
    for (const { edited, longEdge, output } of vectors.effects.outputSizes) {
        const size = geometry.outputSize(...edited, longEdge)
        assert.deepEqual([size.width, size.height], output)
    }
})

test("normalize rounds as the server does", () => {
    const edit = recipes.normalize({
        ...recipes.defaults(),
        exposure: 0.4249,
        straighten: 2.349,
        contrast: 12.5,
        crop: { x: 0.12344, y: 0.1, w: 0.50002, h: 0.333333 },
        curve: [
            [0, 0],
            [0.25004, 0.3],
            [0.2504, 0.31],
            [1, 1],
        ],
    })
    assert.equal(edit.exposure, 0.4)
    assert.equal(edit.straighten, 2.3)
    assert.equal(edit.contrast, 13)
    // The edges rounded (0.12344 and 0.62346), the width and height their differences, not the width rounded alone
    assert.deepEqual(edit.crop, { x: 0.1234, y: 0.1, w: 0.5001, h: 0.3333 })
    // A point closer than a step after the one before is dropped
    assert.deepEqual(edit.curve, [
        [0, 0],
        [0.25, 0.3],
        [1, 1],
    ])
    assert.deepEqual(Object.keys(edit), Object.keys(recipes.defaults()))
})

test("an Edit that sets only the Crop tool's settings is the identity", () => {
    assert.ok(
        recipes.isIdentity({
            ...recipes.defaults(),
            aspect: "3:2",
            portrait: true,
            keepInside: false,
            cropFitted: false,
        }),
    )
    assert.ok(!recipes.isIdentity({ ...recipes.defaults(), rotate: 90 }))
})

test("a Face's box follows the Edit's geometry and is skipped outside the crop", () => {
    const upright = [4000, 3000]
    const box = { x: 1000, y: 500, width: 400, height: 300 }

    // Unchanged by the identity: fractions of the upright original
    const plain = geometry.mapBox(box, recipes.defaults(), ...upright)
    near(
        [plain.x, plain.y, plain.width, plain.height],
        [0.25, 1 / 6, 0.1, 0.1],
        1e-12,
        "identity",
    )

    // A quarter turn: the box's top-left corner (1000, 500) goes to (3000 - 500 - 300, 1000) of a 3000 × 4000 frame
    const turned = geometry.mapBox(
        box,
        { ...recipes.defaults(), rotate: 90 },
        ...upright,
    )
    near(
        [turned.x, turned.y, turned.width, turned.height],
        [2200 / 3000, 1000 / 4000, 300 / 3000, 400 / 4000],
        1e-12,
        "rotate 90",
    )

    // Cropped to the frame's right half: the box is left of it
    const crop = { x: 0.5, y: 0, w: 0.5, h: 1 }
    assert.equal(
        geometry.mapBox(box, { ...recipes.defaults(), crop }, ...upright),
        null,
    )

    // A box whose center is inside the crop but which reaches past it is cut to the crop
    const across = geometry.mapBox(
        { x: 1900, y: 1000, width: 400, height: 200 },
        { ...recipes.defaults(), crop: { x: 0.5, y: 0, w: 0.5, h: 1 } },
        ...upright,
    )
    near([across.x, across.width], [0, 300 / 2000], 1e-12, "cut at the crop")
})

test("the Crop tool keeps a crop inside the straightened image", () => {
    const recipe = { ...recipes.defaults(), straighten: 10 }
    const quad = geometry.outline(4000, 3000, recipe)
    assert.ok(!geometry.validRect({ x: 0, y: 0, w: 1, h: 1 }, quad))

    const fitted = geometry.fitMaxCentered(
        geometry.aspectRatio(recipe, 4000, 3000),
        4000,
        3000,
        quad,
    )
    assert.ok(geometry.validRect(fitted, quad))
    // Centered, at the frame's ratio
    near(
        [fitted.x + fitted.w / 2, fitted.y + fitted.h / 2],
        [0.5, 0.5],
        1e-9,
        "center",
    )
    near((fitted.w * 4000) / (fitted.h * 3000), 4 / 3, 1e-9, "ratio")

    const limited = geometry.limitTransition(
        fitted,
        { ...fitted, x: fitted.x + 0.2 },
        quad,
    )
    assert.ok(geometry.validRect(limited, quad))
    assert.ok(limited.x < fitted.x + 0.2)

    const square = geometry.rectWithRatioAround(fitted, 1, 4000, 3000, quad)
    assert.ok(geometry.validRect(square, quad))
    near((square.w * 4000) / (square.h * 3000), 1, 1e-9, "square")
})

test("the Gaussian kernel is OpenCV's for a float image", () => {
    // σ = 1.5: 13 taps, so a half of 7
    const half = tone.gaussianHalfKernel(1.5)
    assert.equal(half.length, 7)
    const total = half[0] + 2 * half.slice(1).reduce((a, b) => a + b, 0)
    near(total, 1, 1e-6, "sum")
    near(half[1] / half[0], Math.exp(-1 / (2 * 2.25)), 1e-6, "falloff")
})
