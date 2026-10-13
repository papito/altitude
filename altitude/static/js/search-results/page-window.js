import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { getResponseStatus } from "../common/htmx-events.js"
import {
    observeContinuationCell,
    pendingCursorOf,
    renderedGridParamsOf,
} from "./infinite-scroll.js"
import { runSearch } from "./search.js"

/**
 * PAGE WINDOW
 *
 * The grid keeps a window of loaded pages around the viewport, so what it holds is bounded by the
 * viewport and not by how far the session has scrolled. A page is recorded as it arrives: the first
 * one at hydration (the initial children of `#assets`), every later one from the grid's
 * `htmx:after:settle` event, which htmx dispatches on the swap target - the cell or the spacer the
 * page was requested from - with the nodes it inserted.
 *
 * From top to bottom `#assets` holds: zero or more spacers, one per folded page; then the loaded
 * pages, contiguous; then nothing. The last loaded page's last cell is the only cell carrying
 * `data-app-search-after`, the one js/search-results/infinite-scroll.js observes.
 *
 * Folding: once a page is appended and the loaded cells exceed MAX_LOADED_CELLS, the topmost
 * page is folded, unless it is within a viewport of the screen or only two pages are loaded. Its
 * nodes make way for one `.page-spacer` of about the page's height carrying its cursor, so the
 * scrollbar stays proportional. The header of the group still open at the page's end stays
 * before the spacer, where it keeps sticking over the group's loaded cells and counting them
 * (js/alpine/components/date-group-selectable.js walks the header's siblings, and a spacer is not
 * a header).
 *
 * Reloading: a spacer within a viewport (one IntersectionObserver with the lazy images' margin)
 * is reloaded as a continuation is, swapped after the spacer, which then goes with the kept
 * header, since the page renders its own headers again. One reload is in flight per spacer: the
 * detail modal meeting the spacer on its walk (`reloadSpacer`) and the scroll share it. A 204 means the page's
 * assets are gone, and the spacer goes. A reload that pushes the loaded cells over the maximum
 * drops the bottom page: its nodes are removed, the new last cell gets the dropped page's cursor
 * and is observed, and scrolling down loads the page again as any continuation.
 *
 * Every fold, reload and drop re-wraps the rows above the viewport, and `#assets` has
 * `overflow-anchor: none`, so the scroll position is corrected here: the viewport offset of the
 * first cell on screen is noted before the change and restored after it.
 *
 * A spacer is not a `.cell` or a `.result-group` and carries no `data-asset-id`, `img` or
 * `.metadata`: the detail modal's sibling walk, the group headers, selection, drag and drop and
 * lazy loading never see it.
 */

export const MAX_LOADED_CELLS = 600

const SPACER_CLASS = "page-spacer"

// Per grid (`#assets` is replaced on every search): the scroll pane, the page records in grid
// order, and the scroll anchor noted before a change
const windows = new WeakMap()

// The reload in flight per spacer
const pendingReloads = new WeakMap()

let spacerObserver = null

export function bindPageWindow({ assetsElement, scrollElement }) {
    if (windows.has(assetsElement)) {
        return
    }

    const grid = {
        assetsElement,
        scrollElement,
        pages: [
            {
                after: null,
                nodes: Array.from(assetsElement.children),
                keptHeader: null,
                spacer: null,
            },
        ],
        anchor: null,
    }
    windows.set(assetsElement, grid)

    // A page is swapped after a cell or a spacer of this grid, so the request's events bubble
    // here: the anchor is noted before the swap moves anything, and the page recorded once it is in
    assetsElement.addEventListener("htmx:before:swap", () => noteAnchor(grid))
    assetsElement.addEventListener("htmx:after:settle", (event) =>
        recordPage(grid, event),
    )

    // A 204 swaps nothing, so it dispatches no settle: the reload's cursor leads to no assets now
    assetsElement.addEventListener("htmx:after:request", (event) => {
        const page = pageOfSpacer(grid, event.target)

        if (page && getResponseStatus(event) === 204) {
            console.debug(
                "The folded page after %s has no assets left, dropping its spacer",
                page.after,
            )
            keepingViewport(grid, () => {
                detachSpacer(page)
                forget(grid, page)
            })
        }
    })
}

/** The page records of the grid `assetsElement`, in grid order */
export function pagesOf(assetsElement) {
    return windows.get(assetsElement)?.pages
}

