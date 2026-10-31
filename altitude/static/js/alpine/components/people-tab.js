import { Const } from "../../constants.js"
import { context } from "../../context.js"
import Split from "../../lib/split.es.js"
import { effectiveSide, isVerticalAxis } from "../../common/explorer-dock.js"

/**
 * The People tab (`htmx/people.scala.html`): the owner of its People buckets, its Split view and
 * its pane loads, as `viewSettings` owns the grid's metadata switches.
 *
 * The browser owns the state. It is remembered in `localStorage` under
 * `Const.localStore.peopleView` as
 * `{ split, buckets: { main: [...], duplicate?: [...] }, paneSizes: { stacked, sideBySide } }`:
 * whether Split view is on, the buckets each pane shows, the Duplicate pane's only once it has had
 * any, and the two panes' Split.js sizes on each axis. A first visit shows Matched alone, with no
 * split. The server knows nothing of it: it renders a pane's list (`htmx/people_list`) for the
 * buckets the pane asks for, and this component loads each pane's list into its `.list` host
 * itself, on init, when a switch flips, and when a person changes (`Const.events.peopleChanged`: a
 * merge, a hide, a show or a discard moves people between buckets, so every pane reloads with its
 * own).
 *
 * Split view adds the Duplicate pane under the shell's `x-if` and divides the two panes with
 * Split.js along the axis the Dock side calls for (`js/common/explorer-dock.js`): stacked, a
 * vertical instance, under a left or right dock, where the explorer is tall; side by side, a
 * horizontal one, under a top or bottom dock, where it is wide. Each axis remembers its own sizes.
 * A Dock side change (`Const.events.dockSideChanged`) rebuilds the instance on the new axis.
 * Turning Split view off destroys the instance, which takes its gutter and the sizes it set with
 * it, and then drops the pane. The Duplicate pane keeps its remembered buckets across split off
 * and on; it copies the Main pane's only the first time.
 */

const DEFAULT_SIZES = [50, 50]
const MIN_SIZE = 120
const { main: MAIN, duplicate: DUPLICATE } = Const.people.panes
const KNOWN_BUCKETS = Object.values(Const.people.buckets)

// The pane names before the Main and Duplicate panes, still read from an older remembered state
const LEGACY_PANES = { [MAIN]: "top", [DUPLICATE]: "bottom" }

// The two axes a Split.js instance divides the panes along, and the Split.js direction of each
const STACKED = "stacked"
const SIDE_BY_SIDE = "sideBySide"
const DIRECTIONS = { [STACKED]: "vertical", [SIDE_BY_SIDE]: "horizontal" }

export function peopleTab() {
    // The Split.js instance while Split view is on, and the listeners that reload the panes and
    // rebuild the instance. Closure state rather than component data: Alpine would proxy them,
    // and nothing reactive reads any.
    let splitInstance = null
    let onPeopleChanged = null
    let onDockSideChanged = null

    return {
        // The remembered state, reactive: every switch's `aria-checked` and the Duplicate pane's
        // `x-if` follow it
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

            // The axis changes with the Dock side: the panes stay, the instance is remade
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

            this.loadPane(MAIN)
            if (this.split) {
                // The Duplicate pane exists once Alpine has rendered the `x-if`
                this.$nextTick(() => this.openSplit())
            }
        },

        // Alpine calls this when another tab's fragment replaces the shell
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
         * Loads the pane's list from the server, for the buckets it has on; nothing with no pane.
         * The `.list` host is the request's source (htmx takes the target as the source when none
         * is given), and its `hx-sync="replace"` aborts a load of the pane still in flight, so
         * the latest bucket set always lands.
         */
        loadPane(pane) {
            const listEl = this.paneEl(pane)?.querySelector(".list")
            if (!listEl) {
                return
            }

            htmx.ajax("GET", `/htmx/people/r/${context.getRepoId()}/list`, {
                target: listEl,
                swap: "innerHTML",
                values: { buckets: this.bucketsOf(pane).join(",") },
            })
        },

        reloadPanes() {
            this.loadPane(MAIN)
            if (this.split) {
                this.loadPane(DUPLICATE)
            }
        },

        /** The pane's buckets; a pane never shown has none */
        bucketsOf(pane) {
            return this.buckets[pane] ?? []
        },

        /** The pane's section in this shell, or null while it is not rendered */
        paneEl(pane) {
            return this.$root.querySelector(`[data-app-people-pane="${pane}"]`)
        },

        /**
         * Divides the two panes along the current axis at the sizes remembered for it, then fills
         * the Duplicate pane. A drag remembers the sizes under the axis they were dragged on.
         */
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

        // Split.js's `destroy()` also clears the inline sizes it set along its own axis, so the
        // next instance, on either axis, starts from the stylesheet's layout
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

/** The axis Split view divides the panes along for the Dock side the explorer is on */
function currentAxis() {
    return isVerticalAxis(effectiveSide()) ? SIDE_BY_SIDE : STACKED
}

/**
 * The gutter between the panes: Split.js's own classes, which it sizes and drags by, and the
 * page's `.divider` (core.css), so the grip is the explorer's divider's. Split.js names the
 * direction of the split, so its "vertical" gutter is the bar that runs across
 */
function buildGutter(index, direction) {
    const gutterEl = document.createElement("div")
    gutterEl.className = `gutter gutter-${direction} divider ${
        direction === "vertical" ? "horizontal" : "vertical"
    }`
    return gutterEl
}

/**
 * The remembered state, or the first visit's when none is remembered or it cannot be read. Only
 * the bucket names the tab knows are kept, the Duplicate pane's list only when it is one, and an
 * axis's sizes only when they are two percentages. A pane's buckets remembered under its older
 * name are read as its own.
 */
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

/** Those of `names` that are People buckets, in their order */
function knownBuckets(names) {
    return names.filter((name) => KNOWN_BUCKETS.includes(name))
}

/** `sizes` when they are two percentages, else the default */
function sizesOrDefault(sizes) {
    const isPercentage = (size) =>
        Number.isFinite(size) && size >= 0 && size <= 100
    return Array.isArray(sizes) &&
        sizes.length === 2 &&
        sizes.every(isPercentage)
        ? sizes
        : DEFAULT_SIZES
}

/** The JSON value remembered under `key`, or `fallback` when there is none or it does not parse */
function remembered(key, fallback) {
    try {
        return JSON.parse(localStorage.getItem(key)) ?? fallback
    } catch {
        return fallback
    }
}
