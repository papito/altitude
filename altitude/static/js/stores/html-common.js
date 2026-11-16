import { Alpine } from "../lib/alpine.esm.min.js"
import focusPlugin from "../lib/alpine-focus.esm.js"
import { Const } from "../constants.js"
import { createModalStore } from "../common/modal.js"
import { createSnackbarStore } from "../common/snackbar.js"
import { tooltipMagic } from "../common/tooltip.js"

/**
 * Registers everything `html_common` binds, so every page that includes it starts Alpine with the
 * same set. The focus plugin (`x-trap` on the modal hosts) is registered here, so a page cannot
 * start Alpine with the stores and without `x-trap`. Each page calls this exactly once, before
 * `Alpine.start()`, which a plugin must precede: the main page and the style guide through
 * `initializeFrontendStores()`, the nav pages through `startNavPage()`.
 */
export function registerHtmlCommonBindings() {
    Alpine.plugin(focusPlugin)

    Alpine.store(Const.state.modal, createModalStore())

    Alpine.store(Const.state.imageDetailLoading, {
        value: false,
    })

    Alpine.store(Const.state.snackbar, createSnackbarStore())

    // `$tooltip('Message')`: a hint above the element, shown on a click
    Alpine.magic("tooltip", tooltipMagic)
}
