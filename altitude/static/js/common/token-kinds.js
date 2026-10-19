/**
 * What kind of value a `:root` token of `core.css` holds, and the group it is shown in: the style
 * guide (`js/style-guide.js`) places the tokens by these.
 *
 * The groups: a color token is a theme color (`--theme-*`: the two seeds, Base and Accent, and the
 * recipes over them, which together hold the palette), a status color (or a status fill, derived
 * from its color) or `--shadow-color`, which stand apart from them, or derived: a veil, a theme
 * color or `--shadow-color` at an alpha. A length is a
 * step of the spacing scale (`--space-*`), a role that references a step, or a size. The typography
 * tokens, a step of the type scale (`--font-size-*`), a weight, a tracking or a line height, are a
 * kind by name, since a size is a length and a weight a bare number.
 */

const BORDER_STYLES =
    /\b(solid|dashed|dotted|double|groove|ridge|inset|outset)\b/

// The typography tokens, by name, each with the property its sample is drawn with
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
 * The kinds of token, in the order they are tested: the first whose test passes is the token's
 * kind. A test sees the token's value and its name. The order matters because the tests overlap
 * (a color is also a valid border, and lengths separated by spaces a valid shadow), and the
 * typography tokens come first because they are a kind by name: a font size is also a length, and
 * a weight or a line height a bare number no value test tells from any other. A kind may have more
 * than one entry, each drawing its sample its own way. A token's sample is drawn with the token
 * itself: its `var()` reference is the value of each of `sampleProperties`, over `sampleText` for
 * a kind that shows on text.
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

// The kind of a value none of the tests pass, and of a token without a value
export const OTHER_KIND = { kind: "other", sampleProperties: [] }

// The color groups, by token name: the theme colors (the seeds and the recipes over them), and the
// status colors and the ink of shadows, which stand apart from them. Every other token takes its
// colors from these.
const THEME_COLOR = /^--theme-/
const STATUS_OR_SHADOW_COLOR =
    /^--(?:(?:success|warning|error)-.+|shadow-color)$/
// A step of the spacing scale. A length that references one is a role, any other length a size.
export const SPACING_STEP = /^--space-/

// A reference to another token: the group is its name
export const TOKEN_REFERENCE = /var\(\s*(--[a-zA-Z][\w-]*)\s*\)/g

/** The kind entry of a token: the first of `TOKEN_KINDS` whose test its resolved value and name pass */
export function kindOf(name, value) {
    return TOKEN_KINDS.find(({ test }) => test(value, name)) ?? OTHER_KIND
}

/**
 * The group a token is shown in: a length's from its name and the tokens it references (a step of
 * the spacing scale, a role that references a step, or a size), a typography token's its kind, any
 * other token's from its name (`paletteGroupOf`)
 */
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

/** The color group a token's name places it in: "theme", "status-and-shadow", or else "derived" */
export function paletteGroupOf(name) {
    if (THEME_COLOR.test(name)) {
        return "theme"
    }
    if (STATUS_OR_SHADOW_COLOR.test(name)) {
        return "status-and-shadow"
    }
    return "derived"
}

/** The names of the tokens `declaration` references, each once, in the order they first appear */
export function referencedNamesOf(declaration) {
    const names = Array.from(
        declaration.matchAll(TOKEN_REFERENCE),
        ([, name]) => name,
    )
    return [...new Set(names)]
}

/**
 * `text` split at each `separator` outside every parenthesis, each part trimmed, so a part may hold
 * the separator inside a function of its own (`rgb(54, 54, 54)`, `calc(2px * 3)`)
 */
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
