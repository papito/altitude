/**
 * The style guide page (dev only, `views/style_guide.scala.html`).
 *
 * The server scans the source tree (`StyleGuideScan`) and embeds what it found as JSON: the tokens
 * and where they are declared and referenced, the color literals and the icons. The `styleGuide`
 * Alpine component holds what the page's templates render from it: each `:root` token resolved
 * against the stylesheets the browser actually loaded, sorted by the kind of value it holds and
 * placed in a group, the icons and the token audit. It is also the state of the theme switcher, of
 * the font tester of the Typography section and of the alpha slider of the `--background-color`
 * card, and what the page's buttons call. The live menu and dialog specimens are no template:
 * `initStyleGuide` builds them through the modules the explorer lists use.
 *
 * The groups: a color token is one of the five theme colors (`--theme-*`), which hold the palette,
 * a status color or `--shadow-color`, which stand apart from them, or derived. A length is a step
 * of the spacing scale (`--space-*`), a role that references a step, or a size. A token whose
 * declaration references other tokens is shown as declared, a recipe, with the tokens it comes
 * from; any other as it resolves. A color, border or shadow token outside the theme, status and
 * shadow colors that writes a color literal is flagged off palette. Two tokens of one kind that
 * resolve to the same value (a color to the same color in both themes) are flagged as the same
 * value, unless both take their color from the same tokens: the aliases of one color share it by
 * design, while two recipes computing one color should be one token.
 */
import {
    buildContextMenuCtrl,
    buildModalTriggerCtrl,
} from "./common/context-menu-markup.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "./common/snackbar.js"
import { Const } from "./constants.js"
import { Alpine } from "./lib/alpine.esm.min.js"

const BORDER_STYLES =
    /\b(solid|dashed|dotted|double|groove|ridge|inset|outset)\b/

/**
 * The kinds of token value, in the order they are tested: the first whose test passes is the
 * token's kind. The order matters because the tests overlap (a color is also a valid border, and
 * lengths separated by spaces a valid shadow). A kind may have more than one entry, each drawing
 * its sample its own way. A token's sample is drawn with the token itself: its `var()` reference
 * is the value of each of `sampleProperties`, over `sampleText` for a kind that shows on text.
 */
const TOKEN_KINDS = [
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
const OTHER_KIND = { kind: "other", sampleProperties: [] }

// Two lengths are equal by coincidence far more often than by design, so they are not duplicates
const DUPLICATE_KINDS = ["color", "border", "shadow", "font"]
// The kinds whose value carries a color, the only ones that can be off palette: a word of any
// other value that the browser takes for a color (`background` in a transition) names none there
const COLOR_KINDS = ["color", "border", "shadow"]

// The color groups, by token name: the five theme colors, and the status colors and the ink of
// shadows, which stand apart from them. Every other token takes its colors from these.
const THEME_COLOR = /^--theme-/
const STATUS_OR_SHADOW_COLOR =
    /^--(?:(?:success|warning|error)-.+|shadow-color)$/
// A step of the spacing scale. A length that references one is a role, any other length a size.
const SPACING_STEP = /^--space-/

// A reference to another token: the group is its name
const TOKEN_REFERENCE = /var\(\s*(--[a-zA-Z][\w-]*)\s*\)/g

// The color literals: a hex color (as the scan finds them), a color function with its channels
// written out, which the relative form `rgb(from …)` is not, and a named color, which is a word
const HEX_COLOR = /#(?:[0-9a-f]{8}|[0-9a-f]{6}|[0-9a-f]{3,4})(?![\w-])/i
const CHANNEL_FUNCTIONS = "rgba?|hsla?|hwb|lab|lch|oklab|oklch|color"
const LITERAL_COLOR_FUNCTION = new RegExp(
    `(?<![\\w-])(?:${CHANNEL_FUNCTIONS})\\((?!\\s*from\\b)`,
    "i",
)
const WORD = /(?<![\w-])[a-z][\w-]*/gi
// The words the browser takes for a color that name none: the color of the text, and the CSS-wide
// keywords, which are valid for every property
const NOT_A_NAMED_COLOR =
    /^(?:currentcolor|inherit|initial|unset|revert|revert-layer)$/i
// Quoted text, such as a font family name, holds no color
const QUOTED_TEXT = /"[^"]*"|'[^']*'/g
// Any color function: with its channels written out, or computing a color from others
const COLOR_FUNCTION = new RegExp(
    `(?<![\\w-])(?:${CHANNEL_FUNCTIONS}|color-mix|light-dark)\\(`,
    "i",
)

