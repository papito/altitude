import { Const } from "./constants.js"

/**
 * The Import page's status feed (the `hx-ws:connect` element of `pipeline.scala.html`, the vendored
 * `lib/hx-ws.js`) and what the Import history does with it.
 *
 * Every message of the feed is JSON (`ImportStatusWsActor`). An Import's status names the element
 * it replaces, the Import's status in the history, and `hx-ws` swaps it in with the Import's title
 * beside it. This module does the rest:
 *
 * - a status of an Import the page does not show (one just opened) is not swapped, and one of an
 *   Import that closed is; either way the history is read again, so the Import appears, or moves
 *   from "In progress" to "Earlier". So it is when the feed opens again after it was lost: the
 *   page catches up with what it missed;
 * - an Import whose files are shown has them read again with each of its statuses, so the list
 *   follows the Import at the feed's rate;
 * - a part of the history is read through one sequence of requests (`request`): the history
 *   itself, and each Import's files, are each read by the route their root names
 *   (`data-app-view-url`), one request at a time, and a control inside names the view it asks for
 *   instead (`data-app-view`: an outcome filter, "Show more", "Show earlier Imports"). What is
 *   asked for while a request is on its way is read once it has answered, so the last answer shown
 *   is never older than the last change, and a view the user picked is never undone by a read
 *   that began before the click;
 * - an Import's files are read when its `<details>` opens. The `<details>` are `hx-preserve`, so
 *   reading the history again leaves an expanded Import open, with its filter and its files; the
 *   one in view also stays where it is on the screen, whatever was added above it;
 * - a message that says the Library's Watch folders changed swaps nothing: it is announced on the
 *   page (`Const.events.watchFoldersChanged`), and `import/watch-folders.js` reads the cards
 *   again through the same sequence of requests (`readAgain`, which this module exports for it).
 *
 * The extension reconnects a socket the server closed with a code worth trying again on (1013:
 * the account changed, `ImportStatusWsActor.CloseAllFor`), but it would go on forever after a
 * handshake the server refuses (an account deactivated meanwhile, or one that no longer holds
 * Import): after `MAX_RECONNECTS` reconnects in a row that failed, it is stopped here, with no
 * patch to the library, by cancelling the next one (`htmx:ws:before:connection`, whose
 * `connection.attempt` counts the reconnects since the socket last opened), and the status area
 * says to reload. An open socket hides the notice again. A refused session may send the window to
 * the login first, through the nav's poll (`common/session.js`).
 */
export const MAX_RECONNECTS = 5

const HISTORY_ID = "importHistoryList"
const attributes = Const.attributes

// The status each message of the feed holds, for the listener that runs once it is swapped in
const statuses = new WeakMap()

// The parts of the page being read, by the ID of their root: `next` is the route asked for
// while a request is on its way, or null for the view shown, read again
const reading = new Map()

export function registerImportStatus() {
    const notice = document.getElementById("importStatusLost")
    const history = document.getElementById("importHistory")
    if (!notice || !history) {
        return
    }
    // The feed's first opening shows nothing new: the page was rendered with the history
    let hasOpened = false

    htmx.on("htmx:ws:before:connection", (event) => {
        const connection = event.detail.connection
        if (connection.attempt > MAX_RECONNECTS) {
            console.debug(
                `Import status: ${MAX_RECONNECTS} reconnects failed, giving up`,
            )
            connection.cancelled = true
            event.preventDefault()
            notice.hidden = false
        }
    })

    htmx.on("htmx:ws:after:connection", () => {
        notice.hidden = true
        if (hasOpened) {
            readAgain(document.getElementById(HISTORY_ID))
        }
        hasOpened = true
    })

    htmx.on("htmx:ws:before:message:incoming", (event) => {
        event.detail.waitUntil(readStatus(event.detail))
    })

    htmx.on("htmx:ws:after:message:incoming", (event) => {
        followStatus(statuses.get(event.detail.message))
    })

    history.addEventListener("click", (event) => {
        const control = event.target.closest(`[${attributes.view}]`)
        if (control) {
            request(
                control.closest(`[${attributes.viewUrl}]`),
                control.getAttribute(attributes.view),
            )
        }
    })

    // `toggle` does not bubble, so it is taken on its way down
    history.addEventListener(
        "toggle",
        (event) => {
            const files = event.target
            if (files.matches?.(`[${attributes.importFiles}]`) && files.open) {
                readAgain(files.querySelector(`[${attributes.viewUrl}]`))
            }
        },
        true,
    )

    keepExpandedImportInPlace()
}

