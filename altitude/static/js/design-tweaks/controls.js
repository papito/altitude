/**
 * One control per kind of token for the design tweaks panel. Each builder takes the token and a
 * context (`ctx`) that reads the token's current declared text (`ctx.value()`, the tweak or the
 * bundle's), the theme shown (`ctx.theme()`), and writes a new text (`ctx.set(text)`); it returns
 * the control's element and `refresh()`, which re-reads the value into the inputs after a change
 * made elsewhere (a reset, a snapshot, the other theme).
 *
 * Colors are handled through the browser alone, no color library: a color's OKLCH channels come
 * from a `getComputedStyle` round trip over `oklch(from <color> l c h)`, and a color is written back
 * as the hex the same round trip over `rgb(from …)` gives, so what lands in `core.css` is a plain
 * literal. A `light-dark()` pair is edited on the side of the theme shown.
 */
import { asFontFamily, isFontAvailable } from "../common/font-availability.js"
import { resolveColor, themeColorsOf, withThemeColor } from "../common/theme.js"
import { splitOutsideParentheses } from "../common/token-kinds.js"

const BORDER_STYLES = [
    "solid",
    "dashed",
    "dotted",
    "double",
    "groove",
    "ridge",
    "inset",
    "outset",
    "none",
]
const REM = /^(-?\d*\.?\d+)rem$/
const PX = /^(-?\d*\.?\d+)px$/
const EM = /^(-?\d*\.?\d+)em$/
const NUMBER = /^-?\d*\.?\d+$/
// A spacing step referenced whole, as a role token does
const STEP_REFERENCE = /^var\((--space-\d+)\)$/
// A color written as one of the color tokens at an alpha, as the veils and the shadows are
const TOKEN_AT_ALPHA = /^rgb\(from var\((--[\w-]+)\) r g b \/ ([\d.]+)\)$/
const TOKEN_ONLY = /^var\((--[\w-]+)\)$/
// A color that writes its own channels, which the OKLCH sliders can edit: not a recipe over tokens
const OWN_COLOR =
    /^(#|rgba?\(|hsla?\(|oklch\(|oklab\(|lab\(|lch\(|hwb\(|color\()(?!\s*from\b)|^[a-z]+$/i

// The steps of the type scale, from the base, in the order a modular scale derives them
export const TYPE_STEPS = [
    "xs",
    "small",
    "base",
    "medium",
    "large",
    "xl",
    "display",
]
const BASE_STEP_INDEX = TYPE_STEPS.indexOf("base")

/** `el(tag, { class, text, attrs… }, ...children)` */
export function el(tag, props = {}, ...children) {
    const node = document.createElement(tag)
    Object.entries(props).forEach(([key, value]) => {
        if (key === "class") {
            node.className = value
        } else if (key === "text") {
            node.textContent = value
        } else if (key.startsWith("on")) {
            node.addEventListener(key.slice(2), value)
        } else if (value !== undefined && value !== false) {
            node.setAttribute(key, value === true ? "" : value)
        }
    })
    node.append(...children.filter((child) => child !== null))
    return node
}

/** The control for `token`, by its kind, its group and the shape of its declared text */
export function buildControl(token, ctx) {
    const builders = {
        color: buildColorControl,
        length: buildLengthControl,
        border: buildBorderControl,
        shadow: buildShadowControl,
        font: buildFontControl,
        typography: buildTypographyControl,
    }
    return (builders[token.kind] ?? buildTextControl)(token, ctx)
}

// Colors

/**
 * The OKLCH channels of `color` as the browser computes them, in the theme shown: `l` 0..1,
 * `c` 0..0.4, `h` 0..360 (0 for an achromatic color) and `alpha` 0..1
 */
export function oklchOf(color) {
    const computed = resolveColor(`oklch(from ${color} l c h / alpha)`)
    const [, l, c, h, alpha = "1"] =
        computed.match(
            /^oklch\(([\d.e-]+) ([\d.e-]+) ([\d.e-]+|none)(?: \/ ([\d.e-]+))?\)$/,
        ) ?? []
    return {
        l: Number(l ?? 0),
        c: Number(c ?? 0),
        h: h === "none" || h === undefined ? 0 : Number(h),
        alpha: Number(alpha),
    }
}

/**
 * `color` as a hex literal (`#rrggbb`, with alpha `#rrggbbaa`), clipped to sRGB as the browser does.
 * The round trip over `rgb(from …)` computes to `color(srgb r g b / a)` with channels 0..1, or, for
 * a color the browser keeps in the legacy form, `rgb(r, g, b)` / `rgba(r, g, b, a)` with channels
 * 0..255.
 */
export function hexOf(color) {
    const computed = resolveColor(`rgb(from ${color} r g b / alpha)`)
    const [, space, channels] =
        computed.match(/^(color\(srgb|rgba?\()\s*([^)]+)\)$/) ?? []
    if (channels === undefined) {
        return color
    }
    const scale = space.startsWith("color") ? 255 : 1
    const [r, g, b, alpha = "1"] = channels
        .replace("/", " ")
        .split(/[\s,]+/)
        .filter(Boolean)
    const channel = (value, factor = scale) =>
        Math.round(Math.min(255, Math.max(0, Number(value) * factor)))
            .toString(16)
            .padStart(2, "0")
    const hex = `#${channel(r)}${channel(g)}${channel(b)}`
    return Number(alpha) < 1 ? `${hex}${channel(alpha, 255)}` : hex
}

/** Whether `text` is a color the browser can paint */
export function isColor(text) {
    return CSS.supports("color", text)
}

/** The color of the theme shown in `declared`: one side of a pair, or the whole value */
export function themeSideOf(declared = "", theme) {
    const colors = themeColorsOf(declared)
    return colors ? colors[theme === "light" ? 0 : 1] : declared
}

function buildColorControl(token, ctx) {
    const sideOf = () => themeSideOf(ctx.value(), ctx.theme())
    const write = (color) =>
        ctx.set(withThemeColor(ctx.value(), ctx.theme(), color))

    // A recipe over other tokens (a veil, a status fill) keeps its recipe: its alpha is a slider when it
    // has one, and the text is open to edit; the sliders are for a color that writes its channels
    if (!OWN_COLOR.test(sideOf())) {
        return buildRecipeColorControl(token, ctx, sideOf, write)
    }

    const swatch = el("span", { class: "swatch" })
    const hex = el("input", {
        type: "text",
        size: "9",
        "aria-label": `${token.name} hex`,
    })
    const picker = el("input", {
        type: "color",
        "aria-label": `${token.name} picker`,
    })
    const sliders = {
        l: slider(0, 1, 0.005),
        c: slider(0, 0.4, 0.002),
        h: slider(0, 360, 1),
    }
    const readouts = {
        l: el("span", { class: "v" }),
        c: el("span", { class: "v" }),
        h: el("span", { class: "v" }),
    }
    let alpha = 1

    const fromSliders = () => {
        const color = `oklch(${sliders.l.value} ${sliders.c.value} ${sliders.h.value}${alpha < 1 ? ` / ${alpha}` : ""})`
        write(hexOf(color))
        paint()
    }
    const paint = () => {
        const color = sideOf()
        swatch.style.background = color
        hex.value = color
        picker.value = hexOf(color).slice(0, 7)
    }
    const refresh = () => {
        const color = sideOf()
        const { l, c, h, alpha: a } = oklchOf(color)
        alpha = a
        sliders.l.value = l
        sliders.c.value = c
        sliders.h.value = h
        readouts.l.textContent = l.toFixed(3)
        readouts.c.textContent = c.toFixed(3)
        readouts.h.textContent = `${Math.round(h)}°`
        paint()
    }

    Object.entries(sliders).forEach(([channel, input]) =>
        input.addEventListener("input", () => {
            readouts[channel].textContent =
                channel === "h"
                    ? `${Math.round(input.value)}°`
                    : Number(input.value).toFixed(3)
            fromSliders()
        }),
    )
    hex.addEventListener("change", () => {
        if (isColor(hex.value.trim())) {
            write(hex.value.trim())
            refresh()
        } else {
            hex.value = sideOf()
        }
    })
    picker.addEventListener("input", () => {
        write(picker.value)
        refresh()
    })

    refresh()
    return {
        el: el(
            "div",
            { class: "control" },
            el("div", { class: "row" }, swatch, hex, picker),
            channelRow("L", sliders.l, readouts.l),
            channelRow("C", sliders.c, readouts.c),
            channelRow("H", sliders.h, readouts.h),
        ),
        refresh,
    }
}

/** A color that is a recipe over tokens: its text, and an alpha slider when the recipe ends in one */
function buildRecipeColorControl(token, ctx, sideOf, write) {
    const text = el("input", {
        type: "text",
        "aria-label": `${token.name} value`,
    })
    const alpha = slider(0, 1, 0.01)
    const readout = el("span", { class: "v" })
    const alphaRow = channelRow("α", alpha, readout)

    const refresh = () => {
        const value = sideOf()
        text.value = value
        const current = alphaOfRecipe(value)
        alphaRow.hidden = current === null
        if (current !== null) {
            alpha.value = current
            readout.textContent = current.toFixed(2)
        }
    }
    alpha.addEventListener("input", () => {
        readout.textContent = Number(alpha.value).toFixed(2)
        write(withRecipeAlpha(sideOf(), alpha.value))
    })
    text.addEventListener("change", () => {
        if (isColor(text.value.trim())) {
            write(text.value.trim())
        }
        refresh()
    })

    refresh()
    return {
        el: el(
            "div",
            { class: "control" },
            el("div", { class: "row" }, text),
            alphaRow,
        ),
        refresh,
    }
}

// The alpha a relative color ends in: `rgb(from … r g b / 0.45)`
const RECIPE_ALPHA = /\/\s*([\d.]+)\s*\)$/

