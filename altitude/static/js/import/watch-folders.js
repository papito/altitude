import { Const } from "../constants.js"
import { showSuccessSnackBar } from "../common/snackbar.js"
import { readAgain } from "../import-status.js"

const LIST_ID = "watchFolderList"

// How long a read of the cards waits for an open menu or popover dialog before it looks again
const RETRY_MS = 1000

// Whether a read of the cards is waiting for a menu or a popover dialog to close
let isWaiting = false

/**
 * The Watch folders of the Import page (`htmx/watch_folders`): their cards are read again, by the
 * route the list names (`data-app-view-url`), whenever `Const.events.watchFoldersChanged` is
 * announced. An Admin's action on a Watch folder declares it with what the snackbar says of it
 * (`detail.message`): the dialogs' saves and the Remove, and the cards' direct actions (Scan now,
 * Pause, Resume, Retry failed). The status feed announces it with no message when the Library's
 * Watch folders changed for any page (`import-status.js`): another Admin's action, a scan that
 * started or ended, a Watch folder found unavailable or available again, an Import of one opened or
 * closed.
 *
 * The read replaces the cards, and with them a ⋯ menu that is open and the trigger an open popover
 * dialog (Remove) is anchored to, so it waits while either is open, and looks again every second.
 */
export function registerWatchFolders() {
    if (!document.getElementById(LIST_ID)) {
        return
    }

    document.body.addEventListener(
        Const.events.watchFoldersChanged,
        (event) => {
            if (event.detail?.message) {
                showSuccessSnackBar(event.detail.message)
            }
            readCards()
        },
    )
}

function readCards() {
    const list = document.getElementById(LIST_ID)
    if (!list) {
        return
    }

    const isInUse =
        list.querySelector(".context-menu:popover-open") ||
        document.querySelector("#popoverDialog:popover-open")
    if (!isInUse) {
        isWaiting = false
        readAgain(list)
    } else if (!isWaiting) {
        isWaiting = true
        setTimeout(() => {
            isWaiting = false
            readCards()
        }, RETRY_MS)
    }
}
