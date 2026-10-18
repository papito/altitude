/**
 * The design tweaks panel (dev only, loaded by `includes/header_common` on every page): changes the
 * value of any `:root` token of the bundle live, on the real markup and data of the running app.
 *
 * The entry reads the tokens from the served bundle (the `CSSStyleSheet` whose href is under
 * `/assets/`: every custom property its `:root` rules declare, with its declared text, and the
 * `@property` registrations), fetches the source scan for the declarations' locations and the
 * literals (`GET /style-guide/scan.json`), builds the panel in a shadow root on a `popover="manual"` host,
 * in the top layer above every dialog, and applies the stored tweak set, which the store adopted
 * before the first paint (`tweaks-store.js`). The nav's Tweaks button and `Ctrl+Alt+T` toggle it.
 *
 * The sections are the style guide's (`common/token-kinds.js`); the controls are one per kind
 * (`controls.js`); Inspect picks an element and filters the panel to its tokens (`inspect.js`);
 * the Export tab holds the prompt, the CSS lines and the snapshot (`exports.js`).
 */
import {
    explorerPercent,
    hasExplorerSplit,
    setExplorerSizes,
} from "../common/explorer-split.js"
import { applyTheme, THEMES, themeOrStylesheets } from "../common/theme.js"
import {
    groupOf,
    kindOf,
    OTHER_KIND,
    referencedNamesOf,
    SPACING_STEP,
} from "../common/token-kinds.js"
import { http } from "../http/client.js"
import {
    buildControl,
    buildPixelControl,
    buildSeedControls,
    buildTypeScaleControls,
    el,
} from "./controls.js"
import { buildCss, buildPrompt, buildSnapshotJson } from "./exports.js"
import { Inspector } from "./inspect.js"
import { panelSheet } from "./panel.css.js"
import { CONTAINER_TOKENS, DOCKS, TweaksStore } from "./tweaks-store.js"

const HOST_ID = "designTweaks"
const BUTTON_ID = "designTweaksBtn"

// The panel's sections, in order: the style guide's groups, then the two container tokens and the
// explorer width under Panels, and whatever no kind claims
const SECTIONS = [
    {
        id: "theme",
        title: "Theme colors",
        kinds: ["color"],
        group: "theme",
        seed: true,
    },
    {
        id: "status",
        title: "Status and shadow",
        kinds: ["color"],
        group: "status-and-shadow",
    },
    { id: "derived", title: "Derived", kinds: ["color"], group: "derived" },
    {
        id: "typography",
        title: "Typography",
        kinds: ["font", "typography"],
        typeScale: true,
    },
    { id: "scale", title: "Scale", kinds: ["length"], group: "scale" },
    { id: "roles", title: "Roles", kinds: ["length"], group: "role" },
    { id: "sizes", title: "Sizes", kinds: ["length"], group: "size" },
    {
        id: "borders",
        title: "Borders and shadows",
        kinds: ["border", "shadow"],
    },
    { id: "panels", title: "Panels", kinds: ["container"], panels: true },
    { id: "other", title: "Other", kinds: ["other"] },
]

// The two tokens the server sets inline, with the server constants' values, for a page without
// their containers; a page that has one reads the inline value instead
const CONTAINER_DEFAULTS = {
    "--preview-box": "200px",
    "--face-box": "80px",
}

const EXPLORER_DEFAULT = 25
const TABS = [
    ["tokens", "Tokens"],
    ["snapshots", "Snapshots"],
    ["exports", "Export"],
    ["unreachable", "Not reachable"],
]

start().catch((error) =>
    console.error("The design tweaks panel did not start", error),
)