function alphaOfRecipe(value) {
    const [, alpha] = value.match(RECIPE_ALPHA) ?? []
    return alpha === undefined ? null : Number(alpha)
}

function withRecipeAlpha(value, alpha) {
    return value.replace(RECIPE_ALPHA, `/ ${alpha})`)
}

/**
 * The color part of a border or a shadow: a select over the color tokens, and an alpha slider that
 * writes `rgb(from var(--token) r g b / alpha)`, or the token alone at 1. A color written any other
 * way is kept as text.
 */
function buildColorSourceControl(ctx, { read, write }) {
    const select = el("select", { "aria-label": "Color token" })
    select.append(el("option", { value: "", text: "(as written)" }))
    ctx.colorTokens.forEach((name) =>
        select.append(el("option", { value: name, text: name })),
    )
    const alpha = slider(0, 1, 0.01)
    const readout = el("span", { class: "v" })
    const text = el("input", { type: "text", "aria-label": "Color" })

    const parse = () => {
        const color = read()
        const [, atAlpha, alphaValue] = color.match(TOKEN_AT_ALPHA) ?? []
        const [, only] = color.match(TOKEN_ONLY) ?? []
        const token = atAlpha ?? only ?? ""
        return { token, alpha: token ? Number(alphaValue ?? 1) : 1, color }
    }
    const compose = (token, alphaValue) =>
        Number(alphaValue) >= 1
            ? `var(${token})`
            : `rgb(from var(${token}) r g b / ${alphaValue})`

    const refresh = () => {
        const { token, alpha: alphaValue, color } = parse()
        select.value = ctx.colorTokens.includes(token) ? token : ""
        alpha.value = alphaValue
        readout.textContent = Number(alphaValue).toFixed(2)
        text.value = color
        text.hidden = token !== ""
        alpha.disabled = token === ""
    }
    select.addEventListener("change", () => {
        if (select.value === "") {
            return
        }
        write(compose(select.value, alpha.value))
        refresh()
    })
    alpha.addEventListener("input", () => {
        const { token } = parse()
        if (token) {
            readout.textContent = Number(alpha.value).toFixed(2)
            write(compose(token, alpha.value))
        }
    })
    text.addEventListener("change", () => {
        if (isColor(text.value.trim())) {
            write(text.value.trim())
        }
        refresh()
    })

    refresh()
    return {
        el: el(
            "div",
            { class: "control" },
            el("div", { class: "row" }, select, text),
            channelRow("α", alpha, readout),
        ),
        refresh,
    }
}

