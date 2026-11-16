import { Const } from "../constants.js"
import { showSuccessSnackBar } from "../common/snackbar.js"
import { readAgain } from "../import-status.js"

const LIST_ID = "watchFolderList"

// How long a read of the cards waits for an open menu or popover dialog before it looks again
const RETRY_MS = 1000

let isWaiting = false

/**
 * Reads the Import page's Watch folder cards again on each change, whether from an action on this page
 * (with a snackbar message) or from the status feed (another page, a scan, a change of availability).
 * The read replaces the cards, and with them an open ⋯ menu and the anchor of an open popover dialog,
 * so it waits while either is open.
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
