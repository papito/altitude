/**
 * The style guide page (dev only). The server embeds its source scan as JSON, and the `styleGuide`
 * component resolves each `:root` token against the stylesheets the browser actually loaded. A token
 * whose declaration references others is shown as declared (a recipe), any other as it resolves. A
 * color, border or shadow token outside the theme, status and shadow colors that writes a color
 * literal is off palette. Two tokens of one kind resolving to the same value (a color, in both
 * themes) are duplicates unless both take their color from the same tokens: the aliases of one color
 * share it by design, while two recipes computing one color should be one token.
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
    channelPaths,
    HISTOGRAM_BOX,
    HISTOGRAM_TALLEST,
} from "./common/histogram.js"
import {
    applyTheme,
    chooseTheme,
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
import { openSearchPanel } from "./search-results/search-panel.js"
import { Alpine } from "./lib/alpine.esm.min.js"

// Two lengths are equal by coincidence far more often than by design, so they are not duplicates
const DUPLICATE_KINDS = ["color", "border", "shadow", "font"]
// The only kinds that can be off palette: a word of another value that the browser takes for a
// color (`background` in a transition) names none there
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

const SVG_NS = "http://www.w3.org/2000/svg"
const ALPHA_SLIDER_TOKEN = "--scrim-color"
// A color with its alpha replaced, as `withAlpha` writes it: the groups are the function, the
// color and the channels
const ALPHA_REPLACEMENT = /^(rgb|hsl)\(from (.+) (r g b|h s l) \/ [\d.]+\)$/

// Registered on import, before the page starts the app
Alpine.data("styleGuide", styleGuide)

/** Builds the specimens no template renders, once the app has started and the repository is set */
export function initStyleGuide() {
    mountMenuSpecimens()

    document.body.addEventListener(Const.events.styleGuideSampleSubmitted, () =>
        showSuccessSnackBar("Sample submitted. Nothing was saved."),
    )
}

/** A sample Histogram's paths, scaled as the server's bytes are: the tallest bin of the four at the box's top */
function sampleHistogramPaths() {
    const bins = HISTOGRAM_BOX.width
    const channel = (...bumps) =>
        Array.from({ length: bins }, (_, i) =>
            bumps.reduce(
                (sum, [center, width, height]) =>
                    sum + height * Math.exp(-(((i - center) / width) ** 2)),
                0,
            ),
        )
    const raw = {
        luminance: channel([26, 10, 0.8], [48, 6, 0.4]),
        red: channel([30, 9, 0.7], [52, 5, 0.5]),
        green: channel([24, 11, 0.9]),
        blue: channel([18, 8, 0.6], [44, 7, 0.3]),
    }
    const tallest = Math.max(...Object.values(raw).flat())
    return channelPaths(
        Object.fromEntries(
            Object.entries(raw).map(([name, values]) => [
                name,
                values.map((v) =>
                    Math.round((v / tallest) * HISTOGRAM_TALLEST),
                ),
            ]),
        ),
    )
}

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
        theme,
        rootTokens,
        icons: Object.keys(scan.icons)
            .sort()
            .map((name) => ({ name, count: scan.icons[name] })),
        // The tokens declared outside `:root`, and the ones only set from JavaScript
        scopedTokens: scan.scopedTokens,
        runtimeTokens: scan.runtimeTokens,
        literals: groupLiterals(scan.literals, rootTokens),
        lengthLiterals: scan.lengthLiterals ?? [],
        fontInput: "",
        // The Edit panel specimen's Histogram; its Clipping blink only shows the pressed look
        editHistogram: sampleHistogramPaths(),
        clippingBlink: false,

        showSuccessSnackBar,
        showWarningSnackBar,
        showErrorSnackBar,

        init() {
            document.body.addEventListener(
                Const.events.themeChanged,
                ({ detail }) => this.followTheme(detail.theme),
            )
        },

        toggleClippingBlink() {
            this.clippingBlink = !this.clippingBlink
        },

        isTheme(theme) {
            return this.theme === theme
        },

        setTheme(theme) {
            chooseTheme(theme)
        },

        /**
         * The tokens are resolved again, since a token's color depends on the theme. A dragged alpha
         * is by then part of its token's inline declaration, so the new sliders start from it.
         */
        followTheme(theme) {
            this.theme = theme
            this.rootTokens = resolveRootTokens(scan)
            this.literals = groupLiterals(scan.literals, this.rootTokens)
        },

        /** One kind after another, in source order within a kind, so kinds sharing a grid are not interleaved */
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

        alphaOf(token) {
            return token.draggedAlpha ?? token.alpha
        },

        currentValue(token) {
            return token.draggedValue ?? token.value
        },

        /**
         * Declared inline on the root element, where it wins over the stylesheet's, while the page is
         * open. Kept on the token too, so its card depends on the token alone.
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

        get fontFamily() {
            const value = this.fontInput.trim()
            return value === "" ? "" : asFontFamily(value)
        },

        /** Whether the browser shows the specimen in a fallback font of its own */
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

