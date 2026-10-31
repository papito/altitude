/**
 * The results title (`.results-title`, includes/results_title.scala.html) and the toolbar
 * (`#searchControl`) stick to the top of the scrolling pane, one under the other, and the group
 * headers of a grouped grid stick under both rather than sliding beneath them. Neither height is
 * fixed - a folder path wraps, the person's name editor swaps in, the toolbar reflows - so the
 * rendered heights are written on the fragment as `--results-title-height` (the toolbar's offset; 0
 * without a title) and `--results-sticky-height` (the title and the toolbar together, the headers'
 * offset; static/css/includes/search_results.css reads both), and kept current for as long as the
 * fragment lives.
 */
const TITLE_HEIGHT_PROPERTY = "--results-title-height"
const STICKY_HEIGHT_PROPERTY = "--results-sticky-height"

let observer = null

export function bindStickyOffsets({ fragmentEl }) {
    // The previous fragment is gone with its title and toolbar
    observer?.disconnect()
    observer = null

    const titleEl = fragmentEl.querySelector(".results-title")
    const controlsEl = fragmentEl.querySelector("#searchControl")

    if (!controlsEl) {
        return
    }

    const writeHeights = () => {
        const titleHeight = titleEl?.offsetHeight ?? 0

        fragmentEl.style.setProperty(TITLE_HEIGHT_PROPERTY, `${titleHeight}px`)
        fragmentEl.style.setProperty(
            STICKY_HEIGHT_PROPERTY,
            `${titleHeight + controlsEl.offsetHeight}px`,
        )
    }

    observer = new ResizeObserver(writeHeights)
    observer.observe(controlsEl)

    if (titleEl) {
        observer.observe(titleEl)
    }

    writeHeights()
}
