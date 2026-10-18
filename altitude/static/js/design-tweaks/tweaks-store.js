/**
 * The design tweaks' state, under one `localStorage` key (`Const.localStore.designTweaks`), and the
 * stylesheet that applies the active tweak set to the page.
 *
 * The state: the active tweak set (`tweaks`, token name to declared text, and `explorer`, the
 * explorer's width in percent or null), the named `snapshots` (each a tweak set with its theme),
 * the `activeSnapshot`'s name, the `theme` shown (null for the stylesheet's), the panel's `dock`,
 * `open` and `collapsed` state, the two snapshots of the A/B toggle (`ab`), and `css`, the
 * stylesheet's text as last written. The text is stored so that the pre-paint script of
 * `includes/header_common` adopts it before the first paint without knowing how it is built; the
 * store then takes that sheet over (`window.designTweaksSheet`) and rewrites it on every change.
 *
 * The sheet is one `CSSStyleSheet` in `document.adoptedStyleSheets`, which cascades after the
 * document's sheets and so wins over the bundle's `:root` at equal specificity, holding one `:root`
 * rule with every tweaked token. The two tokens the server sets inline on their containers
 * (`--preview-box` on `#assets`, `--face-box` on `#people`) are written `!important` on those
 * containers, since an inline declaration beats any other normal one.
 */
import { Const } from "../constants.js"

// The tokens set inline by the server, each on the container that carries it
export const CONTAINER_TOKENS = {
    "--preview-box": "#assets",
    "--face-box": "#people",
}

export const DOCKS = ["left", "right", "bottom"]

const DEFAULT_STATE = {
    tweaks: {},
    explorer: null,
    snapshots: [],
    activeSnapshot: null,
    theme: null,
    dock: "right",
    open: false,
    collapsed: false,
    ab: [null, null],
    css: "",
}

export class TweaksStore {
    constructor() {
        this.state = { ...DEFAULT_STATE, ...readState() }
        this.listeners = new Set()
        this.sheet = window.designTweaksSheet ?? new CSSStyleSheet()
        if (!document.adoptedStyleSheets.includes(this.sheet)) {
            document.adoptedStyleSheets = [
                ...document.adoptedStyleSheets,
                this.sheet,
            ]
        }
        this.applySheet()
    }

    /** `listener(change)` is called after every change; `change.kind` names what changed */
    subscribe(listener) {
        this.listeners.add(listener)
        return () => this.listeners.delete(listener)
    }

    get tweaks() {
        return this.state.tweaks
    }

    get explorer() {
        return this.state.explorer
    }

    get theme() {
        return this.state.theme
    }

    /** The tweaked declared text of `name`, or undefined while the token stands as the bundle declares it */
    valueOf(name) {
        return this.state.tweaks[name]
    }

    isChanged(name) {
        return name in this.state.tweaks
    }

    /** How many tokens the active tweak set changes; the explorer width counts as one */
    get changedCount() {
        return (
            Object.keys(this.state.tweaks).length +
            (this.state.explorer === null ? 0 : 1)
        )
    }

    /** Writes `name` as `value`, or stands it back as declared when `value` is undefined */
    set(name, value) {
        if (value === undefined) {
            delete this.state.tweaks[name]
        } else {
            this.state.tweaks[name] = value
        }
        this.commit({ kind: "tweak", name })
    }

    reset(name) {
        this.set(name, undefined)
    }

    /** Writes several tokens at once, as the type scale and the seed controls do */
    setMany(tweaks) {
        Object.assign(this.state.tweaks, tweaks)
        this.commit({ kind: "tweaks" })
    }

    resetMany(names) {
        names.forEach((name) => delete this.state.tweaks[name])
        this.commit({ kind: "tweaks" })
    }

    resetAll() {
        this.state.tweaks = {}
        this.state.explorer = null
        this.commit({ kind: "tweaks" })
    }

    /** Replaces the whole active tweak set, as loading a snapshot does */
    replaceTweaks({ tweaks, explorer = null }) {
        this.state.tweaks = { ...tweaks }
        this.state.explorer = explorer
        this.commit({ kind: "tweaks" })
    }

    setExplorer(percent) {
        this.state.explorer = percent
        this.commit({ kind: "explorer" })
    }

    setTheme(theme) {
        this.state.theme = theme
        this.commit({ kind: "theme" })
    }

    setPanel({ dock, open, collapsed }) {
        if (dock !== undefined) {
            this.state.dock = dock
        }
        if (open !== undefined) {
            this.state.open = open
        }
        if (collapsed !== undefined) {
            this.state.collapsed = collapsed
        }
        this.commit({ kind: "panel" })
    }

    // Snapshots: each a named copy of a tweak set with the theme it was taken in

    get snapshots() {
        return this.state.snapshots
    }

    snapshot(name) {
        return this.state.snapshots.find((snapshot) => snapshot.name === name)
    }

    /** The active tweak set as a snapshot named `name`; a snapshot of that name is replaced */
    saveSnapshot(name) {
        const snapshot = {
            name,
            theme: this.state.theme,
            tweaks: { ...this.state.tweaks },
            explorer: this.state.explorer,
        }
        this.state.snapshots = [
            ...this.state.snapshots.filter((other) => other.name !== name),
            snapshot,
        ]
        this.state.activeSnapshot = name
        this.commit({ kind: "snapshots" })
    }

    /** Makes the snapshot the active tweak set, theme included, and names it the active one */
    loadSnapshot(name) {
        const snapshot = this.snapshot(name)
        if (!snapshot) {
            return
        }
        this.state.tweaks = { ...snapshot.tweaks }
        this.state.explorer = snapshot.explorer ?? null
        this.state.theme = snapshot.theme ?? null
        this.state.activeSnapshot = name
        this.commit({ kind: "tweaks" })
    }

