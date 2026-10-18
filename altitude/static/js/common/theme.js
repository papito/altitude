/**
 * The two themes of the app, as `light-dark()` pairs resolve them: which one is shown, and the
 * two colors of a pair. The style guide's theme switcher and the design tweaks panel's toggle both
 * show the page in a theme through `applyTheme`; the rest of the app stays in the stylesheet's.
 */
import { Const } from "../constants.js"
import { splitOutsideParentheses } from "./token-kinds.js"

// The themes, in the order `light-dark()` takes their colors
export const THEMES = ["light", "dark"]
// A `light-dark()` value: the group is its two colors
const LIGHT_DARK = /^light-dark\((.*)\)$/s

/** `theme` when it is one of the two, else the one the stylesheet declares */
export function themeOrStylesheets(theme) {
    return THEMES.includes(theme)
        ? theme
        : getComputedStyle(document.documentElement).colorScheme
}

/** The theme the style guide was shown in on an earlier visit, else the one the stylesheet declares */
export function rememberedTheme() {
    return themeOrStylesheets(
        localStorage.getItem(Const.localStore.styleGuideTheme),
    )
}

/** Shows the page in `theme`: the root element declares the scheme every `light-dark()` color follows */
export function applyTheme(theme) {
    document.documentElement.style.colorScheme = theme
}

/**
 * The color `value` paints in the theme the page is shown in (another while `inTheme` resolves),
 * in the browser's one serialization, so spellings of a color compare equal
 */
export function resolveColor(value) {
    const probeEl = document.createElement("span")
    probeEl.style.color = value
    document.body.appendChild(probeEl)
    const color = getComputedStyle(probeEl).color
    probeEl.remove()
    return color
}

/**
 * What `resolve` returns while the root element declares the scheme of `theme`, which every
 * `light-dark()` color follows. The theme shown is declared again after, before the browser paints.
 */
export function inTheme(theme, resolve) {
    const rootStyle = document.documentElement.style
    const shownScheme = rootStyle.colorScheme

    applyTheme(theme)
    const result = resolve()
    rootStyle.colorScheme = shownScheme
    return result
}

/**
 * The two colors of a `light-dark()` value, in the order of `THEMES`, or nothing for any other
 * value. They are split at the comma outside every parenthesis, since a color may hold commas of
 * its own (`rgb(54, 54, 54)`).
 */
export function themeColorsOf(value) {
    const [, colors] = value.match(LIGHT_DARK) ?? []
    const parts =
        colors === undefined ? [] : splitOutsideParentheses(colors, ",")

    return parts.length === THEMES.length ? parts : null
}

/**
 * `value` with the color of `theme` replaced by `color`: of a `light-dark()` pair only that side
 * changes, so the value stays one declaration for both themes; any other value is replaced whole
 */
export function withThemeColor(value, theme, color) {
    const colors = themeColorsOf(value)
    if (!colors) {
        return color
    }

    colors[THEMES.indexOf(theme)] = color
    return `light-dark(${colors.join(", ")})`
}