async function start() {
    const store = new TweaksStore()
    if (store.theme !== null) {
        applyTheme(store.theme)
    }

    const bundle = await bundleSheet()
    if (!bundle) {
        console.warn(
            "The design tweaks panel found no stylesheet under /assets/",
        )
        return
    }
    const scan = await fetchScan()
    const tokens = readTokens(bundle, scan)
    const panel = new Panel({ store, tokens, scan, bundle })
    document.body.append(panel.host)

    // The explorer split is made by the main page's own script, at DOMContentLoaded, after this module
    window.addEventListener("load", () => panel.applyExplorer(), { once: true })

    document.addEventListener("click", (event) => {
        if (event.target.closest?.(`#${BUTTON_ID}`)) {
            panel.toggle()
        }
    })
    document.addEventListener("keydown", (event) => {
        const onButton = event.target.closest?.(`#${BUTTON_ID}`)
        if (onButton && (event.key === "Enter" || event.key === " ")) {
            event.preventDefault()
            panel.toggle()
        } else if (
            event.ctrlKey &&
            event.altKey &&
            event.key.toLowerCase() === "t"
        ) {
            event.preventDefault()
            panel.toggle()
        }
    })

    if (store.state.open) {
        panel.open()
    }
}

/** The bundle's sheet, once its rules can be read; a sheet still loading is waited for until the page has loaded */
async function bundleSheet() {
    const find = () =>
        Array.from(document.styleSheets).find((sheet) => {
            try {
                return (
                    sheet.href?.includes("/assets/") &&
                    sheet.cssRules.length > 0
                )
            } catch {
                return false
            }
        })
    if (find() === undefined && document.readyState !== "complete") {
        await new Promise((resolve) =>
            window.addEventListener("load", resolve, { once: true }),
        )
    }
    return find()
}

async function fetchScan() {
    try {
        return (await http.get("/style-guide/scan.json")).data
    } catch (error) {
        console.warn(
            "The source scan was not fetched: no locations and no literals",
            error,
        )
        return { rootTokens: [], literals: [], lengthLiterals: [] }
    }
}

/**
 * The tokens of the bundle's `:root` rules, each with its declared text, its kind and group, its
 * `@property` registration, and its location and source value from the scan; then the two
 * container tokens
 */
function readTokens(bundle, scan) {
    const fromScan = new Map(
        scan.rootTokens.map((token) => [token.name, token]),
    )
    const registrations = new Map()
    const declared = new Map()

    Array.from(bundle.cssRules).forEach((rule) => {
        if (rule instanceof window.CSSPropertyRule) {
            registrations.set(rule.name, {
                syntax: rule.syntax,
                inherits: rule.inherits,
                initialValue: rule.initialValue,
            })
        } else if (
            rule instanceof CSSStyleRule &&
            rule.selectorText === ":root"
        ) {
            Array.from(rule.style)
                .filter((property) => property.startsWith("--"))
                .forEach((property) =>
                    declared.set(
                        property,
                        rule.style.getPropertyValue(property).trim(),
                    ),
                )
        }
    })

    const rootStyle = getComputedStyle(document.documentElement)
    const tokens = Array.from(declared, ([name, text]) => {
        const resolved = rootStyle.getPropertyValue(name).trim()
        const { kind } = resolved ? kindOf(name, resolved) : OTHER_KIND
        const scanned = fromScan.get(name)
        return {
            name,
            declared: text,
            kind,
            group: groupOf(name, kind, referencedNamesOf(text)),
            registration: registrations.get(name),
            location: scanned?.location,
            sourceValue: scanned?.value,
        }
    })

    Object.entries(CONTAINER_DEFAULTS).forEach(([name, value]) =>
        tokens.push({
            name,
            declared:
                document
                    .querySelector(CONTAINER_TOKENS[name])
                    ?.style.getPropertyValue(name)
                    .trim() || value,
            kind: "container",
            group: "container",
        }),
    )
    return tokens
}