/**
 * Reads a message before `hx-ws` handles it, which waits for this. One that is no JSON is none of
 * the feed's and is dropped, since `hx-ws` would swap its text in. A status of an Import the page
 * does not show is dropped too, its element being nowhere, and the history is read again.
 */
async function readStatus(detail) {
    let status
    try {
        status = await detail.message.json()
    } catch {
        detail.cancelled = true
        return
    }

    if (status?.watchFolders) {
        document.body.dispatchEvent(
            new CustomEvent(Const.events.watchFoldersChanged),
        )
    }
    // Anything but an Import's status ("connected", the Watch folders' change) swaps nothing
    if (!status?.target) {
        return
    }
    if (!document.querySelector(status.target)) {
        detail.cancelled = true
        readAgain(document.getElementById(HISTORY_ID))
        return
    }
    statuses.set(detail.message, status)
}

/** An Import's status was swapped in: its files follow if they are shown, and the history if it closed */
function followStatus(status) {
    if (!status) {
        return
    }

    const files = document
        .querySelector(status.target)
        ?.closest(`[${attributes.importId}]`)
        ?.querySelector(`[${attributes.importFiles}]`)
    if (files?.open) {
        readAgain(files.querySelector(`[${attributes.viewUrl}]`))
    }
    if (status.closed) {
        readAgain(document.getElementById(HISTORY_ID))
    }
}

/**
 * Reads the view a part of the page shows again, by the route its root names: a part of the
 * history, or the Watch folders' cards
 */
export function readAgain(root) {
    request(root, null)
}

/**
 * Shows the view at `url` in a part of the page, replacing its root (`url` null: the view it
 * shows, read again), one request at a time for the part. Asked while one is on its way, it is
 * read once that has answered, by the route the root names then; a view asked for by a click is
 * not replaced by a read of the old one asked for after it.
 */
async function request(root, url) {
    const id = root?.id
    if (!id) {
        return
    }

    const part = reading.get(id)
    if (part) {
        if (url !== null || part.next === undefined) {
            part.next = url
        }
        return
    }

    const own = { next: url }
    reading.set(id, own)
    try {
        while (own.next !== undefined) {
            const target = document.getElementById(id)
            const route = own.next ?? target?.getAttribute(attributes.viewUrl)
            own.next = undefined
            if (!target || !route) {
                break
            }
            // A failed request is reported by the rule for every htmx request (listeners/htmx-requests.js)
            await htmx.ajax("GET", route, { target, swap: "outerHTML" })
        }
    } finally {
        reading.delete(id)
    }
}

/**
 * Keeps the expanded Import in view where it is on the screen when the history is read again: the
 * Imports above it may have grown, appeared or moved to "Earlier". Its `<details>` is the same
 * element after the swap (`hx-preserve`), so the page scrolls by as much as it moved. htmx
 * announces a swap before it replaces the root and, in the same turn, the settle that follows as
 * soon as the new root is in place, so the settle after the history's swap is its own.
 */
function keepExpandedImportInPlace() {
    let kept = null

    document.addEventListener("htmx:before:swap", (event) => {
        if (event.detail.ctx?.target?.id !== HISTORY_ID) {
            return
        }
        const inView = [
            ...event.detail.ctx.target.querySelectorAll(
                `[${attributes.importFiles}][open]`,
            ),
        ].find((files) => {
            const box = files.getBoundingClientRect()
            return box.bottom > 0 && box.top < window.innerHeight
        })
        kept = inView
            ? { files: inView, top: inView.getBoundingClientRect().top }
            : null
    })

    document.addEventListener("htmx:before:settle", () => {
        if (!kept) {
            return
        }
        if (kept.files.isConnected) {
            window.scrollBy(
                0,
                kept.files.getBoundingClientRect().top - kept.top,
            )
        }
        kept = null
    })
}
