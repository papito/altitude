/**
 * The light and dark themes, as `light-dark()` pairs resolve them: which one is shown, and the two
 * colors of a pair. `js/theme-boot.js` shows the remembered theme before the first paint.
 */
import { Const } from "../constants.js"
import { splitOutsideParentheses } from "./token-kinds.js"

// In the order `light-dark()` takes their colors
export const THEMES = ["light", "dark"]
const LIGHT_DARK = /^light-dark\((.*)\)$/s

export function themeOrStylesheets(theme) {
    return THEMES.includes(theme)
        ? theme
        : getComputedStyle(document.documentElement).colorScheme
}

export function rememberedTheme() {
    return themeOrStylesheets(localStorage.getItem(Const.localStore.theme))
}

/** `data-theme` picks the nav switch's icon */
export function applyTheme(theme) {
    document.documentElement.style.colorScheme = theme
    document.documentElement.dataset.theme = theme
}

export function chooseTheme(theme) {
    applyTheme(theme)
    localStorage.setItem(Const.localStore.theme, theme)
    document.body.dispatchEvent(
        new CustomEvent(Const.events.themeChanged, { detail: { theme } }),
    )
}

export function toggleTheme() {
    chooseTheme(themeOrStylesheets() === "light" ? "dark" : "light")
}

/**
 * The color `value` paints in the current scheme (another inside `inTheme`), in the browser's
 * serialization, so spellings of a color compare equal
 */
export function resolveColor(value) {
    const probeEl = document.createElement("span")
    probeEl.style.color = value
    document.body.appendChild(probeEl)
    const color = getComputedStyle(probeEl).color
    probeEl.remove()
    return color
}

/** What `resolve` returns under `theme`'s scheme, restored synchronously so nothing paints */
export function inTheme(theme, resolve) {
    const rootStyle = document.documentElement.style
    const shownScheme = rootStyle.colorScheme

    rootStyle.colorScheme = theme
    const result = resolve()
    rootStyle.colorScheme = shownScheme
    return result
}

/**
 * The two colors of a `light-dark()` value, or null for any other value. Split outside parentheses,
 * since a color may hold commas of its own (`rgb(54, 54, 54)`).
 */
export function themeColorsOf(value) {
    const [, colors] = value.match(LIGHT_DARK) ?? []
    const parts =
        colors === undefined ? [] : splitOutsideParentheses(colors, ",")

    return parts.length === THEMES.length ? parts : null
}

/** Of a `light-dark()` pair only `theme`'s side changes; any other value is replaced whole */
export function withThemeColor(value, theme, color) {
    const colors = themeColorsOf(value)
    if (!colors) {
        return color
    }

    colors[THEMES.indexOf(theme)] = color
    return `light-dark(${colors.join(", ")})`
}
