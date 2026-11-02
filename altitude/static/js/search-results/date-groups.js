import { Const } from "../constants.js"

/**
 * The group headers of a grouped grid (`.result-group`, a day, a Location or a Sequence, rendered by
 * `htmx/results_grid_grouped.scala.html` with the group's full match count in `.count`, across every
 * page loaded or not). This is the one place a header changes on the client: a cell leaving the grid
 * takes one off its group, and a group with no matches left loses its header. A header whose loaded
 * cells are all gone but whose count is still positive stays - the group still has matches on pages
 * not loaded yet.
 *
 * A Sequence's header has a Curate button while its group has enough frames, the number the server
 * renders on the button (`data-min-frames`), and loses it when the count falls under that or Curation
 * clears the Sequence.
 *
 * The header's checkbox over the group's loaded cells is its own component
 * (js/alpine/components/date-group-selectable.js).
 */

/**
 * Takes `cellEl` off the count of its group's header, removing the header when the count reaches zero.
 * Call it before the cell leaves the grid: the header is found by walking back over the cell's
 * siblings, which crosses page boundaries, since a continued group repeats no header.
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

    const curateEl = headerEl.querySelector(`[${Const.attributes.sequenceId}]`)
    if (curateEl && count < Number(curateEl.dataset.minFrames)) {
        curateEl.remove()
    }
}

/**
 * The headers of Sequences Curation cleared lose their Curate button; their kept frames stay under
 * them. A Sequence can have two headers on screen: one whose first frame left the grid is overtaken
 * by another Sequence on the next page, and opens again after it.
 */
export function removeCurateButtons(sequenceIds) {
    sequenceIds.forEach((sequenceId) => {
        document
            .querySelectorAll(
                `[${Const.attributes.sequenceId}="${CSS.escape(sequenceId)}"]`,
            )
            .forEach((buttonEl) => buttonEl.remove())
    })
}

/**
 * The count as a header reads it. The server renders the same text for the counts it sends
 * (`Util.humanReadableItemCount`); the two have to agree.
 */
function itemCountText(count) {
    return count === 1 ? "(1 item)" : `(${count} items)`
}

function dateGroupOf(cellEl) {
    let el = cellEl.previousElementSibling

    while (el && !el.classList.contains("result-group")) {
        el = el.previousElementSibling
    }

    return el
}
