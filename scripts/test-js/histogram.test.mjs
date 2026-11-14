/**
 * The Histogram's SVG paths (`altitude/static/js/common/histogram.js`), which Curation's strips and the Edit panel
 * draw from bin arrays. Run by `make test-js`.
 */
import { test } from "node:test"
import assert from "node:assert/strict"

import {
    areaPath,
    binTop,
    channelPaths,
    linePath,
    percentLabel,
} from "../../altitude/static/js/common/histogram.js"

test("A bin reaches the box's top at the tallest byte and its baseline at 0", () => {
    assert.equal(binTop(255), 0)
    assert.equal(binTop(0), 16)
    assert.equal(binTop(127.5), 8)
})

test("A color is a closed shape of steps and the luminance an open line", () => {
    const bins = [0, 255, 51, 0]
    assert.equal(areaPath(bins), "M0,16V16H16V0H32V12.8H48V16H64V16Z")
    assert.equal(linePath(bins), "M0,16V16H16V0H32V12.8H48V16H64")
    const paths = channelPaths({
        luminance: bins,
        red: bins,
        green: bins,
        blue: bins,
    })
    assert.equal(paths.luminance, linePath(bins))
    assert.equal(paths.blue, areaPath(bins))
})

test("A fraction reads as a percentage to one decimal, and none as nothing", () => {
    assert.equal(percentLabel(0.142), "14.2%")
    assert.equal(percentLabel("0.08"), "8.0%")
    assert.equal(percentLabel(undefined), "")
    assert.equal(percentLabel(null), "")
})
