/**
 * The kind of value a `:root` token of `core.css` holds and the style guide group it is shown in.
 * A color is a theme color (the palette), a standalone one (semantic, shadow ink, Histogram
 * channels) or derived from those. A length is a spacing step, a role that references a step, or a
 * size.
 */

const BORDER_STYLES =
    /\b(solid|dashed|dotted|double|groove|ridge|inset|outset)\b/

// Each typography token pattern with the property its sample is drawn with
const TYPOGRAPHY_PROPERTIES = [
    [/^--font-size-/, "fontSize"],
    [/^--font-weight-/, "fontWeight"],
    [/^--letter-spacing-/, "letterSpacing"],
    [/^--line-height-/, "lineHeight"],
]
// Long enough to wrap in a card, so a line height shows
const TYPOGRAPHY_SAMPLE =
    "The quick brown fox jumps over the lazy dog, and the lazy dog sleeps on"

/**
 * Tested in order, the first pass wins, because the tests overlap: a color is also a valid border,
 * and spaced lengths a valid shadow. Typography comes first, by name, since a font size is also a
 * length and a weight a bare number. A sample is drawn with the token's `var()` as the value of each
 * of `sampleProperties`, over `sampleText` when it shows on text.
 */
export const TOKEN_KINDS = [
    ...TYPOGRAPHY_PROPERTIES.map(([pattern, property]) => ({
        kind: "typography",
        test: (value, name) => pattern.test(name),
        sampleProperties: [property],
        sampleText: TYPOGRAPHY_SAMPLE,
    })),
    {
        kind: "color",
        test: (value) => CSS.supports("color", value),
        sampleProperties: ["backgroundColor"],
    },
    {
        kind: "length",
        test: (value) => CSS.supports("width", value),
        sampleProperties: ["width"],
    },
    {
        // Lengths separated by spaces, such as a padding, which no one bar can draw: they pad a text
        kind: "length",
        test: (value) =>
            splitOutsideParentheses(value, " ")
                .filter((part) => part !== "")
                .every((part) => CSS.supports("width", part)),
        sampleProperties: ["padding"],
        sampleText: "Padding",
    },
    {
        kind: "border",
        test: (value) =>
            BORDER_STYLES.test(value) && CSS.supports("border", value),
        sampleProperties: ["border"],
    },
    {
        kind: "shadow",
        test: (value) => CSS.supports("box-shadow", value),
        // The shadow tokens are used for both boxes and text
        sampleProperties: ["boxShadow", "textShadow"],
        sampleText: "Shadow",
    },
    {
        kind: "font",
        test: (value) => CSS.supports("font-family", value),
        sampleProperties: ["fontFamily"],
        sampleText: "The quick brown fox jumps over the lazy dog",
    },
]

// For a value no test passes, and a token without a value
export const OTHER_KIND = { kind: "other", sampleProperties: [] }

// Every color outside these two groups is derived from them
const THEME_COLOR = /^--theme-/
const STANDALONE_COLOR =
    /^--(?:(?:success|warning|error|info)-.+|shadow-color|histogram-(?:red|green|blue))$/
// A length that references a spacing step is a role, any other a size
export const SPACING_STEP = /^--space-/

// The group is the referenced token's name
export const TOKEN_REFERENCE = /var\(\s*(--[a-zA-Z][\w-]*)\s*\)/g

/** `value` is the token's resolved value */
export function kindOf(name, value) {
    return TOKEN_KINDS.find(({ test }) => test(value, name)) ?? OTHER_KIND
}

export function groupOf(name, kind, referencedNames) {
    if (kind === "typography") {
        return "typography"
    }
    if (kind !== "length") {
        return paletteGroupOf(name)
    }
    if (SPACING_STEP.test(name)) {
        return "scale"
    }
    return referencedNames.some((referenced) => SPACING_STEP.test(referenced))
        ? "role"
        : "size"
}

export function paletteGroupOf(name) {
    if (THEME_COLOR.test(name)) {
        return "theme"
    }
    if (STANDALONE_COLOR.test(name)) {
        return "status-and-shadow"
    }
    return "derived"
}

/** Each referenced name once, in order of first appearance */
export function referencedNamesOf(declaration) {
    const names = Array.from(
        declaration.matchAll(TOKEN_REFERENCE),
        ([, name]) => name,
    )
    return [...new Set(names)]
}

/** Splits outside parentheses, so a part keeps a function's own separators (`rgb(54, 54, 54)`) */
export function splitOutsideParentheses(text, separator) {
    const parts = []
    let depth = 0
    let start = 0

    for (let index = 0; index < text.length; index++) {
        const char = text[index]

        if (char === "(") {
            depth++
        } else if (char === ")") {
            depth--
        } else if (char === separator && depth === 0) {
            parts.push(text.slice(start, index).trim())
            start = index + 1
        }
    }

    parts.push(text.slice(start).trim())
    return parts
}
