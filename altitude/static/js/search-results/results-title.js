/**
 * The results title (`.results-title`, includes/results_title.scala.html) sticks to the top of the
 * scrolling pane, and so do the group headers of a grouped grid, which have to stick below it rather
 * than slide under it. The title's height is not fixed - a folder path wraps, the person's name
 * editor swaps in - so its rendered height is written to `--results-title-height` on the fragment,
 * the offset the headers stick at (static/css/includes/search_results.css), and kept current for as
 * long as the fragment lives. A fragment without a title writes nothing, and the headers stick at 0.
 */
const HEIGHT_PROPERTY = "--results-title-height"

let observer = null

export function bindResultsTitle({ fragmentEl }) {
    // The previous fragment is gone with its title
    observer?.disconnect()
    observer = null

    const titleEl = fragmentEl.querySelector(".results-title")

    if (!titleEl) {
        return
    }

    const writeHeight = () => {
        fragmentEl.style.setProperty(
            HEIGHT_PROPERTY,
            `${titleEl.offsetHeight}px`,
        )
    }

    observer = new ResizeObserver(writeHeight)
    observer.observe(titleEl)
    writeHeight()
}