/** Folds the pages above the window of `assetsElement` as an appended page does */
export function foldPagesIfNeeded(assetsElement) {
    const grid = windows.get(assetsElement)

    if (grid) {
        keepingViewport(grid, () => foldPages(grid))
    }
}

/**
 * Reloads the page a spacer stands for, resolving when the request completes. A reload in flight
 * is shared, so nothing is ever requested twice.
 */
export function reloadSpacer(spacerEl) {
    const pending = pendingReloads.get(spacerEl)
    if (pending) {
        return pending
    }

    const grid = windows.get(spacerEl.parentElement)
    const page = grid && pageOfSpacer(grid, spacerEl)
    if (!page) {
        return Promise.resolve()
    }

    console.debug("Reloading the folded page after: %s", page.after)

    // The first page is requested with no cursor, as the fragment was
    const request = runSearch({
        transient: {
            ...renderedGridParamsOf(spacerEl),
            ...(page.after ? { after: page.after } : {}),
            isContinuousScroll: true,
        },
        target: spacerEl,
        swap: "afterend",
    }).finally(() => pendingReloads.delete(spacerEl))

    pendingReloads.set(spacerEl, request)

    return request
}

/** Whether `el` is the spacer of a folded page: what the detail modal's walk reloads as it meets one */
export function isSpacer(el) {
    return el.classList.contains(SPACER_CLASS)
}

/**
 * Records the page a settled swap brought: the one a spacer was reloaded into, or a continuation
 * appended after the last cell, and keeps the window within its size.
 */
function recordPage(grid, event) {
    const nodes = Array.from(event.detail.newContent)
    const reloaded = pageOfSpacer(grid, event.target)

    if (reloaded) {
        reloaded.nodes = nodes

        // The server renders the page's last cell with its cursor; the page it leads to is loaded already
        const lastCellEl = nodes.filter(isCell).at(-1)
        if (lastCellEl) {
            delete lastCellEl.dataset.appSearchAfter
        }

        reloaded.keptHeader?.remove()
        reloaded.keptHeader = null
        detachSpacer(reloaded)
        dropPages(grid)
    } else {
        const after = pendingCursorOf(event.target)

        // Not a page: a swap of something else inside the grid
        if (after === undefined) {
            return
        }

        grid.pages.push({ after, nodes, keptHeader: null, spacer: null })
        foldPages(grid)
    }

    restoreAnchor(grid)
}

function foldPages(grid) {
    while (isOverfull(grid)) {
        const page = loadedPages(grid)[0]

        if (isNearViewport(page)) {
            return
        }

        foldPage(grid, page)
    }
}

/**
 * A page within a viewport stays, as a fold leaves one: dropping the page on screen would bring
 * it straight back through its last cell, and the fold after that would take the page the modal
 * is stepping up into. The window grows past its size only while the modal walks away from the
 * viewport, and the next scroll trims it.
 */
function dropPages(grid) {
    while (isOverfull(grid)) {
        const page = loadedPages(grid).at(-1)

        if (isNearViewport(page)) {
            return
        }

        dropPage(grid, page)
    }
}

function isOverfull(grid) {
    return (
        grid.assetsElement.querySelectorAll(".cell").length >
            MAX_LOADED_CELLS && loadedPages(grid).length > 2
    )
}

function foldPage(grid, page) {
    const bounds = cellBounds(page)

    // Its cells all left the grid (an action moved its assets out of these results), so its cursor
    // now leads to the next page's assets: there is nothing to fold or to bring back. Its headers
    // stay for the groups they still count.
    if (!bounds) {
        forget(grid, page)
        return
    }

    // The header of the group still open at the page's end - the next page continues it with no
    // header of its own - stays with the group's loaded cells; a group closed with the page keeps nothing
    const elements = connectedElements(page)
    const nextPage = loadedPages(grid)[1]
    const nextFirstEl = nextPage && connectedElements(nextPage)[0]
    const groupContinues = !nextFirstEl || !isHeader(nextFirstEl)
    const keptHeader = groupContinues
        ? (elements.findLast(isHeader) ?? null)
        : null

    const spacerEl = document.createElement("div")
    spacerEl.className = SPACER_CLASS
    spacerEl.dataset.appSearchAfter = page.after ?? ""
    spacerEl.style.height = `${bounds.bottom - bounds.top}px`

    // After the page's last element, so it follows the kept header and precedes the next page,
    // which was swapped in right after that element; the page's other nodes then go
    elements.at(-1).after(spacerEl)
    page.nodes.forEach((node) => {
        if (node !== keptHeader) {
            node.remove()
        }
    })

    Object.assign(page, { nodes: [], keptHeader, spacer: spacerEl })
    getSpacerObserver().observe(spacerEl)

    console.debug(
        "Folded the page after %s into a spacer of %dpx",
        page.after,
        bounds.bottom - bounds.top,
    )
    noteGridChange()
}

