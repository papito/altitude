/**
 * Fullscreen for Asset detail: `f` (`global.js`), the toolbar's toggle (`includes/html_common`,
 * `data-app-fullscreen-toggle`) and the player's own fullscreen button all put the Asset detail
 * container (`#imageDetailModalContainer`, the host) in fullscreen, on an image as on a Video, so
 * the Detail panel, the Face highlights and the player's control bar come along; `core.css` hides
 * the toolbar under `:fullscreen` and makes the box the whole screen, which the stage fits the media
 * to. Fullscreen is never remembered. Stepping stays in it; closing Asset detail leaves it
 * (`common/modal.js`, on every close path), and Escape leaves it before a second Escape closes
 * Asset detail (the browser's own exit, which `global.js` leaves alone).
 *
 * The player's button goes through the same element: each fragment's `<media-controller>` gets the
 * container as its `fullscreenElement` once media-chrome has defined it, so its button requests
 * fullscreen on the container and shows the state the container is in. The toolbar's toggle is left
 * out where the browser allows no fullscreen (`document.fullscreenEnabled`), as the player leaves its
 * own out.
 *
 * The snackbar's stacks are popovers outside the container: one open as fullscreen begins would sit
 * under the fullscreen element in the top layer, so they are shown again on entering, which puts
 * them above it (`raiseSnackbarStacks`).
 */
import { Const } from "../constants.js"
import { raiseSnackbarStacks } from "../common/snackbar.js"

const CONTAINER_ID = "imageDetailModalContainer"

export function createDetailFullscreen() {
    function containerEl() {
        return document.getElementById(CONTAINER_ID)
    }

    /** Whether the Asset detail container is the fullscreen element */
    function isFullscreen() {
        const fullscreenEl = document.fullscreenElement
        return Boolean(fullscreenEl && fullscreenEl === containerEl())
    }

    /** Binds the toolbar's toggle once, or removes it where fullscreen is not allowed, and follows the state */
    function start() {
        const toggleEls = document.querySelectorAll(
            `[${Const.attributes.fullscreenToggle}]`,
        )

        if (!document.fullscreenEnabled) {
            toggleEls.forEach((buttonEl) => buttonEl.remove())
            return
        }

        toggleEls.forEach((buttonEl) =>
            buttonEl.addEventListener("click", toggle),
        )

        document.addEventListener("fullscreenchange", () => {
            syncToggleButtons()
            if (isFullscreen()) {
                raiseSnackbarStacks()
            }
        })
    }

    /**
     * A new Asset detail fragment arrived: its player's fullscreen button acts on the container.
     * The property is set once media-chrome has defined the element, since a property set on an
     * element not yet upgraded would shadow the class's accessor; a fragment replaced before then
     * is left alone.
     */
    function attach(fragmentEl) {
        const playerEl = fragmentEl.querySelector("media-controller")
        if (!playerEl) {
            return
        }

        customElements.whenDefined("media-controller").then(() => {
            if (playerEl.isConnected) {
                playerEl.fullscreenElement = containerEl()
            }
        })
    }

    /** Puts Asset detail in fullscreen, or leaves it; a refused request (no gesture) is logged and nothing changes */
    function toggle() {
        if (document.fullscreenElement) {
            document.exitFullscreen().catch(() => {})
            return
        }

        const el = containerEl()
        if (!el || !document.fullscreenEnabled) {
            return
        }

        el.requestFullscreen().catch((error) => {
            console.debug(`Fullscreen was refused: ${error.message}`)
        })
    }

    function syncToggleButtons() {
        document
            .querySelectorAll(`[${Const.attributes.fullscreenToggle}]`)
            .forEach((buttonEl) =>
                buttonEl.setAttribute("aria-pressed", String(isFullscreen())),
            )
    }

    return { start, attach, toggle, isFullscreen }
}
