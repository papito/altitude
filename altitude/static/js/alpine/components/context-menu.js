/**
 * Alpine coordination for the folder, album and Location menus, the View settings popover and the
 * choice menus (Group, Sort, Dock side, the nav's Library menu).
 *
 * The component root is the small menu cell holding the trigger and its `popover="auto"` panel, so a
 * child folder's menu is never a DOM descendant of its ancestor's panel: the browser treats the menus
 * as siblings, and opening one closes any other.
 *
 * The browser owns visibility and outside-click dismissal. This component adds placement against the
 * trigger, initial focus and arrow keys, dismissal when focus leaves or on scroll or resize, and the
 * View settings dialog host. Escape is handled document-wide in `global.js`. At most one menu is open
 * at a time, so callers outside close "the open menu" with `closeOpenContextMenu()`.
 */

import { placeAnchoredPanel } from "../../common/anchored-panel.js"

const OPEN_MENU_SELECTOR = ".context-menu:popover-open"

// A menu's actions: its buttons, and a choice menu's links to another page
const ACTION_SELECTOR = ".actions :is(button, a[href])"

/** The ⋯ control that opens `panel`: the button in the same menu cell that targets it. */
export function getContextMenuTrigger(panel) {
    return panel.parentElement?.querySelector(`[popovertarget="${panel.id}"]`)
}

/**
 * Hides `panel` if open, optionally refocusing its trigger, and returns whether it closed. `reason`
 * is logged so a surprising dismissal can be traced.
 */
export function closeContextMenu(panel, { reason, returnFocus = false }) {
    if (!panel?.matches(":popover-open")) {
        return false
    }

    console.debug(`Closing context menu ${panel.id}: ${reason}`)

    panel.hidePopover()

    if (returnFocus) {
        getContextMenuTrigger(panel)?.focus()
    }

    return true
}

/** Closes the open context menu inside `within`, if any. Returns whether one was closed. */
export function closeOpenContextMenu({
    reason,
    returnFocus = false,
    within = document,
} = {}) {
    return closeContextMenu(within.querySelector(OPEN_MENU_SELECTOR), {
        reason,
        returnFocus,
    })
}

