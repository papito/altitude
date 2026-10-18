/**
 * The split between the explorer and the content area of the main page (`views/index.scala.html`),
 * a Split.js instance over `#explorer` and `#content` whose sizes this browser remembers
 * (`Const.localStore.horizontalSplitSizes`). The design tweaks panel's explorer slider sets the
 * sizes through `setExplorerSizes`, so the width is explored like a token and reported as the Split
 * sizes it is.
 *
 * see: https://github.com/nathancahill/split/
 */
import { Const } from "../constants.js"
import Split from "../lib/split.es.js"

const DEFAULT_SIZES = [25, 75]
const MIN_SIZES = [100, 300]

// The one instance, while the page has a split
let split = null
let sizes = DEFAULT_SIZES

/**
 * Lays the main page out for `view`. In the trash bin the explorer is not useful: it is hidden
 * entirely and the content area fills the width, and no Split is made, so no gutter is inserted
 * into the DOM. In every other view the two areas split at the remembered sizes.
 */
export function initExplorerSplit(view) {
    if (view === Const.views.trashbin) {
        const explorer = document.getElementById("explorer")
        explorer.style.display = "none"

        const content = document.getElementById("content")
        content.style.float = "none"
        content.style.width = "100%"
        return
    }

    sizes = rememberedSizes()
    split = Split(["#explorer", "#content"], {
        sizes,
        minSize: MIN_SIZES,
        onDragEnd: rememberSizes,
    })
}

/** The explorer's share of the width, in percent, as the split stands or as it was remembered */
export function explorerPercent() {
    return (split === null ? rememberedSizes() : sizes)[0]
}

/** Whether the page has a split to set: not in the trash bin, and not on a page without the explorer */
export function hasExplorerSplit() {
    return split !== null
}

/** Gives the explorer `percent` of the width and the content the rest, and remembers it as a drag would */
export function setExplorerSizes(percent) {
    if (split === null) {
        return
    }
    const next = [percent, 100 - percent]
    split.setSizes(next)
    rememberSizes(next)
}

function rememberedSizes() {
    const saved = localStorage.getItem(Const.localStore.horizontalSplitSizes)
    return saved ? JSON.parse(saved) : DEFAULT_SIZES
}

function rememberSizes(next) {
    sizes = next
    localStorage.setItem(
        Const.localStore.horizontalSplitSizes,
        JSON.stringify(next),
    )
}
