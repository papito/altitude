/**
 * The snackbar: short plain-text outcome messages in two stacks. Errors (top center) stay until
 * dismissed; successes and warnings (bottom left) dismiss themselves after 3 s and 6 s, with timers
 * that pause while the pointer or focus is in the stack or the document is hidden.
 *
 * Each stack holds three messages; a fourth dismisses the oldest. A message equal to one in its
 * stack is not added: that one counts it (`×n`) and restarts its timer. Stacks are
 * `popover="manual"` so they paint in the top layer, above the modal hosts and the map.
 *
 * Screen readers hear messages through hidden live regions, not the stacks. `x-trap.inert` hides
 * everything outside an active trapped host, so while one is active the message goes into that
 * host's regions. On a page without the store, a message is logged and dropped.
 */
import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { getActiveTrapContainer, isInModalHost } from "./modal.js"

// How long a message stays, in ms; an error stays until dismissed
const lifetimes = {
    success: 3000,
    warning: 6000,
    error: null,
}

// The icon of each type, so a message's type is not given by its color alone
const icons = {
    success: "fa-check-circle",
    warning: "fa-exclamation-triangle",
    error: "fa-times-circle",
}

const MAX_PER_STACK = 3

const NOTICES_STACK_ID = "snackbarNotices"

const STAYING_DISMISS_SELECTOR = ".snackbar:not(.leaving) .snackbar-dismiss"

// Its value is the politeness: "polite" (notices) or "assertive" (errors)
const ANNOUNCER_ATTRIBUTE = "data-app-snackbar-announcer"

// How long an announced message stays in its live region; removals are not announced
const ANNOUNCED_MS = 5000

let lastId = 0

// Each notice's timer by message id, outside the reactive store: `remaining` ms as of `startedAt`,
// `handle` null while held
const timers = new Map()

// Why the notices' timers are held: "pointer" or "focus" (a hidden document holds them too)
const holds = new Set()

document.addEventListener("visibilitychange", syncTimers)

export function createSnackbarStore() {
    return {
        // Every message, in arrival order: { id, type, message, count, leaving }
        items: [],

        get errors() {
            return this.items.filter((item) => isError(item.type))
        },

        get notices() {
            return this.items.filter((item) => !isError(item.type))
        },

        add(type, message) {
            console.debug(`Snackbar ${type}: ${message}`)

            const repeat = this.items.find(
                (item) =>
                    item.type === type &&
                    item.message === message &&
                    !item.leaving,
            )
            if (repeat) {
                repeat.count++
                startTimer(repeat, () => this.dismiss(repeat.id))
                announce(type, message, repeat.count)
                return
            }

            const item = {
                id: ++lastId,
                type,
                message,
                count: 1,
                leaving: false,
            }
            this.items.push(item)
            startTimer(item, () => this.dismiss(item.id))

            const staying = this.items.filter(
                (other) =>
                    isError(other.type) === isError(type) && !other.leaving,
            )
            if (staying.length > MAX_PER_STACK) {
                this.dismiss(staying[0].id)
            }

            announce(type, message, 1)
        },

        /**
         * Fades the message out, then removes it. `button` is the clicked dismiss button: if it has
         * focus, focus moves to a neighbour so a keyboard user stays in the stack.
         */
        dismiss(id, button = null) {
            const item = this.items.find((other) => other.id === id)
            if (!item || item.leaving) {
                return
            }

            if (button && button === document.activeElement) {
                focusNeighbourOf(button)
            }

            item.leaving = true
            clearTimer(id)

            const remove = () => {
                this.items = this.items.filter((other) => other.id !== id)
                // Once Alpine has taken the message out of the DOM
                Alpine.nextTick(() => releaseStaleHolds(this.notices.length))
            }

            if (matchMedia("(prefers-reduced-motion: reduce)").matches) {
                remove()
            } else {
                // Not `transitionend`: a hidden tab may run no transition, and a message must never
                // stay behind as leaving
                setTimeout(remove, exitDuration())
            }
        },

        pause(reason) {
            holds.add(reason)
            syncTimers()
        },

        resume(reason) {
            holds.delete(reason)
            syncTimers()
        },

        // Focus moving between the stack's own messages keeps the hold
        onStackFocusOut(event) {
            if (!event.currentTarget.contains(event.relatedTarget)) {
                this.resume("focus")
            }
        },

        // Template bindings, so its expressions stay single calls
        classesOf(item) {
            return { [item.type]: true, leaving: item.leaving }
        },

        iconOf(item) {
            return icons[item.type]
        },

        isRepeated(item) {
            return item.count > 1
        },

        countOf(item) {
            return `×${item.count}`
        },

        // A stack's `x-effect`: a leaving message keeps the stack shown
        syncStack(el, messages) {
            const isOpen = el.matches(":popover-open")

            if (messages.length && !isOpen) {
                el.showPopover()
            } else if (!messages.length && isOpen) {
                el.hidePopover()
            }
        },
    }
}

