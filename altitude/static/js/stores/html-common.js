import { Alpine } from "../lib/alpine.esm.min.js"
import focusPlugin from "../lib/alpine-focus.esm.js"
import { Const } from "../constants.js"
import { createModalStore } from "../common/modal.js"
import { createSnackbarStore } from "../common/snackbar.js"

/**
 * Registers everything `views/includes/html_common.scala.html` binds, so every page that includes
 * it starts Alpine with the same set: the `modal` store (the modal hosts and the popover title),
 * the `imageDetailLoading` store (asset detail's spinner), the `snackbar` store (the two stacks)
 * and the focus plugin (`x-trap` on the modal hosts, the only Alpine plugin and its only user).
 *
 * The plugin is registered here rather than by each page's entry module, so a page cannot start
 * Alpine with the stores and without `x-trap`. Each page calls this exactly once, before
 * `Alpine.start()`, which a plugin must precede: the main page and the style guide through
 * `initializeFrontendStores()`, which `FrontendApp.start()` runs once, and Settings from
 * `settings-page.js`.
 */
export function registerHtmlCommonBindings() {
    Alpine.plugin(focusPlugin)

    Alpine.store(Const.state.modal, createModalStore())

    Alpine.store(Const.state.imageDetailLoading, {
        value: false,
    })

    // The snackbar's two stacks of messages, errors and notices (common/snackbar.js)
    Alpine.store(Const.state.snackbar, createSnackbarStore())
}