class Panel {
    constructor({ store, tokens, scan, bundle }) {
        this.store = store
        this.tokens = tokens
        this.tokensByName = new Map(tokens.map((token) => [token.name, token]))
        this.scan = scan
        this.sections = SECTIONS.map((section) => ({
            ...section,
            tokens: tokens.filter(
                (token) =>
                    section.kinds.includes(token.kind) &&
                    (section.group === undefined ||
                        token.group === section.group),
            ),
        }))
        this.cards = new Map()
        this.refreshers = []
        this.filters = { search: "", changedOnly: false, inspect: null }
        this.tab = "tokens"

        this.host = el("div", {
            id: HOST_ID,
            popover: "manual",
            "data-dock": store.state.dock,
        })
        this.host.toggleAttribute("data-collapsed", store.state.collapsed)
        this.shadow = this.host.attachShadow({ mode: "open" })
        this.shadow.adoptedStyleSheets = [panelSheet]

        this.inspector = new Inspector({
            bundle,
            declaredOf: (name) => this.valueOf(name),
            isOwn: (target) =>
                this.host.contains(target) || this.shadow.contains(target),
            outline: (rect) => this.outline(rect),
            onPick: ({ tokens: picked, label }) =>
                this.setInspectFilter(picked, label),
            onLeave: () =>
                this.inspectBtn.setAttribute("aria-pressed", "false"),
        })

        this.build()
        store.subscribe((change) => this.onStoreChange(change))

        // The modal's focus trap marks everything outside the dialog inert, this host among it; a
        // tool for looking at the dialog must stay usable while it is open
        new MutationObserver(() => {
            if (this.host.hasAttribute("inert")) {
                this.host.removeAttribute("inert")
            }
        }).observe(this.host, { attributes: true, attributeFilter: ["inert"] })
    }

    // Reading values

    /** The current declared text of a token: its tweak, or the bundle's */
    valueOf(name) {
        return this.store.valueOf(name) ?? this.tokensByName.get(name)?.declared
    }

    get theme() {
        return themeOrStylesheets(this.store.theme)
    }

    /** What a control reads and writes: see `controls.js` */
    contextFor(token) {
        return {
            value: (name = token?.name) => this.valueOf(name),
            theme: () => this.theme,
            set: (text) => this.store.set(token.name, text),
            setMany: (tweaks) => this.store.setMany(tweaks),
            colorTokens: this.tokens
                .filter(
                    (other) =>
                        other.kind === "color" && other.group !== "derived",
                )
                .map((other) => other.name),
            scaleSteps: this.tokens
                .filter((other) => SPACING_STEP.test(other.name))
                .map((other) => ({ name: other.name, value: other.declared })),
        }
    }

    // Building

    build() {
        this.outlineEl = el("div", { class: "inspect-outline" })
        this.strip = el("span", { class: "strip" })
        this.inspectBtn = el("button", {
            type: "button",
            class: "full",
            title: "Inspect: pick an element to see its tokens",
            "aria-pressed": "false",
            text: "⌖",
            onclick: () => this.toggleInspect(),
        })

        const themeButtons = THEMES.map((theme) =>
            el("button", {
                type: "button",
                "aria-pressed": String(this.theme === theme),
                text: theme[0].toUpperCase() + theme.slice(1),
                onclick: () => this.setTheme(theme),
            }),
        )
        this.themeButtons = themeButtons
        const dockButtons = DOCKS.map((dock) =>
            el("button", {
                type: "button",
                title: `Dock ${dock}`,
                "data-dock": dock,
                "aria-pressed": String(this.store.state.dock === dock),
                text: { left: "◧", right: "◨", bottom: "⬓" }[dock],
                onclick: () => this.setDock(dock),
            }),
        )
        this.dockButtons = dockButtons

        const header = el(
            "header",
            {},
            el("strong", { text: "Tweaks" }),
            this.strip,
            el("span", { class: "group full" }, ...themeButtons),
            el("span", { class: "group full" }, ...dockButtons),
            this.inspectBtn,
            el("button", {
                type: "button",
                class: "quiet",
                title: "Collapse or expand",
                text: this.store.state.collapsed ? "▴" : "▾",
                onclick: (event) =>
                    this.setCollapsed(
                        !this.host.hasAttribute("data-collapsed"),
                        event.target,
                    ),
            }),
        )

        this.tabButtons = new Map(
            TABS.map(([id, label]) => [
                id,
                el("button", {
                    type: "button",
                    text: label,
                    onclick: () => this.showTab(id),
                }),
            ]),
        )
        const tabs = el("nav", { class: "tabs" }, ...this.tabButtons.values())

        this.panes = {
            tokens: this.buildTokensTab(),
            snapshots: el("section", { class: "tab" }),
            exports: this.buildExportsTab(),
            unreachable: this.buildUnreachableTab(),
        }
        this.shadow.append(
            header,
            tabs,
            ...Object.values(this.panes),
            this.outlineEl,
        )
        this.renderSnapshots()
        this.showTab(this.tab)
        this.updateStrip()
    }

