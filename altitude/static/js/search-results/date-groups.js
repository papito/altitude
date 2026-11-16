import { Const } from "../constants.js"

const WEAKER_MATCHES_CLASS = "weaker-matches"

/**
 * The group headers of a grouped grid (a day, a Location or a Sequence), each counting its group's
 * matches across every page, loaded or not. This is the one place a header changes on the client: a
 * cell leaving the grid takes one off its group, and a header whose count reaches zero goes, while one
 * whose loaded cells are gone but whose count is positive stays, for the pages not loaded yet. A
 * Sequence header's Curate button goes when the count falls under `data-min-frames` or Curation clears
 * the Sequence.
 */

/**
 * Call it before the cell leaves the grid: the header is found by walking back over the cell's
 * siblings, across page boundaries, since a continued group repeats no header. A Describe search's
 * weaker matches have a header of their own counting them all; on a grouped grid their groups follow
 * it, so a cell there leaves both counts.
 */
export function decrementDateGroupOf(cellEl) {
    const headerEl = headerBefore(cellEl, "result-group")
    if (!headerEl) {
        return
    }

    decrement(headerEl)

    if (!headerEl.classList.contains(WEAKER_MATCHES_CLASS)) {
        const weakerEl = headerBefore(headerEl, WEAKER_MATCHES_CLASS)
        if (weakerEl) {
            decrement(weakerEl)
        }
    }
}

function decrement(headerEl) {
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
 * A Sequence can have two headers on screen (overtaken by another Sequence on the next page and opened
 * again after it), so every button of it goes
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

/** Must agree with the server's `Util.humanReadableItemCount` */
function itemCountText(count) {
    return count === 1 ? "(1 item)" : `(${count} items)`
}

function headerBefore(el, className) {
    let previous = el.previousElementSibling

    while (previous && !previous.classList.contains(className)) {
        previous = previous.previousElementSibling
    }

    return previous
}