// Lengths

function remOf(value) {
    const [, rem] = value.match(REM) ?? []
    return rem === undefined ? null : Number(rem)
}

/** A slider and a number field over a rem value; any other text is edited as text */
function buildLengthControl(token, ctx) {
    const value = ctx.value()
    // A role names one step, or was detached to one rem value; a padding of two steps is edited as text
    if (
        STEP_REFERENCE.test(value) ||
        (token.group === "role" && remOf(value) !== null)
    ) {
        return buildRoleControl(token, ctx)
    }
    if (remOf(value) === null) {
        return buildTextControl(token, ctx)
    }
    return buildRemControl(token, ctx)
}

function buildRemControl(token, ctx, { max = 4, step = 0.0625 } = {}) {
    const range = slider(0, max, step)
    const number = el("input", {
        type: "number",
        min: "0",
        step,
        "aria-label": `${token.name} rem`,
    })
    const px = el("span", { class: "v" })

    const paint = (rem) => {
        range.value = rem
        number.value = rem
        px.textContent = `${Math.round(rem * rootFontSize() * 100) / 100}px`
    }
    const refresh = () => {
        const rem = remOf(ctx.value()) ?? 0
        range.max = Math.max(max, Math.ceil(rem * 2))
        paint(rem)
    }
    const write = (rem) => {
        ctx.set(`${round(rem)}rem`)
        paint(round(rem))
    }
    range.addEventListener("input", () => write(Number(range.value)))
    number.addEventListener("change", () => write(Number(number.value)))

    refresh()
    return {
        el: el(
            "div",
            { class: "control" },
            el(
                "div",
                { class: "row" },
                range,
                number,
                el("span", { class: "k", text: "rem" }),
                px,
            ),
        ),
        refresh,
    }
}