    buildTokensTab() {
        this.searchInput = el("input", {
            type: "text",
            placeholder: "Search tokens",
            "aria-label": "Search tokens",
            oninput: () => {
                this.filters.search = this.searchInput.value
                    .trim()
                    .toLowerCase()
                this.applyFilters()
            },
        })
        const changedOnly = el("input", {
            type: "checkbox",
            onchange: () => {
                this.filters.changedOnly = changedOnly.checked
                this.applyFilters()
            },
        })
        this.inspectChip = el("span", { class: "chip", hidden: true })
        const toolbar = el(
            "div",
            { class: "toolbar" },
            this.searchInput,
            el("label", {}, changedOnly, "Changed only"),
            el("button", {
                type: "button",
                text: "Reset all",
                onclick: () => this.store.resetAll(),
            }),
            this.inspectChip,
        )

        const sections = this.sections.map((section) =>
            this.buildSection(section),
        )
        return el("section", { class: "tab" }, toolbar, ...sections)
    }

    buildSection(section) {
        const resetBtn = el("button", {
            type: "button",
            class: "quiet reset",
            title: "Reset this section",
            text: "↺ section",
            onclick: () =>
                this.store.resetMany(section.tokens.map((token) => token.name)),
        })
        const heading = el("h3", {}, section.title, resetBtn)
        const children = [heading]

        if (section.seed) {
            const seed = buildSeedControls(this.contextFor(null))
            this.refreshers.push(seed.refresh)
            children.push(
                el(
                    "details",
                    {},
                    el("summary", {
                        text: "Seed the theme colors from one hue",
                    }),
                    seed.el,
                ),
            )
        }
        if (section.typeScale) {
            const scale = buildTypeScaleControls(this.contextFor(null))
            this.refreshers.push(scale.refresh)
            children.push(scale.el)
        }
        if (section.panels) {
            children.push(this.buildExplorerControl())
        }

        section.tokens.forEach((token) => children.push(this.buildCard(token)))
        section.el = el(
            "section",
            { class: "group", "data-section": section.id },
            ...children,
        )
        return section.el
    }

    buildCard(token) {
        const ctx = this.contextFor(token)
        const control =
            token.kind === "container"
                ? buildPixelControl(token, ctx, { min: 40, max: 600, step: 10 })
                : buildControl(token, ctx)
        const value = el("div", { class: "value" })
        const card = el(
            "div",
            { class: "card", "data-token": token.name },
            el(
                "div",
                { class: "name" },
                el("span", { class: "dot" }),
                el("code", { text: token.name }),
                token.registration
                    ? el("span", {
                          class: "note",
                          text: `@property ${token.registration.syntax}`,
                      })
                    : null,
                el("button", {
                    type: "button",
                    class: "quiet reset",
                    title: "Reset to the bundle's value",
                    text: "↺",
                    onclick: () => this.store.reset(token.name),
                }),
            ),
            value,
            control.el,
        )
        const entry = { token, card, control, value }
        this.cards.set(token.name, entry)
        this.paintCard(entry)
        return card
    }

