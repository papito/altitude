import { Const } from "../constants.js"
import { context } from "../context.js"

/**
 * INFINITE SCROLL
 * ===============
 * HTMX continuous load should be automatically adding the "data-hx-revealed" attribute
 * to the element that triggered content loading, so they do not trigger another load
 * when coming into view during scrolling back up.
 *
 * This does not seem to work with the "intersect" option, which we have to use because
 * our "overflow-y" is set to scroll/auto for the content div.
 *
 * As a workaround, "data-hx-revealed" is set after each request is finished, to mimic that behavior.
 * When it is present, we abort the request (as HTMX will try to fire it off anyway)
 */
export function initInfiniteScroll() {
    const assetsElement = document.getElementById("assets")

    /**
     * We are watching the #assets container for these events and not individual cells
     * as the cells are added dynamically, and we would have to re-wire the events every time
     * we infinite-load more images.
     */
    assetsElement.addEventListener("htmx:beforeRequest", function (evt) {
        if (!evt.target.classList.contains("last-cell")) {
            return
        }

        if (evt.detail.target.getAttribute("data-hx-revealed")) {
            evt.preventDefault()
        } else {
            console.debug("Loading more: %s", evt.detail.pathInfo.requestPath)
        }
    })

    assetsElement.addEventListener("htmx:afterRequest", function (evt) {
        if (!evt.target.classList.contains("last-cell")) {
            return
        }
        evt.detail.target.setAttribute("data-hx-revealed", "true")
    })
}

/**
 * LAZY-LOAD INTERSECTION OBSERVER
 * How does this work: https://developer.mozilla.org/en-US/docs/Web/API/Intersection_Observer_API
 *
 * Once an image comes into the viewport replace "src" with "data-src".
 * Once an image is out of the view, replace "src" with a placeholder.
 */

// This is a transparent GIF image
const placeholderImageData =
    "data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw=="

export function initLazyLoad() {
    const observerOptions = {
        root: null, // intersection with Viewport
        rootMargin: "0px 100% 0px 100%",
        threshold: [0, 1], // when goes fully invisible OR visible
    }
    const observer = new IntersectionObserver(function (entries, self) {
        entries.forEach((entry) => {
            if (entry.isIntersecting) {
                if (
                    entry.target.getAttribute("src") ===
                    entry.target.getAttribute(Const.attributes.dataSrc)
                ) {
                    // console.debug("Already loaded: %s", entry.target.getAttribute(Const.attributes.dataSrc))
                    return
                }

                if (entry.target.hasAttribute(Const.attributes.dataSrc)) {
                    // console.debug("Loading: %s", entry.target.src)
                    entry.target.src = entry.target.getAttribute(
                        Const.attributes.dataSrc,
                    )
                }
            } else {
                if (entry.target.getAttribute("src") === placeholderImageData) {
                    return
                }

                if (entry.target.hasAttribute(Const.attributes.dataSrc)) {
                    // console.debug("Unloading %s", entry.target.getAttribute(Const.attributes.dataSrc))
                    entry.target.src = placeholderImageData
                }
            }
        })
    }, observerOptions)

    document
        .querySelector("#assets")
        .addEventListener("htmx:load", function (evt) {
            /**
             * Observe the image element for viewport intersection, to lazy load/unload
             */
            const imgEl = evt.target.querySelector("img")
            if (imgEl !== null) {
                observer.observe(imgEl)
            }

            showOrHideAssetGridMetadata(evt.target)
        })

    /**
     * IMPORTANT: We need to trigger this manually for the initial load.
     * The metadata fields are painted when HTMX loads more data into #assets,
     * but initial load triggers the load event on #content, not #assets.
     */
    document.querySelectorAll("#assets .cell").forEach((el) => {
        showOrHideAssetGridMetadata(el)
    })
}

function showOrHideAssetGridMetadata(el) {
    const showFields = context.getGridMetadataFields()

    const toShowFieldsSelectorStr = Array.from(showFields)
        .map((fieldName) => ".metadata > div." + fieldName)
        .join(", ")

    if (toShowFieldsSelectorStr.length) {
        el.querySelectorAll(toShowFieldsSelectorStr).forEach((div) => {
            div.style.display = "block"
        })
        el.querySelector(".metadata").style.display = "grid"
    } else {
        el.querySelector(".metadata").style.display = "none"
    }
}
