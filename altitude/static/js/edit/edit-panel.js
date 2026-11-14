/**
 * The Edit panel's coordinator (`CONTEXT.md`: Edit panel, Edit, Crop tool): Asset detail's `aside#editPanel`
 * (`includes/edit_panel`), whose controls are the `editPanel` Alpine component (`alpine/components/edit-panel.js`), which
 * reads and writes through this module (`connectEditPanelView`). It holds what the component does not:
 *
 * - **Open or closed** (`e`, the toolbar's toggle `data-app-edit-panel-toggle`), remembered per browser under
 *   `Const.localStore.assetDetailView` as `editOpen`, written as `data-app-edit-panel-open` on the fragment, which
 *   `view_image_detail_modal.css` lays the grid out from. The toggle is in the page for a holder of Edit images alone,
 *   and disabled, with a title saying why, on an asset no Edit may change: a Video or an animated image, an asset in the
 *   trash (the server's `editRefusal`), an original the browser could not decode, or a browser without WebGL2. Open on
 *   such an asset, the panel says why and draws nothing.
 * - **The dock:** the Detail panel's opposite side (`common/dock.js` with `followSide`), laid out again when that one
 *   moves or opens; 28 % of the width or 42 % of the height until a drag, remembered as `editDock`.
 * - **The stage:** while the panel is open the canvas draws the Edit shown (`edit-stage.js`: `setEditing`, `setLive`),
 *   the whole frame in the Crop tool and Before left of the divider with Before/after; the Histogram of each drawing
 *   (`renderer.readHistogram`) and, a moment after the last one, its crushed and blown fractions and, while the Clipping
 *   blink is on, its mask (`common/clipping.js` over the canvas, at the measurement's levels and box, which the fragment
 *   carries as Curation's set does).
 * - **The session** of each asset (`edit-session.js`): a change is shown at once and saved as one step; a session still
 *   saving when Asset detail steps away finishes in the background. Once a session is idle after a save, the stage and
 *   the badge take the new head, and the render the server queues is waited for (`render-status.js`), after which the
 *   grid's cells show the new Preview and the stage, once the panel is closed, the Display image.
 * - **The Crop tool** (`crop-tool.js`): a whole session is one step, on Done; Cancel puts back the Edit it opened on;
 *   stepping to another asset, closing the panel or closing Asset detail while it is open is Done.
 * - **The keys' handlers** (`listeners/edit.js`): the toggle, the Crop tool's, Undo and Redo.
 */
import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { clippingMask, readClipping } from "../common/clipping.js"
import { createDock } from "../common/dock.js"
import { getActiveModalHost, ModalHost } from "../common/modal.js"
import { showErrorSnackBar, showWarningSnackBar } from "../common/snackbar.js"
import { readStoredObject, writeStoredFields } from "../common/stored-state.js"
import { getHttpErrorMessage, http } from "../http/client.js"
import {
    flipped,
    resetAll,
    resetSection,
    rotated,
    sliderDrag,
    swappedOrientation,
    withAspect,
    withKeepInside,
    withSlider,
} from "./adjust.js"
import { createCropTool } from "./crop-tool.js"
import { createEditSession, headOf } from "./edit-session.js"
import { isIdentity, normalize, sameRecipe } from "./recipe.js"
import { refreshAfterRender } from "./render-status.js"
import { isRendererSupported } from "./renderer.js"

export const DEFAULT_WIDTH_SHARE = 28
export const DEFAULT_HEIGHT_SHARE = 42

const VIEW_ID = "imageDetailView"
const PANEL_ID = "editPanel"
const DIVIDER_ID = "editPanelDivider"
const CANVAS_ID = "editCanvas"
const CLIPPING_ID = "editClipping"
const SPLIT_ID = "editSplit"
const SPLIT_HANDLE_ID = "editSplitHandle"

const OPPOSITE = Object.freeze({
    left: "right",
    right: "left",
    top: "bottom",
    bottom: "top",
})

// How long after the last drawing the clipping is read, so a slider's drag reads it once it rests
const CLIPPING_READ_DELAY_MS = 150
// Before/after's divider: where it starts, how near the edges it goes, and an arrow key's step
const SPLIT_START = 0.5
const SPLIT_MARGIN = 0.02
const SPLIT_KEY_STEP = 0.05

const CONFLICT_MESSAGE =
    "This photo was changed elsewhere; showing its latest Edit"
