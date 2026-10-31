/**
 * Single owner of the three dialog hosts in `views/includes/html_common.scala.html`: the general
 * modal (`#modalContent`), the popover dialog (`#popoverContent`) and asset detail
 * (`#imageDetailModalContent`).
 *
 * The active host lives in the Alpine `modal` store (registered with everything else `html_common`
 * binds by `registerHtmlCommonBindings` in `stores/html-common.js`).
 * The two modal hosts bind to it declaratively: `x-show` toggles a host and
 * `x-trap.inert.noscroll` from the official `@alpinejs/focus` plugin contains focus and hides the
 * rest of the page from assistive technology while a host is active. Everything the hosts must
 * agree on is decided here instead:
 *
 * - only one host is active at a time; a new open replaces whatever is active,
 * - only one of the general and popover hosts holds a dialog fragment: a host keeps its last one
 *   after closing, and opening the other empties it, so two dialogs never share an ID,
 * - initial focus and focus restoration on close,
 * - the identity of the latest "open a dialog" request, so a slow response for an earlier open can
 *   never display a dialog after the user dismissed or replaced it,
 * - the identity of each displayed open (`openId`), so asynchronous work started for one open
 *   (form submissions, image loads) can tell whether that open is still the active one.
 *
 * The popover dialog is a native `<dialog popover="auto">` with no scrim and no focus trap. The
 * browser owns its visibility (`:popover-open`, changed here only through the popover API; nothing binds
 * `x-show` to it), and CSS anchor positioning places it below the control its request names in
 * `data-app-dialog-anchor`. The store still names it while it is open, for the shared title and the
 * one-active-host rule. The browser dismisses it on an outside click (light dismiss), leaving focus
 * where the click put it; Tab moving focus to a control outside it closes it too, leaving focus on
 * that control. Escape, its close control and a completed operation close it here and restore focus
 * as they do for a modal.
 *
 * The general modal is centered; asset detail is fitted to its asset by CSS.
 *
 * `global.js` imports this module on every page, including pages without the hosts or the store,
 * so every entry point tolerates a missing store.
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
}

const hostContentIds = {
    [ModalHost.general]: "modalContent",
    [ModalHost.popover]: "popoverContent",
    [ModalHost.assetDetail]: "imageDetailModalContent",
}

const hostContainerIds = {
    [ModalHost.general]: "modalContainer",
    [ModalHost.popover]: "popoverDialog",
    [ModalHost.assetDetail]: "imageDetailModalContainer",
}

// The hosts that trap focus with `x-trap.inert`; the popover dialog has no trap
const trappedHosts = [ModalHost.general, ModalHost.assetDetail]

// Identity of each displayed open; the store mirrors the current value for reactive consumers.
let lastOpenId = 0

// The latest request that loads a modal fragment, from `htmx:before:request` until the fragment is
// displayed, dismissed, or superseded. Carries the element focused when the user triggered it.
let pendingOpen = null

// Where focus goes when the active modal closes.
let returnFocus = null

// The element that issued the request whose fragment the displayed open shows, when it was an htmx
// request: a results cell's thumbnail for asset detail, which is where its navigation starts from
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
 * Whether the open identified by `openId` is the one currently displayed. Asynchronous work that
 * started for an open must check this before touching the modal.
 */
export function isModalOpenActive(openId) {
    return isModalActive() && getModalOpenId() === openId
}

/**
 * The container of the active host when that host traps focus (the general modal or asset
 * detail): its `x-trap.inert` hides the rest of the page from assistive technology. Null while no
 * host, or the popover dialog, is active.
 */
export function getActiveTrapContainer() {
    const host = getActiveModalHost()
    return trappedHosts.includes(host) ? getContainer(host) : null
}

/** Whether `el` is inside one of the three hosts */
export function isInModalHost(el) {
    return Object.values(ModalHost).some((host) =>
        getContainer(host)?.contains(el),
    )
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
 * The popover host is shown below the control its request names (`data-app-dialog-anchor`), or
 * placed like the general host (`data-unanchored`) when that control is gone or not displayed.
 *
 * An open context menu (folder, album, or Location) is closed first, so a dialog
 * never appears over one.
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

    // Validation re-hydrates a form inside the active host without a new open request. Keep the
    // original return control instead of remembering the field that the response just removed,
    // and leave the popover where it is.
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

        // `showPopover()` displays the host synchronously, so focus can move in right away. The
        // anchor as its source puts the popover next in the Tab order after it, so Tab out of the
        // popover goes on from the trigger.
        const popoverEl = getContainer(ModalHost.popover)
        if (!popoverEl.matches(":popover-open")) {
            popoverEl.showPopover(anchorEl ? { source: anchorEl } : undefined)
        }
        placeInitialFocus({ host, focusSelector, selectOnFocus })
    }

    const activeHost = store.activeHost
    if (activeHost && activeHost !== host) {
        // Release the active host in its own reactive flush before activating the other one. The
        // modal hosts' `x-trap.inert` effects mark the rest of the page aria-hidden and undo that
        // on release; running the release first keeps the new host's marks from being undone, and
        // the popover from opening under the old host's. The popover host is hidden here, after
        // the store has released it, so its `beforetoggle` listener leaves it alone.
        store.activeHost = null
        if (activeHost === ModalHost.popover) {
            hidePopoverHost()
        }
        Alpine.nextTick(activate)
    } else {
        activate()
    }

    return openId
}