/** The fourth component of `rgba(r, g, b, a)`, or what follows the slash of `color(srgb r g b / a)` */
function alphaOfColor(color) {
    const [, alpha = 1] = color.match(/(?:\/|^rgba\(.*,)\s*([\d.]+)\)$/) ?? []
    return Number(alpha)
}

/** Of a `light-dark()` pair only the color of `theme` changes, so the value stays one declaration */
function withAlpha(value, alpha, theme) {
    const colors = themeColorsOf(value)
    const color = colors ? colors[THEMES.indexOf(theme)] : value
    return withThemeColor(value, theme, withColorAlpha(color, alpha))
}

/**
 * In HSL relative color syntax, so the color itself stays as written. A color that already is such a
 * replacement gets the new alpha in place of its own rather than a second wrapper.
 */
function withColorAlpha(color, alpha) {
    const [, fn = "hsl", base = color, channels = "h s l"] =
        color.match(ALPHA_REPLACEMENT) ?? []
    return `${fn}(from ${base} ${channels} / ${alpha})`
}

/**
 * The scan's `:root` tokens as the loaded stylesheets resolve them. A token the served stylesheet
 * does not have yet resolves to nothing. A declaration an alpha slider set inline on the root
 * element wins over the source's.
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
            colorSources: colorSources.get(name).toSorted().join(" "),
            color,
            alpha: isColor ? alphaOfColor(color) : undefined,
            hasAlphaSlider: isColor && name === ALPHA_SLIDER_TOKEN,
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
 * The tokens each token takes its color from. A token writing a color of its own, a literal or one
 * computed in place, is its own source; an alias has the sources of the tokens it references.
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

/** A named color is a word the browser takes for a color, as it does `transparent` */
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
 * One entry per color, its spellings grouped by what they resolve to, most used first. A color a
 * `:root` token already holds in the theme shown names that token as the replacement.
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

/** The live menu specimens, built by the functions the explorer lists build theirs with */
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
            // A folder's shape: Search here, a rule, then the dialogs
            actions: [
                { label: "Search here", run: () => openSearchPanel() },
                { separator: true },
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

    // A compact choice menu, as the explorer's Dock side menu is rendered: each option a glyph and a label
    const compactHostEl = document.createElement("div")
    compactHostEl.id = "styleGuideCompactChoiceHost"
    compactHostEl.dataset.choiceMenu = "Dock side"
    compactHostEl.dataset.choiceMenuCompact = ""
    Object.values(Const.dockSides).forEach((side, index) => {
        const optionEl = document.createElement("button")
        optionEl.type = "button"
        const glyphEl = document.createElementNS(SVG_NS, "svg")
        glyphEl.setAttribute("class", "glyph")
        glyphEl.setAttribute("width", "16")
        glyphEl.setAttribute("height", "16")
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