/**
 * A role token: a select over the spacing steps, each labelled with its value, and "custom", which
 * detaches the role to a literal with the step's value, edited with the rem control
 */
function buildRoleControl(token, ctx) {
    const select = el("select", { "aria-label": `${token.name} step` })
    ctx.scaleSteps.forEach(({ name, value }) =>
        select.append(
            el("option", { value: name, text: `${name} (${value})` }),
        ),
    )
    select.append(el("option", { value: "", text: "custom (detach)" }))
    const rem = buildRemControl(token, ctx)

    const refresh = () => {
        const [, step] = ctx.value().match(STEP_REFERENCE) ?? []
        select.value = step ?? ""
        rem.el.hidden = step !== undefined
        if (step === undefined) {
            rem.refresh()
        }
    }
    select.addEventListener("change", () => {
        if (select.value === "") {
            const current = ctx.value()
            const [, step] = current.match(STEP_REFERENCE) ?? []
            const stepValue =
                ctx.scaleSteps.find(({ name }) => name === step)?.value ??
                current
            ctx.set(remOf(stepValue) === null ? current : stepValue)
        } else {
            ctx.set(`var(${select.value})`)
        }
        refresh()
    })

    refresh()
    return {
        el: el(
            "div",
            { class: "control" },
            el("div", { class: "row" }, select),
            rem.el,
        ),
        refresh,
    }
}

// Borders and shadows

