import { Const } from "./constants.js"

/**
 * The Import page's status feed (`hx-ws`) and the Import history it keeps current. `hx-ws` swaps each
 * Import status into the element the status names; this module does the rest:
 *
 * - a status of an Import the page does not show, or of one that closed, reads the history again, so
 *   the Import appears or moves from in progress to earlier; so does the feed reopening, to catch up;
 * - an Import's shown files are read again with each of its statuses;
 * - each part of the history, and the Watch folder cards, is read one request at a time; what is asked
 *   meanwhile is read once it answers, so what is shown is never older than the last change and a
 *   view the user picked is never undone by a read begun before the click;
 * - expanded Imports are `hx-preserve`, and the one in view keeps its place on screen.
 *
 * `hx-ws` reconnects after a 1013 close but would retry forever after a refused handshake (an account
 * deactivated, or no longer holding Import), so after `MAX_RECONNECTS` failed reconnects in a row it
 * is stopped and the page says to reload.
 */
export const MAX_RECONNECTS = 5

const HISTORY_ID = "importHistoryList"
const attributes = Const.attributes

// Each message's parsed status, for the listener that runs once it is swapped in
const statuses = new WeakMap()

// Parts being read, by root ID. `next` is what to read once the request in flight answers: a route,
// null for the shown view again, undefined for nothing
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
 * Runs before `hx-ws` handles a message, which waits for it. Non-JSON is dropped, since `hx-ws` would
 * swap its text in; so is a status whose element is not on the page, and the history is read again.
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
    // Only an Import's status swaps anything
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

/** Reads a part of the page again, by the route its root names */
export function readAgain(root) {
    request(root, null)
}

/**
 * Swaps the view at `url` (null: the root's own route) in for a part's root, one request at a time per
 * part. A pending click's route is never overwritten by a later re-read.
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
            // A failure is reported like any htmx request's (listeners/htmx-requests.js)
            await htmx.ajax("GET", route, { target, swap: "outerHTML" })
        }
    } finally {
        reading.delete(id)
    }
}

/**
 * Keeps the expanded Import in view at its screen position when the history is swapped, since the
 * Imports above it may change. Its `<details>` survives the swap (`hx-preserve`), so the page scrolls
 * by as much as it moved. htmx fires the settle in the same turn as the swap, so the next settle after
 * the history's swap is its own.
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