const SAVE_FAILED_MESSAGE = "Could not save the Edit"
const LOAD_FAILED_MESSAGE = "Could not read the Edit's History"
const NOTHING_TO_LEVEL_MESSAGE = "No straight lines to level the image by"

// How long a change made with a control's keys (a slider's arrows, a curve point's) or by a click on a slider's track waits
// before it is saved, so a held key is one step and a double-click resets without a step in between
const KEY_COMMIT_DELAY_MS = 400
const CLICK_COMMIT_DELAY_MS = 300
const NO_WEBGL_REFUSAL = "This browser cannot draw an Edit: it has no WebGL2"
const UNDECODABLE_REFUSAL = "The browser could not decode this image"

// The page's coordinator, which the Alpine component connects to
let controller = null

/**
 * Connects the Edit panel's Alpine component (`view`, with its `sync(snapshot)`) to the coordinator, which syncs it now
 * and on every change; answers the coordinator's actions and the disconnection, or null on a page without one
 */
export function connectEditPanelView(view) {
    return controller?.connect(view) ?? null
}

/**
 * The upright original's size as the server read it, from an Edit at its head or a state (`uprightWidth`,
 * `uprightHeight`), or null when it carries none
 */
function uprightOf(state) {
    return state?.uprightWidth > 0 && state?.uprightHeight > 0
        ? { width: state.uprightWidth, height: state.uprightHeight }
        : null
}

/** Calls `fn` on the next frame, or soon in a hidden tab, which runs no frames */
function nextFrame(fn) {
    if (document.visibilityState === "visible") {
        requestAnimationFrame(fn)
    } else {
        setTimeout(fn, 0)
    }
}

