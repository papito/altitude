/**
 * Alpine coordination for a folder's or an album's native popover menu.
 *
 * The component root is the `.menu-ctrl` cell built by `common/context-menu.js`: it holds the
 * meatball trigger (`x-ref="trigger"`, opening the panel with `popovertarget`) and the
 * `popover="auto"` panel (`x-ref="panel"`). Keeping the root that small means a child folder's
 * menu is never a DOM descendant of its ancestor's panel, so the browser treats the menus as
 * siblings: opening one closes any other, exactly as required.
 *
 * The panel has two states. It shows its actions (`x-ref="actions"`) until one of them loads its
 * inline dialog into the dialog host (`x-ref="dialog"`); the dialog then shows in place of the
 * actions until the panel closes, which discards the dialog and shows the actions again. The
 * actions stay in the DOM throughout, so their htmx wiring is never lost.
 *
 * The browser owns visibility. It toggles the panel from the trigger, dismisses it on a click
 * anywhere outside, and reports every change through `beforetoggle`/`toggle`. This component
 * adds only what native popovers lack: placement against the trigger (again when a dialog changes
 * the panel's height), the switch between actions and dialog, dismissal when keyboard focus
 * leaves, dismissal on scroll or resize, and cleanup. Escape is handled once for the whole
 * document in `global.js`.
 */
import { closeContextMenu } from "../../common/context-menu.js"

// Minimum distance kept between the panel and the viewport edges when it has to be shifted,
// flipped, or shrunk
const VIEWPORT_GAP = 8

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
            } else {
                this.detachOpenListeners?.()
                dialogRequest = null
                this.showActions()
            }
        },

        /**
         * Tab or Shift+Tab leaving the trigger and its panel closes the menu. Focus is left where
         * it went; a menu must never pull it back from the control the user moved to. A click on
         * non-interactive dialog content (heading, label, padding) moves focus to the panel
         * itself, which is inside the root, so it keeps the menu open.
         *
         * A validation response replaces the dialog's form while its field has focus: htmx marks
         * the form `htmx-swapping`, removes it (the browser reports the field losing focus with
         * nowhere to go), and focuses the field's copy in the same task. That is not focus
         * leaving, so it keeps the menu open too.
         */
        handleFocusOut(event) {
            if (
                !this.isOpen() ||
                this.$root.contains(event.relatedTarget) ||
                event.target.closest(".htmx-swapping")
            ) {
                return
            }

            this.close("focus left the menu")
        },

        /**
         * A request targeting the dialog host is an action loading its dialog: remember it as
         * the one whose response may show. Requests submitted from the dialog itself target the
         * dialog, not the host, and are the dialog operation tracker's concern.
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
         * (and possibly reopened) is dropped, so a late dialog never appears over the actions. A
         * failed load is reported by the folder request listener.
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
         * A dialog has been swapped into the host (loaded, or replaced by its validation copy):
         * show it instead of the actions and place the panel again for its new height. The
         * fragment hydrator that runs next, from the body-level settle listener, gives the dialog
         * focus.
         */
        handleAfterSettle(event) {
            if (!this.$refs.dialog.contains(event.target)) {
                return
            }

            this.$refs.actions.hidden = true
            this.$refs.dialog.hidden = false
            this.place()
        },

        /** Back to the actions state, discarding any dialog the host holds. */
        showActions() {
            this.$refs.dialog.replaceChildren()
            this.$refs.dialog.hidden = true
            this.$refs.actions.hidden = false
        },

        /**
         * Places the panel against its trigger, before the browser shows it or after its content
         * changed while open.
         *
         * Before showing, the panel is hidden, so it is given its open-state display just long
         * enough to measure: its size depends only on its own styles, which are the same in and
         * out of the top layer. Once shown, the panel is a fixed-position element in the top
         * layer, whose containing block is the viewport whatever its ancestors do, so viewport
         * coordinates from `getBoundingClientRect()` apply directly; the explorer's scrolling,
         * clipping, and drag transforms cannot affect it. Any menu closes when the page scrolls
         * or resizes, so the position is never tracked.
         *
         * Vertical placement: below the trigger by default; above it when there is no room below;
         * when it fits on neither side, on the roomier side with its height capped so the content
         * scrolls inside the panel. Horizontal placement: aligned with the trigger's left edge,
         * shrunk to the viewport width if needed, then shifted left as far as it takes to stay
         * inside the viewport, so every control stays reachable in narrow windows.
         */
        place() {
            const panel = this.$refs.panel
            const trigger = this.$refs.trigger.getBoundingClientRect()
            const viewportWidth = document.documentElement.clientWidth
            const viewportHeight = document.documentElement.clientHeight

            // Measure the panel free of the constraints an earlier placement may have applied
            panel.style.display = "grid"
            panel.style.maxHeight = ""
            panel.style.maxWidth = `${viewportWidth - 2 * VIEWPORT_GAP}px`

            const spaceBelow = Math.max(
                0,
                viewportHeight - trigger.bottom - VIEWPORT_GAP,
            )
            const spaceAbove = Math.max(0, trigger.top - VIEWPORT_GAP)
            const { height } = panel.getBoundingClientRect()

            let top
            if (height <= spaceBelow) {
                top = trigger.bottom
            } else if (height <= spaceAbove) {
                top = trigger.top - height
            } else if (spaceBelow >= spaceAbove) {
                panel.style.maxHeight = `${spaceBelow}px`
                top = trigger.bottom
            } else {
                panel.style.maxHeight = `${spaceAbove}px`
                top = VIEWPORT_GAP
            }

            // Re-measure the width: an internal scrollbar added by the height cap widens the panel
            const { width } = panel.getBoundingClientRect()
            const left = Math.max(
                VIEWPORT_GAP,
                Math.min(trigger.left, viewportWidth - VIEWPORT_GAP - width),
            )

            panel.style.top = `${top}px`
            panel.style.left = `${left}px`
            panel.style.display = ""
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
