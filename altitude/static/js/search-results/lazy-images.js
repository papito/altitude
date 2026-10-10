import { Const } from "../constants.js"

/**
 * LAZY IMAGES
 *
 * Every thumbnail carries its URL in `data-src`, the first page's included; the first page's also
 * has `src`, so it paints before any script runs. One IntersectionObserver, shared by every grid
 * the page displays, loads an image as it comes within a viewport and swaps the transparent
 * placeholder back in once it is more than a viewport away, so the thumbnails held are bounded by
 * the viewport, not by how far the session has scrolled. A released image that scrolls back in is
 * a browser cache hit: the content route sends Cache-Control for an hour.
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

    // Fires once per swap with the nodes htmx inserted - the next page of cells here
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
 * The placeholder is a transparent 1x1 pixel, which would shrink the thumbnail box to nothing
 * once it replaces a loaded image. The box is what a box selection hit-tests against, and it
 * must stay where the image was so a rectangle drawn over scrolled-away cells still finds them.
 * The cell's row is fixed, so the grid layout is unchanged either way. An image that never
 * loaded has no size to keep.
 */
function keepRenderedSize(imgEl) {
    if (imgEl.naturalWidth > 1 && imgEl.offsetWidth > 0) {
        imgEl.style.width = `${imgEl.offsetWidth}px`
        imgEl.style.height = `${imgEl.offsetHeight}px`
    }
}
