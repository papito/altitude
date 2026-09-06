/**
 * Single owner of modal visibility for the two hosts in `views/includes/html_common.scala.html`:
 * the general dialog host (`#modalContent`) and asset detail (`#imageDetailModalContent`).
 *
 * Visible state lives in the Alpine `modal` store (registered from `stores/app-stores.js`) so the
 * hosts bind to it declaratively: `x-show` toggles a host and `x-trap.inert.noscroll` from the
 * official `@alpinejs/focus` plugin contains focus and hides the rest of the page from assistive
 * technology while a host is active. Everything the hosts must agree on is decided here instead:
 *
 * - only one host is active at a time; a new open replaces whatever is active,
 * - initial focus and focus restoration on close,
 * - the identity of the latest "open a modal" request, so a slow response for an earlier open can
 *   never display a dialog after the user dismissed or replaced it,
 * - the identity of each displayed open (`openId`), so asynchronous work started for one open
 *   (form submissions, image loads) can tell whether that open is still the active one.
 *
 * Placement is the hosts' CSS: the general box is centered horizontally with a fixed top padding.
 *
 * `global.js` imports this module on every page, including pages without the hosts or the store,
 * so every entry point tolerates a missing store.
 */
import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { closeOpenFolderMenu } from "./folder-menu.js"

export const ModalHost = {
    general: "general",
    assetDetail: "assetDetail",
}

const hostContentIds = {
    [ModalHost.general]: "modalContent",
    [ModalHost.assetDetail]: "imageDetailModalContent",
}

const hostContainerIds = {
    [ModalHost.general]: "modalContainer",
    [ModalHost.assetDetail]: "imageDetailModalContainer",
}

// Identity of each displayed open; the store mirrors the current value for reactive consumers.
let lastOpenId = 0

// The latest request that loads a modal fragment, from `htmx:before:request` until the fragment is
// displayed, dismissed, or superseded. Carries the element focused when the user triggered it.
let pendingOpen = null

// Where focus goes when the active modal closes.
let returnFocus = null

export function createModalStore() {
    return {
        activeHost: null,
        title: "",
        openId: 0,

        isActive(host) {
            return this.activeHost === host
        },

        close() {
            closeModal()
        },
    }
}

function modalStore() {
    return Alpine.store(Const.state.modal)
}

export function getActiveModalHost() {
    return modalStore()?.activeHost ?? null
}

export function isModalActive() {
    return getActiveModalHost() !== null
}

export function getModalOpenId() {
    return modalStore()?.openId ?? 0
}

/**
 * Whether the open identified by `openId` is the one currently displayed. Asynchronous work that
 * started for an open must check this before touching the modal.
 */
export function isModalOpenActive(openId) {
    return isModalActive() && getModalOpenId() === openId
}

/**
 * Displays `host` with `title`, replacing any active modal, and returns the new open identity.
 *
 * Initial focus goes to `focusSelector` (resolved inside the host content, optionally selecting the
 * field's text) or else to the host's close control, which is never a destructive action.
 * On close, focus goes to `returnFocusSelector` - the control the fragment declares as the
 * relevant one to return to, chosen to survive the page update its operation triggers - or, without
 * one, back to the element that was focused when the open was requested.
 *
 * An open folder menu, whichever state it is in, is closed first, so a modal never appears over
 * one.
 */
export function openModal({
    host,
    title = "",
    focusSelector,
    selectOnFocus = false,
    returnFocusSelector,
}) {
    closeOpenFolderMenu({ reason: "modal opening" })

    const store = modalStore()
    const openId = ++lastOpenId
    store.openId = openId

    returnFocus = {
        opener: pendingOpen?.opener ?? document.activeElement,
        fallbackSelector: returnFocusSelector,
    }
    pendingOpen = null

    console.debug(`Opening ${host} modal "${title}" (open ${openId})`)

    const activate = () => {
        // Superseded while waiting for the previous host to release
        if (store.openId !== openId) {
            return
        }

        store.activeHost = host
        store.title = title
        whenHostDisplayed(host, openId, () => {
            placeInitialFocus({ host, focusSelector, selectOnFocus })
        })
    }

    if (store.activeHost && store.activeHost !== host) {
        // Release the active host in its own reactive flush before activating the other one. Both
        // hosts' `x-trap.inert` effects mark the rest of the page aria-hidden and undo that on
        // release; running the release first keeps the new host's marks from being undone.
        store.activeHost = null
        Alpine.nextTick(activate)
    } else {
        activate()
    }

    return openId
}