/** Width, style and color of a border token */
function buildBorderControl(token, ctx) {
    const parse = () => {
        const parts = splitOutsideParentheses(ctx.value(), " ").filter(Boolean)
        const style =
            parts.find((part) => BORDER_STYLES.includes(part)) ?? "solid"
        const width =
            parts.find((part) => PX.test(part) || TOKEN_ONLY.test(part)) ??
            "1px"
        const color =
            parts
                .filter((part) => part !== style && part !== width)
                .join(" ") || "currentcolor"
        return { width, style, color }
    }
    const compose = ({ width, style, color }) => `${width} ${style} ${color}`

    const width = el("input", {
        type: "number",
        min: "0",
        step: "1",
        "aria-label": `${token.name} width`,
    })
    const widthText = el("input", {
        type: "text",
        size: "16",
        "aria-label": `${token.name} width`,
    })
    const style = el("select", { "aria-label": `${token.name} style` })
    BORDER_STYLES.forEach((name) =>
        style.append(el("option", { value: name, text: name })),
    )
    const color = buildColorSourceControl(ctx, {
        read: () => parse().color,
        write: (value) => ctx.set(compose({ ...parse(), color: value })),
    })

    const refresh = () => {
        const current = parse()
        const [, px] = current.width.match(PX) ?? []
        width.hidden = px === undefined
        widthText.hidden = px !== undefined
        width.value = px ?? ""
        widthText.value = current.width
        style.value = current.style
        color.refresh()
    }
    width.addEventListener("change", () =>
        ctx.set(compose({ ...parse(), width: `${width.value}px` })),
    )
    widthText.addEventListener("change", () =>
        ctx.set(compose({ ...parse(), width: widthText.value.trim() })),
    )
    style.addEventListener("change", () =>
        ctx.set(compose({ ...parse(), style: style.value })),
    )

    refresh()
    return {
        el: el(
            "div",
            { class: "control" },
            el(
                "div",
                { class: "row" },
                width,
                widthText,
                el("span", { class: "k", text: "px" }),
                style,
            ),
            color.el,
        ),
        refresh,
    }
}

/** The offsets, blur and spread of a shadow token, and its color */
function buildShadowControl(token, ctx) {
    const parse = () => {
        const parts = splitOutsideParentheses(ctx.value(), " ").filter(Boolean)
        const colorIndex = parts.findIndex(
            (part) => !PX.test(part) && part !== "0" && part !== "inset",
        )
        const color = colorIndex === -1 ? "currentcolor" : parts[colorIndex]
        const lengths = parts.filter(
            (part, index) => index !== colorIndex && part !== "inset",
        )
        const inset = parts.includes("inset")
        // The color comes first in `--text-shadow` and `--box-shadow`, last in `--drop-shadow`
        return {
            color,
            colorFirst: colorIndex === 0,
            inset,
            lengths: lengths.map((part) => Number(part.replace("px", ""))),
        }
    }
    const compose = ({ color, colorFirst, inset, lengths }) => {
        const offsets = lengths
            .map((value) => (value === 0 ? "0" : `${value}px`))
            .join(" ")
        return [
            inset ? "inset" : "",
            colorFirst ? color : "",
            offsets,
            colorFirst ? "" : color,
        ]
            .filter(Boolean)
            .join(" ")
    }

    const labels = ["x", "y", "blur", "spread"]
    const inputs = labels.map((label) =>
        el("input", {
            type: "number",
            step: "1",
            "aria-label": `${token.name} ${label}`,
        }),
    )
    const rows = inputs.map((input, index) =>
        channelRow(
            labels[index],
            input,
            el("span", { class: "k", text: "px" }),
        ),
    )
    const color = buildColorSourceControl(ctx, {
        read: () => parse().color,
        write: (value) => ctx.set(compose({ ...parse(), color: value })),
    })

    const refresh = () => {
        const { lengths } = parse()
        rows.forEach((row, index) => {
            row.hidden = index >= Math.max(lengths.length, 2)
            inputs[index].value = lengths[index] ?? 0
        })
        color.refresh()
    }
    inputs.forEach((input, index) =>
        input.addEventListener("change", () => {
            const current = parse()
            while (current.lengths.length <= index) {
                current.lengths.push(0)
            }
            current.lengths[index] = Number(input.value)
            ctx.set(compose(current))
        }),
    )

    refresh()
    return { el: el("div", { class: "control" }, ...rows, color.el), refresh }
}

// Fonts and typography