export function createEditPanel({ context, editStage, detailPanel }) {
    let open =
        readStoredObject(Const.localStore.assetDetailView).editOpen === true
    // The asset Asset detail shows: { assetId, mediaType, edit, editable, refusal, undrawable }
    let subject = null
    // The session of the asset shown while the panel edits it, and its unsubscription
    let session = null
    let unsubscribe = null
    // Every session still saving or following a render, by asset, so stepping back to one picks it up
    const sessions = new Map()
    // The Edit shown, and the last one the session itself showed, so a change it makes (Undo, a conflict) is told
    // from a change of the panel's not saved yet (a slider being dragged)
    let working = null
    let sessionRecipe = null
    let sessionSnapshot = null
    // The upright original's size: the server's (the Edit's `uprightWidth`, `uprightHeight`), or the canvas's once it drew
    let upright = null
    // A change shown and waiting to be saved (`commitSoon`)
    let commitTimer = 0
    let commitWaiting = false
    let splitOn = false
    let splitAt = SPLIT_START
    let clippingBlink = false
    // The last drawing's Histogram (bytes by channel) and fractions, and the read of its clipping
    let histogram = null
    let fractions = null
    let clippingTimer = 0
    let clippingToken = 0
    let drawScheduled = false
    let autoBusy = false
    // The Edit at the head as each asset's last saved state and render left it, by asset
    const latestHead = new Map()
    const renderTokens = new Map()
    let view = null

    const cropTool = createCropTool({
        recipe: () => working,
        upright: () => upright,
        preview,
        done: finishCrop,
        cancel: cancelCrop,
        changed: push,
    })

    const dock = createDock({
        storage: { key: Const.localStore.assetDetailView, field: "editDock" },
        followSide: () => OPPOSITE[detailPanel.dockSide()],
        layout: {
            sideAttribute: "data-edit-dock-side",
            shareProperty: "--edit-panel-share",
        },
        container: viewEl,
        sizeEl: viewEl,
        divider: () => document.getElementById(DIVIDER_ID),
        defaults: {
            side: () => OPPOSITE[detailPanel.dockSide()],
            widthShare: DEFAULT_WIDTH_SHARE,
            heightShare: DEFAULT_HEIGHT_SHARE,
        },
        minimums: panelMinimums,
        orientsDivider: true,
    })

    function viewEl() {
        return document.getElementById(VIEW_ID)
    }

    function panelEl() {
        return document.getElementById(PANEL_ID)
    }

    function canvasEl() {
        return document.getElementById(CANVAS_ID)
    }

    /** Binds the page-wide controls once: the toolbar's toggle, the Crop tool's bar, Asset detail's closing */
    function start() {
        controller = { connect }
        dock.start()
        cropTool.start()
        detailPanel.onLayoutChange(() => {
            if (panelEl()) {
                dock.layOut()
            }
        })

        document.addEventListener("click", (event) => {
            if (
                event.target instanceof Element &&
                event.target.closest(`[${Const.attributes.editPanelToggle}]`)
            ) {
                toggle()
            }
        })

        // Closing Asset detail ends what is open on its asset: the Crop tool's session is saved as Done
        let wasActive = false
        Alpine.effect(() => {
            const active = getActiveModalHost() === ModalHost.assetDetail
            if (wasActive && !active) {
                queueMicrotask(leaveAsset)
            }
            wasActive = active
        })
        syncToggle()
    }

    /** A new Asset detail fragment: its panel is laid out open or closed, its divider bound and its tools attached */
    function attach(fragmentEl) {
        cropTool.attach(fragmentEl)
        splitOn = false
        clippingBlink = false
        if (!panelEl()) {
            return
        }
        applyOpenState()
        dock.layOut()
        dock.bindDivider(fragmentEl.querySelector(`#${DIVIDER_ID}`))
        bindSplit(fragmentEl)
    }

    /**
     * Asset detail shows, or starts loading, an asset (`detail-navigator.js`, before its media): `edit` its Edit at the
     * head (or null), `editable` whether the account may edit it and `refusal` why not. What was open on the last asset
     * ends (the Crop tool's session saved as Done), and while the panel is open the stage is told to draw the coming
     * asset's Edit and its session starts.
     */
    function showAsset({ assetId, mediaType, edit, editable, refusal }) {
        if (subject && subject.assetId !== assetId) {
            leaveAsset()
        }
        const undrawable =
            subject?.assetId === assetId ? subject.undrawable : null
        subject = { assetId, mediaType, edit, editable, refusal, undrawable }
        upright = uprightOf(edit)
        syncToggle()
        if (!panelEl()) {
            return
        }
        const edits = open && canEdit()
        editStage.setEditing(edits, { present: false })
        if (edits) {
            startSession()
        } else {
            stopSession()
        }
        push()
    }

    /**
     * The stage showed `assetId` (`detail-navigator.js`, once `edit-stage.js` settled): while the panel edits it, the
     * size the canvas drew is the upright original's, a canvas that could not draw makes the asset one the panel cannot
     * edit, and an Edit of the session the stage did not draw (one still saving) is drawn
     */
    function afterShown(assetId) {
        if (subject?.assetId !== assetId || !open || !canEdit()) {
            return
        }
        const shown = editStage.current()
        if (shown?.drawFailed) {
            subject.undrawable = isRendererSupported()
                ? UNDECODABLE_REFUSAL
                : NO_WEBGL_REFUSAL
            editStage.setEditing(false)
            stopSession()
            syncToggle()
            push()
            return
        }
        if (shown?.source === "canvas" && shown.upright) {
            upright = shown.upright
        } else if (!upright && shown?.upright && !shown.showsOriginal) {
            upright = shown.upright
        }
        if (working && shown?.recipe && !sameRecipe(working, shown.recipe)) {
            present()
        } else {
            afterDraw()
        }
        cropTool.sync()
        push()
    }

    /** What was open on the asset shown ends: the Crop tool's session is saved as Done, Before/after and the blink go */
    function leaveAsset() {
        flushCommit()
        if (cropTool.isOpen()) {
            finishCrop()
        }
        splitOn = false
        syncSplit()
        clippingBlink = false
        hideClipping()
        histogram = null
        fractions = null
        upright = null
        if (getActiveModalHost() !== ModalHost.assetDetail) {
            subject = null
            stopSession()
            syncToggle()
        }
        push()
    }

    /** Whether the panel can edit the asset shown: an image the account may edit, which the canvas can draw */
    function canEdit() {
        return Boolean(
            subject?.editable && !subject.undrawable && isRendererSupported(),
        )
    }

    /** Why the asset shown cannot be edited, for the toggle's title and the panel; empty when it can */
    function refusalOf() {
        if (!subject || canEdit()) {
            return ""
        }
        if (!isRendererSupported()) {
            return NO_WEBGL_REFUSAL
        }
        return subject.undrawable ?? subject.refusal ?? ""
    }

    /**
     * The toolbar's toggle: pressed while the panel is open; on an asset no Edit may change, `aria-disabled` with the reason
     * as its title, so a keyboard or a screen reader still reaches the reason, and its press does nothing
     */
    function syncToggle() {
        const refusal = refusalOf()
        document
            .querySelectorAll(`[${Const.attributes.editPanelToggle}]`)
            .forEach((buttonEl) => {
                buttonEl.setAttribute("aria-pressed", String(open))
                // Open on such an asset, the toggle still closes the panel
                buttonEl.setAttribute(
                    "aria-disabled",
                    String(Boolean(refusal) && !open),
                )
                buttonEl.title = refusal || "Edit (E)"
            })
    }

    function applyOpenState() {
        viewEl()?.toggleAttribute(Const.attributes.editPanelOpen, open)
        syncToggle()
    }

    /**
     * `e` (`fromKey`) and the toolbar's toggle: opens the panel on an asset it can edit, or closes it. On an asset it cannot
     * edit, the toggle's press does nothing (its title says why), and `e` says why in the snackbar
     */
    function toggle({ fromKey = false } = {}) {
        if (
            !panelEl() ||
            getActiveModalHost() !== ModalHost.assetDetail ||
            !subject
        ) {
            return
        }
        if (!open && !canEdit()) {
            if (fromKey && refusalOf()) {
                showWarningSnackBar(refusalOf())
            }
            return
        }
        setOpen(!open)
    }

    function setOpen(value) {
        if (!value) {
            leaveAsset()
            // Focus in the panel would fall to the body as it hides: it goes to the toggle that opens it again
            if (panelEl()?.contains(document.activeElement)) {
                document
                    .querySelector(`[${Const.attributes.editPanelToggle}]`)
                    ?.focus({ preventScroll: true })
            }
        }
        open = value
        writeStoredFields(Const.localStore.assetDetailView, { editOpen: open })
        applyOpenState()
        dock.layOut()
        detailPanel.layOut()
        if (open && canEdit()) {
            // The Edit is what opening shows; a held `\` stays held
            editStage.releaseShowOriginal()
            startSession()
            const { assetId } = subject
            editStage
                .setEditing(true)
                .then((shown) => shown && afterShown(assetId))
        } else {
            // The stage shows the head as the last save left it
            const head = subject && latestHead.get(subject.assetId)
            if (head) {
                editStage.adoptEdit(subject.assetId, head, { present: false })
            }
            editStage.setEditing(false)
            stopSession()
        }
        push()
    }

    /** The session of the asset shown, picked up when one is still at work on it, else a new one reading the History */
    function startSession() {
        const { assetId, edit } = subject
        if (session?.assetId === assetId) {
            return
        }
        stopSession()
        session = sessions.get(assetId) ?? createSession(assetId, edit)
        sessions.set(assetId, session)
        const snapshot = session.snapshot()
        working = snapshot.recipe
        sessionRecipe = snapshot.recipe
        upright = upright ?? uprightOf(snapshot.state)
        unsubscribe = session.subscribe(onSessionChange)
        // An idle session kept for a render, or a new one, reads the History: it may have moved elsewhere meanwhile
        if (!snapshot.busy) {
            session.load()
        }
    }

    function stopSession() {
        unsubscribe?.()
        unsubscribe = null
        const left = session
        session = null
        sessionSnapshot = null
        working = null
        sessionRecipe = null
        if (left) {
            forgetWhenIdle(left.assetId)
        }
    }

    /** A session leaves the map once it is idle and not the one shown, so the next visit reads the History afresh */
    function forgetWhenIdle(assetId) {
        const kept = sessions.get(assetId)
        if (
            kept &&
            kept !== session &&
            !kept.isBusy() &&
            !renderTokens.has(assetId)
        ) {
            sessions.delete(assetId)
        }
    }

    /**
     * A new session of `assetId`, which leaves the map of sessions as soon as it is idle and not the one shown (stepping
     * away while it reads or saves, a failure, a conflict), so no idle session is kept but for a render
     */
    function createSession(assetId, edit) {
        const created = createEditSession({
            assetId,
            initial: edit,
            api: apiFor(assetId),
            onConflict: () => showWarningSnackBar(CONFLICT_MESSAGE),
            onFailure: (error, kind) => {
                const reading = kind === "load"
                console.error(
                    `Could not ${reading ? "read" : "save"} the Edit of ${assetId}: ${getHttpErrorMessage(error)}`,
                )
                showErrorSnackBar(
                    reading ? LOAD_FAILED_MESSAGE : SAVE_FAILED_MESSAGE,
                )
            },
            onSaved: (state) => saved(assetId, state),
        })
        created.subscribe((snapshot) => {
            if (!snapshot.busy) {
                forgetWhenIdle(assetId)
            }
        })
        return created
    }

    /** The Edit routes of an asset (`EditController`), each answering the state; a 409's state rejects as `conflictState` */
    function apiFor(assetId) {
        const url = `/api/edit/r/${context.getRepoId()}/asset/${assetId}`
        const answer = (request) =>
            request
                .then((response) => response.data)
                .catch((error) => {
                    const state = error.response?.data?.state
                    if (error.response?.status === 409 && state) {
                        const conflict = new Error(
                            error.response.data.error ?? "Conflict",
                        )
                        conflict.conflictState = state
                        throw conflict
                    }
                    throw error
                })
        return {
            load: () => answer(http.get(url)),
            addStep: ({ base, recipe }) =>
                answer(http.post(`${url}/steps`, { base, recipe })),
            moveHead: ({ base, seq }) =>
                answer(http.put(`${url}/head`, { base, seq })),
        }
    }

    /**
     * A session's writes were saved: the stage's badge and toggle follow the new head at once (and the stage shows it,
     * when the panel no longer draws this asset), and the render the server queued is waited for, after which the
     * grid's cells show the new Preview and the stage, with the panel closed, the Display image
     */
    function saved(assetId, state) {
        const head = headOf(state)
        latestHead.set(assetId, head)
        editStage.adoptEdit(assetId, head, { present: !drawsAsset(assetId) })
        if (subject?.assetId === assetId) {
            subject.edit = head
        }

        const token = (renderTokens.get(assetId)?.token ?? 0) + 1
        renderTokens.set(assetId, { token })
        refreshAfterRender({
            repoId: context.getRepoId(),
            assetId,
            head: state.head,
            isCancelled: () => renderTokens.get(assetId)?.token !== token,
        })
            .then((status) => {
                if (!status || renderTokens.get(assetId)?.token !== token) {
                    return
                }
                const rendered = {
                    ...latestHead.get(assetId),
                    rendered: status.rendered,
                    rev: status.rev,
                }
                latestHead.set(assetId, rendered)
                if (subject?.assetId === assetId) {
                    subject.edit = rendered
                }
                editStage.adoptEdit(assetId, rendered, {
                    present: !drawsAsset(assetId),
                })
            })
            .finally(() => {
                if (renderTokens.get(assetId)?.token === token) {
                    renderTokens.delete(assetId)
                    forgetWhenIdle(assetId)
                }
            })
    }

    /** Whether the panel draws `assetId` on the stage now */
    function drawsAsset(assetId) {
        return open && subject?.assetId === assetId && canEdit()
    }

    /**
     * The session changed: its own change of the Edit (an answer to Undo, Redo or a jump, a conflict's or a failure's
     * state) is drawn, ending a Crop tool session it overtakes; the panel's own changes not saved yet stay on screen
     */
    function onSessionChange(snapshot) {
        sessionSnapshot = snapshot
        if (snapshot.assetId !== subject?.assetId) {
            return
        }
        if (!upright) {
            upright = uprightOf(snapshot.state)
        }
        if (!sameRecipe(snapshot.recipe, sessionRecipe)) {
            sessionRecipe = snapshot.recipe
            if (!sameRecipe(snapshot.recipe, working)) {
                cropTool.close()
                working = snapshot.recipe
                present()
            }
        }
        push()
    }

    /** The options the stage draws the Edit with: the whole frame in the Crop tool, Before/after's divider */
    function liveOptions() {
        return {
            wholeFrame: cropTool.isOpen(),
            split: splitOn && !cropTool.isOpen() ? splitAt : null,
        }
    }

    /** Draws the Edit shown on the stage now, and reads its Histogram once drawn */
    function present() {
        if (!working || !drawsAsset(subject?.assetId)) {
            return
        }
        const { assetId } = subject
        editStage
            .setLive(working, liveOptions())
            .then(
                (shown) => shown && subject?.assetId === assetId && afterDraw(),
            )
    }

    /** Draws on the next frame, once however many changes come before it (a slider's drag) */
    function requestDraw() {
        if (drawScheduled) {
            return
        }
        drawScheduled = true
        nextFrame(() => {
            drawScheduled = false
            present()
        })
    }

    /** A drawing is in: its Histogram is read at once, and its clipping once the drawings rest */
    function afterDraw() {
        const renderer = editStage.renderer()
        if (!renderer || editStage.current()?.source !== "canvas") {
            return
        }
        histogram = renderer.readHistogram()
        push()
        clearTimeout(clippingTimer)
        clippingTimer = setTimeout(readClippingNow, CLIPPING_READ_DELAY_MS)
    }

    /** The measurement's levels and box, from the fragment (`ImageMeasurement`'s constants) */
    function clippingLevels() {
        const dataset = viewEl()?.dataset ?? {}
        return {
            fitBox: Number(dataset.measurementBox),
            clippedLevel: Number(dataset.clippedLevel),
            crushedLevel: Number(dataset.crushedLevel),
        }
    }

    /**
     * Reads the canvas's clipping: the fractions for the Histogram's corners and, while the blink is on, the mask. Not
     * with Before on the canvas or in the Crop tool, where the canvas holds more than the Edit's result
     */
    async function readClippingNow() {
        const canvas = canvasEl()
        if (
            !canvas ||
            canvas.hidden ||
            splitOn ||
            cropTool.isOpen() ||
            !drawsAsset(subject?.assetId)
        ) {
            return
        }
        const token = ++clippingToken
        let read
        try {
            read = await readClipping(canvas, clippingLevels())
        } catch (error) {
            console.warn(`Could not read the Edit's clipping: ${error.message}`)
            return
        }
        if (token !== clippingToken) {
            return
        }
        fractions = { blown: read.blown, crushed: read.crushed }
        if (clippingBlink) {
            paintClipping(read)
        }
        push()
    }

    /**
     * The Clipping blink's mask over the canvas, in the colors its stylesheet gives it (`color` the blown highlights,
     * `border-color` the crushed shadows), so the script holds no literal
     */
    function paintClipping(read) {
        const clippingEl = document.getElementById(CLIPPING_ID)
        if (!clippingEl) {
            return
        }
        const style = getComputedStyle(clippingEl)
        clippingEl.width = read.width
        clippingEl.height = read.height
        clippingEl.getContext("2d").putImageData(
            clippingMask(read, {
                blownColor: style.color,
                crushedColor: style.borderColor,
            }),
            0,
            0,
        )
        clippingEl.hidden = false
    }

    function hideClipping() {
        clearTimeout(clippingTimer)
        clippingToken++
        const clippingEl = document.getElementById(CLIPPING_ID)
        if (clippingEl) {
            clippingEl.hidden = true
        }
    }

    /** The Histogram's button: the Clipping blink on the stage, on or off */
    function toggleClippingBlink() {
        if (!drawsAsset(subject?.assetId)) {
            return
        }
        clippingBlink = !clippingBlink
        if (clippingBlink) {
            readClippingNow()
        } else {
            hideClipping()
        }
        push()
    }

    /** Whether a change may be made now: the Edit is shown, and no Undo, Redo or History jump is on its way */
    function changeable() {
        return Boolean(working) && !session?.isMoving()
    }

    /**
     * A change shown on the stage and not saved: a slider's drag, a curve point's, a gesture of the Crop tool. None while
     * a head move is on its way: it would be built on the Edit before the move
     */
    function preview(recipe) {
        if (!changeable()) {
            return
        }
        working = normalize(recipe)
        cropTool.sync()
        requestDraw()
        push()
    }

    /**
     * A change saved as one step, once shown: a slider's release, a button. In the Crop tool it joins the tool's
     * session, which Done saves as one step.
     */
    function commit(recipe) {
        if (!changeable() || !session) {
            return
        }
        cancelWaitingCommit()
        preview(recipe)
        if (!cropTool.isOpen()) {
            session.commit(working)
        }
    }

    /**
     * A change shown at once and saved once no other comes for a moment (`via` "key": a control's keys, so a held key is
     * one step; "click": a click on a slider's track, so a double-click's reset makes no step in between)
     */
    function commitSoon(recipe, via) {
        if (!changeable() || !session) {
            return
        }
        preview(recipe)
        commitWaiting = true
        clearTimeout(commitTimer)
        commitTimer = setTimeout(
            flushCommit,
            via === "key" ? KEY_COMMIT_DELAY_MS : CLICK_COMMIT_DELAY_MS,
        )
    }

    /** The change waiting (`commitSoon`) saved now: before a move of the head, a tool, or leaving the asset */
    function flushCommit() {
        clearTimeout(commitTimer)
        if (commitWaiting) {
            commitWaiting = false
            commit(working)
        }
    }

    function cancelWaitingCommit() {
        clearTimeout(commitTimer)
        commitWaiting = false
    }

    /** The component's changes of the Edit, made from the Edit shown (`adjust.js`), as one step */
    const changes = {
        slider: (key, value) => withSlider(working, key, value, upright),
        aspect: (aspect) => withAspect(working, aspect, upright),
        swap: () => swappedOrientation(working, upright),
        rotate: (direction) => rotated(working, direction, upright),
        flip: (axis) => flipped(working, axis, upright),
        keepInside: (on) => withKeepInside(working, on, upright),
        resetSection: (id) => resetSection(working, id, upright),
        resetAll: () => resetAll(working, upright),
    }

    /** The Edit shown with a change of `kind` made to it, not shown or saved: the Edit shown before the asset is drawn */
    function changeOf(kind, ...args) {
        return working && upright ? changes[kind](...args) : working
    }

    /** A drag of the slider `key` from the Edit shown (`adjust.sliderDrag`), or null before the asset is drawn */
    function dragOf(key) {
        return working && upright ? sliderDrag(working, key, upright) : null
    }

    function change(kind, ...args) {
        if (working && upright) {
            commit(changes[kind](...args))
        }
    }

    function undo() {
        if (session && !cropTool.isOpen()) {
            flushCommit()
            session.undo()
        }
    }

    function redo() {
        if (session && !cropTool.isOpen()) {
            flushCommit()
            session.redo()
        }
    }

    function jump(seq) {
        if (session && !cropTool.isOpen()) {
            flushCommit()
            session.jump(seq)
        }
    }

    /** Revert to original: the Original's Edit as one step, which Undo takes back */
    function revert() {
        if (working) {
            commit(normalize(null))
        }
    }

    /** `c` and the Crop button: opens the Crop tool on the Edit shown, or, open, is Done */
    function toggleCropTool() {
        if (!drawsAsset(subject?.assetId) || !upright) {
            return
        }
        if (cropTool.isOpen()) {
            finishCrop()
            return
        }
        if (!changeable()) {
            return
        }
        flushCommit()
        splitOn = false
        syncSplit()
        hideClipping()
        cropTool.open()
        present()
        push()
    }

    /** Done: the Crop tool closes and its session is saved as one step */
    function finishCrop() {
        if (!cropTool.isOpen()) {
            return
        }
        cropTool.close()
        present()
        if (session && working) {
            flushCommit()
            session.commit(working)
        }
        push()
    }

    /** Cancel: the Crop tool closes and the Edit it opened on is shown again */
    function cancelCrop() {
        const start = cropTool.startRecipe()
        if (!start) {
            return
        }
        cropTool.close()
        working = start
        present()
        push()
    }

    /** The Level button: opens the Crop tool if it is closed, and turns the Level tool on or off */
    function toggleLevel() {
        if (!cropTool.isOpen()) {
            toggleCropTool()
        }
        if (cropTool.isOpen()) {
            cropTool.setLevel(!cropTool.levelOn())
        }
    }

    /** `o` in the Crop tool */
    function cycleCropGuide() {
        if (cropTool.isOpen()) {
            cropTool.cycleGuide()
        }
    }

    /** `x` in the Crop tool: the lock's orientation swapped, as part of the tool's session */
    function swapCropAspect() {
        if (cropTool.isOpen() && working && upright) {
            preview(swappedOrientation(working, upright))
        }
    }

    /**
     * Straighten's Auto: the server levels the image's lines (`auto-straighten`) as the Edit shown turns and mirrors it; when
     * no lines agree on a level (`angle` null) Straighten stays and the snackbar says so
     */
    async function autoStraighten() {
        if (!drawsAsset(subject?.assetId) || autoBusy || !changeable()) {
            return
        }
        flushCommit()
        const { assetId } = subject
        autoBusy = true
        push()
        try {
            const response = await http.get(
                `/api/edit/r/${context.getRepoId()}/asset/${assetId}/auto-straighten`,
                { params: { recipe: JSON.stringify(working) } },
            )
            if (subject?.assetId !== assetId || !working) {
                return
            }
            if (response.data.angle === null) {
                showWarningSnackBar(NOTHING_TO_LEVEL_MESSAGE)
            } else {
                commit(
                    withSlider(
                        working,
                        "straighten",
                        response.data.angle,
                        upright,
                    ),
                )
            }
        } catch (error) {
            console.error(
                `Could not level ${assetId}: ${getHttpErrorMessage(error)}`,
            )
            showErrorSnackBar("Could not level the image")
        } finally {
            autoBusy = false
            push()
        }
    }

    /** Before/after: the divider over the stage, Before left of it */
    function toggleSplit() {
        if (!drawsAsset(subject?.assetId) || cropTool.isOpen()) {
            return
        }
        splitOn = !splitOn
        if (splitOn) {
            hideClipping()
        } else if (clippingBlink) {
            readClippingNow()
        }
        syncSplit()
        present()
        push()
    }

    function syncSplit() {
        const splitEl = document.getElementById(SPLIT_ID)
        if (!splitEl) {
            return
        }
        splitEl.hidden = !splitOn
        splitEl.style.setProperty("--edit-split", `${splitAt * 100}%`)
        document
            .getElementById(SPLIT_HANDLE_ID)
            ?.setAttribute("aria-valuenow", String(Math.round(splitAt * 100)))
    }

    function moveSplit(at) {
        splitAt = Math.min(1 - SPLIT_MARGIN, Math.max(SPLIT_MARGIN, at))
        syncSplit()
        requestDraw()
    }

    /** Before/after's divider: dragged with the pointer, or moved with the arrows */
    function bindSplit(fragmentEl) {
        const handleEl = fragmentEl.querySelector(`#${SPLIT_HANDLE_ID}`)
        const splitEl = fragmentEl.querySelector(`#${SPLIT_ID}`)
        if (!handleEl || !splitEl) {
            return
        }
        let dragging = false
        handleEl.addEventListener("pointerdown", (event) => {
            if (event.button !== 0) {
                return
            }
            dragging = true
            handleEl.setPointerCapture(event.pointerId)
            event.preventDefault()
        })
        handleEl.addEventListener("pointermove", (event) => {
            if (!dragging) {
                return
            }
            const box = splitEl.getBoundingClientRect()
            moveSplit((event.clientX - box.left) / box.width)
        })
        const end = () => (dragging = false)
        handleEl.addEventListener("pointerup", end)
        handleEl.addEventListener("pointercancel", end)
        handleEl.addEventListener("keydown", (event) => {
            const step = {
                ArrowLeft: -SPLIT_KEY_STEP,
                ArrowRight: SPLIT_KEY_STEP,
            }[event.key]
            if (step) {
                event.preventDefault()
                moveSplit(splitAt + step)
            }
        })
    }

    /**
     * The least the panel and the stage may be squeezed to along the axis, in px: the `core.css` tokens, registered as
     * lengths; the Detail panel, docked opposite, and its divider take what they take besides
     */
    function panelMinimums(vertical) {
        const styles = getComputedStyle(viewEl() ?? document.documentElement)
        const token = (name) => parseFloat(styles.getPropertyValue(name))
        const sizeOf = (id) => {
            const el = document.getElementById(id)
            return el ? (vertical ? el.offsetHeight : el.offsetWidth) : 0
        }
        return {
            panel: token(
                vertical ? "--edit-panel-min-height" : "--edit-panel-min-width",
            ),
            content:
                token(
                    vertical
                        ? "--detail-stage-min-height"
                        : "--detail-stage-min-width",
                ) +
                sizeOf("detailPanel") +
                sizeOf("detailPanelDivider"),
        }
    }

    /** What the component shows, read by its bindings */
    function snapshot() {
        const state = sessionSnapshot
        const ready = Boolean(
            open && canEdit() && working && upright && session,
        )
        return {
            open,
            ready,
            refusal: open ? refusalOf() : "",
            recipe: working,
            upright,
            head: state?.head ?? 0,
            steps: state?.steps ?? [],
            status: state?.status ?? "idle",
            moving: Boolean(state?.moving),
            canUndo: ready && !cropTool.isOpen() && Boolean(state?.canUndo),
            canRedo: ready && !cropTool.isOpen() && Boolean(state?.canRedo),
            edited: Boolean(working) && !isIdentity(working),
            cropToolOpen: cropTool.isOpen(),
            levelOn: cropTool.levelOn(),
            splitOn,
            clippingBlink,
            histogram,
            fractions,
            autoBusy,
        }
    }

    function push() {
        view?.sync(snapshot())
    }

    /** The component connected: it is synced now, and handed the actions it calls */
    function connect(connected) {
        view = connected
        push()
        return {
            disconnect: () => {
                if (view === connected) {
                    view = null
                }
            },
            preview,
            commit,
            commitSoon,
            change,
            changeOf,
            dragOf,
            undo,
            redo,
            jump,
            revert,
            toggleCropTool,
            toggleLevel,
            toggleSplit,
            toggleClippingBlink,
            autoStraighten,
            recipe: () => working,
        }
    }

    return {
        start,
        attach,
        showAsset,
        afterShown,
        toggle,
        toggleCropTool,
        cycleCropGuide,
        swapCropAspect,
        cropDone: finishCrop,
        undo,
        redo,
        isOpen: () => open,
    }
}