/** The element whose htmx request loaded the displayed open's fragment, or null (a page-held dialog, a programmatic open) */
export function getModalOpenSource() {
    return openSource
}

/**
 * Closes the active dialog and restores focus. Returns false when nothing was open.
 *
 * Focus stays put when the popover host is closed while focus already sits on a control outside
 * it.
 */
export function closeModal() {
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
 * Binds the popover host's own dismissals, once per page. A page without the host binds nothing.
 *
 * The browser hides the popover by itself on an outside click (and on its own Escape handling,
 * which `global.js` consumes first). `beforetoggle` reports that synchronously, at the pointerup,
 * before the click that dismissed it can request another dialog, so dropping the pending open here
 * never cancels the one that click requests; the `toggle` that follows is a queued task, which runs
 * after the click. `closeModal()` releases the store before it hides the host, so its own hiding
 * finds nothing to do here.
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

        // Focus moves only when the popover hid around it; a click elsewhere keeps the focus it
        // gave, or left on the page
        releaseActiveHost({
            reason: "dismissed by the browser",
            keepFocus: !popoverEl.contains(document.activeElement),
        })
    })

    // Tab, or Shift+Tab from the first control, moving focus out. A null related target is a click,
    // which light dismiss handles, or a validation swap removing the focused field.
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
 * Releases the active host from the store and, unless `keepFocus`, restores focus. Hiding the
 * popover host is the caller's: the browser may already be hiding it. Returns false when nothing
 * was open.
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
    // A video keeps playing in a hidden host otherwise, and a paused one that still has its source
    // keeps the browser's media overlay, whose play button would resume it there
    getContainer(store.activeHost)
        ?.querySelectorAll("video")
        .forEach(unloadVideo)
    clearMediaSession()
    // The title stays, so the popover keeps it while it fades out; a modal host is hidden at once
    // (`x-show`), and the next open sets its own
    store.activeHost = null

    const focusTarget = returnFocus
    returnFocus = null
    // Restore after a focus trap has released, and after the browser's own focus restoration when
    // the popover hides around the focused control
    if (!keepFocus) {
        Alpine.nextTick(() => restoreFocus(focusTarget))
    }

    return true
}

/**
 * Empties the other of the general and popover hosts when `host` is one of them, so only one dialog
 * fragment exists at a time: a host keeps its last fragment after closing, and the dialogs share
 * field IDs, so a hidden form there would shadow the opened dialog's (a `<label for>`, an autofocus
 * selector). The fragment goes once its host has faded out, so a closing popover keeps its content,
 * and only that fragment goes: one swapped in meanwhile stays.
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
 * Points the popover host's `position-anchor` at the trigger `anchorId` names (each trigger carries
 * `anchor-name: --<its id>`), so a list rebuild that renders the trigger again re-anchors it, which
 * the implicit anchor of `showPopover({ source })`, a reference to one element, would not. With no
 * such trigger displayed, the host is placed like the general one. Returns the trigger, or null.
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
 * Hands the asset's stored size to the asset-detail box, which CSS fits into the viewport below the
 * toolbar and refits when the viewport changes (core.css). Without dimensions the box falls back to
 * the whole viewport, which is what the host shows while the first asset loads.
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

/**
 * `htmx:before:request` hook: records a request that loads a fragment into a dialog host as the
 * latest open request, with the trigger a popover open is anchored to. Returns whether the event
 * was such a request.
 */
export function trackModalOpenRequest(event) {
    const host = getModalOpenRequestHost(event)
    if (!host) {
        return false
    }

    // Menu actions become hidden when the dialog opens. Return to their visible ⋯ trigger.
    const sourceEl = event.detail.ctx.sourceElement
    const panel = sourceEl?.closest(".context-menu")
    pendingOpen = {
        ctx: event.detail.ctx,
        host,
        opener: panel ? getContextMenuTrigger(panel) : document.activeElement,
        anchorId: sourceEl?.dataset.appDialogAnchor,
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

// The document body is what `activeElement` reports when nothing focusable was clicked, so it is
// never a meaningful place to return to.
function isFocusRestorable(el) {
    return Boolean(el && el !== document.body && el.isConnected)
}

// Focus already sits on a control outside the popover host: Tab or a click put it there
function isOutsidePopoverHost(el) {
    return (
        isFocusRestorable(el) && !getContainer(ModalHost.popover).contains(el)
    )
}