    /** The explorer's width: a slider over the Split sizes, live on the main page only */
    buildExplorerControl() {
        const range = el("input", {
            type: "range",
            min: "10",
            max: "70",
            step: "1",
            "aria-label": "Explorer width",
        })
        const readout = el("span", { class: "v" })
        const note = el("div", { class: "note" })
        this.explorerRange = range
        this.explorerReadout = readout

        range.addEventListener("input", () => {
            readout.textContent = `${range.value}%`
            setExplorerSizes(Number(range.value))
        })
        range.addEventListener("change", () =>
            this.store.setExplorer(Number(range.value)),
        )

        const card = el(
            "div",
            { class: "card", "data-token": "explorer" },
            el(
                "div",
                { class: "name" },
                el("span", { class: "dot" }),
                el("code", { text: "explorer width" }),
                el("button", {
                    type: "button",
                    class: "quiet reset",
                    title: "Back to the default split",
                    text: "↺",
                    onclick: () => {
                        setExplorerSizes(EXPLORER_DEFAULT)
                        this.store.setExplorer(null)
                    },
                }),
            ),
            note,
            el(
                "div",
                { class: "control" },
                el("div", { class: "row" }, range, readout),
            ),
        )
        this.explorerCard = card
        this.paintExplorer()
        return card
    }

    paintExplorer() {
        const live = hasExplorerSplit()
        const percent = this.store.explorer ?? explorerPercent()
        this.explorerRange.value = percent
        this.explorerRange.disabled = !live
        this.explorerReadout.textContent = `${percent}%`
        this.explorerCard.classList.toggle(
            "changed",
            this.store.explorer !== null,
        )
        const note = this.explorerCard.querySelector(".note")
        note.hidden = live
        note.textContent = live
            ? ""
            : "Set on the main page, where the explorer is"
    }

    /** Moves the split to the stored width, when this page has one */
    applyExplorer() {
        if (this.store.explorer !== null && hasExplorerSplit()) {
            setExplorerSizes(this.store.explorer)
        }
        this.paintExplorer()
    }

    buildExportsTab() {
        this.exportPres = {}
        const blocks = [
            [
                "prompt",
                "Prompt",
                () =>
                    buildPrompt({
                        sections: this.sections,
                        store: this.store,
                        explorerDefault: EXPLORER_DEFAULT,
                    }),
            ],
            [
                "css",
                "CSS for core.css",
                () => buildCss({ sections: this.sections, store: this.store }),
            ],
            ["snapshot", "Snapshot JSON", () => buildSnapshotJson(this.store)],
        ].map(([id, title, build]) => {
            const pre = el("pre")
            this.exportPres[id] = { pre, build }
            return el(
                "div",
                { class: "export" },
                el(
                    "h3",
                    {},
                    title,
                    el("button", {
                        type: "button",
                        text: "Copy",
                        onclick: (event) =>
                            copyText(pre.textContent, event.target),
                    }),
                ),
                pre,
            )
        })
        return el(
            "section",
            { class: "tab" },
            el("p", { class: "hint", text: "Regenerated on every change." }),
            ...blocks,
        )
    }

    renderExports() {
        Object.values(this.exportPres).forEach(({ pre, build }) => {
            pre.textContent = build()
        })
    }