function dropPage(grid, page) {
    page.nodes.forEach((node) => node.remove())
    forget(grid, page)

    // The grid's last cell continues to the dropped page, as the last cell always does - unless a
    // spacer follows it, whose reload brings the page with the dropped page's cursor on its last cell
    const lastEl = Array.from(grid.assetsElement.children).findLast(
        (el) => isCell(el) || isSpacer(el),
    )
    if (lastEl && isCell(lastEl)) {
        lastEl.dataset.appSearchAfter = page.after
        observeContinuationCell(lastEl)
    }

    console.debug("Dropped the page after %s", page.after)
    noteGridChange()
}

function detachSpacer(page) {
    spacerObserver?.unobserve(page.spacer)
    page.spacer.remove()
    page.spacer = null
}

function forget(grid, page) {
    grid.pages.splice(grid.pages.indexOf(page), 1)
}

/**
 * Whether any of the page's cells is within a viewport of the screen: the margin the spacers and
 * the lazy images are observed with, so a page folded here is not reloaded at once.
 */
function isNearViewport(page) {
    const bounds = cellBounds(page)
    const margin = window.innerHeight

    return bounds !== null && bounds.bottom > -margin && bounds.top < 2 * margin
}

/**
 * The top of the page's first cell and the bottom of its last, relative to the viewport, or null
 * when no cell of it is in the grid. Its headers are left out: a header above the pane sticks to
 * the pane's top, so its box says nothing of where the page is.
 */
function cellBounds(page) {
    const cells = connectedElements(page).filter(isCell)

    if (cells.length === 0) {
        return null
    }

    return {
        top: cells[0].getBoundingClientRect().top,
        bottom: cells.at(-1).getBoundingClientRect().bottom,
    }
}

/** Keeps the cells on screen where they are across `change`, however the rows above them re-wrap */
function keepingViewport(grid, change) {
    noteAnchor(grid)
    change()
    restoreAnchor(grid)
}

/** Notes the viewport offset of the first cell on screen, or of the first one below it when none is */
function noteAnchor(grid) {
    const paneTop = grid.scrollElement.getBoundingClientRect().top
    grid.anchor = null

    for (const el of grid.assetsElement.children) {
        if (!isCell(el)) {
            continue
        }

        const { top, bottom } = el.getBoundingClientRect()
        if (bottom > paneTop) {
            grid.anchor = { el, top }
            return
        }
    }
}

function restoreAnchor(grid) {
    const { anchor } = grid
    grid.anchor = null

    if (!anchor?.el.isConnected) {
        return
    }

    const shift = anchor.el.getBoundingClientRect().top - anchor.top
    if (shift !== 0) {
        grid.scrollElement.scrollTop += shift
    }
}

function getSpacerObserver() {
    spacerObserver ??= new IntersectionObserver(
        (entries) => {
            entries.forEach((entry) => {
                if (entry.isIntersecting) {
                    reloadSpacer(entry.target)
                }
            })
        },
        {
            // One viewport above and below, as the lazy images are loaded
            rootMargin: "100% 0px",
        },
    )

    return spacerObserver
}

function pageOfSpacer(grid, el) {
    return grid.pages.find((page) => page.spacer === el)
}

function loadedPages(grid) {
    return grid.pages.filter((page) => !page.spacer)
}

function connectedElements(page) {
    return page.nodes.filter(
        (node) => node instanceof Element && node.isConnected,
    )
}

function isCell(node) {
    return node instanceof Element && node.classList.contains("cell")
}

function isHeader(node) {
    return node.classList.contains("result-group")
}

/** Cells left the grid: the group headers recount */
function noteGridChange() {
    Alpine.store(Const.state.selectedAssets).noteGridChange()
}
