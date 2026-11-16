/**
 * Fullscreen for Asset detail: `f`, the toolbar's toggle and the player's own button all put the Asset
 * detail container in fullscreen, image or Video, so the Detail panel, the Face highlights and the
 * player's bar come along. It is never remembered and survives stepping; Escape, the browser's, leaves
 * it before a second Escape closes Asset detail. The toggles are removed where fullscreen is not
 * allowed. Entering raises the snackbar's stacks, popovers outside the container that would otherwise
 * sit under the fullscreen element in the top layer.
 */
import { Const } from "../constants.js"
import { raiseSnackbarStacks } from "../common/snackbar.js"

const CONTAINER_ID = "imageDetailModalContainer"

export function createDetailFullscreen() {
    function containerEl() {
        return document.getElementById(CONTAINER_ID)
    }

    function isFullscreen() {
        const fullscreenEl = document.fullscreenElement
        return Boolean(fullscreenEl && fullscreenEl === containerEl())
    }

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
     * Points the player's fullscreen button at the container, once media-chrome has defined the
     * element: a property set before the upgrade would shadow the class's accessor.
     */
    function attach(fragmentEl) {
        // The key legend comes with each fragment, so its fullscreen entry is removed here
        if (!document.fullscreenEnabled) {
            fragmentEl
                .querySelectorAll(`[${Const.attributes.fullscreenKey}]`)
                .forEach((keyEl) => keyEl.remove())
        }

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

    /** A refused request (no user gesture) is logged and nothing changes */
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
