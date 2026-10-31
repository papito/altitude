/**
 * The snackbar: short plain-text messages for the outcome of an action. Producers call
 * `showSuccessSnackBar`, `showWarningSnackBar` or `showErrorSnackBar`; the messages live in the
 * Alpine `snackbar` store (registered with everything else `html_common` binds by
 * `registerHtmlCommonBindings` in `stores/html-common.js`), which
 * `views/includes/html_common.scala.html` renders as two stacks:
 *
 * - errors at top center, which stay until dismissed,
 * - successes and warnings ("notices") at bottom left, which dismiss themselves after 3 s and 6 s.
 *   Their timers pause while the pointer is over the stack or focus is inside it, and while the
 *   document is hidden, and resume with the time they had left.
 *
 * Each stack holds three messages: a fourth dismisses the oldest. A message whose type and text
 * equal one already in its stack is not added; that message counts it (`×n`) and restarts its
 * timer. A dismissed message leaves no trace, so the same message arriving later is shown again.
 * Each stack is a `popover="manual"` element, shown while it has a message (`syncStack`), so it
 * paints in the top layer, above the modal hosts and the map. A keyboard user reaches a message's
 * dismiss button by Tab; dismissing the focused one moves focus to its neighbour in the stack.
 *
 * Screen readers hear a message through visually hidden live regions
 * (`data-app-snackbar-announcer`), not through the stacks. Each place holds two sibling regions,
 * a polite `role="status"` one for notices and an assertive `role="alert"` one for errors.
 * `x-trap.inert` hides everything outside an active modal host from assistive technology, so
 * while the general modal or asset detail is active the message is written into that host's
 * regions, and otherwise into the top-level ones (the popover dialog has no trap).
 *
 * On a page without the store, a message is logged and dropped.
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

// A message's dismiss button, in a message that is not leaving (`.snackbar.leaving`)
const STAYING_DISMISS_SELECTOR = ".snackbar:not(.leaving) .snackbar-dismiss"

// Each region's value is its politeness: "polite" (notices) or "assertive" (errors)
const ANNOUNCER_ATTRIBUTE = "data-app-snackbar-announcer"

// How long an announced message stays in its live region; removals are not announced
const ANNOUNCED_MS = 5000

let lastId = 0

// The running or held timer of each notice, by message id: `remaining` ms are left as of
// `startedAt`, `handle` is null while the timer is held, and `onExpire` dismisses the message.
// Kept outside the reactive store.
const timers = new Map()

// Why the notices' timers are held: "pointer" and "focus", the notices stack's events. A hidden
// document holds them too, read when it changes.
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

        /**
         * Shows `message` of `type` (`success`, `warning` or `error`) in its stack, or counts it
         * on the equal message already there, then announces it.
         */
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
         * Marks the message as leaving, so it fades out, and removes it once the fade has run,
         * or at once under reduced motion. `button` is the message's dismiss button when it was
         * clicked: if it has focus, focus moves to the next message's button in the stack, or the
         * previous one's when it was the last, so a keyboard user stays in the stack.
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
                // A timer, not `transitionend`: a hidden tab may run no transition, and a message
                // must never stay behind as leaving
                setTimeout(remove, exitDuration())
            }
        },

        // Hold the notices' timers while `reason` lasts: the pointer over the stack, or focus in it
        pause(reason) {
            holds.add(reason)
            syncTimers()
        },

        resume(reason) {
            holds.delete(reason)
            syncTimers()
        },

        // The notices stack's `focusout`: focus moving between its own messages keeps the hold
        onStackFocusOut(event) {
            if (!event.currentTarget.contains(event.relatedTarget)) {
                this.resume("focus")
            }
        },

        // The bindings of a message in `html_common`, which keeps its expressions to single calls
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

        /**
         * A stack's `x-effect`: shows the stack's popover while it has a message, a leaving one
         * included, and hides it when the last one is gone.
         */
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

function isError(type) {
    return type === "error"
}

/**
 * Starts the message's full lifetime over, calling `onExpire` when it runs out. An error has no
 * timer. While the timers are held, the new one waits for them to resume.
 */
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

/**
 * Runs every timer when nothing holds them (the stack is neither hovered nor focused, and the
 * document is visible), and otherwise holds each with the time it has left.
 */
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
 * Releases the holds the notices stack can no longer report the end of, after a message has left
 * the DOM: with no notice left the stack is hidden, so no `pointerleave` says the pointer is gone,
 * and removing the focused element fires no `focusout` in Firefox, so focus outside the stack ends
 * the focus hold.
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

// Moves focus from a dismissed message's button to the next staying message's in its stack, or to
// the previous one's when there is no next; with no other message, focus stays where it is
function focusNeighbourOf(button) {
    const stack = button.closest(".snackbar-stack")
    const buttons = [...stack.querySelectorAll(STAYING_DISMISS_SELECTOR)]
    const index = buttons.indexOf(button)

    const neighbour = buttons[index + 1] ?? buttons[index - 1]
    neighbour?.focus()
}

// The fade of a dismissed message, `--snackbar-exit-duration` of core.css, written in ms
function exitDuration() {
    const value = getComputedStyle(document.documentElement).getPropertyValue(
        "--snackbar-exit-duration",
    )
    return parseFloat(value) || 0
}

/**
 * Adds the message to the live region screen readers can hear now (see the module comment), the
 * assertive one for an error and the polite one for a notice, and takes it out again once it has
 * been read. Messages are added rather than replacing one another, so a burst (an error and a
 * success in the same moment) is announced in full. A repeat carries its count, so its text
 * differs and is announced again.
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

// The region of `politeness` inside the active trapped host, or else the top-level one, outside
// every host
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

export function showSuccessSnackBar(message) {
    _showSnackbar({ message: message })
}

export function showWarningSnackBar(message) {
    _showSnackbar({ type: "warning", message: message })
}

export function showErrorSnackBar(message) {
    _showSnackbar({ type: "error", message: message })
}
