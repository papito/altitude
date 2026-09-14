import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { runSearch } from "./search.js"

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
 *
 * A continuation continues the grid as it was rendered: the layout and grouping its results fragment
 * carries (`data-results-layout`, `data-results-group-by`, `data-results-group-direction`) are sent
 * for that one request, whatever the store now says. For the main grid they are the store's own
 * values; for the crowded-pin panel's grid (js/map/map-panel.js) they are the plain grid it was
 * requested as, while the store says map.
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
            ...renderedGridParamsOf(lastCellEl),
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

/** The layout and grouping the grid holding `cellEl` was rendered with, from its results fragment */
function renderedGridParamsOf(cellEl) {
    const fragment = cellEl.closest('[data-app-fragment="search-results"]')
    if (!fragment) {
        return {}
    }

    const { resultsLayout, resultsGroupBy, resultsGroupDirection } =
        fragment.dataset

    return {
        layout: resultsLayout || null,
        groupBy: resultsGroupBy || null,
        groupDirection: resultsGroupDirection || null,
    }
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

/**
 * Watches the displayed grid's last cell, and every last cell of the pages appended to it. A page
 * arriving is also the one moment cells enter the grid, which the selection store is told about
 * so a group header can recount its group.
 */
export function bindInfiniteScroll({ assetsElement }) {
    const observer = getNextPageObserver()

    if (assetsElement.dataset.appInfiniteScrollBound !== "true") {
        assetsElement.dataset.appInfiniteScrollBound = "true"

        // Fires once per swap with the nodes htmx inserted - the next page's own last cell among them
        assetsElement.addEventListener("htmx:after:settle", (event) => {
            event.detail.newContent.forEach((node) => {
                observeLastCell({ root: node, observer })
            })

            Alpine.store(Const.state.selectedAssets).noteGridChange()
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