    buildUnreachableTab() {
        const colors = this.scan.literals ?? []
        const lengths = this.scan.lengthLiterals ?? []
        const locationsCell = (locations) =>
            el(
                "td",
                {},
                el(
                    "details",
                    { class: "locations" },
                    el("summary", { text: String(locations.length) }),
                    ...locations.map((location) =>
                        el("div", { text: location }),
                    ),
                ),
            )

        return el(
            "section",
            { class: "tab" },
            el("p", {
                class: "hint",
                text: "Values written as literals in the stylesheets and templates, which no token reaches. Each names its file and line.",
            }),
            el("h3", { text: `Color literals (${colors.length})` }),
            el(
                "table",
                {},
                el(
                    "tr",
                    {},
                    el("th", { text: "Literal" }),
                    el("th", { text: "Uses" }),
                ),
                ...colors.map((literal) =>
                    el(
                        "tr",
                        {},
                        el(
                            "td",
                            {},
                            el("span", {
                                class: "swatch",
                                style: `background: ${literal.value}`,
                            }),
                            " ",
                            el("code", { text: literal.value }),
                        ),
                        locationsCell(literal.locations),
                    ),
                ),
            ),
            el("h3", { text: `Length literals (${lengths.length})` }),
            el(
                "table",
                {},
                el(
                    "tr",
                    {},
                    el("th", { text: "Property" }),
                    el("th", { text: "Literal" }),
                    el("th", { text: "Uses" }),
                ),
                ...lengths.map((literal) =>
                    el(
                        "tr",
                        {},
                        el("td", {}, el("code", { text: literal.property })),
                        el("td", {}, el("code", { text: literal.value })),
                        locationsCell(literal.locations),
                    ),
                ),
            ),
        )
    }

    renderSnapshots() {
        const store = this.store
        const pane = this.panes.snapshots
        pane.replaceChildren()

        const nameInput = el("input", {
            type: "text",
            placeholder: "Snapshot name",
            "aria-label": "Snapshot name",
            value: store.activeSnapshot ?? "",
        })
        const saveAs = () => {
            const name = nameInput.value.trim()
            if (name !== "") {
                store.saveSnapshot(name)
            }
        }
        nameInput.addEventListener("keydown", (event) => {
            if (event.key === "Enter") {
                saveAs()
            }
        })
        pane.append(
            el(
                "div",
                { class: "toolbar" },
                nameInput,
                el("button", {
                    type: "button",
                    text: "Save as",
                    onclick: saveAs,
                }),
            ),
        )

        if (!store.snapshots.length) {
            pane.append(
                el("p", {
                    class: "hint",
                    text: "No snapshots yet. Save the current tweaks under a name to keep and compare them.",
                }),
            )
        }
        store.snapshots.forEach((snapshot) =>
            pane.append(this.buildSnapshotRow(snapshot)),
        )

        // A/B: two chosen snapshots, flipped between
        const abSelects = [0, 1].map((index) => {
            const select = el("select", {
                "aria-label": index === 0 ? "A" : "B",
            })
            select.append(
                el("option", {
                    value: "",
                    text: index === 0 ? "A: choose" : "B: choose",
                }),
            )
            store.snapshots.forEach(({ name }) =>
                select.append(el("option", { value: name, text: name })),
            )
            select.value = store.ab[index] ?? ""
            select.addEventListener("change", () =>
                store.setAb(index, select.value || null),
            )
            return select
        })
        const [a, b] = store.ab
        pane.append(
            el("h3", { text: "A / B" }),
            el(
                "div",
                { class: "row" },
                ...abSelects,
                el("button", {
                    type: "button",
                    text: "Flip",
                    disabled: !(a && b),
                    onclick: () => store.flipAb(),
                }),
                el("span", {
                    class: "note",
                    text:
                        a && b
                            ? `Showing ${store.activeSnapshot === b ? "B" : store.activeSnapshot === a ? "A" : "neither"}`
                            : "",
                }),
            ),
        )

        // Import: a snapshot's JSON, pasted or from a file
        const importText = el("textarea", {
            placeholder: "Paste a snapshot's JSON",
            "aria-label": "Snapshot JSON to import",
        })
        const importStatus = el("span", { class: "note" })
        const importJson = (json) => {
            const name = store.importSnapshot(json)
            importStatus.textContent =
                name === null ? "Not a snapshot." : `Imported as ${name}.`
        }
        const file = el("input", {
            type: "file",
            accept: "application/json",
            "aria-label": "Snapshot file",
        })
        file.addEventListener("change", () =>
            file.files[0]?.text().then(importJson),
        )
        pane.append(
            el("h3", { text: "Import" }),
            importText,
            el(
                "div",
                { class: "row" },
                el("button", {
                    type: "button",
                    text: "Import",
                    onclick: () => importJson(importText.value),
                }),
                file,
                importStatus,
            ),
        )
    }

