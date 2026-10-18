/**
 * The three ways a tweak set leaves the design tweaks panel.
 *
 * The prompt names only the tokens that differ from the bundle, grouped as the panel groups them,
 * each with a qualitative phrase (lighter, more saturated, wider, …) and the exact value, naming the
 * side of a `light-dark()` pair that changed; the explorer width and the preview and face box sizes
 * are named as the JavaScript and server constants they are, not as tokens. The CSS is the
 * replacement declaration lines for `core.css`, each with its file and line from the scan, as a
 * before/after block an edit applies verbatim. The snapshot is the JSON the store writes.
 */
import { THEMES, themeColorsOf } from "../common/theme.js"
import { CONTAINER_TOKENS } from "./tweaks-store.js"
import { hexOf, oklchOf } from "./controls.js"

// The server constants behind the two container tokens, for the prompt
const CONTAINER_CONSTANTS = {
    "--preview-box": "Const.AssetView.PREVIEW_BOX_PIXELS",
    "--face-box": "FaceDetectionService.faceDetectionBoxPx",
}

/**
 * `sections` is the panel's list of `{ title, tokens }`; `tokensByName` maps a name to its token
 * (`declared`, the bundle's text, and `location` from the scan); `store` holds the tweaks
 */
export function buildPrompt({ sections, store, explorerDefault }) {
    const lines = []

    sections.forEach(({ title, tokens }) => {
        // The container tokens are named below as the constants they are
        const changed = tokens.filter(
            (token) =>
                store.isChanged(token.name) &&
                !(token.name in CONTAINER_TOKENS),
        )
        if (!changed.length) {
            return
        }
        lines.push(`${title}:`)
        changed.forEach((token) =>
            lines.push(`- ${describeChange(token, store.valueOf(token.name))}`),
        )
        lines.push("")
    })

    const constants = []
    Object.keys(CONTAINER_TOKENS).forEach((name) => {
        if (store.isChanged(name)) {
            constants.push(
                `- ${CONTAINER_CONSTANTS[name]}: ${parseInt(store.valueOf(name), 10)} (the ${name} size)`,
            )
        }
    })
    if (store.explorer !== null) {
        const sizes = `[${store.explorer}, ${100 - store.explorer}]`
        const defaults = `[${explorerDefault}, ${100 - explorerDefault}]`
        constants.push(
            `- the explorer split (Split.js sizes in js/common/explorer-split.js): ${sizes} instead of ${defaults}`,
        )
    }
    if (constants.length) {
        lines.push("Constants, not tokens:", ...constants, "")
    }

    if (!lines.length) {
        return "No token differs from the bundle."
    }
    return [
        "Apply these design changes to core.css. Each token's new value is exact.",
        "",
        ...lines,
    ]
        .join("\n")
        .trim()
}

/** `--name: phrase, from <before> to <after>` */
function describeChange(token, value) {
    const before = token.declared
    const sides = changedSides(before, value)
    const side =
        sides.length === 1
            ? ` (${sides[0]} side)`
            : sides.length === 2
              ? " (both sides)"
              : ""
    return `${token.name}${side}: ${phraseFor(token, before, value, sides)}; from \`${before}\` to \`${value}\``
}

/** The themes whose color of a pair changed, or [] for a value that is no pair */
function changedSides(before, after) {
    const a = themeColorsOf(before)
    const b = themeColorsOf(after)
    if (!a || !b) {
        return []
    }
    return THEMES.filter((theme, index) => a[index] !== b[index])
}

function phraseFor(token, before, after, sides) {
    if (token.kind === "color") {
        const theme = sides[0]
        const index = theme ? THEMES.indexOf(theme) : -1
        const from = index === -1 ? before : themeColorsOf(before)[index]
        const to = index === -1 ? after : themeColorsOf(after)[index]
        return colorPhrase(from, to)
    }
    if (token.kind === "length" || token.kind === "typography") {
        return lengthPhrase(before, after)
    }
    return "changed"
}

const HUE_NAMES = [
    [20, "red"],
    [50, "orange"],
    [90, "yellow"],
    [150, "green"],
    [200, "teal"],
    [260, "blue"],
    [300, "purple"],
    [340, "magenta"],
    [360, "red"],
]