/** The family stack as text, with the style guide's availability check on the first family */
function buildFontControl(token, ctx) {
    const text = el("input", {
        type: "text",
        "aria-label": `${token.name} family`,
    })
    const status = el("span", { class: "status" })

    const refresh = () => {
        text.value = ctx.value()
        const family = asFontFamily(text.value.trim())
        const missing = family !== "" && !isFontAvailable(family)
        status.textContent = missing
            ? "Not available in this browser: shown in the fallback font."
            : "Available in this browser."
        status.classList.toggle("missing", missing)
    }
    text.addEventListener("change", () => {
        const value = text.value.trim()
        if (value !== "") {
            ctx.set(asFontFamily(value))
        }
        refresh()
    })

    refresh()
    return {
        el: el(
            "div",
            { class: "control" },
            el("div", { class: "row" }, text),
            status,
        ),
        refresh,
    }
}

/** A font size as a rem control, a weight, a tracking or a line height as a number */
function buildTypographyControl(token, ctx) {
    if (token.name.startsWith("--font-size-")) {
        return buildRemControl(token, ctx, { max: 3, step: 0.0625 })
    }
    if (token.name.startsWith("--font-weight-")) {
        return buildNumberControl(token, ctx, { min: 100, max: 900, step: 100 })
    }
    if (token.name.startsWith("--letter-spacing-")) {
        return buildNumberControl(token, ctx, {
            min: -0.1,
            max: 0.5,
            step: 0.01,
            unit: "em",
            pattern: EM,
        })
    }
    return buildNumberControl(token, ctx, { min: 0.8, max: 2.5, step: 0.05 })
}

/** A size in px, such as the two the server sets on their containers */
export function buildPixelControl(token, ctx, { min, max, step }) {
    return buildNumberControl(token, ctx, {
        min,
        max,
        step,
        unit: "px",
        pattern: PX,
    })
}

/** A bare number, or a number in `unit`, over a slider and a number field */
function buildNumberControl(
    token,
    ctx,
    { min, max, step, unit = "", pattern = NUMBER },
) {
    const range = slider(min, max, step)
    const number = el("input", {
        type: "number",
        min,
        max,
        step,
        "aria-label": token.name,
    })

    const paint = (value) => {
        range.value = value
        number.value = value
    }
    const refresh = () => {
        const [, value] = ctx.value().match(pattern) ?? []
        paint(value === undefined ? min : Number(value))
    }
    const write = (value) => {
        ctx.set(`${round(value)}${unit}`)
        paint(round(value))
    }
    range.addEventListener("input", () => write(Number(range.value)))
    number.addEventListener("change", () => write(Number(number.value)))

    refresh()
    return {
        el: el(
            "div",
            { class: "control" },
            el(
                "div",
                { class: "row" },
                range,
                number,
                unit ? el("span", { class: "k", text: unit }) : null,
            ),
        ),
        refresh,
    }
}

/**
 * The type scale's two controls: the base size, and a modular-scale ratio that writes every
 * `--font-size-*` step from it (`base * ratio ^ distance`, `xs` two steps below the base, `display`
 * four above), rounded to four decimals
 */
export function buildTypeScaleControls(ctx) {
    const base = el("input", {
        type: "number",
        min: "0.5",
        max: "2",
        step: "0.0625",
        "aria-label": "Base size",
    })
    const ratio = el("input", {
        type: "number",
        min: "1",
        max: "1.5",
        step: "0.01",
        value: "1.2",
        "aria-label": "Scale ratio",
    })

    const refresh = () => {
        base.value = remOf(ctx.value("--font-size-base")) ?? 0.875
    }
    const apply = () => {
        const steps = Object.fromEntries(
            TYPE_STEPS.map((step, index) => [
                `--font-size-${step}`,
                `${round(Number(base.value) * Number(ratio.value) ** (index - BASE_STEP_INDEX))}rem`,
            ]),
        )
        ctx.setMany(steps)
    }

    refresh()
    return {
        el: el(
            "div",
            { class: "group-controls" },
            el(
                "div",
                { class: "row" },
                el(
                    "label",
                    {},
                    "Base ",
                    base,
                    el("span", { class: "k", text: "rem" }),
                ),
                el("label", {}, "Ratio ", ratio),
                el("button", {
                    type: "button",
                    text: "Write the scale",
                    onclick: apply,
                }),
            ),
            el("span", {
                class: "note",
                text: "Writes every --font-size-* step as base × ratio to its distance from the base.",
            }),
        ),
        refresh,
    }
}

