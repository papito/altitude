/**
 * Alpine coordination for folder, album, and Location menus, the View settings popover and the
 * choice menus (Group, Sort).
 *
 * The component root is the `.menu-ctrl` cell (the View settings `.dialog-trigger-ctrl`, a choice menu's
 * `.choice-menu-ctrl`) built by `common/context-menu-markup.js`: it holds the trigger (`x-ref="trigger"`, opening the
 * panel with `popovertarget`) and the `popover="auto"` panel (`x-ref="panel"`). Keeping the root that small means a
 * child folder's menu is never a DOM descendant of its ancestor's panel, so the browser treats the menus as
 * siblings: opening one closes any other, exactly as required.
 *
 * Entity menus hold actions that open popover dialogs (`common/modal.js`). Only View settings has a
 * dialog host (`x-ref="dialog"`), cleared when its panel closes so a later open loads fresh
 * settings. A choice menu (`data-menu-kind="choice"`) holds options that run a search; picking one
 * closes the menu.
 *
 * The browser owns visibility. It toggles the panel from the trigger, dismisses it on a click
 * anywhere outside, and reports every change through `beforetoggle`/`toggle`. This component
 * adds only what native popovers lack: placement against the trigger (again when a dialog changes
 * the panel's height), focus on the checked or first action when the panel opens and the arrow
 * keys between actions, dismissal when keyboard focus
 * leaves, dismissal on scroll or resize, and cleanup. Escape is handled once for the whole
 * document in `global.js`.
 *
 * Callers outside the component that must close a menu (that Escape handler, the folder model when
 * an ancestor collapses, the modal owner) go through
 * `closeContextMenu()` / `closeOpenContextMenu()` below. Because at most one auto popover of this
 * kind is open at a time, "the open menu" is a single panel. The menu markup itself is built by
 * `common/context-menu-markup.js`.
 */

import { placeAnchoredPanel } from "../../common/anchored-panel.js"

const OPEN_MENU_SELECTOR = ".context-menu:popover-open"

/** The ⋯ control that opens `panel`: the button in the same menu cell that targets it. */
export function getContextMenuTrigger(panel) {
    return panel.parentElement?.querySelector(`[popovertarget="${panel.id}"]`)
}

/**
 * Hides `panel` if it is open, optionally moving focus to the panel's trigger. Returns
 * whether a menu was closed. `reason` is logged so a surprising dismissal can be traced.
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

/**
 * Closes the open context menu, if any, looking only inside `within`. Returns whether one was
 * closed.
 */
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
    // The request loading a dialog into this panel, from `htmx:before:request` until its response
    // is handled or the panel closes. Closure state rather than component data: an htmx request
    // context has no reactive consumers.
    let dialogRequest = null

    return {
        // Removes the listeners and observer that exist only while the menu is open
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
         * `beforetoggle` fires synchronously just before the browser shows the panel, so placing
         * it here means it is never displayed, painted, or focusable at the browser's default
         * centered position. The `toggle` event that follows is a queued task, too late for that.
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

        /** The panel's actions that can take focus: its buttons, less the disabled ones */
        actions() {
            return Array.from(
                this.$refs.panel.querySelectorAll(
                    ".actions button:not(:disabled)",
                ),
            )
        },

        /**
         * A menu opens on its current choice, else on its first action, as a menu button does;
         * View settings has no actions and keeps focus where the dialog hydrator puts it.
         */
        focusInitialAction() {
            const actions = this.actions()
            const checked = actions.find(
                (actionEl) => actionEl.getAttribute("aria-checked") === "true",
            )

            ;(checked ?? actions[0])?.focus()
        },

        /**
         * Down and Up move focus through the actions, wrapping; Home and End jump to the ends.
         * Enter and Space are the focused button's own. Other keys pass, Escape to `global.js`.
         */
        handleKeydown(event) {
            const actions = this.actions()
            if (actions.length === 0) {
                return
            }

            const current = actions.indexOf(document.activeElement)
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

        /**
         * Picking a choice menu's option closes the menu; the option's own search trigger runs
         * on the same click, and the results it brings back carry a new control showing the choice.
         */
        handleActionClick(event) {
            if (!event.target.closest(".actions button")) {
                return
            }

            this.close("option picked", { returnFocus: true })
        },

        /**
         * Tab or Shift+Tab leaving the trigger and its panel closes the menu. Focus is left where
         * it went; a menu must never pull it back from the control the user moved to. A click on
         * non-interactive dialog content (heading, label, padding) moves focus to the panel
         * itself, which is inside the root, so it keeps the menu open.
         */
        handleFocusOut(event) {
            if (!this.isOpen() || this.$root.contains(event.relatedTarget)) {
                return
            }

            this.close("focus left the menu")
        },

        /**
         * Remember the View settings load as the only response allowed to fill its dialog host.
         */
        handleBeforeRequest(event) {
            const ctx = event.detail.ctx

            if (ctx.target === this.$refs.dialog) {
                dialogRequest = ctx
            }
        },

        /**
         * Runs before htmx swaps a response into the dialog host. Only the response to the
         * request the open panel is waiting for may show; one for a panel that has closed since
         * (and possibly reopened) is dropped. Failed loads use the shared HTMX request listener.
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
         * View settings has loaded: reveal its content and place the panel for its new height. The
         * fragment hydrator that runs next, from the body-level settle listener, gives the dialog
         * focus.
         */
        handleAfterSettle(event) {
            if (!this.$refs.dialog.contains(event.target)) {
                return
            }

            this.$refs.dialog.hidden = false
            this.place()
        },

        /** Only View settings has inline content to discard; entity menus keep their actions. */
        clearDialog() {
            const dialog = this.$refs.dialog
            if (dialog) {
                dialog.replaceChildren()
                dialog.hidden = true
            }
        },

        /**
         * The native popover is fixed in the top layer, so the shared viewport placement applies
         * before it opens too. Menus close on scroll/resize instead of following their trigger.
         */
        place() {
            placeAnchoredPanel(this.$refs.panel, this.$refs.trigger, {
                center: this.$root.dataset.menuAlign === "center",
            })
        },

        /**
         * While the menu is open, any scrolling outside the panel, a window resize, or a change in
         * the explorer's size closes it: the trigger has moved (or may have), and the agreed
         * behavior is to dismiss rather than follow it. Scrolling inside the panel (its own
         * scrollbar when the height is capped) keeps it open.
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

            // The Split.js divider resizes the explorer without resizing the window. A
            // ResizeObserver reports the current size once as soon as it starts observing; only a
            // later notification means the size changed.
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
