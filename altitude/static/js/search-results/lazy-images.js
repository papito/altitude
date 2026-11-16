import { Const } from "../constants.js"

/**
 * LAZY IMAGES
 *
 * Every thumbnail carries its URL in `data-src`; the first page's also has `src`, so it paints before
 * any script runs. One shared IntersectionObserver loads an image within a viewport of view and swaps
 * the placeholder back in once it is farther away, so the thumbnails held are bounded by the viewport,
 * not by how far the session scrolled. A released image that scrolls back in is a cache hit: the
 * content route sends `Cache-Control` for a year, `immutable`.
 */

const placeholderImageData =
    "data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw=="

let lazyImageObserver = null

export function bindLazyImages({ assetsElement }) {
    if (assetsElement.dataset.appLazyLoadBound === "true") {
        return
    }

    assetsElement.dataset.appLazyLoadBound = "true"

    const observer = getLazyImageObserver()

    // Fires once per swap with the nodes htmx inserted: the next page of cells
    assetsElement.addEventListener("htmx:after:settle", (event) => {
        event.detail.newContent.forEach((cellEl) => {
            if (cellEl instanceof Element) {
                observeImages({ root: cellEl, observer })
            }
        })
    })

    observeImages({ root: assetsElement, observer })
}

function observeImages({ root, observer }) {
    root.querySelectorAll(".cell img").forEach((imgEl) =>
        observer.observe(imgEl),
    )
}

function getLazyImageObserver() {
    if (lazyImageObserver) {
        return lazyImageObserver
    }

    lazyImageObserver = new IntersectionObserver(
        (entries) => {
            entries.forEach((entry) => {
                const imgEl = entry.target
                const url = imgEl.getAttribute(Const.attributes.dataSrc)

                if (!url) {
                    return
                }

                if (entry.isIntersecting) {
                    if (imgEl.getAttribute("src") !== url) {
                        imgEl.src = url
                    }
                    return
                }

                if (imgEl.getAttribute("src") !== placeholderImageData) {
                    keepRenderedSize(imgEl)
                    imgEl.src = placeholderImageData
                }
            })
        },
        {
            root: null,
            // One viewport above and below: load that far ahead, release that far behind
            rootMargin: "100% 0px",
            threshold: [0, 1],
        },
    )

    return lazyImageObserver
}

/**
 * The 1x1 placeholder would shrink the thumbnail box to nothing, and box selection hit-tests that
 * box, so it keeps the loaded image's size for a rectangle drawn over scrolled-away cells. The cell's
 * row is fixed, so the layout does not change. An image that never loaded has no size to keep.
 */
function keepRenderedSize(imgEl) {
    if (imgEl.naturalWidth > 1 && imgEl.offsetWidth > 0) {
        imgEl.style.width = `${imgEl.offsetWidth}px`
        imgEl.style.height = `${imgEl.offsetHeight}px`
    }
}