/**
 * Closes the active modal and restores focus. Returns false when nothing was open.
 */
export function closeModal() {
    const store = modalStore()
    pendingOpen = null

    if (!store?.activeHost) {
        return false
    }

    console.debug(`Closing ${store.activeHost} modal (open ${store.openId})`)
    store.activeHost = null
    store.title = ""

    const focusTarget = returnFocus
    returnFocus = null
    // Restore after the focus trap has released
    Alpine.nextTick(() => restoreFocus(focusTarget))

    return true
}

/**
 * Sizes the asset-detail box to the image, scaled to fit the viewport. Without dimensions the box
 * falls back to its CSS size, which is what the host shows while the first image loads.
 */
export function setAssetDetailSize({ width, height } = {}) {
    const box = getContainer(ModalHost.assetDetail).querySelector(".modal-box")

    if (width && height) {
        const maxW = window.innerWidth - 10
        const maxH = window.innerHeight - 40
        const scale = Math.min(1, maxW / width, maxH / height)
        box.style.width = `${Math.round(width * scale)}px`
        box.style.height = `${Math.round(height * scale) + 40}px` // +40 for toolbar
    } else {
        box.style.width = ""
        box.style.height = ""
    }
}

function getContainer(host) {
    return document.getElementById(hostContainerIds[host])
}

/**
 * `htmx:before:request` hook: records a request that loads a fragment into a modal host as the
 * latest open request. Returns whether the event was such a request.
 */
export function trackModalOpenRequest(event) {
    const host = getModalOpenRequestHost(event)
    if (!host) {
        return false
    }

    pendingOpen = {
        ctx: event.detail.ctx,
        host,
        opener: document.activeElement,
    }

    return true
}

/**
 * `htmx:after:request` hook for modal open requests. Cancels the swap when the request is no
 * longer the latest open (the user dismissed or requested another modal meanwhile) and reports
 * failed opens. Returns whether the event was a modal open request.
 */
export function settleModalOpenRequest(event, { onFailure }) {
    const host = getModalOpenRequestHost(event)
    if (!host) {
        return false
    }

    const ctx = event.detail.ctx

    if (pendingOpen?.ctx !== ctx) {
        console.debug(`Ignoring superseded ${host} modal response`)
        event.preventDefault()
        return true
    }

    if (ctx.response.status >= 400) {
        pendingOpen = null
        onFailure(ctx)
    }

    return true
}

export function isModalOpenRequest(event) {
    return getModalOpenRequestHost(event) !== null
}

function getModalOpenRequestHost(event) {
    const target = event.detail.ctx.target

    return (
        Object.keys(hostContentIds).find(
            (host) => target?.id === hostContentIds[host],
        ) ?? null
    )
}

/**
 * Runs `callback` once the host is displayed, as long as `openId` is still the current open.
 * `x-show` reveals a host in a macrotask of its own (Alpine's click-away compatible show), and a
 * hidden element cannot take focus, so this polls the computed display instead of relying on the
 * relative order of Alpine's ticks and timers.
 */
function whenHostDisplayed(host, openId, callback, attempt = 0) {
    const container = getContainer(host)

    if (modalStore().openId !== openId || attempt > 100) {
        return
    }

    if (getComputedStyle(container).display === "none") {
        setTimeout(() => whenHostDisplayed(host, openId, callback, attempt + 1))
        return
    }

    callback()
}

function placeInitialFocus({ host, focusSelector, selectOnFocus }) {
    const container = getContainer(host)
    const content = document.getElementById(hostContentIds[host])
    const focusEl =
        (focusSelector && content.querySelector(focusSelector)) ||
        container.querySelector(".close-modal")

    focusEl.focus()

    if (selectOnFocus && typeof focusEl.select === "function") {
        focusEl.select()
    }
}

function restoreFocus({ opener, fallbackSelector } = {}) {
    const declared =
        fallbackSelector && document.querySelector(fallbackSelector)
    const target = declared || (isFocusRestorable(opener) ? opener : null)

    target?.focus()
}

// The document body is what `activeElement` reports when nothing focusable was clicked, so it is
// never a meaningful place to return to.
function isFocusRestorable(el) {
    return Boolean(el && el !== document.body && el.isConnected)
}
