/**
 * Single owner of the four dialog hosts: the general modal, the popover dialog, asset detail and
 * Curation. The active host lives in the Alpine `modal` store; the three modal hosts bind `x-show`
 * and `x-trap.inert` to it. Decided here: one host is active at a time; a close guard may hold a
 * host's close; only one of the general and popover hosts holds a fragment, so two dialogs never
 * share an ID; focus on open and close; and open identities, so a slow response or async work for
 * a dismissed open never touches the modal.
 *
 * The popover dialog is a native `<dialog popover="auto">`, unscrimmed and untrapped, anchored by
 * CSS to the control its request names. The browser owns its visibility and light dismiss; Tab out
 * of it closes it too. Pages without the hosts or the store import this module, so every entry
 * point tolerates a missing store.
 */
import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { clearMediaSession, unloadVideo } from "./video-playback.js"
import {
    closeOpenContextMenu,
    getContextMenuTrigger,
} from "../alpine/components/context-menu.js"

export const ModalHost = {
    general: "general",
    popover: "popover",
    assetDetail: "assetDetail",
    curation: "curation",
}

const hostContentIds = {
    [ModalHost.general]: "modalContent",
    [ModalHost.popover]: "popoverContent",
    [ModalHost.assetDetail]: "imageDetailModalContent",
    [ModalHost.curation]: "curationModalContent",
}

const hostContainerIds = {
    [ModalHost.general]: "modalContainer",
    [ModalHost.popover]: "popoverDialog",
    [ModalHost.assetDetail]: "imageDetailModalContainer",
    [ModalHost.curation]: "curationModalContainer",
}

// The hosts that trap focus with `x-trap.inert`; the popover dialog has no trap
const trappedHosts = [
    ModalHost.general,
    ModalHost.assetDetail,
    ModalHost.curation,
]

// The store mirrors the current open identity for reactive consumers
let lastOpenId = 0

// The latest request that loads a modal fragment, until it is displayed, dismissed or superseded
let pendingOpen = null

let returnFocus = null

// An open waiting one Alpine tick for the host it replaces to be released: work started for it
// already counts as the displayed open's
let activatingOpenId = null

// The element whose htmx request loaded the displayed fragment: for asset detail, the results cell
// its navigation starts from
let openSource = null

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
 * Whether `openId` is displayed or about to be: an open over another host is displayed one Alpine
 * tick later, and a load finishing in that tick (a cached image) is still its own. Async work for an
 * open checks this before touching the modal.
 */
export function isModalOpenActive(openId) {
    return (
        getModalOpenId() === openId &&
        (isModalActive() || activatingOpenId === openId)
    )
}

/**
 * The active host's container when it traps focus, which hides the rest of the page from assistive
 * technology; null for none or the popover dialog.
 */
export function getActiveTrapContainer() {
    const host = getActiveModalHost()
    return trappedHosts.includes(host) ? getContainer(host) : null
}

export function isInModalHost(el) {
    return Object.values(ModalHost).some((host) =>
        getContainer(host)?.contains(el),
    )
}

/**
 * Displays `host`, replacing any active modal, and returns the new open identity. Initial focus
 * goes to `focusSelector`, else to the close control, never a destructive action. On close, focus
 * goes to `returnFocusSelector`, a control chosen to survive the page update the operation
 * triggers, else back to the opener (`openerOf`).
 */
export function openModal({
    host,
    title = "",
    focusSelector,
    selectOnFocus = false,
    returnFocusSelector,
}) {
    closeOpenContextMenu({ reason: "dialog opening" })

    const store = modalStore()
    const openId = ++lastOpenId
    store.openId = openId

    // Validation re-hydrates a form in the active host without an open request: keep the original
    // return control, not the field the response just removed, and leave the popover in place
    const isReplacement =
        !pendingOpen && store.activeHost === host && returnFocus !== null
    if (!isReplacement) {
        returnFocus = {
            opener: pendingOpen?.opener ?? document.activeElement,
            fallbackSelector: returnFocusSelector,
        }
    }
    const anchorId = pendingOpen?.anchorId
    openSource = pendingOpen?.ctx.sourceElement ?? null
    pendingOpen = null

    console.debug(`Opening ${host} modal "${title}" (open ${openId})`)

    const activate = () => {
        if (activatingOpenId === openId) {
            activatingOpenId = null
        }
        // Superseded while waiting for the previous host to release
        if (store.openId !== openId) {
            return
        }

        store.activeHost = host
        store.title = title

        if (!isReplacement) {
            clearOtherDialogHost(host)
        }

        if (host !== ModalHost.popover) {
            whenHostDisplayed(host, openId, () => {
                placeInitialFocus({ host, focusSelector, selectOnFocus })
            })
            return
        }

        const anchorEl = isReplacement ? null : anchorPopover(anchorId)

        // `showPopover()` displays the host synchronously, so focus can move in at once. The anchor
        // as `source` puts the popover next after it in the Tab order
        const popoverEl = getContainer(ModalHost.popover)
        if (!popoverEl.matches(":popover-open")) {
            popoverEl.showPopover(anchorEl ? { source: anchorEl } : undefined)
        }
        placeInitialFocus({ host, focusSelector, selectOnFocus })
    }

    const activeHost = store.activeHost
    if (activeHost && activeHost !== host) {
        // Release the active host in its own reactive flush first: `x-trap.inert` marks the rest of
        // the page aria-hidden and undoes that on release, which would otherwise undo the new
        // host's marks. The popover is hidden after the store released it, so its `beforetoggle`
        // listener leaves it alone.
        store.activeHost = null
        if (activeHost === ModalHost.popover) {
            hidePopoverHost()
        }
        activatingOpenId = openId
        Alpine.nextTick(activate)
    } else {
        activate()
    }

    return openId
}