// The themes, in the order `light-dark()` takes their colors
const THEMES = ["light", "dark"]
// A `light-dark()` value: the group is its two colors
const LIGHT_DARK = /^light-dark\((.*)\)$/s

// The token whose card has an alpha slider
const ALPHA_SLIDER_TOKEN = "--background-color"
// A color with its alpha replaced, as `withAlpha` writes it: the first group is the color
const ALPHA_REPLACEMENT = /^rgb\(from (.+) r g b \/ [\d.]+\)$/

// Wide glyphs and digits, so two fonts seldom measure the same
const FONT_PROBE_TEXT = "mmmmmmmmmmlliWWW0123456789"
// The typed value is tried in front of each generic family: a font that is the default of one of
// them measures the same as that fallback, and the others tell it apart
const FONT_PROBE_FALLBACKS = ["monospace", "sans-serif", "serif"]

// Referenced as `x-data="styleGuide"` by the style guide page, whose module script imports this
// module, and so registers the component, before it starts the app
Alpine.data("styleGuide", styleGuide)

/** Builds the specimens no template renders, once the app has started and the repository is set */
export function initStyleGuide() {
    mountMenuSpecimens()

    document.body.addEventListener(Const.events.styleGuideSampleSubmitted, () =>
        showSuccessSnackBar("Sample submitted. Nothing was saved."),
    )
}

/** The data and the actions of the page's templates */
function styleGuide() {
    const scan = JSON.parse(
        document.getElementById("styleGuideScan").textContent,
    )
    // A token resolves to its color in the theme the page is shown in, so the theme comes first
    const theme = rememberedTheme()
    applyTheme(theme)
    const rootTokens = resolveRootTokens(scan)
    const rootValues = new Map(
        rootTokens.map(({ name, resolved }) => [name, resolved]),
    )

    return {
        // The theme the page is shown in
        theme,
        rootTokens,
        icons: Object.keys(scan.icons)
            .sort()
            .map((name) => ({ name, count: scan.icons[name] })),
        // The tokens declared outside `:root`, and the ones only set from JavaScript
        scopedTokens: scan.scopedTokens,
        runtimeTokens: scan.runtimeTokens,
        literals: groupLiterals(scan.literals, rootTokens),
        // What is typed into the font tester
        fontInput: "",

        showSuccessSnackBar,
        showWarningSnackBar,
        showErrorSnackBar,

        isTheme(theme) {
            return this.theme === theme
        },

        /**
         * Shows the page in `theme` and remembers it for the next visit. The tokens are resolved
         * again, since the color a token holds depends on the theme, and the new tokens are the one
         * change the cards follow: a card reads nothing but its token. An alpha a slider was dragged
         * to is by then part of its token's declaration, inline on the root element, so the new
         * tokens' sliders start from what they resolve to, with no dragged alpha.
         */
        setTheme(theme) {
            this.theme = theme
            applyTheme(theme)
            localStorage.setItem(Const.localStore.styleGuideTheme, theme)

            this.rootTokens = resolveRootTokens(scan)
            this.literals = groupLiterals(scan.literals, this.rootTokens)
        },

        /**
         * The `:root` tokens of `kinds` (space-separated) in `group` (`groupOf`), or in every group
         * when none is given, one kind after another and in source order within a kind, so kinds
         * sharing a grid are not interleaved
         */
        tokensOf(kinds, group = "") {
            return kinds
                .split(" ")
                .flatMap((kind) =>
                    this.rootTokens.filter(
                        (token) =>
                            token.kind === kind &&
                            (group === "" || token.group === group),
                    ),
                )
        },

        referencesOf(name) {
            return referencesOf(scan, name)
        },

        /** A scoped token named like a `:root` token overrides it */
        overrideNote(name) {
            return rootValues.has(name)
                ? `Overrides the :root value ${rootValues.get(name)}`
                : ""
        },

        copyReference(name) {
            copy(`var(${name})`)
        },

        copyValue(token) {
            copy(this.currentValue(token))
        },

        /**
         * Where a token's alpha slider stands: at the alpha of the token's color in the theme
         * shown, until it is dragged
         */
        alphaOf(token) {
            return token.draggedAlpha ?? token.alpha
        },

        /** A token's value as its card shows it: with the alpha its slider was dragged to, if any */
        currentValue(token) {
            return token.draggedValue ?? token.value
        },

        /**
         * Gives the token `alpha` on its color of the theme shown, for as long as the page is
         * open: the value is declared inline on the root element, where it wins over the
         * stylesheet's. The alpha and the value are kept on the token, so its card depends on the
         * token alone.
         */
        setAlpha(token, alpha) {
            token.draggedAlpha = alpha
            token.draggedValue =
                alpha === token.alpha
                    ? token.value
                    : withAlpha(token.value, alpha, this.theme)
            document.documentElement.style.setProperty(
                token.name,
                token.draggedValue,
            )
        },

        /** The typed value as the specimen's `font-family`, empty while nothing is typed */
        get fontFamily() {
            const value = this.fontInput.trim()
            return value === "" ? "" : asFontFamily(value)
        },

        /** Whether the browser shows the specimen in a fallback font of its own choosing */
        get isFontMissing() {
            return this.fontFamily !== "" && !isFontAvailable(this.fontFamily)
        },

        get fontStatus() {
            if (this.fontFamily === "") {
                return "Type a font family to preview it."
            }
            return this.isFontMissing
                ? "Not available in this browser: shown in the fallback font."
                : "Available in this browser."
        },
    }
}