    buildSnapshotRow(snapshot) {
        const store = this.store
        const count =
            Object.keys(snapshot.tweaks).length +
            (snapshot.explorer == null ? 0 : 1)
        const title = el("span", { class: "title", text: snapshot.name })
        const row = el("div", {
            class: `snapshot${store.activeSnapshot === snapshot.name ? " active" : ""}`,
        })

        const rename = () => {
            const input = el("input", {
                type: "text",
                value: snapshot.name,
                "aria-label": "New name",
            })
            const commit = () => {
                if (!store.renameSnapshot(snapshot.name, input.value.trim())) {
                    this.renderSnapshots()
                }
            }
            input.addEventListener("keydown", (event) => {
                if (event.key === "Enter") {
                    commit()
                } else if (event.key === "Escape") {
                    this.renderSnapshots()
                }
            })
            input.addEventListener("blur", commit)
            title.replaceWith(input)
            input.focus()
            input.select()
        }

        row.append(
            title,
            el("span", {
                class: "count",
                text: `${count} changed${snapshot.theme ? `, ${snapshot.theme}` : ""}`,
            }),
            el("button", {
                type: "button",
                text: "Load",
                onclick: () => store.loadSnapshot(snapshot.name),
            }),
            el("button", {
                type: "button",
                class: "quiet",
                text: "Rename",
                onclick: rename,
            }),
            el("button", {
                type: "button",
                class: "quiet",
                text: "Duplicate",
                onclick: () => store.duplicateSnapshot(snapshot.name),
            }),
            el("button", {
                type: "button",
                class: "quiet",
                text: "Export",
                onclick: () =>
                    downloadText(
                        `${snapshot.name}.design-tweaks.json`,
                        store.exportSnapshot(snapshot.name),
                    ),
            }),
            el("button", {
                type: "button",
                class: "quiet",
                text: "Delete",
                onclick: () => store.deleteSnapshot(snapshot.name),
            }),
        )
        return row
    }

    // Painting

    paintCard({ token, card, value }) {
        card.classList.toggle("changed", this.store.isChanged(token.name))
        value.textContent = this.valueOf(token.name)
    }

    refreshAll() {
        this.cards.forEach((entry) => {
            entry.control.refresh()
            this.paintCard(entry)
        })
        this.refreshers.forEach((refresh) => refresh())
        this.paintExplorer()
    }

    updateStrip() {
        const name = this.store.activeSnapshot
        const parts = [`${this.store.changedCount} changed`]
        if (name) {
            parts.push(`${name}${this.store.activeSnapshotModified ? "*" : ""}`)
        }
        this.strip.textContent = parts.join(" · ")
    }

    applyFilters() {
        const { search, changedOnly, inspect } = this.filters
        this.sections.forEach((section) => {
            let shown = 0
            section.tokens.forEach((token) => {
                const { card } = this.cards.get(token.name)
                const visible =
                    (search === "" || token.name.includes(search)) &&
                    (!changedOnly || this.store.isChanged(token.name)) &&
                    (inspect === null || inspect.has(token.name))
                card.hidden = !visible
                shown += visible ? 1 : 0
            })
            if (section.panels) {
                const explorerVisible =
                    inspect === null &&
                    (!changedOnly || this.store.explorer !== null) &&
                    (search === "" || "explorer width".includes(search))
                this.explorerCard.hidden = !explorerVisible
                shown += explorerVisible ? 1 : 0
            }
            section.el.hidden = shown === 0
        })
    }

