import { Alpine } from "../../lib/alpine.esm.min.js"
import { Const } from "../../constants.js"
import { context } from "../../context.js"
import Split from "../../lib/split.es.js"
import { effectiveSide, isVerticalAxis } from "../../common/explorer-dock.js"

/**
 * The People tab's buckets, Split view and pane loads. The browser owns the state, remembered in
 * `localStorage`; the server only renders a pane's list for the buckets it asks for. Every pane
 * reloads when people change and when "Show triaged items" flips.
 *
 * Split view divides the Main and Duplicate panes with Split.js: stacked under a left or right dock,
 * where the explorer is tall, side by side under a top or bottom one. Each axis remembers its own
 * sizes, and the Duplicate pane copies the Main pane's buckets only the first time.
 */

const DEFAULT_SIZES = [50, 50]
const MIN_SIZE = 120
const { main: MAIN, duplicate: DUPLICATE } = Const.people.panes
const KNOWN_BUCKETS = Object.values(Const.people.buckets)

// Older pane names, still read from a remembered state
const LEGACY_PANES = { [MAIN]: "top", [DUPLICATE]: "bottom" }

// The Split.js direction of each axis
const STACKED = "stacked"
const SIDE_BY_SIDE = "sideBySide"
const DIRECTIONS = { [STACKED]: "vertical", [SIDE_BY_SIDE]: "horizontal" }

export function peopleTab() {
    // Closure state rather than component data: Alpine would proxy them, and nothing reactive reads any
    let splitInstance = null
    let onPeopleChanged = null
    let onDockSideChanged = null

    return {
        // The remembered state; the switches and the Duplicate pane's `x-if` follow it
        split: false,
        buckets: { [MAIN]: [Const.people.buckets.matched] },
        paneSizes: { [STACKED]: DEFAULT_SIZES, [SIDE_BY_SIDE]: DEFAULT_SIZES },

        init() {
            Object.assign(this, rememberedView())

            onPeopleChanged = () => this.reloadPanes()
            document.body.addEventListener(
                Const.events.peopleChanged,
                onPeopleChanged,
            )

            // The axis follows the Dock side: the panes stay, the instance is remade
            onDockSideChanged = () => {
                if (!this.split) {
                    return
                }
                this.closeSplit()
                this.$nextTick(() => this.openSplit())
            }
            document.body.addEventListener(
                Const.events.dockSideChanged,
                onDockSideChanged,
            )

            // Each Person's count depends on "Show triaged items"
            this.$watch("$store.searchParams.triaged", () => this.reloadPanes())

            this.loadPane(MAIN)
            if (this.split) {
                // The Duplicate pane exists once Alpine has rendered the `x-if`
                this.$nextTick(() => this.openSplit())
            }
        },

        destroy() {
            document.body.removeEventListener(
                Const.events.peopleChanged,
                onPeopleChanged,
            )
            document.body.removeEventListener(
                Const.events.dockSideChanged,
                onDockSideChanged,
            )
            onPeopleChanged = null
            onDockSideChanged = null
            this.closeSplit()
        },

        isOn(pane, bucket) {
            return this.bucketsOf(pane).includes(bucket)
        },

        toggle(pane, bucket) {
            const on = this.bucketsOf(pane)
            this.buckets[pane] = this.isOn(pane, bucket)
                ? on.filter((name) => name !== bucket)
                : [...on, bucket]
            this.remember()
            this.loadPane(pane)
        },

        toggleSplit() {
            if (this.split) {
                // The Split.js instance goes before the `x-if` drops the pane it divides
                this.closeSplit()
                this.split = false
            } else {
                this.split = true
                if (!this.buckets[DUPLICATE]) {
                    this.buckets[DUPLICATE] = [...this.buckets[MAIN]]
                }
                this.$nextTick(() => this.openSplit())
            }
            this.remember()
        },

        /**
         * The request names the pane for the rename pencils' IDs: a Person can be in both panes, and
         * each pencil anchors its own dialog. The `.list` host is the source (htmx's default, the
         * target), so its `hx-sync="replace"` aborts a load still in flight and the latest buckets land.
         */
        loadPane(pane) {
            const listEl = this.paneEl(pane)?.querySelector(".list")
            if (!listEl) {
                return
            }

            // Triage lists everyone, whatever the switch: its people may be in triaged items alone.
            // Sent only when on: null would go out as `triaged=null`, which the server refuses
            const showsTriaged =
                Alpine.store(Const.state.currentView).isTriageView() ||
                Alpine.store(Const.state.searchParams).showsTriaged()

            htmx.ajax("GET", `/htmx/people/r/${context.getRepoId()}/list`, {
                target: listEl,
                swap: "innerHTML",
                values: {
                    pane,
                    buckets: this.bucketsOf(pane).join(","),
                    ...(showsTriaged ? { triaged: "true" } : {}),
                },
            })
        },

        reloadPanes() {
            this.loadPane(MAIN)
            if (this.split) {
                this.loadPane(DUPLICATE)
            }
        },

        /** A pane never shown has none */
        bucketsOf(pane) {
            return this.buckets[pane] ?? []
        },

        /** Null while the pane is not rendered */
        paneEl(pane) {
            return this.$root.querySelector(`[data-app-people-pane="${pane}"]`)
        },

        openSplit() {
            const panes = [this.paneEl(MAIN), this.paneEl(DUPLICATE)]
            if (panes.some((pane) => !pane)) {
                return
            }

            const axis = currentAxis()
            splitInstance = Split(panes, {
                direction: DIRECTIONS[axis],
                sizes: this.paneSizes[axis],
                minSize: MIN_SIZE,
                gutter: buildGutter,
                onDragEnd: (sizes) => {
                    this.paneSizes[axis] = sizes
                    this.remember()
                },
            })
            this.loadPane(DUPLICATE)
        },

        // `destroy()` also clears the inline sizes Split.js set, so the next instance, on either
        // axis, starts from the stylesheet's layout
        closeSplit() {
            splitInstance?.destroy()
            splitInstance = null
        },

        remember() {
            localStorage.setItem(
                Const.localStore.peopleView,
                JSON.stringify({
                    split: this.split,
                    buckets: this.buckets,
                    paneSizes: this.paneSizes,
                }),
            )
        },
    }
}

