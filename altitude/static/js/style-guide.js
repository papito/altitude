/**
 * The style guide page (dev only, `views/style_guide.scala.html`).
 *
 * The server scans the source tree (`StyleGuideScan`) and embeds what it found as JSON: the tokens
 * and where they are declared and referenced, the color literals and the icons. This module
 * resolves each `:root` token against the stylesheets the browser actually loaded, sorts the
 * tokens into the page's sections by the kind of value they hold, and renders the icons and the
 * token audit. It also builds the live menu and dialog specimens through the modules the explorer
 * lists use, and wires the snackbar demo buttons.
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

const BORDER_STYLES =
    /\b(solid|dashed|dotted|double|groove|ridge|inset|outset)\b/

/**
 * The kinds of token value, in the order they are tested: the first whose test passes is the
 * token's kind. The order matters because the tests overlap (a color is also a valid border).
 */
const TOKEN_KINDS = [
    { kind: "color", test: (value) => CSS.supports("color", value) },
    { kind: "length", test: (value) => CSS.supports("width", value) },
    {
        kind: "border",
        test: (value) =>
            BORDER_STYLES.test(value) && CSS.supports("border", value),
    },
    { kind: "shadow", test: (value) => CSS.supports("box-shadow", value) },
    { kind: "font", test: (value) => CSS.supports("font-family", value) },
]

// Two lengths are equal by coincidence far more often than by design, so they are not duplicates
const DUPLICATE_KINDS = ["color", "border", "shadow", "font"]

const SNACKBARS = {
    success: showSuccessSnackBar,
    warning: showWarningSnackBar,
    error: showErrorSnackBar,
}

export function initStyleGuide() {
    const scan = JSON.parse(
        document.getElementById("styleGuideScan").textContent,
    )
    const rootTokens = resolveRootTokens(scan)

    renderRootTokens(rootTokens)
    renderIcons(scan.icons)
    renderScopedTokens(scan, rootTokens)
    renderLiterals(scan.literals, rootTokens)
    mountMenuSpecimens()
    bindSnackbarButtons()

    // A single-field form submits on Return; the error specimen has nowhere to submit to
    document
        .getElementById("styleGuideErrorForm")
        .addEventListener("submit", (event) => event.preventDefault())

    document.body.addEventListener(Const.events.styleGuideSampleSubmitted, () =>
        showSuccessSnackBar("Sample submitted. Nothing was saved."),
    )
}

function createEl(tag, { className, text } = {}) {
    const el = document.createElement(tag)
    if (className) {
        el.className = className
    }
    if (text !== undefined) {
        el.textContent = text
    }
    return el
}

function kindOf(value) {
    return TOKEN_KINDS.find(({ test }) => test(value))?.kind ?? "other"
}

/** The color `value` paints, in the browser's one serialization, so spellings of a color compare equal */
function resolveColor(value) {
    const probeEl = createEl("span")
    probeEl.style.color = value
    document.body.appendChild(probeEl)
    const color = getComputedStyle(probeEl).color
    probeEl.remove()
    return color
}

/**
 * The `:root` tokens of the scan with the value the loaded stylesheets give them, their kind, their
 * reference count, and the names of the other tokens of that kind with the same value. A token the
 * scan found in the source but the served stylesheet does not have yet resolves to nothing
 * (`value` is empty, `kind` is "other").
 */