/**
 * Shows the open stacks again, moving them to the top of the top layer, above an element that went
 * fullscreen after them. A hidden popover leaves the top layer only at the next rendering update,
 * and one shown again before then keeps its place, so the show waits a frame past the hide.
 */
export function raiseSnackbarStacks() {
    const stackEls = [
        ...document.querySelectorAll(".snackbar-stack:popover-open"),
    ]
    stackEls.forEach((stackEl) => stackEl.hidePopover())

    requestAnimationFrame(() =>
        requestAnimationFrame(() =>
            stackEls.forEach((stackEl) => {
                if (stackEl.isConnected && !stackEl.matches(":popover-open")) {
                    stackEl.showPopover()
                }
            }),
        ),
    )
}

function isError(type) {
    return type === "error"
}

/** Starts the full lifetime over; while timers are held, the new one waits for them to resume */
function startTimer(item, onExpire) {
    const lifetime = lifetimes[item.type]
    if (!lifetime) {
        return
    }

    clearTimer(item.id)
    timers.set(item.id, {
        handle: null,
        remaining: lifetime,
        startedAt: 0,
        onExpire,
    })
    syncTimers()
}

function clearTimer(id) {
    const timer = timers.get(id)
    if (timer) {
        clearTimeout(timer.handle)
        timers.delete(id)
    }
}

/** Runs every timer when nothing holds them, and otherwise holds each with the time it has left */
function syncTimers() {
    const run = holds.size === 0 && !document.hidden
    const now = performance.now()

    timers.forEach((timer, id) => {
        if (run && timer.handle === null) {
            timer.startedAt = now
            timer.handle = setTimeout(() => {
                timers.delete(id)
                timer.onExpire()
            }, timer.remaining)
        } else if (!run && timer.handle !== null) {
            clearTimeout(timer.handle)
            timer.handle = null
            timer.remaining = Math.max(
                0,
                timer.remaining - (now - timer.startedAt),
            )
        }
    })
}

/**
 * Releases holds whose end the stack can no longer report: a hidden stack fires no `pointerleave`,
 * and removing the focused element fires no `focusout` in Firefox.
 */
function releaseStaleHolds(noticeCount) {
    if (!noticeCount) {
        holds.delete("pointer")
    }

    const stack = document.getElementById(NOTICES_STACK_ID)
    if (!stack?.contains(document.activeElement)) {
        holds.delete("focus")
    }

    syncTimers()
}

// The next staying message's button, else the previous one's; with neither, focus stays put
function focusNeighbourOf(button) {
    const stack = button.closest(".snackbar-stack")
    const buttons = [...stack.querySelectorAll(STAYING_DISMISS_SELECTOR)]
    const index = buttons.indexOf(button)

    const neighbour = buttons[index + 1] ?? buttons[index - 1]
    neighbour?.focus()
}

// `--snackbar-exit-duration`, which is written in ms
function exitDuration() {
    const value = getComputedStyle(document.documentElement).getPropertyValue(
        "--snackbar-exit-duration",
    )
    return parseFloat(value) || 0
}

/**
 * Messages are appended rather than replacing one another, so a burst is announced in full. A
 * repeat carries its count, so its text differs and is announced again.
 */
function announce(type, message, count) {
    const region = findAnnouncer(isError(type) ? "assertive" : "polite")
    if (!region) {
        return
    }

    const paragraph = document.createElement("p")
    paragraph.textContent = count > 1 ? `${message} (×${count})` : message
    region.append(paragraph)
    setTimeout(() => paragraph.remove(), ANNOUNCED_MS)
}

// Inside the active trapped host, else the top-level one
function findAnnouncer(politeness) {
    const selector = `[${ANNOUNCER_ATTRIBUTE}="${politeness}"]`
    const trapContainer = getActiveTrapContainer()

    if (trapContainer) {
        return trapContainer.querySelector(selector)
    }

    const regions = [...document.querySelectorAll(selector)]
    return regions.find((el) => !isInModalHost(el)) ?? null
}

function _showSnackbar({ type = "success", message }) {
    const store = Alpine.store(Const.state.snackbar)
    if (!store) {
        console.warn(
            `No snackbar store on this page; dropped ${type}: ${message}`,
        )
        return
    }

    store.add(type, message)
}

/** For a message that is no snackbar, such as a tooltip's hint */
export function announcePolitely(message) {
    announce("success", message, 1)
}

export function showSuccessSnackBar(message) {
    _showSnackbar({ message: message })
}

export function showWarningSnackBar(message) {
    _showSnackbar({ type: "warning", message: message })
}

export function showErrorSnackBar(message) {
    _showSnackbar({ type: "error", message: message })
}