/** Lighter or darker, more or less saturated, and a hue shift, between two colors; "the same color" when none shows */
function colorPhrase(from, to) {
    if (!CSS.supports("color", from) || !CSS.supports("color", to)) {
        return "changed"
    }
    const a = oklchOf(from)
    const b = oklchOf(to)
    const parts = []
    const dl = b.l - a.l
    const dc = b.c - a.c
    if (Math.abs(dl) >= 0.02) {
        parts.push(
            `${Math.abs(dl) >= 0.15 ? "much " : Math.abs(dl) < 0.05 ? "slightly " : ""}${dl > 0 ? "lighter" : "darker"}`,
        )
    }
    if (Math.abs(dc) >= 0.01) {
        parts.push(
            `${Math.abs(dc) < 0.03 ? "slightly " : ""}${dc > 0 ? "more" : "less"} saturated`,
        )
    }
    if (b.c >= 0.02 && (a.c < 0.02 || hueDistance(a.h, b.h) >= 12)) {
        parts.push(
            `${a.c < 0.02 ? "tinted" : "hue shifted"} toward ${hueName(b.h)}`,
        )
    }
    if (Math.abs(b.alpha - a.alpha) >= 0.02) {
        parts.push(
            `${b.alpha > a.alpha ? "more" : "less"} opaque (alpha ${b.alpha.toFixed(2)})`,
        )
    }
    const phrase = parts.length
        ? parts.join(", ")
        : "the same color, written differently"
    return `${phrase} (${hexOf(to)})`
}

function hueDistance(a, b) {
    const d = Math.abs(a - b) % 360
    return d > 180 ? 360 - d : d
}

function hueName(hue) {
    return HUE_NAMES.find(([upTo]) => hue < upTo)?.[1] ?? "red"
}

const NUMBER_WITH_UNIT = /^(-?\d*\.?\d+)(rem|px|em)?$/

function lengthPhrase(before, after) {
    const [, a, unitA = ""] = before.match(NUMBER_WITH_UNIT) ?? []
    const [, b, unitB = ""] = after.match(NUMBER_WITH_UNIT) ?? []
    if (a === undefined || b === undefined || unitA !== unitB) {
        return "changed"
    }
    const delta = Number(b) - Number(a)
    const ratio = Number(a) === 0 ? 0 : Number(b) / Number(a)
    const size =
        ratio >= 1.5 || ratio <= 0.66
            ? "much "
            : Math.abs(ratio - 1) < 0.15
              ? "slightly "
              : ""
    const direction = delta > 0 ? "larger" : "smaller"
    return `${size}${direction} (${delta > 0 ? "+" : ""}${Math.round(delta * 10000) / 10000}${unitA})`
}

/**
 * The before and after declaration lines of every changed `:root` token, with the file and line the
 * scan found the declaration at; the lines are indented as `core.css` indents its `:root` block
 */
export function buildCss({ sections, store }) {
    const blocks = []

    sections.forEach(({ tokens }) =>
        tokens.forEach((token) => {
            if (
                !store.isChanged(token.name) ||
                token.name in CONTAINER_TOKENS
            ) {
                return
            }
            const before = token.sourceValue ?? token.declared
            blocks.push(
                [
                    `/* ${token.location ?? "declared in the served bundle only"} */`,
                    `-    ${token.name}: ${before};`,
                    `+    ${token.name}: ${store.valueOf(token.name)};`,
                ].join("\n"),
            )
        }),
    )

    const notes = Object.keys(CONTAINER_TOKENS)
        .filter((name) => store.isChanged(name))
        .map(
            (name) =>
                `/* ${name}: set ${CONTAINER_CONSTANTS[name]} to ${parseInt(store.valueOf(name), 10)} */`,
        )
    if (store.explorer !== null) {
        notes.push(
            `/* explorer split: DEFAULT_SIZES in js/common/explorer-split.js, [${store.explorer}, ${100 - store.explorer}] */`,
        )
    }

    if (!blocks.length && !notes.length) {
        return "/* No token differs from the bundle. */"
    }
    return [
        "/* core.css: replace each - line with its + line (the file and line of the declaration above each) */",
        "",
        ...blocks,
        ...(notes.length ? ["", ...notes] : []),
    ].join("\n")
}

export function buildSnapshotJson(store) {
    return store.exportSnapshot()
}