function resolveRootTokens(scan) {
    const rootStyle = getComputedStyle(document.documentElement)

    const tokens = scan.rootTokens.map(({ name, note }) => {
        const value = rootStyle.getPropertyValue(name).trim()
        const kind = value ? kindOf(value) : "other"

        return {
            name,
            note,
            value,
            kind,
            // What two tokens must share to be duplicates of each other
            identity: kind === "color" ? resolveColor(value) : value,
            references: scan.references[name] ?? 0,
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

/** A specimen of the token, drawn with the token itself */
function buildSample({ name, kind }) {
    const sampleEl = createEl("div", { className: `sg-sample ${kind}` })
    const reference = `var(${name})`

    if (kind === "color") {
        sampleEl.style.backgroundColor = reference
    } else if (kind === "length") {
        sampleEl.style.width = reference
    } else if (kind === "border") {
        sampleEl.style.border = reference
    } else if (kind === "shadow") {
        // The shadow tokens are used for both boxes and text
        sampleEl.style.boxShadow = reference
        sampleEl.style.textShadow = reference
        sampleEl.textContent = "Shadow"
    } else if (kind === "font") {
        sampleEl.style.fontFamily = reference
        sampleEl.textContent = "The quick brown fox jumps over the lazy dog"
    }

    return sampleEl
}

function referencesLabel(count) {
    return count === 1 ? "1 reference" : `${count} references`
}

/** A token card: clicking it copies the token's `var()` reference */
function buildTokenCard(token) {
    const cardEl = createEl("button", { className: "sg-token" })
    cardEl.type = "button"

    cardEl.appendChild(buildSample(token))
    cardEl.appendChild(createEl("code", { text: token.name }))
    cardEl.appendChild(
        createEl("span", {
            className: token.value ? "" : "sg-flag",
            text: token.value || "Not served yet: rebuild the resources",
        }),
    )
    if (token.note) {
        cardEl.appendChild(
            createEl("span", { className: "sg-dim", text: token.note }),
        )
    }
    cardEl.appendChild(
        token.references === 0
            ? createEl("span", { className: "sg-flag", text: "Unused" })
            : createEl("span", {
                  className: "sg-dim",
                  text: referencesLabel(token.references),
              }),
    )
    if (token.duplicates.length > 0) {
        cardEl.appendChild(
            createEl("span", {
                className: "sg-flag",
                text: `Same value as ${token.duplicates.join(", ")}`,
            }),
        )
    }

    cardEl.addEventListener("click", () => copyReference(token.name))
    return cardEl
}

function copyReference(name) {
    const reference = `var(${name})`

    navigator.clipboard.writeText(reference).then(
        () => showSuccessSnackBar(`Copied ${reference}`),
        () => showErrorSnackBar(`Could not copy ${reference}`),
    )
}

/** Each token goes to the container of its kind (`data-token-kind`), in source order */
function renderRootTokens(rootTokens) {
    rootTokens.forEach((token) => {
        document
            .querySelector(`[data-token-kind="${token.kind}"]`)
            .appendChild(buildTokenCard(token))
    })
}

function renderIcons(icons) {
    const hostEl = document.getElementById("styleGuideIcons")

    Object.keys(icons)
        .sort()
        .forEach((icon) => {
            const rowEl = createEl("div")
            const iconEl = createEl("i", { className: `fas ${icon}` })
            iconEl.setAttribute("aria-hidden", "true")
            const labelEl = createEl("span")
            labelEl.appendChild(createEl("code", { text: icon }))
            labelEl.appendChild(
                createEl("span", {
                    className: "sg-dim",
                    text: ` × ${icons[icon]}`,
                }),
            )
            rowEl.appendChild(iconEl)
            rowEl.appendChild(labelEl)
            hostEl.appendChild(rowEl)
        })
}

/** A table row of plain text cells; a cell may also be an element */
function buildRow(tag, cells) {
    const rowEl = createEl("tr")
    cells.forEach((cell) => {
        const cellEl = createEl(tag)
        if (cell instanceof Node) {
            cellEl.appendChild(cell)
        } else {
            cellEl.textContent = cell
        }
        rowEl.appendChild(cellEl)
    })
    return rowEl
}

/**
 * The tokens declared outside `:root` and the ones only set from JavaScript. Their values are shown
 * as declared: the elements they apply to are not on this page, so there is nothing to resolve them
 * against. A scoped token named like a `:root` token overrides it.
 */
function renderScopedTokens(scan, rootTokens) {
    const tableEl = document.getElementById("styleGuideScopedTokens")
    const rootValues = new Map(
        rootTokens.map(({ name, value }) => [name, value]),
    )

    tableEl.appendChild(
        buildRow("th", ["Token", "Value", "Declared at", "References", ""]),
    )

    scan.scopedTokens.forEach(({ name, value, location }) => {
        tableEl.appendChild(
            buildRow("td", [
                createEl("code", { text: name }),
                value,
                location,
                scan.references[name] ?? 0,
                rootValues.has(name)
                    ? `Overrides the :root value ${rootValues.get(name)}`
                    : "",
            ]),
        )
    })

    scan.runtimeTokens.forEach(({ name, locations }) => {
        tableEl.appendChild(
            buildRow("td", [
                createEl("code", { text: name }),
                "",
                locations.join(", "),
                scan.references[name] ?? 0,
                "Set at runtime from JavaScript",
            ]),
        )
    })
}

/**
 * The color literals, one row per color: the spellings of a color (`#fff`, `#FFF`, `#FFFFFF`) are
 * grouped by what they resolve to, most used first. A color a `:root` token already holds names
 * that token as the replacement.
 */
function renderLiterals(literals, rootTokens) {
    const tableEl = document.getElementById("styleGuideLiterals")
    const colorTokens = rootTokens.filter(({ kind }) => kind === "color")
    const byColor = new Map()

    literals.forEach(({ value, locations }) => {
        const color = resolveColor(value)
        const group = byColor.get(color) ?? { spellings: [], locations: [] }
        group.spellings.push(value)
        group.locations.push(...locations)
        byColor.set(color, group)
    })

    tableEl.appendChild(
        buildRow("th", ["", "Literal", "Uses", "Token to use instead"]),
    )

    Array.from(byColor.entries())
        .sort(([, a], [, b]) => b.locations.length - a.locations.length)
        .forEach(([color, { spellings, locations }]) => {
            const swatchEl = createEl("span", { className: "sg-swatch" })
            swatchEl.style.backgroundColor = color

            const usesEl = createEl("details")
            usesEl.appendChild(
                createEl("summary", { text: String(locations.length) }),
            )
            locations.forEach((location) =>
                usesEl.appendChild(createEl("div", { text: location })),
            )

            tableEl.appendChild(
                buildRow("td", [
                    swatchEl,
                    spellings.join(", "),
                    usesEl,
                    colorTokens
                        .filter(({ identity }) => identity === color)
                        .map(({ name }) => name)
                        .join(", "),
                ]),
            )
        })
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
    hostEl.appendChild(createEl("span", { text: "A row's ⋯ menu" }))

    // Alpine's mutation observer initializes the new controls; htmx needs to be told
    htmx.process(hostEl)
}

function bindSnackbarButtons() {
    document
        .querySelectorAll("[data-style-guide-snackbar]")
        .forEach((buttonEl) => {
            const type = buttonEl.dataset.styleGuideSnackbar
            buttonEl.addEventListener("click", () =>
                SNACKBARS[type](`A ${type} message`),
            )
        })
}