function kindOf(value) {
    return TOKEN_KINDS.find(({ test }) => test(value)) ?? OTHER_KIND
}

function referencesOf(scan, name) {
    return scan.references[name] ?? 0
}

function copy(text) {
    navigator.clipboard.writeText(text).then(
        () => showSuccessSnackBar(`Copied ${text}`),
        () => showErrorSnackBar(`Could not copy ${text}`),
    )
}

function usageLabel(references) {
    if (references === 0) {
        return "Unused"
    }
    return references === 1 ? "1 reference" : `${references} references`
}

/** The theme chosen on an earlier visit, else the one the stylesheet declares */
function rememberedTheme() {
    const theme = localStorage.getItem(Const.localStore.styleGuideTheme)

    return THEMES.includes(theme)
        ? theme
        : getComputedStyle(document.documentElement).colorScheme
}

/** Shows the page in `theme`: the root element declares the scheme every `light-dark()` color follows */
function applyTheme(theme) {
    document.documentElement.style.colorScheme = theme
}

/**
 * The color `value` paints in the theme the page is shown in (another while `inTheme` resolves),
 * in the browser's one serialization, so spellings of a color compare equal
 */
function resolveColor(value) {
    const probeEl = document.createElement("span")
    probeEl.style.color = value
    document.body.appendChild(probeEl)
    const color = getComputedStyle(probeEl).color
    probeEl.remove()
    return color
}

/**
 * The alpha of a color in the browser's serialization (`resolveColor`): the fourth component of
 * `rgba(r, g, b, a)`, or what follows the slash of `color(srgb r g b / a)`. A color serialized
 * without an alpha is opaque.
 */
function alphaOfColor(color) {
    const [, alpha = 1] = color.match(/(?:\/|^rgba\(.*,)\s*([\d.]+)\)$/) ?? []
    return Number(alpha)
}

/**
 * `value` with the alpha of its color replaced. Of a `light-dark()` pair only the color of `theme`
 * changes, so the value stays one declaration for both themes.
 */
function withAlpha(value, alpha, theme) {
    const colors = themeColorsOf(value)
    if (!colors) {
        return withColorAlpha(value, alpha)
    }

    const index = THEMES.indexOf(theme)
    colors[index] = withColorAlpha(colors[index], alpha)
    return `light-dark(${colors.join(", ")})`
}

/**
 * The two colors of a `light-dark()` value, in the order of `THEMES`, or nothing for any other
 * value. They are split at the comma outside every parenthesis, since a color may hold commas of
 * its own (`rgb(54, 54, 54)`).
 */
function themeColorsOf(value) {
    const [, colors] = value.match(LIGHT_DARK) ?? []
    const parts =
        colors === undefined ? [] : splitOutsideParentheses(colors, ",")

    return parts.length === THEMES.length ? parts : null
}

/**
 * `text` split at each `separator` outside every parenthesis, each part trimmed, so a part may hold
 * the separator inside a function of its own (`rgb(54, 54, 54)`, `calc(2px * 3)`)
 */
