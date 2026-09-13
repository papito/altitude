import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * METADATA VISIBILITY
 *
 * Which metadata fields show under every cell is the `gridMetadataFields` set in the context
 * (persisted in localStorage; changed by the view settings dialog). The grid reflects it with one
 * `show-<field>` class per field on `#assets`, and the CSS in `includes/search_results.scala.html`
 * does the rest: `#assets.show-fileName .metadata .fileName { display: block }`. So there is no
 * per-cell work, nothing to re-apply when a page is appended, and a change repaints every cell,
 * loaded or not yet loaded, through the stylesheet.
 *
 * One reactive effect per displayed grid keeps the classes in step with the set; the previous
 * grid's effect is released when the grid is replaced.
 */

const CLASS_PREFIX = "show-"

let currentEffect = null

export function bindMetadataVisibility({ assetsElement }) {
    if (currentEffect) {
        Alpine.release(currentEffect)
    }

    currentEffect = Alpine.effect(() => {
        const fields = Alpine.store(Const.context.gridMetadataFields)
        const shown = new Set([...fields].map((name) => CLASS_PREFIX + name))

        ;[...assetsElement.classList]
            .filter((name) => name.startsWith(CLASS_PREFIX) && !shown.has(name))
            .forEach((name) => assetsElement.classList.remove(name))

        shown.forEach((name) => assetsElement.classList.add(name))
    })
}