/** Null for a page-held dialog or a programmatic open */
export function getModalOpenSource() {
    return openSource
}

// By host, the guard `closeModal` asks first, and the answer to the last close a guard held
const closeGuards = new Map()
let heldClose = null

/**
 * `guard` answers true to let the close go ahead, or holds it with a promise of whether the host
 * closed after all, and later closes the host itself with `closeModal`, answering true that time.
 * An open of another host replaces the active one without asking.
 */
export function setCloseGuard(host, guard) {
    closeGuards.set(host, guard)
}

/**
 * For the last `closeModal` a guard held, the promise of whether the host closed after all; null if
 * not held. Taken once: a caller that changed the page for the close (a Back) restores it if the
 * host stays.
 */
export function takeHeldClose() {
    const held = heldClose
    heldClose = null
    return held
}

/**
 * Returns false when nothing was open, and true when a close guard holds the close. Focus stays put
 * when the popover closes while focus is already outside it.
 */
export function closeModal() {
    const guard = closeGuards.get(getActiveModalHost())
    const verdict = guard ? guard() : true
    heldClose = verdict === true ? null : Promise.resolve(verdict)
    if (heldClose) {
        return true
    }
    const keepFocus =
        getActiveModalHost() === ModalHost.popover &&
        isOutsidePopoverHost(document.activeElement)
    const closed = releaseActiveHost({ keepFocus })

    if (closed) {
        hidePopoverHost()
    }

    return closed
}

/**
 * Binds the popover host's own dismissals, once per page. `beforetoggle` fires synchronously at the
 * pointerup of a light dismiss, before the click can request another dialog, so dropping the pending
 * open here never cancels the one that click requests (the queued `toggle` would run after it).
 */
export function bindPopoverHost() {
    const popoverEl = getContainer(ModalHost.popover)
    if (!popoverEl) {
        return
    }

    popoverEl.addEventListener("beforetoggle", (event) => {
        if (
            event.newState !== "closed" ||
            getActiveModalHost() !== ModalHost.popover
        ) {
            return
        }

        // Focus moves only when the popover hid around it; a click elsewhere keeps its focus
        releaseActiveHost({
            reason: "dismissed by the browser",
            keepFocus: !popoverEl.contains(document.activeElement),
        })
    })

    // Tab moving focus out. A null related target is a click, which light dismiss handles, or a
    // validation swap removing the focused field
    popoverEl.addEventListener("focusout", (event) => {
        if (
            getActiveModalHost() !== ModalHost.popover ||
            !event.relatedTarget ||
            popoverEl.contains(event.relatedTarget)
        ) {
            return
        }

        releaseActiveHost({ reason: "focus left", keepFocus: true })
        hidePopoverHost()
    })
}

/**
 * Hiding the popover host is the caller's, since the browser may already be hiding it. Returns false
 * when nothing was open.
 */
function releaseActiveHost({ reason, keepFocus }) {
    const store = modalStore()
    pendingOpen = null

    if (!store?.activeHost) {
        return false
    }

    console.debug(
        `Closing ${store.activeHost} modal (open ${store.openId})${reason ? `: ${reason}` : ""}`,
    )
    // A video would keep playing in a hidden host, and a paused one with its source keeps the
    // browser's media overlay, whose play button would resume it
    const containerEl = getContainer(store.activeHost)
    containerEl?.querySelectorAll("video").forEach(unloadVideo)
    clearMediaSession()
    // Leave fullscreen on every close path, or the screen would stay on the hidden host
    if (containerEl?.contains(document.fullscreenElement)) {
        document.exitFullscreen().catch(() => {})
    }
    // The title stays, so the popover keeps it while it fades out
    store.activeHost = null

    const focusTarget = returnFocus
    returnFocus = null
    // After the focus trap has released, and after the browser's own restoration when the popover
    // hides around the focused control
    if (!keepFocus) {
        Alpine.nextTick(() => restoreFocus(focusTarget))
    }

    return true
}