function splitOutsideParentheses(text, separator) {
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

/**
 * `color` with its alpha replaced, in relative color syntax, so the color itself stays as it is
 * written. A color that already is such a replacement (a slider's value pasted into the
 * stylesheet) gets the new alpha in place of its own, rather than a second wrapper.
 */
function withColorAlpha(color, alpha) {
    const [, base = color] = color.match(ALPHA_REPLACEMENT) ?? []
    return `rgb(from ${base} r g b / ${alpha})`
}

/**
 * The `:root` tokens of the scan with the value the loaded stylesheets give them, their kind, group
 * and sample, the tokens they reference, their reference count, whether they are off palette, the
 * names of the other tokens of that kind with the same value, and for a color its alpha. A token
 * the scan found in the source but the served stylesheet does not have yet resolves to nothing
 * (`value` is empty, `kind` is "other").
 *
 * A token's declaration is the source's, or the one an alpha slider declared inline on the root
 * element, which wins over it. Two colors are the same value when they are in both themes; a color
 * has its alpha, and names the literals of its color, in the theme the page is shown in.
 */
function resolveRootTokens(scan) {
    const rootEl = document.documentElement
    const rootStyle = getComputedStyle(rootEl)
    const declarations = new Map(
        scan.rootTokens.map(({ name, value }) => [
            name,
            rootEl.style.getPropertyValue(name).trim() || value,
        ]),
    )
    const colorSources = colorSourcesOf(declarations)

    const tokens = scan.rootTokens.map(({ name, note }) => {
        const declaration = declarations.get(name)
        // The declaration with each reference replaced by the referenced token's value
        const resolved = rootStyle.getPropertyValue(name).trim()
        const {
            kind,
            sampleProperties,
            sampleText = "",
        } = resolved ? kindOf(resolved) : OTHER_KIND
        const referencedNames = referencedNamesOf(declaration)
        const references = referencesOf(scan, name)
        const isColor = kind === "color"
        // The color in the theme shown
        const color = isColor ? resolveColor(resolved) : undefined

        return {
            name,
            note,
            // What the card shows: a recipe as declared, any other value as it resolves
            value: resolved && referencedNames.length ? declaration : resolved,
            resolved,
            kind,
            group: groupOf(name, kind, referencedNames),
            derivedFrom: referencedNames.length
                ? `From ${referencedNames.join(", ")}`
                : "",
            isOffPalette:
                COLOR_KINDS.includes(kind) &&
                paletteGroupOf(name) === "derived" &&
                holdsColorLiteral(ownPartOf(declaration)),
            // What two tokens must share to be duplicates of each other; a color's is set below
            identity: resolved,
            // The tokens it takes its color from, in one string
            colorSources: colorSources.get(name).toSorted().join(" "),
            color,
            alpha: isColor ? alphaOfColor(color) : undefined,
            hasAlphaSlider: isColor && name === ALPHA_SLIDER_TOKEN,
            // The alpha its slider was dragged to in the theme shown, and the value that gives it
            draggedAlpha: undefined,
            draggedValue: undefined,
            references,
            usage: usageLabel(references),
            sampleStyle: Object.fromEntries(
                sampleProperties.map((property) => [property, `var(${name})`]),
            ),
            sampleText,
        }
    })

    // A color's identity is its color in each theme, so colors equal in one theme only differ
    const colorTokens = tokens.filter(({ kind }) => kind === "color")
    const colorsByTheme = THEMES.map((theme) =>
        inTheme(theme, () =>
            colorTokens.map(({ resolved }) => resolveColor(resolved)),
        ),
    )
    colorTokens.forEach((token, index) => {
        token.identity = colorsByTheme
            .map((colors) => colors[index])
            .join(" | ")
    })

    tokens.forEach((token) => {
        token.duplicates = tokens
            .filter(
                (other) =>
                    other !== token &&
                    DUPLICATE_KINDS.includes(token.kind) &&
                    other.kind === token.kind &&
                    other.identity === token.identity &&
                    // The aliases of one color share it by design; a value without a color has
                    // no sources, so it is compared by its value alone
                    (token.colorSources === "" ||
                        other.colorSources !== token.colorSources),
            )
            .map((other) => other.name)
    })

    return tokens
}

/**
 * The names of the tokens each token takes its color from, by token name. A token whose
 * declaration writes a color of its own, a literal or one computed in place (`color-mix()`,
 * `light-dark()`, the relative form), is its own source, even when it computes it from other
 * tokens. Any other is an alias, whose sources are those of the tokens it references. A token that
 * carries no color, such as a length, has none.
 */
function colorSourcesOf(declarations) {
    const sources = new Map()

    const sourcesOf = (name) => {
        if (!sources.has(name)) {
            // None until found, so a cycle of references (which leaves its tokens without a
            // value) ends
            sources.set(name, [])
            const declaration = declarations.get(name) ?? ""
            const found = holdsColor(ownPartOf(declaration))
                ? [name]
                : referencedNamesOf(declaration).flatMap(sourcesOf)
            sources.set(name, [...new Set(found)])
        }
        return sources.get(name)
    }

    declarations.forEach((_, name) => sourcesOf(name))
    return sources
}

/**
 * The group a token is shown in: a length's from its name and the tokens it references (a step of
 * the spacing scale, a role that references a step, or a size), any other token's from its name
 * (`paletteGroupOf`)
 */
function groupOf(name, kind, referencedNames) {
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
function paletteGroupOf(name) {
    if (THEME_COLOR.test(name)) {
        return "theme"
    }
    return STATUS_OR_SHADOW_COLOR.test(name) ? "status-and-shadow" : "derived"
}

/** The names of the tokens `declaration` references, each once, in the order they first appear */
function referencedNamesOf(declaration) {
    const names = Array.from(
        declaration.matchAll(TOKEN_REFERENCE),
        ([, name]) => name,
    )
    return [...new Set(names)]
}

/** `declaration` without its references to other tokens: what it writes itself */
function ownPartOf(declaration) {
    return declaration.replace(TOKEN_REFERENCE, " ")
}

/**
 * Whether `text` writes a color literal: a hex color, a color function with its channels written
 * out, or a named color. A word is a named color when the browser takes it for a color, as it does
 * `transparent`, unless it is `currentcolor` or a CSS-wide keyword.
 */
function holdsColorLiteral(text) {
    const unquoted = text.replace(QUOTED_TEXT, " ")

    return (
        HEX_COLOR.test(unquoted) ||
        LITERAL_COLOR_FUNCTION.test(unquoted) ||
        (unquoted.match(WORD) ?? []).some(
            (word) =>
                !NOT_A_NAMED_COLOR.test(word) && CSS.supports("color", word),
        )
    )
}

/** Whether `text` writes a color: a literal, or a color function computing one from others */
function holdsColor(text) {
    return COLOR_FUNCTION.test(text) || holdsColorLiteral(text)
}

/**
 * What `resolve` returns while the root element declares the scheme of `theme`, which every
 * `light-dark()` color follows. The theme shown is declared again after, before the browser paints.
 */
function inTheme(theme, resolve) {
    const rootStyle = document.documentElement.style
    const shownScheme = rootStyle.colorScheme

    applyTheme(theme)
    const result = resolve()
    rootStyle.colorScheme = shownScheme
    return result
}

/**
 * The color literals, one entry per color: the spellings of a color (`#fff`, `#FFF`, `#FFFFFF`)
 * are grouped by what they resolve to, most used first. A color a `:root` token already holds in
 * the theme shown names that token as the replacement.
 */
function groupLiterals(literals, rootTokens) {
    const colorTokens = rootTokens.filter(({ kind }) => kind === "color")
    const byColor = new Map()

    literals.forEach(({ value, locations }) => {
        const color = resolveColor(value)
        const group = byColor.get(color) ?? {
            color,
            spellings: [],
            locations: [],
            tokens: colorTokens
                .filter((token) => token.color === color)
                .map(({ name }) => name),
        }
        group.spellings.push(value)
        group.locations.push(...locations)
        byColor.set(color, group)
    })

    return Array.from(byColor.values()).sort(
        (a, b) => b.locations.length - a.locations.length,
    )
}

/**
 * The live specimens: an Add button and a row's ⋯ menu, built by the functions the explorer lists
 * build theirs with, each opening the sample dialog in the shared modal host.
 */
function mountMenuSpecimens() {
    const hostEl = document.getElementById("styleGuideMenuSpecimens")
    const url = `/htmx/style-guide/r/${window.ctx.getRepoId()}/dialogs/sample`

    hostEl.appendChild(
        buildModalTriggerCtrl({
            triggerId: "styleGuideAddBtn",
            label: "Add sample",
            iconClass: "fas fa-plus",
            url,
            buttonClass: "action-button small",
        }),
    )

    hostEl.appendChild(
        buildContextMenuCtrl({
            triggerId: "styleGuideMenuCtrl",
            panelId: "styleGuideMenu",
            ariaLabel: "Actions for the sample row",
            entityAttr: "data-style-guide-specimen",
            entityId: "sample",
            actions: [
                { label: "Rename", url },
                { label: "Delete", url },
            ],
        }),
    )
    hostEl.append("A row's ⋯ menu")

    // Alpine's mutation observer initializes the new controls; htmx needs to be told
    htmx.process(hostEl)
}

/** Whether the browser renders `family` (a family, a stack or a generic keyword) with a font it has */
function isFontAvailable(family) {
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
 * The typed value as a `font-family` value: as written when it parses (a family, a stack or a
 * generic keyword), else quoted as one family name (a name with a digit, such as "Neue Haas 55").
 * It is made parseable before it is measured because the canvas font setter ignores a value it
 * cannot parse and keeps the previous font.
 */
function asFontFamily(value) {
    return CSS.supports("font-family", value) ? value : JSON.stringify(value)
}
