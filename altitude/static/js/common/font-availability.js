/** Whether the browser has a font, for the style guide's font tester */

// Wide glyphs and digits, so two fonts seldom measure the same
const FONT_PROBE_TEXT = "mmmmmmmmmmlliWWW0123456789"
// The typed value is tried in front of each generic family: a font that is the default of one of
// them measures the same as that fallback, and the others tell it apart
const FONT_PROBE_FALLBACKS = ["monospace", "sans-serif", "serif"]

/** Whether the browser renders `family` (a family, a stack or a generic keyword) with a font it has */
export function isFontAvailable(family) {
    const context = document.createElement("canvas").getContext("2d")
    const widthIn = (fontFamily) => {
        context.font = `72px ${fontFamily}`
        return context.measureText(FONT_PROBE_TEXT).width
    }
    return FONT_PROBE_FALLBACKS.some(
        (fallback) => widthIn(`${family}, ${fallback}`) !== widthIn(fallback),
    )
}

/**
 * The typed value as written when it parses, else quoted as one family name ("Neue Haas 55"): the
 * canvas font setter ignores a value it cannot parse and keeps the previous font.
 */
export function asFontFamily(value) {
    return CSS.supports("font-family", value) ? value : JSON.stringify(value)
}
