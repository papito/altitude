/**
 * The style guide page (dev only, `views/style_guide.scala.html`).
 *
 * The server scans the source tree (`StyleGuideScan`) and embeds what it found as JSON: the tokens
 * and where they are declared and referenced, the color literals and the icons. The `styleGuide`
 * Alpine component holds what the page's templates render from it: each `:root` token resolved
 * against the stylesheets the browser actually loaded and sorted by the kind of value it holds, the
 * icons and the token audit. It is also the state of the theme switcher, of the font tester of the
 * Typography section and of the alpha slider of the `--background-color` card, and what the page's
 * buttons call. The live menu and dialog specimens are no template: `initStyleGuide` builds them
 * through the modules the explorer lists use.
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
 * token's kind. The order matters because the tests overlap (a color is also a valid border).
 * A token's sample is drawn with the token itself: its `var()` reference is the value of each of
 * `sampleProperties`, over `sampleText` for a kind that shows on text.
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
        rootTokens.map(({ name, value }) => [name, value]),
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
        // The alphas the sliders were dragged to in the theme shown, by token name
        alphas: {},

        showSuccessSnackBar,
        showWarningSnackBar,
        showErrorSnackBar,

        isTheme(theme) {
            return this.theme === theme
        },

        /**
         * Shows the page in `theme` and remembers it for the next visit. The tokens are resolved
         * again, since the color a token holds depends on the theme. An alpha a slider was dragged
         * to is by then part of its token's value, declared inline on the root element, so the
         * sliders start over from what the tokens resolve to.
         */
        setTheme(theme) {
            this.theme = theme
            applyTheme(theme)
            localStorage.setItem(Const.localStore.styleGuideTheme, theme)

            this.rootTokens = resolveRootTokens(scan)
            this.literals = groupLiterals(scan.literals, this.rootTokens)
            this.alphas = {}
        },

        /**
         * The `:root` tokens of `kinds` (space-separated), one kind after another and in source
         * order within a kind, so kinds sharing a grid are not interleaved
         */
        tokensOf(kinds) {
            return kinds
                .split(" ")
                .flatMap((kind) =>
                    this.rootTokens.filter((token) => token.kind === kind),
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
            return this.alphas[token.name] ?? token.alpha
        },

        /**
         * A token's value: as declared, or with the alpha its slider was dragged to on its color
         * of the theme shown
         */
        currentValue(token) {
            const alpha = this.alphaOf(token)
            return alpha === token.alpha
                ? token.value
                : withAlpha(token.value, alpha, this.theme)
        },

        /**
         * Gives the token `alpha` for as long as the page is open: the value is declared inline on
         * the root element, where it wins over the stylesheet's
         */
        setAlpha(token, alpha) {
            this.alphas[token.name] = alpha
            document.documentElement.style.setProperty(
                token.name,
                this.currentValue(token),
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
 * The color `value` paints in the theme the page is shown in, in the browser's one serialization,
 * so spellings of a color compare equal
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
    const [, colors = ""] = value.match(LIGHT_DARK) ?? []
    let depth = 0

    for (let index = 0; index < colors.length; index++) {
        const char = colors[index]

        if (char === "(") {
            depth++
        } else if (char === ")") {
            depth--
        } else if (char === "," && depth === 0) {
            return [
                colors.slice(0, index).trim(),
                colors.slice(index + 1).trim(),
            ]
        }
    }

    return null
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
 * The `:root` tokens of the scan with the value the loaded stylesheets give them, their kind and
 * sample, their reference count, the names of the other tokens of that kind with the same value,
 * and for a color its alpha. Two colors are the same, and a color has its alpha, in the theme the
 * page is shown in. A token the scan found in the source but the served stylesheet does not have
 * yet resolves to nothing (`value` is empty, `kind` is "other").
 */
function resolveRootTokens(scan) {
    const rootStyle = getComputedStyle(document.documentElement)

    const tokens = scan.rootTokens.map(({ name, note }) => {
        const value = rootStyle.getPropertyValue(name).trim()
        const {
            kind,
            sampleProperties,
            sampleText = "",
        } = value ? kindOf(value) : OTHER_KIND
        const references = referencesOf(scan, name)
        const isColor = kind === "color"
        // What two tokens must share to be duplicates of each other
        const identity = isColor ? resolveColor(value) : value

        return {
            name,
            note,
            value,
            kind,
            identity,
            // The alpha of a color, in the theme shown
            alpha: isColor ? alphaOfColor(identity) : undefined,
            hasAlphaSlider: isColor && name === ALPHA_SLIDER_TOKEN,
            references,
            usage: usageLabel(references),
            sampleStyle: Object.fromEntries(
                sampleProperties.map((property) => [property, `var(${name})`]),
            ),
            sampleText,
        }
    })

    tokens.forEach((token) => {
        token.duplicates = tokens
            .filter(
                (other) =>
                    other !== token &&
                    DUPLICATE_KINDS.includes(token.kind) &&
                    other.kind === token.kind &&
                    other.identity === token.identity,
            )
            .map((other) => other.name)
    })

    return tokens
}

/**
 * The color literals, one entry per color: the spellings of a color (`#fff`, `#FFF`, `#FFFFFF`)
 * are grouped by what they resolve to, most used first. A color a `:root` token already holds
 * names that token as the replacement.
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
                .filter(({ identity }) => identity === color)
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
