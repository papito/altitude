import { Alpine } from "./lib/alpine.esm.min.js"
import { contextMenu } from "./alpine/components/context-menu.js"
import { localTime } from "./alpine/components/local-time.js"
import { ensureChoiceMenus } from "./common/choice-menus.js"
import { findFragmentRoots } from "./fragments/helpers.js"
import { hydrateModalFragment } from "./fragments/modal.js"
import { registerAccountListeners } from "./listeners/account.js"
import { registerDialogListeners } from "./listeners/dialogs.js"
import { registerHtmxRequestListeners } from "./listeners/htmx-requests.js"
import { registerHtmlCommonBindings } from "./stores/html-common.js"

/**
 * Starts a page that carries the nav and `html_common` outside the main app: Import
 * (`upload-form-page.js`), Settings (`settings-page.js`) and Users (`users-page.js`). What such a
 * page shares is the nav's menus and the dialogs they open: the Library menu's New library, and the
 * account info dialog, whose Change password's outcome `listeners/account.js` confirms:
 *
 * - Alpine, with what `html_common` binds (`registerHtmlCommonBindings`: the modal hosts, the
 *   snackbar's stacks, `x-trap`), the `contextMenu` component of the menu's control and the
 *   `localTime` component of the account info dialog's times;
 * - the one rule for every htmx request (`listeners/htmx-requests.js`: a failure to the snackbar,
 *   a validation replacement swapped in, a declared success event dispatched) and the dialog
 *   wiring (`listeners/dialogs.js`: the open requests, the operations dialogs submit, the popover
 *   dialog host's dismissals);
 * - the hydration of what a swap inserts, which on these pages is a dialog (`modal` fragments)
 *   or a choice menu host: the nav's, built here with the page. A nav reload carries the host
 *   empty and keeps the built one, `hx-preserve` (`includes/library_menu`); the Import page's
 *   polling reload skips a tick while the menu or the dialog is open, since without
 *   `Element.moveBefore` htmx's move would close it.
 *
 * Returns the minimal app handed to those listeners, for a page's own listeners to dispatch with.
 */
export function startNavPage() {
    registerHtmlCommonBindings()
    // Referenced as `x-data="contextMenu"` by the control `common/context-menu-markup.js` builds
    Alpine.data("contextMenu", contextMenu)
    // Referenced as `x-data="localTime"` by the account info dialog the nav's account trigger opens
    Alpine.data("localTime", localTime)

    const app = {
        dispatch(eventName, detail = {}) {
            document.body.dispatchEvent(new CustomEvent(eventName, { detail }))
        },
        hydrateFragments(root) {
            findFragmentRoots(root, "modal").forEach((fragmentEl) => {
                hydrateModalFragment({ fragmentEl })
            })
            ensureChoiceMenus(root)
        },
    }

    registerHtmxRequestListeners(app)
    registerDialogListeners(app)
    registerAccountListeners()

    // Built before Alpine starts, whose first pass then initializes the menu's component
    ensureChoiceMenus(document)
    Alpine.start()

    return app
}
