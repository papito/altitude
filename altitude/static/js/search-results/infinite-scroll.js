import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { runSearch } from "./search.js"

/**
 * INFINITE SCROLL
 *
 * The last cell of a page carries the cursor the next page continues from (`data-app-search-after`),
 * in a grouped grid and an ungrouped one alike. When it comes into view we request that page through
 * the search funnel, which supplies the rest of the current search, and append the result after the
 * cell. Each cell loads its page once: it loses the attribute as it does, so scrolling back up over
 * it loads nothing again.
 *
 * The detail modal loads pages the same way, through `loadNextPage`, when it steps past the last
 * loaded cell (js/search-results/detail-navigator.js).
 *
 * The grid holds a window of pages (js/search-results/page-window.js): a page far above the
 * viewport is folded into a spacer and reloaded as a continuation when it comes near again, and a
 * page far below is dropped. So a cell carrying a cursor is observed only when nothing follows it
 * in the grid - a reloaded page's last cell carries one too, but the page it leads to is there
 * already - and the window puts the cursor back on the last cell when the page after it is
 * dropped. The window learns which cursor an arriving page was requested with from the request in
 * flight here, keyed by the cell it was requested from.
 *
 * A continuation continues the grid as it was rendered: the layout and grouping its results fragment
 * carries (`data-results-layout`, `data-results-group-by`, `data-results-group-direction`) are sent
 * for that one request, whatever the store now says. For the main grid they are the store's own
 * values; for the crowded-pin panel's grid (js/map/map-panel.js) they are the plain grid it was
 * requested as, while the store says map.
 */

// A cell that has already loaded its page keeps the class but loses the attribute, so only the one
// page still to be loaded is ever picked up
const CONTINUATION_SELECTOR = ".last-cell[data-app-search-after]"

// The page request in flight per last cell, with the cursor it asked for: a second caller while it
// is in flight - the modal stepping past the cell the scroll is already loading, or the reverse -
// shares it, and the page window reads the cursor off it when the page settles
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
 * Watches the displayed grid's last cell, and every last cell of the pages appended to it. A page
 * arriving is also the one moment cells enter the grid, which the selection store is told about
 * so a group header can recount its group.
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

/**
 * Watches `cellEl`, the grid's last cell again now that the page after it was dropped. It fires
 * at once if the cell is already in view.
 */
export function observeContinuationCell(cellEl) {
    getNextPageObserver().observe(cellEl)
}

/**
 * Observes the continuation cells under `root` that nothing follows in the grid: a reloaded
 * page's last cell carries a cursor too, but the page it leads to is loaded already.
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
