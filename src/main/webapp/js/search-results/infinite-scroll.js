import { Const } from "../constants.js"

/**
 * LAZY-LOAD INTERSECTION OBSERVER
 * How does this work: https://developer.mozilla.org/en-US/docs/Web/API/Intersection_Observer_API
 *
 * Once an image comes into the viewport replace "src" with "data-src".
 * Once an image is out of the view, replace "src" with a placeholder.
 */
const observerOptions = {
    root: null, // intersection with Viewport
    rootMargin: "0px 100% 0px 100%",
    threshold: [0, 1], // when goes fully invisible OR visible
}

// This is a transparent GIF image
const placeholderImageData =
    "data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw=="

export const observer = new IntersectionObserver(function (entries, self) {
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
                entry.target.src = entry.target.getAttribute(Const.attributes.dataSrc)
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
