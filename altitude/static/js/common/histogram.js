/**
 * A Histogram drawn as SVG paths: Curation's strip under each stage pane (`alpine/components/curation.js`) and the
 * Edit panel's (`alpine/components/edit-panel.js`). DOM-free, so the tests run it under Node (`scripts/test-js/`).
 *
 * The bins are a channel's counts as bytes over the tallest bin of the four channels (`Histogram.relativeBytes` on the
 * server, `readHistogram` of the Edit's renderer), so the channels share one scale and the tallest reaches the box's top
 * (`HISTOGRAM_TALLEST`). The shapes are drawn in `HISTOGRAM_BOX`, the `viewBox` of the `<svg>` that holds them: each
 * color a filled shape (`areaPath`), the luminance an open line over them (`linePath`).
 */

/** The `viewBox` the shapes are drawn in, `0 0 64 16`: a bin per unit of width at 64 bins */
export const HISTOGRAM_BOX = Object.freeze({ width: 64, height: 16 })

/** The byte of the tallest bin of the four channels, which reaches the box's top */
export const HISTOGRAM_TALLEST = 255

/** The channels in the order of the server's bytes (`data-histogram`), a byte per bin */
export const HISTOGRAM_CHANNELS = Object.freeze([
    "luminance",
    "red",
    "green",
    "blue",
])

/** A value of the paths, rounded to two decimals to keep the string short */
function rounded(value) {
    return Math.round(value * 100) / 100
}

/** The height a bin's byte reaches in `HISTOGRAM_BOX`, as the y of its top */
export function binTop(bin) {
    const { height } = HISTOGRAM_BOX
    return rounded(height - (height * bin) / HISTOGRAM_TALLEST)
}

/**
 * A channel's bins as steps across `HISTOGRAM_BOX`, the path commands that follow a start at the
 * box's left edge: up or down to each bin's top, then across the bin's width, so the bins stand
 * side by side with no gap and the shape is honest to them
 */
export function stepsOf(bins) {
    const binWidth = HISTOGRAM_BOX.width / bins.length
    return Array.from(
        bins,
        (bin, index) => `V${binTop(bin)}H${rounded((index + 1) * binWidth)}`,
    ).join("")
}

/** The `d` of a color channel's shape: its steps, closed down to the baseline, to be filled */
export function areaPath(bins) {
    const { height } = HISTOGRAM_BOX
    return `M0,${height}${stepsOf(bins)}V${height}Z`
}

/** The `d` of the luminance line: its steps alone, open, from the first bin's top, to be stroked */
export function linePath(bins) {
    return `M0,${binTop(bins[0])}${stepsOf(bins)}`
}

/**
 * The paths of a Histogram by channel, `{ luminance, red, green, blue }`, from its bins by channel
 * (arrays of bytes): the luminance a line, each color a filled shape
 */
export function channelPaths(binsByChannel) {
    return Object.fromEntries(
        HISTOGRAM_CHANNELS.map((channel) => [
            channel,
            channel === "luminance"
                ? linePath(binsByChannel[channel])
                : areaPath(binsByChannel[channel]),
        ]),
    )
}

/** A fraction (a number, or the string an attribute holds) as a corner's label, "14.2%"; empty for none */
export function percentLabel(fraction) {
    return fraction === undefined || fraction === null
        ? ""
        : `${(Number(fraction) * 100).toFixed(1)}%`
}