/**
 * Empties the other of the general and popover hosts: a host keeps its last fragment after closing,
 * and dialogs share field IDs, so a hidden form would shadow the opened one's (`<label for>`,
 * autofocus). The fragment goes once its host has faded out, and a fragment swapped in meanwhile
 * stays.
 */
function clearOtherDialogHost(host) {
    if (host !== ModalHost.general && host !== ModalHost.popover) {
        return
    }

    const other =
        host === ModalHost.general ? ModalHost.popover : ModalHost.general
    const staleEls = [
        ...(document.getElementById(hostContentIds[other])?.children ?? []),
    ]
    const fadeOuts = (getContainer(other)?.getAnimations() ?? []).map(
        (animation) => animation.finished,
    )

    Promise.allSettled(fadeOuts).then(() => {
        staleEls.forEach((el) => el.remove())
    })
}

function hidePopoverHost() {
    const popoverEl = getContainer(ModalHost.popover)
    if (popoverEl?.matches(":popover-open")) {
        popoverEl.hidePopover()
    }
}

/**
 * Anchors by name rather than through `showPopover({ source })`, which holds one element, so a list
 * rebuild that renders the trigger again re-anchors it. With no trigger displayed the host is
 * placed like the general one. Returns the trigger, or null.
 */
function anchorPopover(anchorId) {
    const popoverEl = getContainer(ModalHost.popover)
    const anchorEl = anchorId && document.getElementById(anchorId)

    if (anchorEl?.getClientRects().length) {
        popoverEl.style.positionAnchor = `--${anchorId}`
        popoverEl.removeAttribute("data-unanchored")
        return anchorEl
    }

    popoverEl.style.positionAnchor = ""
    popoverEl.setAttribute("data-unanchored", "")
    return null
}

/**
 * The CSS fits the asset-detail box to this size within the viewport; without one it fills the
 * viewport, as while the first asset loads.
 */
export function setAssetDetailSize({ width, height } = {}) {
    const box = getContainer(ModalHost.assetDetail).querySelector(".modal-box")

    if (width && height) {
        box.style.setProperty("--asset-width", width)
        box.style.setProperty("--asset-height", height)
    } else {
        box.style.removeProperty("--asset-width")
        box.style.removeProperty("--asset-height")
    }
}

function getContainer(host) {
    return document.getElementById(hostContainerIds[host])
}

/** `htmx:before:request` hook. Returns whether the request loads a fragment into a dialog host */
export function trackModalOpenRequest(event) {
    const host = getModalOpenRequestHost(event)
    if (!host) {
        return false
    }

    const sourceEl = event.detail.ctx.sourceElement
    pendingOpen = {
        ctx: event.detail.ctx,
        host,
        opener: openerOf(sourceEl),
        anchorId: sourceEl?.dataset.appDialogAnchor,
    }

    return true
}

/**
 * Where focus returns when the dialog closes. A control the open takes away hands over the one it
 * stands for: a menu action its ⋯ trigger, and a control of a replaced popover dialog that dialog's
 * opener. Otherwise it is the control focused as the open was requested.
 */
function openerOf(sourceEl) {
    const panel = sourceEl?.closest(".context-menu")
    if (panel) {
        return getContextMenuTrigger(panel)
    }

    const replacesPopover =
        returnFocus !== null &&
        getActiveModalHost() === ModalHost.popover &&
        getContainer(ModalHost.popover).contains(sourceEl)

    return replacesPopover ? returnFocus.opener : document.activeElement
}

/**
 * `htmx:after:request` hook. Cancels the swap of a superseded open and reports failed ones. A 204,
 * which htmx does not swap, opens nothing, and `onNoContent` lets its source act instead. Returns
 * whether the event was a modal open request.
 */
export function settleModalOpenRequest(event, { onFailure, onNoContent }) {
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
    } else if (ctx.response.status === 204) {
        pendingOpen = null
        onNoContent(ctx)
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
 * `x-show` reveals a host in a macrotask of its own, and a hidden element cannot take focus, so this
 * polls the computed display rather than rely on the order of Alpine's ticks and timers.
 */
function whenHostDisplayed(host, openId, callback, attempt = 0) {
    const container = getContainer(host)

    if (!isModalOpenActive(openId) || attempt > 100) {
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
        container.querySelector(".close-modal") ||
        container

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

// `activeElement` is the body when nothing focusable was clicked: never a place to return to
function isFocusRestorable(el) {
    return Boolean(el && el !== document.body && el.isConnected)
}

function isOutsidePopoverHost(el) {
    return (
        isFocusRestorable(el) && !getContainer(ModalHost.popover).contains(el)
    )
}