/**
 * The seed controls of the theme colors: one accent hue, one base lightness and one chroma derive
 * the five theme colors for the theme shown, as a starting point to edit from. The base takes the
 * lightness at a hint of the hue, the raised surface stands one step off it, the muted and the text
 * colors are set by lightness against the base, and the accent is the hue at a readable lightness.
 * They are written as resolved literals, never as a formula.
 */
export function buildSeedControls(ctx) {
    const hue = slider(0, 360, 1)
    const lightness = slider(0.1, 0.95, 0.01)
    const chroma = slider(0, 0.2, 0.005)
    const readouts = [
        el("span", { class: "v" }),
        el("span", { class: "v" }),
        el("span", { class: "v" }),
    ]

    const refresh = () => {
        const { h, c } = oklchOf(
            themeSideOf(ctx.value("--theme-accent"), ctx.theme()),
        )
        const { l } = oklchOf(
            themeSideOf(ctx.value("--theme-base"), ctx.theme()),
        )
        hue.value = h
        lightness.value = l
        chroma.value = Math.min(0.2, c)
        paint()
    }
    const paint = () => {
        readouts[0].textContent = `${Math.round(hue.value)}°`
        readouts[1].textContent = Number(lightness.value).toFixed(2)
        readouts[2].textContent = Number(chroma.value).toFixed(3)
    }
    const apply = () => {
        const h = Number(hue.value)
        const l = Number(lightness.value)
        const c = Number(chroma.value)
        const dark = l < 0.5
        const colors = {
            "--theme-base": `oklch(${l} ${c * 0.15} ${h})`,
            "--theme-raised": `oklch(${dark ? l + 0.08 : l - 0.06} ${c * 0.2} ${h})`,
            "--theme-muted": `oklch(${dark ? 0.75 : 0.45} ${c * 0.15} ${h})`,
            "--theme-text": `oklch(${dark ? 0.98 : 0.18} ${c * 0.05} ${h})`,
            "--theme-accent": `oklch(0.7 ${Math.max(c, 0.15)} ${h})`,
        }
        ctx.setMany(
            Object.fromEntries(
                Object.entries(colors).map(([name, color]) => [
                    name,
                    withThemeColor(ctx.value(name), ctx.theme(), hexOf(color)),
                ]),
            ),
        )
    }
    ;[hue, lightness, chroma].forEach((input) =>
        input.addEventListener("input", paint),
    )

    refresh()
    return {
        el: el(
            "div",
            { class: "group-controls" },
            channelRow("H", hue, readouts[0]),
            channelRow("L", lightness, readouts[1]),
            channelRow("C", chroma, readouts[2]),
            el(
                "div",
                { class: "row" },
                el("button", {
                    type: "button",
                    text: "Seed the five theme colors",
                    onclick: apply,
                }),
            ),
        ),
        refresh,
    }
}

// Any other value

function buildTextControl(token, ctx) {
    const text = el("input", { type: "text", "aria-label": token.name })
    const refresh = () => {
        text.value = ctx.value()
    }
    text.addEventListener("change", () => {
        if (text.value.trim() !== "") {
            ctx.set(text.value.trim())
        }
        refresh()
    })

    refresh()
    return {
        el: el("div", { class: "control" }, el("div", { class: "row" }, text)),
        refresh,
    }
}

// Helpers

function slider(min, max, step) {
    return el("input", { type: "range", min, max, step })
}

function channelRow(label, input, readout) {
    return el(
        "div",
        { class: "row" },
        el("span", { class: "k", text: label }),
        input,
        readout,
    )
}

function round(value) {
    return Math.round(value * 10000) / 10000
}

/** The root font size in px, which a rem is a multiple of */
export function rootFontSize() {
    return parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
}
