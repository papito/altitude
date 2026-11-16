import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { runSearch } from "./search.js"

/**
 * INFINITE SCROLL
 *
 * The last cell of a page carries the cursor the next page continues from (`data-app-search-after`).
 * When it comes into view, the page is requested through the search funnel and appended after it;
 * the cell loses the attribute as it loads, so each page loads once.
 *
 * The page window (`page-window.js`) folds pages far above into spacers and drops pages far below. It
 * learns which cursor an arriving page was requested with from the request in flight here.
 *
 * A continuation is sent with the layout and grouping its results fragment was rendered with, not the
 * store's: the crowded-pin panel's grid is a plain grid while the store says map.
 */

// A cell that has already loaded its page keeps the class but loses the attribute, so only the one
// page still to be loaded is ever picked up
const CONTINUATION_SELECTOR = ".last-cell[data-app-search-after]"

// The page request in flight per last cell, with its cursor: a second caller (the modal stepping past
// the cell the scroll is loading, or the reverse) shares it, and the page window reads the cursor off
// it when the page settles
const pendingPageLoads = new WeakMap()

let nextPageObserver = null

/** Loads the page `lastCellEl` continues to, after the cell; a page already in flight shares its request */
export function loadNextPage(lastCellEl) {
    const pending = pendingPageLoads.get(lastCellEl)
    if (pending) {
        return pending.request
    }

    const after = lastCellEl.dataset.appSearchAfter
    if (!after) {
        return Promise.resolve()
    }

    // One request per cell, whatever the scroll does afterwards
    nextPageObserver?.unobserve(lastCellEl)
    delete lastCellEl.dataset.appSearchAfter

    console.debug("Loading more after: %s", after)

    const request = runSearch({
        transient: {
            ...renderedGridParamsOf(lastCellEl),
            after,
            isContinuousScroll: true,
        },
        target: lastCellEl,
        swap: "afterend",
    }).finally(() => pendingPageLoads.delete(lastCellEl))

    pendingPageLoads.set(lastCellEl, { after, request })

    return request
}

/** The cursor of the page `lastCellEl` is loading, or undefined when none is in flight */
export function pendingCursorOf(lastCellEl) {
    return pendingPageLoads.get(lastCellEl)?.after
}

/** The layout and grouping the grid holding `cellEl` was rendered with, from its results fragment */
export function renderedGridParamsOf(cellEl) {
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

/**
 * Watches the grid's last cell and those of the pages appended to it. A page arriving is the one
 * moment cells enter the grid, so the selection store is told then, for group headers to recount.
 */
export function bindInfiniteScroll({ assetsElement }) {
    const observer = getNextPageObserver()

    if (assetsElement.dataset.appInfiniteScrollBound !== "true") {
        assetsElement.dataset.appInfiniteScrollBound = "true"

        // Fires once per swap with the nodes htmx inserted: the next page, with its own last cell
        // among them, or a page reloaded above the window, whose last cell nothing continues from
        assetsElement.addEventListener("htmx:after:settle", (event) => {
            event.detail.newContent.forEach((node) => {
                observeLastCell({ root: node, observer })
            })

            Alpine.store(Const.state.selectedAssets).noteGridChange()
        })
    }

    observeLastCell({ root: assetsElement, observer })
}

/** Watches `cellEl`, the last cell again once the page after it was dropped; fires at once if it is in view */
export function observeContinuationCell(cellEl) {
    getNextPageObserver().observe(cellEl)
}

/**
 * Observes the cursor cells under `root` that nothing follows: a reloaded page's last cell carries a
 * cursor too, but the page it leads to is already there.
 */
function observeLastCell({ root, observer }) {
    if (!(root instanceof Element)) {
        return
    }

    const cellEls = root.matches(CONTINUATION_SELECTOR)
        ? [root]
        : root.querySelectorAll(CONTINUATION_SELECTOR)

    cellEls.forEach((cellEl) => {
        if (!cellEl.nextElementSibling) {
            observer.observe(cellEl)
        }
    })
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