    onStoreChange(change) {
        if (change.kind === "tweak") {
            const entry = this.cards.get(change.name)
            if (entry) {
                this.paintCard(entry)
            }
        } else if (change.kind === "tweaks" || change.kind === "theme") {
            if (change.kind === "tweaks") {
                this.applyExplorer()
            }
            this.refreshAll()
            this.themeButtons.forEach((button) =>
                button.setAttribute(
                    "aria-pressed",
                    String(button.textContent.toLowerCase() === this.theme),
                ),
            )
        } else if (change.kind === "explorer") {
            this.paintExplorer()
        }
        if (change.kind !== "panel") {
            this.updateStrip()
            // A slider tick (a "tweak") leaves the list alone, so a name being typed there survives it
            if (["tweaks", "snapshots", "theme"].includes(change.kind)) {
                this.renderSnapshots()
            }
            if (this.filters.changedOnly) {
                this.applyFilters()
            }
            if (this.tab === "exports") {
                this.renderExports()
            }
        }
    }

    // Actions

    setTheme(theme) {
        applyTheme(theme)
        this.store.setTheme(theme)
    }

    setDock(dock) {
        this.host.setAttribute("data-dock", dock)
        this.dockButtons.forEach((button) =>
            button.setAttribute(
                "aria-pressed",
                String(button.dataset.dock === dock),
            ),
        )
        this.store.setPanel({ dock })
    }

    setCollapsed(collapsed, button) {
        this.host.toggleAttribute("data-collapsed", collapsed)
        button.textContent = collapsed ? "▴" : "▾"
        this.store.setPanel({ collapsed })
    }

    showTab(id) {
        this.tab = id
        this.tabButtons.forEach((button, tabId) =>
            button.setAttribute("aria-pressed", String(tabId === id)),
        )
        Object.entries(this.panes).forEach(([paneId, pane]) =>
            pane.classList.toggle("shown", paneId === id),
        )
        if (id === "exports") {
            this.renderExports()
        }
    }

    toggleInspect() {
        if (this.inspector.active) {
            this.inspector.stop()
        } else {
            this.inspectBtn.setAttribute("aria-pressed", "true")
            this.inspector.start()
        }
    }

    setInspectFilter(tokens, label) {
        this.inspectBtn.setAttribute("aria-pressed", "false")
        this.filters.inspect = tokens
        this.inspectChip.replaceChildren(
            `Inspecting ${label} (${tokens.size})`,
            el("button", {
                type: "button",
                class: "quiet",
                text: "×",
                title: "Show every token",
                onclick: () => this.clearInspect(),
            }),
        )
        this.inspectChip.hidden = false
        this.showTab("tokens")
        this.applyFilters()
        // The outline sits where the element was; it goes once the page moves under it
        window.addEventListener("scroll", () => this.outline(null), {
            once: true,
            capture: true,
        })
    }

    clearInspect() {
        this.filters.inspect = null
        this.inspectChip.hidden = true
        this.outline(null)
        this.applyFilters()
    }

    outline(rect) {
        const style = this.outlineEl.style
        this.outlineEl.classList.toggle("shown", rect !== null)
        if (rect) {
            style.top = `${rect.top}px`
            style.left = `${rect.left}px`
            style.width = `${rect.width}px`
            style.height = `${rect.height}px`
        }
    }

    open() {
        if (!this.host.matches(":popover-open")) {
            this.host.showPopover()
        }
        this.store.setPanel({ open: true })
    }

    close() {
        if (this.host.matches(":popover-open")) {
            this.host.hidePopover()
        }
        this.inspector.stop()
        this.store.setPanel({ open: false })
    }

    toggle() {
        if (this.host.matches(":popover-open")) {
            this.close()
        } else {
            this.open()
        }
    }
}

function copyText(text, button) {
    navigator.clipboard.writeText(text).then(
        () => flash(button, "Copied"),
        () => flash(button, "Copy failed"),
    )
}

function flash(button, label) {
    const previous = button.textContent
    button.textContent = label
    setTimeout(() => {
        button.textContent = previous
    }, 1200)
}

function downloadText(fileName, text) {
    const url = URL.createObjectURL(
        new Blob([text], { type: "application/json" }),
    )
    const link = el("a", { href: url, download: fileName })
    document.body.append(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
}
