/**
 * Alpine coordination for a folder's native popover menu.
 *
 * The component root is the `.menu-ctrl` cell built by `common/folder-tree.js`: it holds the
 * meatball trigger (`x-ref="trigger"`, opening the panel with `popovertarget`) and the
 * `popover="auto"` panel of action buttons (`x-ref="panel"`). Keeping the root that small means a
 * child folder's menu is never a DOM descendant of its ancestor's panel, so the browser treats the
 * menus as siblings: opening one closes any other, exactly as required.
 *
 * The browser owns visibility. It toggles the panel from the trigger, dismisses it on a click
 * anywhere outside, and reports every change through `beforetoggle`/`toggle`. This component
 * adds only what native popovers lack: placement against the trigger, dismissal when keyboard
 * focus leaves, dismissal when an action is chosen, dismissal on scroll or resize, and cleanup.
 * Escape is handled once for the whole document in `global.js`.
 */
import { Const } from "../../constants.js"
import { closeFolderMenu } from "../../common/folder-menu.js"

// Minimum distance kept between the panel and the viewport edges when it has to be shifted,
// flipped, or shrunk
const VIEWPORT_GAP = 8

export function folderMenu() {
    return {
        // Removes the listeners and observer that exist only while the menu is open
        detachOpenListeners: null,

        destroy() {
            this.detachOpenListeners?.()
        },

        get folderId() {
            return this.$refs.panel.getAttribute(Const.attributes.folderId)
        },

        isOpen() {
            return this.$refs.panel.matches(":popover-open")
        },

        close(reason, { returnFocus = false } = {}) {
            closeFolderMenu(this.$refs.panel, { reason, returnFocus })
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
                console.debug(`Opened folder menu for ${this.folderId}`)
                this.attachOpenListeners()
            } else {
                this.detachOpenListeners?.()
            }
        },

        /**
         * Tab or Shift+Tab leaving the trigger and its panel closes the menu. Focus is left where
         * it went; a menu must never pull it back from the control the user moved to.
         */
        handleFocusOut(event) {
            if (!this.isOpen() || this.$root.contains(event.relatedTarget)) {
                return
            }

            this.close("focus left the menu")
        },

        /**
         * Choosing an action closes the menu so it cannot remain above the dialog it opens. This
         * runs as the click bubbles up from the action button, after htmx has already handled it
         * there, and hiding the panel leaves the button in the DOM, so the dialog request is
         * unaffected. The modal owner restores focus when the dialog closes; until then focus
         * rests on the trigger rather than on a hidden button. Clicks on the panel's own padding
         * are not actions and keep the menu open.
         */
        handleActionClick(event) {
            if (!event.target.closest("button")) {
                return
            }

            this.close("action chosen", { returnFocus: true })
        },

        /**
         * Places the panel against its trigger, before the browser shows it.
         *
         * The panel is hidden at that point, so it is given its open-state display just long
         * enough to measure: its size depends only on its own styles, which are the same in and
         * out of the top layer. Once shown, the panel is a fixed-position element in the top
         * layer, whose containing block is the viewport whatever its ancestors do, so viewport
         * coordinates from `getBoundingClientRect()` apply directly; the explorer's scrolling,
         * clipping, and drag transforms cannot affect it. Any menu closes when the page scrolls
         * or resizes, so the position is computed once per open and never tracked.
         *
         * Vertical placement: below the trigger by default; above it when there is no room below;
         * when it fits on neither side, on the roomier side with its height capped so the actions
         * scroll inside the panel. Horizontal placement: aligned with the trigger's left edge,
         * shrunk to the viewport width if needed, then shifted left as far as it takes to stay
         * inside the viewport, so every action stays reachable in narrow windows.
         */
        place() {
            const panel = this.$refs.panel
            const trigger = this.$refs.trigger.getBoundingClientRect()
            const viewportWidth = document.documentElement.clientWidth
            const viewportHeight = document.documentElement.clientHeight

            // Measure the panel free of the constraints an earlier open may have applied
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
