import { notifyGridSelectionChanged } from "../alpine/components/selectable.js"

/**
 * The date headers of a grouped grid (`.date-group`, rendered by `htmx/results_grid_grouped.scala.html`
 * with the day's full match count in `.count`, across every page loaded or not). This is the one
 * place a header changes on the client: a cell leaving the grid takes one off its day, and a day
 * with no matches left loses its header. A header whose loaded cells are all gone but whose count is
 * still positive stays - the day still has matches on pages not loaded yet.
 *
 * The header's checkbox over the day's loaded cells is its own component
 * (js/alpine/components/date-group-selectable.js).
 */

/**
 * Takes `cellEl` off the count of its day's header, removing the header when the count reaches zero.
 * Call it before the cell leaves the grid: the header is found by walking back over the cell's
 * siblings, which crosses page boundaries, since a continued day repeats no header.
 */
export function decrementDateGroupOf(cellEl) {
    const headerEl = dateGroupOf(cellEl)
    if (!headerEl) {
        return
    }

    const countEl = headerEl.querySelector(".count")
    const count = Math.max(0, Number(countEl?.dataset.count) - 1)

    if (count === 0) {
        headerEl.remove()
        return
    }

    countEl.dataset.count = String(count)
    countEl.textContent = itemCountText(count)
}

/**
 * The count as a header reads it. The server renders the same text for the counts it sends
 * (`Util.humanReadableItemCount`); the two have to agree.
 */
function itemCountText(count) {
    return count === 1 ? "(1 item)" : `(${count} items)`
}

/**
 * A page appended by continuous scroll can land in a day whose header is already on screen, and
 * those cells arrive unselected: the header's checkbox has to recount. Bound once per displayed
 * grid, from the search results fragment hydrator.
 */
export function bindDateGroupSelectionSync({ assetsElement }) {
    if (assetsElement.dataset.appDateGroupSyncBound === "true") {
        return
    }

    assetsElement.dataset.appDateGroupSyncBound = "true"

    assetsElement.addEventListener("htmx:after:settle", () => {
        notifyGridSelectionChanged()
    })
}

function dateGroupOf(cellEl) {
    let el = cellEl.previousElementSibling

    while (el && !el.classList.contains("date-group")) {
        el = el.previousElementSibling
    }

    return el
}
