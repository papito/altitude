import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"

/**
 * METADATA VISIBILITY
 *
 * The metadata fields shown under the cells (the `gridMetadataFields` context set) are one
 * `show-<field>` class per field on `#assets`, and the stylesheet does the rest, so there is no
 * per-cell work and nothing to re-apply when a page is appended. One reactive effect per displayed
 * grid keeps the classes in step; the previous grid's is released.
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
