/**
 * The results title and the toolbar stick to the top of the pane, one under the other, and group
 * headers stick under both. Neither height is fixed (a folder path wraps, the toolbar reflows), so the
 * rendered heights are written on the fragment and kept current: `--results-title-height` (the
 * toolbar's offset, 0 without a title) and `--results-sticky-height` (both together, the headers').
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
