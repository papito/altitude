/**
 * A Histogram drawn as SVG paths. DOM-free, so the tests run it under Node.
 *
 * Each bin is a byte relative to the tallest bin of all four channels (`Histogram.relativeBytes` on the server,
 * `readHistogram` in the Edit's renderer), so the channels share one scale. Colors are filled shapes; luminance is an
 * open line over them.
 */

/** The `viewBox` of the `<svg>` */
export const HISTOGRAM_BOX = Object.freeze({ width: 64, height: 16 })

export const HISTOGRAM_TALLEST = 255

/** The channels in the order of the server's bytes (`data-histogram`) */
export const HISTOGRAM_CHANNELS = Object.freeze([
    "luminance",
    "red",
    "green",
    "blue",
])

/** Two decimals keep the path string short */
function rounded(value) {
    return Math.round(value * 100) / 100
}

/** The y of a bin's top in `HISTOGRAM_BOX` */
export function binTop(bin) {
    const { height } = HISTOGRAM_BOX
    return rounded(height - (height * bin) / HISTOGRAM_TALLEST)
}

/** A point per bin, the first on the box's left edge and the last on its right, whatever the number of bins */
export function pointsOf(bins) {
    const step = HISTOGRAM_BOX.width / Math.max(1, bins.length - 1)
    return Array.from(
        bins,
        (bin, index) => `${rounded(index * step)},${binTop(bin)}`,
    ).join("L")
}

/** A color channel's shape, closed down to the baseline at both edges */
export function areaPath(bins) {
    const { width, height } = HISTOGRAM_BOX
    return `M0,${height}L${pointsOf(bins)}L${width},${height}Z`
}

export function linePath(bins) {
    return `M${pointsOf(bins)}`
}

/** Every channel empty, for a pane with no frame or a frame not measured */
export const NO_PATHS = Object.freeze(
    Object.fromEntries(HISTOGRAM_CHANNELS.map((channel) => [channel, ""])),
)

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

/** A fraction (a number or an attribute's string) as "14.2%"; empty for none */
export function percentLabel(fraction) {
    return fraction === undefined || fraction === null
        ? ""
        : `${(Number(fraction) * 100).toFixed(1)}%`
}
