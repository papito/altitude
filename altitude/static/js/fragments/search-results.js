import { Const } from "../constants.js"
import { setViewedAlbum } from "../common/album-list.js"
import { setViewedFolderScope } from "../common/viewed-folder-scope.js"
import { buildDialogTriggerCtrl } from "../common/context-menu.js"
import { runSearch } from "../search-results/search.js"
import { bindBoxSelection } from "../search-results/box-selection.js"
import { bindDateGroupSelectionSync } from "../search-results/date-groups.js"

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

    syncViewedScope(fragmentEl)
    ensureViewSettingsControl(fragmentEl)

    // Also discards the previous grid's controller, and any box it was still drawing
    bindBoxSelection({ assetsElement, contentElement })

    if (!assetsElement) {
        return
    }

    bindSearchResultsInfiniteScroll({ assetsElement })
    bindSearchResultsLazyLoad({ assetsElement, app })
    bindDateGroupSelectionSync({ assetsElement })
    applyGridMetadataVisibilityToAllCells({
        assetsElement,
        context: app.context,
    })
}

/**
 * The folder tree highlights the folder scope of the results now displayed, and the album list the
 * album. The fragment carries the scope the server resolved, which is what is on screen - not what
 * the search store now asks for, so a superseded or failed navigation never moves the highlight.
 * Triage and trash results have no folder or album scope, whatever is still in the search parameters.
 */
function syncViewedScope(fragmentEl) {
    const { resultsRepoId, resultsView, resultsFolderId, resultsAlbumId } =
        fragmentEl.dataset
    const hasScope =
        resultsView !== Const.views.triage &&
        resultsView !== Const.views.trashbin

    setViewedFolderScope({
        repoId: resultsRepoId,
        folderId: hasScope ? resultsFolderId : null,
    })
    setViewedAlbum(hasScope ? resultsAlbumId : null)
}

/**
 * Builds the ⚙ View control into its host, the same dialog-trigger context menu as the Add album and
 * Add folder buttons: it toggles a panel holding only the view settings dialog, which it requests
 * into that panel. Built here rather than in the template because it is a context menu component,
 * like the folder and album menus; the template only supplies the host.
 *
 * The whole results fragment is re-swapped on every search, so the host arrives empty and the
 * control is built again; the guard keeps a re-hydration of the same DOM from building a second one.
 */
function ensureViewSettingsControl(fragmentEl) {
    const hostEl = fragmentEl.querySelector("#viewSettingsActions")

    if (!hostEl || hostEl.childElementCount > 0) {
        return
    }

    hostEl.appendChild(
        buildDialogTriggerCtrl({
            triggerId: "viewSettingsBtn",
            panelId: "viewSettingsMenu",
            dialogId: "viewSettingsDialog",
            label: "View",
            iconClass: "fas fa-cog",
            url: `/htmx/view-settings/r/${fragmentEl.dataset.resultsRepoId}/dialogs/view-settings`,
            buttonClass: "action-button small",
        }),
    )

    if (window.htmx) {
        htmx.process(hostEl)
    }
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

/**
 * INFINITE SCROLL
 *
 * The last cell of a page carries how the next page is reached: its number (`data-app-search-next-page`,
 * an ungrouped grid) or the cursor to continue from (`data-app-search-after`, a grouped grid). When
 * it comes into view we request that page through the search funnel, which supplies the rest of the
 * current search, and append the result after the cell. Each cell loads its page once: it loses the
 * attribute as it does, so scrolling back up over it loads nothing again.
 *
 * The detail modal loads pages the same way, through `loadNextPage`, when it steps past the last
 * loaded cell (js/search-results/detail-navigator.js).
 */

// A cell that has already loaded its page keeps the class but loses the attribute, so only the one
// page still to be loaded is ever picked up
const CONTINUATION_SELECTOR =
    ".last-cell[data-app-search-next-page], .last-cell[data-app-search-after]"

// The page request in flight per last cell: a second caller while it is in flight - the modal
// stepping past the cell the scroll is already loading, or the reverse - shares it
const pendingPageLoads = new WeakMap()

let nextPageObserver = null

/**
 * Loads the page `lastCellEl` continues to, appending it after the cell, and resolves when the
 * request completes. A cell without a continuation resolves at once, and a cell whose page is in
 * flight returns that request, so nothing is ever requested twice.
 */
export function loadNextPage(lastCellEl) {
    const pending = pendingPageLoads.get(lastCellEl)
    if (pending) {
        return pending
    }

    const continuation = continuationOf(lastCellEl)
    if (!continuation) {
        return Promise.resolve()
    }

    // One request per cell, whatever the scroll does afterwards
    nextPageObserver?.unobserve(lastCellEl)
    delete lastCellEl.dataset.appSearchNextPage
    delete lastCellEl.dataset.appSearchAfter

    console.debug("Loading more: %o", continuation)

    // `p: null` keeps the store's page out of a cursor continuation; the store's own value is
    // never right for a continuation anyway
    const request = runSearch({
        transient: {
            ...continuation,
            p: continuation.p ?? null,
            isContinuousScroll: true,
        },
        target: lastCellEl,
        swap: "afterend",
    }).finally(() => pendingPageLoads.delete(lastCellEl))

    pendingPageLoads.set(lastCellEl, request)

    return request
}

/** `{ after }` or `{ p }`, whichever the cell carries; `null` when it carries neither */
function continuationOf(lastCellEl) {
    const { appSearchAfter, appSearchNextPage } = lastCellEl.dataset

    if (appSearchAfter) {
        return { after: appSearchAfter }
    }

    if (appSearchNextPage) {
        return { p: Number(appSearchNextPage) }
    }

    return null
}

function bindSearchResultsInfiniteScroll({ assetsElement }) {
    const observer = getNextPageObserver()

    if (assetsElement.dataset.appInfiniteScrollBound !== "true") {
        assetsElement.dataset.appInfiniteScrollBound = "true"

        // Fires once per swap with the nodes htmx inserted - the next page's own last cell among them
        assetsElement.addEventListener("htmx:after:settle", (event) => {
            event.detail.newContent.forEach((node) => {
                observeLastCell({ root: node, observer })
            })
        })
    }

    observeLastCell({ root: assetsElement, observer })
}

function observeLastCell({ root, observer }) {
    if (!(root instanceof Element)) {
        return
    }

    if (root.matches(CONTINUATION_SELECTOR)) {
        observer.observe(root)
    }

    root.querySelectorAll(CONTINUATION_SELECTOR).forEach((cellEl) =>
        observer.observe(cellEl),
    )
}

function getNextPageObserver() {
    if (nextPageObserver) {
        return nextPageObserver
    }

    nextPageObserver = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
            if (entry.isIntersecting) {
                loadNextPage(entry.target)
            }
        })
    })

    return nextPageObserver
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
                keepRenderedSize(imgEl)
                imgEl.src = placeholderImageData
            }
        })
    }, observerOptions)

    return app.lazyImageObserver
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