function currentAxis() {
    return isVerticalAxis(effectiveSide()) ? SIDE_BY_SIDE : STACKED
}

/**
 * Split.js's classes, which it sizes and drags by, plus the page's `.divider`, so the grip matches
 * the explorer's. Split.js names the split's direction, so its "vertical" gutter runs across.
 */
function buildGutter(index, direction) {
    const gutterEl = document.createElement("div")
    gutterEl.className = `gutter gutter-${direction} divider ${
        direction === "vertical" ? "horizontal" : "vertical"
    }`
    return gutterEl
}

/** Keeps only known bucket names and valid sizes; falls back to the first visit's state */
function rememberedView() {
    const saved = remembered(Const.localStore.peopleView, null)
    const savedBuckets = (pane) =>
        saved?.buckets?.[pane] ?? saved?.buckets?.[LEGACY_PANES[pane]]

    const paneSizes = {
        [STACKED]: sizesOrDefault(saved?.paneSizes?.[STACKED]),
        [SIDE_BY_SIDE]: sizesOrDefault(saved?.paneSizes?.[SIDE_BY_SIDE]),
    }

    if (!Array.isArray(savedBuckets(MAIN))) {
        return {
            split: false,
            buckets: { [MAIN]: [Const.people.buckets.matched] },
            paneSizes,
        }
    }
    const buckets = { [MAIN]: knownBuckets(savedBuckets(MAIN)) }
    if (Array.isArray(savedBuckets(DUPLICATE))) {
        buckets[DUPLICATE] = knownBuckets(savedBuckets(DUPLICATE))
    }
    return { split: saved.split === true, buckets, paneSizes }
}

function knownBuckets(names) {
    return names.filter((name) => KNOWN_BUCKETS.includes(name))
}

function sizesOrDefault(sizes) {
    const isPercentage = (size) =>
        Number.isFinite(size) && size >= 0 && size <= 100
    return Array.isArray(sizes) &&
        sizes.length === 2 &&
        sizes.every(isPercentage)
        ? sizes
        : DEFAULT_SIZES
}

function remembered(key, fallback) {
    try {
        return JSON.parse(localStorage.getItem(key)) ?? fallback
    } catch {
        return fallback
    }
}
