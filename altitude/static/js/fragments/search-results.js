import { Const } from "../constants.js"
import {
    getRequestPath,
    getRequestTarget,
    isRequestSuccessful,
} from "../common/htmx-events.js"

const placeholderImageData =
    "data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw=="

export function hydrateSearchResultsFragment({ fragmentEl, app }) {
    const resultsTotal = Number(fragmentEl.dataset.resultsTotal || 0)
    const contentElement = document.getElementById("content")
    const assetsElement = fragmentEl.querySelector("#assets")

    if (contentElement) {
        contentElement.scrollTo({ top: 0, behavior: "auto" })
    }

    app.Alpine.store(Const.state.selectedAssets).reset()
    app.Alpine.store(Const.state.resultsTotal).set(resultsTotal)
    app.Alpine.store(Const.state.shadowResults).reset()

    if (!assetsElement) {
        return
    }

    bindSearchResultsInfiniteScroll({ assetsElement, app })
    bindSearchResultsLazyLoad({ assetsElement, app })
    applyGridMetadataVisibilityToAllCells({
        assetsElement,
        context: app.context,
    })
    app.searchDetailCoordinator.syncShadowResultsFromSearchUrl()
}

export function handleViewSettingChanged({ event, context }) {
    const fieldName = event.detail.fieldName
    const checked = event.detail.checked

    document
        .querySelectorAll(`.metadata > div.${fieldName}`)
        .forEach((divEl) => {
            divEl.style.display = checked ? "block" : "none"
        })

    const showFields = context.getGridMetadataFields()
    const metadataDisplay = showFields.size === 0 ? "none" : "grid"

    document.querySelectorAll("#assets .metadata").forEach((divEl) => {
        divEl.style.display = metadataDisplay
    })
}

function bindSearchResultsInfiniteScroll({ assetsElement, app }) {
    if (assetsElement.dataset.appInfiniteScrollBound === "true") {
        return
    }

    assetsElement.dataset.appInfiniteScrollBound = "true"

    assetsElement.addEventListener("htmx:before:request", (event) => {
        if (!event.target.classList.contains("last-cell")) {
            return
        }

        if (getRequestTarget(event).getAttribute("data-hx-revealed")) {
            event.preventDefault()
        } else {
            console.debug("Loading more: %s", getRequestPath(event))
        }
    })

    assetsElement.addEventListener("htmx:after:request", (event) => {
        if (!event.target.classList.contains("last-cell")) {
            return
        }

        getRequestTarget(event).setAttribute("data-hx-revealed", "true")

        if (isRequestSuccessful(event)) {
            app.searchDetailCoordinator.appendShadowResultsForRequestPath(
                getRequestPath(event),
            )
        }
    })
}

function bindSearchResultsLazyLoad({ assetsElement, app }) {
    if (assetsElement.dataset.appLazyLoadBound === "true") {
        return
    }

    assetsElement.dataset.appLazyLoadBound = "true"

    const observer = getLazyImageObserver(app)

    // Fires once per swap with the nodes htmx inserted - the next page of cells here
    assetsElement.addEventListener("htmx:after:settle", (event) => {
        event.detail.newContent.forEach((cellEl) => {
            if (!(cellEl instanceof Element)) {
                return
            }

            const imgEl = cellEl.querySelector("img")
            if (imgEl) {
                observer.observe(imgEl)
            }

            showOrHideAssetGridMetadata({ cellEl, context: app.context })
        })
    })

    assetsElement.querySelectorAll(".cell").forEach((cellEl) => {
        const imgEl = cellEl.querySelector("img")
        if (imgEl) {
            observer.observe(imgEl)
        }

        showOrHideAssetGridMetadata({ cellEl, context: app.context })
    })
}

function getLazyImageObserver(app) {
    if (app.lazyImageObserver) {
        return app.lazyImageObserver
    }

    const observerOptions = {
        root: null,
        rootMargin: "0px 100% 0px 100%",
        threshold: [0, 1],
    }

    app.lazyImageObserver = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
            const imgEl = entry.target

            if (entry.isIntersecting) {
                if (
                    imgEl.getAttribute("src") ===
                    imgEl.getAttribute(Const.attributes.dataSrc)
                ) {
                    return
                }

                if (imgEl.hasAttribute(Const.attributes.dataSrc)) {
                    imgEl.src = imgEl.getAttribute(Const.attributes.dataSrc)
                }

                return
            }

            if (imgEl.getAttribute("src") === placeholderImageData) {
                return
            }

            if (imgEl.hasAttribute(Const.attributes.dataSrc)) {
                imgEl.src = placeholderImageData
            }
        })
    }, observerOptions)

    return app.lazyImageObserver
}

function applyGridMetadataVisibilityToAllCells({ assetsElement, context }) {
    assetsElement.querySelectorAll(".cell").forEach((cellEl) => {
        showOrHideAssetGridMetadata({ cellEl, context })
    })
}

function showOrHideAssetGridMetadata({ cellEl, context }) {
    const showFields = context.getGridMetadataFields()
    const metadata = cellEl.querySelector(".metadata")

    if (!metadata) {
        return
    }

    const toShowFieldsSelectorStr = Array.from(showFields)
        .map((fieldName) => `.metadata > div.${fieldName}`)
        .join(", ")

    metadata.style.display = toShowFieldsSelectorStr.length ? "grid" : "none"

    if (!toShowFieldsSelectorStr.length) {
        return
    }

    cellEl.querySelectorAll(".metadata > div").forEach((divEl) => {
        divEl.style.display = "none"
    })

    cellEl.querySelectorAll(toShowFieldsSelectorStr).forEach((divEl) => {
        divEl.style.display = "block"
    })
}
