import { Const } from "../../constants.js"
import { context } from "../../context.js"
import Split from "../../lib/split.es.js"

/**
 * The People tab (`htmx/people.scala.html`): the owner of its People buckets, its Split view and
 * its pane loads, as `viewSettings` owns the grid's metadata switches.
 *
 * The browser owns the state. It is remembered in `localStorage` under
 * `Const.localStore.peopleView` as `{ split, buckets: { top: [...], bottom?: [...] } }`: whether
 * Split view is on and the buckets each pane shows, the bottom pane's only once it has had any.
 * A first visit shows Matched alone, with no split. The server knows nothing of it: it renders a
 * pane's list (`htmx/people_list`) for the buckets the pane asks for, and this component loads
 * each pane's list into its `.list` host itself, on init, when a switch flips, and when a person
 * changes (`Const.events.peopleChanged`: a merge, a hide, a show or a discard moves people between
 * buckets, so every pane reloads with its own).
 *
 * Split view adds the bottom pane under the shell's `x-if` and divides the two panes vertically
 * with the explorer's Split.js, whose sizes this browser remembers too
 * (`Const.localStore.verticalSplitSizes`). Turning it off destroys the Split.js instance, which
 * takes its gutter and the heights it set with it, and then drops the pane. The bottom pane keeps
 * its remembered buckets across split off and on; it copies the top pane's only the first time.
 */

const DEFAULT_SIZES = [50, 50]
const MIN_SIZE = 120
const { top: TOP, bottom: BOTTOM } = Const.people.panes
const KNOWN_BUCKETS = Object.values(Const.people.buckets)

export function peopleTab() {
    // The Split.js instance while Split view is on, and the listener that reloads the panes.
    // Closure state rather than component data: Alpine would proxy them, and nothing reactive
    // reads either.
    let splitInstance = null
    let onPeopleChanged = null

    return {
        // The remembered state, reactive: every switch's `aria-checked` and the bottom pane's
        // `x-if` follow it
        split: false,
        buckets: { [TOP]: [Const.people.buckets.matched] },

        init() {
            Object.assign(this, rememberedView())

            onPeopleChanged = () => this.reloadPanes()
            document.body.addEventListener(
                Const.events.peopleChanged,
                onPeopleChanged,
            )

            this.loadPane(TOP)
            if (this.split) {
                // The bottom pane exists once Alpine has rendered the `x-if`
                this.$nextTick(() => this.openSplit())
            }
        },

        // Alpine calls this when another tab's fragment replaces the shell
        destroy() {
            document.body.removeEventListener(
                Const.events.peopleChanged,
                onPeopleChanged,
            )
            onPeopleChanged = null
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
                if (!this.buckets[BOTTOM]) {
                    this.buckets[BOTTOM] = [...this.buckets[TOP]]
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
            this.loadPane(TOP)
            if (this.split) {
                this.loadPane(BOTTOM)
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

        /** Divides the two panes at the remembered sizes, then fills the bottom one */
        openSplit() {
            const panes = [this.paneEl(TOP), this.paneEl(BOTTOM)]
            if (panes.some((pane) => !pane)) {
                return
            }

            splitInstance = Split(panes, {
                direction: "vertical",
                sizes: rememberedSizes(),
                minSize: MIN_SIZE,
                onDragEnd: (sizes) =>
                    localStorage.setItem(
                        Const.localStore.verticalSplitSizes,
                        JSON.stringify(sizes),
                    ),
            })
            this.loadPane(BOTTOM)
        },

        closeSplit() {
            splitInstance?.destroy()
            splitInstance = null
        },

        remember() {
            localStorage.setItem(
                Const.localStore.peopleView,
                JSON.stringify({ split: this.split, buckets: this.buckets }),
            )
        },
    }
}

/**
 * The remembered state, or the first visit's when none is remembered or it cannot be read. Only
 * the bucket names the tab knows are kept, and the bottom pane's list only when it is one.
 */
function rememberedView() {
    const saved = remembered(Const.localStore.peopleView, null)
    if (!Array.isArray(saved?.buckets?.[TOP])) {
        return {
            split: false,
            buckets: { [TOP]: [Const.people.buckets.matched] },
        }
    }
    const buckets = { [TOP]: knownBuckets(saved.buckets[TOP]) }
    if (Array.isArray(saved.buckets[BOTTOM])) {
        buckets[BOTTOM] = knownBuckets(saved.buckets[BOTTOM])
    }
    return { split: saved.split === true, buckets }
}

/** Those of `names` that are People buckets, in their order */
function knownBuckets(names) {
    return names.filter((name) => KNOWN_BUCKETS.includes(name))
}

/** The remembered split sizes when they are two percentages, else the default */
function rememberedSizes() {
    const saved = remembered(Const.localStore.verticalSplitSizes, null)
    const isPercentage = (size) =>
        Number.isFinite(size) && size >= 0 && size <= 100
    return Array.isArray(saved) &&
        saved.length === 2 &&
        saved.every(isPercentage)
        ? saved
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
