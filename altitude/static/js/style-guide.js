/**
 * The style guide page (dev only, `views/style_guide.scala.html`).
 *
 * The server scans the source tree (`StyleGuideScan`) and embeds what it found as JSON: the tokens
 * and where they are declared and referenced, the color literals and the icons. The `styleGuide`
 * Alpine component holds what the page's templates render from it: each `:root` token resolved
 * against the stylesheets the browser actually loaded, sorted by the kind of value it holds (or,
 * for a typography token, by its name) and placed in a group, the icons and the token audit. It
 * is also the state of the theme switcher, of the font tester of the Typography section and of
 * the alpha slider of the `--scrim-color` card, and what the page's buttons call. The live menu and dialog specimens are no template:
 * `initStyleGuide` builds them through the modules the explorer lists use.
 *
 * The kinds and the groups are `common/token-kinds.js`'s (the design tweaks panel's adapter,
 * `js/design-tweaks.js`, configures the same sections); the themes are `common/theme.js`'s. A
 * token whose declaration references other tokens is shown as declared, a recipe, with the tokens
 * it comes from; any other as it resolves. A color, border or shadow token outside
 * the theme, status and shadow colors that writes a color literal is flagged off
 * palette. Two tokens of one kind that
 * resolve to the same value (a color to the same color in both themes) are flagged as the same
 * value, unless both take their color from the same tokens: the aliases of one color share it by
 * design, while two recipes computing one color should be one token.
 */
import {
    buildChoiceMenuCtrl,
    buildContextMenuCtrl,
    buildModalTriggerCtrl,
} from "./common/context-menu-markup.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "./common/snackbar.js"
import { asFontFamily, isFontAvailable } from "./common/font-availability.js"
import {
    applyTheme,
    inTheme,
    rememberedTheme,
    resolveColor,
    THEMES,
    themeColorsOf,
    withThemeColor,
} from "./common/theme.js"
import {
    groupOf,
    kindOf,
    OTHER_KIND,
    paletteGroupOf,
    referencedNamesOf,
    TOKEN_REFERENCE,
} from "./common/token-kinds.js"
import { Const } from "./constants.js"
import { Alpine } from "./lib/alpine.esm.min.js"

// Two lengths are equal by coincidence far more often than by design, so they are not duplicates
const DUPLICATE_KINDS = ["color", "border", "shadow", "font"]
// The kinds whose value carries a color, the only ones that can be off palette: a word of any
// other value that the browser takes for a color (`background` in a transition) names none there
const COLOR_KINDS = ["color", "border", "shadow"]

// The color literals: a hex color (as the scan finds them), a color function with its channels
// written out, which the relative form `hsl(from …)` is not, and a named color, which is a word
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

// The token whose card has an alpha slider
const SVG_NS = "http://www.w3.org/2000/svg"
const ALPHA_SLIDER_TOKEN = "--scrim-color"
// A color with its alpha replaced, as `withAlpha` writes it: the groups are the function, the
// color and the channels
const ALPHA_REPLACEMENT = /^(rgb|hsl)\(from (.+) (r g b|h s l) \/ [\d.]+\)$/

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
        // The px, rem and em literals of the sizing properties, each with its property and locations
        lengthLiterals: scan.lengthLiterals ?? [],
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
    const color = colors ? colors[THEMES.indexOf(theme)] : value
    return withThemeColor(value, theme, withColorAlpha(color, alpha))
}

/**
 * `color` with its alpha replaced, in HSL relative color syntax as the veils of `core.css` write
 * it, so the color itself stays as it is written. A color that already is such a replacement, in
 * HSL or RGB (a veil, or a slider's value pasted into the stylesheet), gets the new alpha in place
 * of its own, in its own syntax, rather than a second wrapper.
 */
function withColorAlpha(color, alpha) {
    const [, fn = "hsl", base = color, channels = "h s l"] =
        color.match(ALPHA_REPLACEMENT) ?? []
    return `${fn}(from ${base} ${channels} / ${alpha})`
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
        } = resolved ? kindOf(name, resolved) : OTHER_KIND
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
 * build theirs with, each opening the sample dialog as a popover dialog below it, and a choice
 * menu whose options run nothing.
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

    // A choice menu from a static host of options, as the results fragment renders Group and Sort
    const choiceHostEl = document.createElement("div")
    choiceHostEl.id = "styleGuideChoiceHost"
    choiceHostEl.dataset.choiceMenu = "Sort"
    choiceHostEl.dataset.choiceMenuIcon = "fas fa-sort-amount-down"
    ;["Newest first", "Oldest first", "File name"].forEach((label, index) => {
        const optionEl = document.createElement("button")
        optionEl.type = "button"
        optionEl.textContent = label
        if (index === 0) {
            optionEl.setAttribute("aria-checked", "true")
        }
        choiceHostEl.appendChild(optionEl)
    })
    hostEl.appendChild(choiceHostEl)
    buildChoiceMenuCtrl(choiceHostEl)

    // A compact choice menu, as the explorer's Dock side menu is rendered: an option is its glyph
    // (the symbols of includes/dock_glyphs, included by the page) and its label
    const compactHostEl = document.createElement("div")
    compactHostEl.id = "styleGuideCompactChoiceHost"
    compactHostEl.dataset.choiceMenu = "Dock side"
    compactHostEl.dataset.choiceMenuCompact = ""
    Object.values(Const.dockSides).forEach((side, index) => {
        const optionEl = document.createElement("button")
        optionEl.type = "button"
        const glyphEl = document.createElementNS(SVG_NS, "svg")
        glyphEl.setAttribute("class", "glyph")
        glyphEl.setAttribute("aria-hidden", "true")
        const useEl = document.createElementNS(SVG_NS, "use")
        useEl.setAttribute("href", `#dockGlyph-${side}`)
        glyphEl.appendChild(useEl)
        const labelEl = document.createElement("span")
        labelEl.textContent = side.charAt(0).toUpperCase() + side.slice(1)
        optionEl.append(glyphEl, labelEl)
        if (index === 0) {
            optionEl.setAttribute("aria-checked", "true")
        }
        compactHostEl.appendChild(optionEl)
    })
    hostEl.appendChild(compactHostEl)
    buildChoiceMenuCtrl(compactHostEl)
    hostEl.append("A compact choice menu")

    // Alpine's mutation observer initializes the new controls; htmx needs to be told
    htmx.process(hostEl)
}