    renameSnapshot(name, newName) {
        const snapshot = this.snapshot(name)
        if (!snapshot || newName === "" || this.snapshot(newName)) {
            return false
        }
        snapshot.name = newName
        if (this.state.activeSnapshot === name) {
            this.state.activeSnapshot = newName
        }
        this.state.ab = this.state.ab.map((side) =>
            side === name ? newName : side,
        )
        this.commit({ kind: "snapshots" })
        return true
    }

    deleteSnapshot(name) {
        this.state.snapshots = this.state.snapshots.filter(
            (snapshot) => snapshot.name !== name,
        )
        if (this.state.activeSnapshot === name) {
            this.state.activeSnapshot = null
        }
        this.state.ab = this.state.ab.map((side) =>
            side === name ? null : side,
        )
        this.commit({ kind: "snapshots" })
    }

    duplicateSnapshot(name) {
        const snapshot = this.snapshot(name)
        if (!snapshot) {
            return
        }
        this.state.snapshots = [
            ...this.state.snapshots,
            {
                ...snapshot,
                tweaks: { ...snapshot.tweaks },
                name: copyName(name, this.state.snapshots),
            },
        ]
        this.commit({ kind: "snapshots" })
    }

    /**
     * Adds the snapshot `json` holds (as `exportSnapshot` wrote it), under a name no other has, and
     * returns its name; a text that is no snapshot returns null
     */
    importSnapshot(json) {
        let parsed
        try {
            parsed = JSON.parse(json)
        } catch {
            return null
        }
        if (!parsed || typeof parsed !== "object" || !isTweaks(parsed.tweaks)) {
            return null
        }

        const name = copyName(
            typeof parsed.name === "string" && parsed.name !== ""
                ? parsed.name
                : "Imported",
            this.state.snapshots,
            this.snapshot(parsed.name) === undefined,
        )
        this.state.snapshots = [
            ...this.state.snapshots,
            {
                name,
                theme: parsed.theme ?? null,
                tweaks: { ...parsed.tweaks },
                explorer:
                    typeof parsed.explorer === "number"
                        ? parsed.explorer
                        : null,
            },
        ]
        this.commit({ kind: "snapshots" })
        return name
    }

    /** The snapshot named, or the active tweak set when `name` is null, as JSON for a file */
    exportSnapshot(name = null) {
        const snapshot =
            name === null ? this.currentAsSnapshot() : this.snapshot(name)
        return JSON.stringify(snapshot, null, 4)
    }

    /** The active tweak set as an unsaved snapshot, named after the active one when there is one */
    currentAsSnapshot() {
        return {
            name: this.state.activeSnapshot ?? "Current tweaks",
            theme: this.state.theme,
            tweaks: { ...this.state.tweaks },
            explorer: this.state.explorer,
        }
    }

    /** Whether the active tweak set differs from the snapshot it was loaded from or saved as */
    get activeSnapshotModified() {
        const snapshot = this.snapshot(this.state.activeSnapshot)
        return (
            snapshot !== undefined &&
            (JSON.stringify(snapshot.tweaks) !==
                JSON.stringify(this.state.tweaks) ||
                (snapshot.explorer ?? null) !== this.state.explorer)
        )
    }

    get activeSnapshot() {
        return this.state.activeSnapshot
    }

    // The A/B toggle: two chosen snapshots, flipped between

    get ab() {
        return this.state.ab
    }

    setAb(index, name) {
        this.state.ab[index] = name
        this.commit({ kind: "snapshots" })
    }

    /** Loads the side of the A/B pair that is not the active snapshot (A when neither is) */
    flipAb() {
        const [a, b] = this.state.ab
        if (!a || !b) {
            return
        }
        this.loadSnapshot(this.state.activeSnapshot === a ? b : a)
    }

    /** Writes the state and the sheet, and tells the listeners */
    commit(change) {
        this.applySheet()
        this.state.css = sheetText(this.state.tweaks)
        try {
            localStorage.setItem(
                Const.localStore.designTweaks,
                JSON.stringify(this.state),
            )
        } catch (error) {
            console.warn("The design tweaks were not stored", error)
        }
        this.listeners.forEach((listener) => listener(change))
    }

    applySheet() {
        this.sheet.replaceSync(sheetText(this.state.tweaks))
    }
}

/** The stylesheet that applies `tweaks`: the `:root` rule, and a rule per container token */
function sheetText(tweaks) {
    const root = []
    const containers = []

    Object.entries(tweaks).forEach(([name, value]) => {
        if (name in CONTAINER_TOKENS) {
            containers.push(
                `${CONTAINER_TOKENS[name]} { ${name}: ${value} !important; }`,
            )
        } else {
            root.push(`    ${name}: ${value};`)
        }
    })

    return [`:root {`, ...root, `}`, ...containers].join("\n")
}

function readState() {
    try {
        const stored = JSON.parse(
            localStorage.getItem(Const.localStore.designTweaks),
        )
        return stored && typeof stored === "object" ? stored : {}
    } catch {
        return {}
    }
}

function isTweaks(tweaks) {
    return (
        tweaks !== null &&
        typeof tweaks === "object" &&
        Object.entries(tweaks).every(
            ([name, value]) =>
                name.startsWith("--") && typeof value === "string",
        )
    )
}

/** `name` when it is free (and `preferOwn`), else "name (2)", "name (3)", … the first that is */
function copyName(name, snapshots, preferOwn = false) {
    const taken = new Set(snapshots.map((snapshot) => snapshot.name))
    if (preferOwn && !taken.has(name)) {
        return name
    }
    let copy = 2
    while (taken.has(`${name} (${copy})`)) {
        copy++
    }
    return `${name} (${copy})`
}
