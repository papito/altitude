/**
 * A Histogram drawn as SVG paths, for the Histogram's one markup (`views/includes/histogram.scala.html`): Curation's
 * strip under each stage pane (`alpine/components/curation.js`), the Edit panel's (`alpine/components/edit-panel.js`)
 * and the style guide's specimen. DOM-free, so the tests run it under Node (`scripts/test-js/`).
 *
 * The bins are a channel's counts as bytes over the tallest bin of the four channels (`Histogram.relativeBytes` on the
 * server, `readHistogram` of the Edit's renderer), so the channels share one scale and the tallest reaches the box's top
 * (`HISTOGRAM_TALLEST`). The shapes are drawn in `HISTOGRAM_BOX`, the `viewBox` of the `<svg>` that holds them: each
 * color a filled shape (`areaPath`), the luminance an open line over them (`linePath`), both through a point a bin.
 */

/** The `viewBox` the shapes are drawn in, `0 0 64 16` */
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
 * A channel's bins as points across `HISTOGRAM_BOX`, joined by straight lines: a point a bin at its top, the first bin
 * on the box's left edge and the last on its right, so the shape spans the box whatever the number of bins
 */
export function pointsOf(bins) {
    const step = HISTOGRAM_BOX.width / Math.max(1, bins.length - 1)
    return Array.from(
        bins,
        (bin, index) => `${rounded(index * step)},${binTop(bin)}`,
    ).join("L")
}

/** The `d` of a color channel's shape: its points, closed down to the baseline at both edges, to be filled */
export function areaPath(bins) {
    const { width, height } = HISTOGRAM_BOX
    return `M0,${height}L${pointsOf(bins)}L${width},${height}Z`
}

/** The `d` of the luminance line: its points alone, open, to be stroked */
export function linePath(bins) {
    return `M${pointsOf(bins)}`
}

/** The paths of no Histogram, every channel's empty: what a pane with no frame, or a frame not measured, draws */
export const NO_PATHS = Object.freeze(
    Object.fromEntries(HISTOGRAM_CHANNELS.map((channel) => [channel, ""])),
)

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