export function contextMenu() {
    // The request loading this panel's dialog, until its response is handled or the panel closes.
    // Closure state, not component data: nothing reacts to it.
    let dialogRequest = null

    return {
        // Removes the listeners that exist only while the menu is open
        detachOpenListeners: null,

        destroy() {
            dialogRequest = null
            this.detachOpenListeners?.()
        },

        isOpen() {
            return this.$refs.panel.matches(":popover-open")
        },

        close(reason, { returnFocus = false } = {}) {
            closeContextMenu(this.$refs.panel, { reason, returnFocus })
        },

        /**
         * `beforetoggle` fires synchronously before the panel shows, so placing it here means it is
         * never painted at the default centered position; the queued `toggle` is too late.
         */
        handleBeforeToggle(event) {
            if (event.newState === "open") {
                this.place()
            }
        },

        handleToggle(event) {
            if (event.newState === "open") {
                console.debug(`Opened context menu ${this.$refs.panel.id}`)
                this.attachOpenListeners()
                this.focusInitialAction()
            } else {
                this.detachOpenListeners?.()
                dialogRequest = null
                this.clearDialog()
            }
        },

        actions() {
            return Array.from(
                this.$refs.panel.querySelectorAll(ACTION_SELECTOR),
            ).filter((actionEl) => !actionEl.disabled)
        },

        /**
         * Focuses the current choice, else the first action. View settings has no actions and keeps
         * the focus its dialog hydrator gives.
         */
        focusInitialAction() {
            const actions = this.actions()
            const checked = actions.find(
                (actionEl) => actionEl.getAttribute("aria-checked") === "true",
            )

            ;(checked ?? actions[0])?.focus()
        },

        /**
         * Arrows move through the actions, wrapping; Home and End jump to the ends. A link natively
         * takes only Enter, so Space follows it too, letting either key pick any option.
         */
        handleKeydown(event) {
            const actions = this.actions()
            if (actions.length === 0) {
                return
            }

            const current = actions.indexOf(document.activeElement)
            if (event.key === " " && actions[current]?.matches("a")) {
                event.preventDefault()
                actions[current].click()
                return
            }

            let next
            if (event.key === "ArrowDown") {
                next = (current + 1) % actions.length
            } else if (event.key === "ArrowUp") {
                next = (current - 1 + actions.length) % actions.length
            } else if (event.key === "Home") {
                next = 0
            } else if (event.key === "End") {
                next = actions.length - 1
            } else {
                return
            }

            event.preventDefault()
            actions[next].focus()
        },

        /** Picking an option or action closes the menu; the click's own search, link or dialog still runs. */
        handleActionClick(event) {
            if (!event.target.closest(ACTION_SELECTOR)) {
                return
            }

            this.close("option picked", { returnFocus: true })
        },

        /**
         * Focus leaving the trigger and panel closes the menu, without pulling focus back. A click on
         * non-interactive dialog content focuses the panel itself, inside the root, so it stays open.
         */
        handleFocusOut(event) {
            if (!this.isOpen() || this.$root.contains(event.relatedTarget)) {
                return
            }

            this.close("focus left the menu")
        },

        /**
         * Records the View settings load as the only response allowed to fill the dialog host, and
         * empties the host first so the load never swaps over a leftover fragment.
         */
        handleBeforeRequest(event) {
            const ctx = event.detail.ctx

            if (ctx.target === this.$refs.dialog) {
                dialogRequest = ctx
                this.clearDialog()
            }
        },

        /**
         * Runs before the swap into the dialog host: a response for a panel that has closed since (and
         * possibly reopened) is dropped.
         */
        handleAfterRequest(event) {
            const ctx = event.detail.ctx

            if (ctx.target !== this.$refs.dialog) {
                return
            }

            if (!this.isOpen() || ctx !== dialogRequest) {
                console.debug(
                    `Dropping dialog response for closed or reopened context menu ${this.$refs.panel.id}`,
                )
                event.preventDefault()
                return
            }

            dialogRequest = null
        },

        /**
         * View settings has loaded: reveal it and place the panel for its new height. Alpine binds the
         * fragment in a microtask the swap queued, so the reveal waits in a later one: placing computes
         * the switches' style, and before their bindings ran every switch would slide from off.
         */
        handleAfterSettle(event) {
            if (!this.$refs.dialog.contains(event.target)) {
                return
            }

            queueMicrotask(() => {
                this.$refs.dialog.hidden = false
                this.place()
            })
        },

        /** Only View settings has a dialog host to empty. */
        clearDialog() {
            const dialog = this.$refs.dialog
            if (dialog) {
                dialog.replaceChildren()
                dialog.hidden = true
            }
        },

        place() {
            placeAnchoredPanel(this.$refs.panel, this.$refs.trigger, {
                center: this.$root.dataset.menuAlign === "center",
            })
        },

        /**
         * Scrolling outside the panel, a window resize or an explorer resize closes the open menu: the
         * trigger may have moved, and menus dismiss rather than follow it.
         */
        attachOpenListeners() {
            this.detachOpenListeners?.()

            const onScroll = (event) => {
                if (this.$refs.panel.contains(event.target)) {
                    return
                }

                this.close("scrolled")
            }
            const onResize = () => this.close("window resized")

            document.addEventListener("scroll", onScroll, {
                capture: true,
                passive: true,
            })
            window.addEventListener("resize", onResize)

            // The explorer's divider resizes it without resizing the window. A ResizeObserver reports
            // the current size once on observe; only a later notification is a change.
            let observer = null
            const explorer = document.getElementById("explorer")

            if (explorer && window.ResizeObserver) {
                let initialSizeReported = false

                observer = new ResizeObserver(() => {
                    if (!initialSizeReported) {
                        initialSizeReported = true
                        return
                    }

                    this.close("explorer resized")
                })
                observer.observe(explorer)
            }

            this.detachOpenListeners = () => {
                document.removeEventListener("scroll", onScroll, {
                    capture: true,
                })
                window.removeEventListener("resize", onResize)
                observer?.disconnect()
                this.detachOpenListeners = null
            }
        },
    }
}
