/**
 * The Edit panel's coordinator in Asset detail; the `editPanel` Alpine component reads and writes through it
 * (`connectEditPanelView`). It owns the panel's open state and its dock (opposite the Detail panel), what the stage
 * draws while the panel is open, with the Histogram and clipping read from each drawing, and one Edit session per
 * asset. A session still saving when Asset detail steps away finishes in the background, and every state it adopts is
 * followed until the server has rendered it. Leaving an asset or closing the panel with a change pending asks to save,
 * discard or stay; leaving the page has the browser ask, and nothing is saved as the page goes.
 */
import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { clippingMask, readClipping } from "../common/clipping.js"
import { createDock } from "../common/dock.js"
import {
    closeModal,
    getActiveModalHost,
    ModalHost,
    setCloseGuard,
} from "../common/modal.js"
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
import { registerEditKeys } from "../common/edit-keys.js"
import { createCropTool, isCropToolOpen } from "./crop-tool.js"
import { createEditSession, isNewerEditState } from "./edit-session.js"
import {
    askToLeave,
    closeLeaveDialog,
    isLeaveDialogOpen,
    LeaveChoice,
} from "./leave-dialog.js"
import { isIdentity, normalize, sameRecipe } from "./recipe.js"
import { refreshAfterRender, swapGridPreviews } from "./render-status.js"
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
const LOAD_FAILED_MESSAGE = "Could not read the Edit"
// The warning's words: changes held, and a Done not answered yet
const HELD_MESSAGE = "The changes to this photo are not saved yet."
const SAVE_PENDING_MESSAGE = "The last change to this photo is not saved yet."
const BAR_ID = "editBar"
// How long a save on its way is waited for before the warning asks about it
const SAVE_SETTLE_MS = 1000
const NOTHING_TO_LEVEL_MESSAGE = "No straight lines to level the image by"
const AUTO_FAILED_MESSAGE = "Could not level the image"

// How long a change by a control's keys, or by a click on a slider's track, waits before it is one change, so a held
// key is one Undo step and a double-click's reset makes no step in between
const KEY_COMMIT_DELAY_MS = 400
const CLICK_COMMIT_DELAY_MS = 300
const NO_WEBGL_REFUSAL = "This browser cannot draw an Edit: it has no WebGL2"
const UNDECODABLE_REFUSAL = "The browser could not decode this image"

// The page's coordinator, which the Alpine component connects to
let controller = null

/**
 * Connects the Alpine component (`view`, with its `sync(snapshot)`), synced now and on every change; returns the
 * coordinator's actions and `disconnect`, or null on a page without a coordinator
 */
export function connectEditPanelView(view) {
    return controller?.connect(view) ?? null
}

/** The upright original's size as the server read it, from an Edit's state, or null when it carries none */
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
    // The Edit shown, and the last one the session showed, to tell a session's change (Undo, a conflict) from the
    // panel's own change on its way (a slider being dragged)
    let working = null
    let sessionRecipe = null
    let sessionSnapshot = null
    // The upright original's size: the server's (the Edit's `uprightWidth`, `uprightHeight`), or the canvas's once it drew
    let upright = null
    // A change shown and waiting to be one change (`commitSoon`)
    let commitTimer = 0
    let commitWaiting = false
    // Whether the Edit shown is a change on its way (a drag, a change waiting out its delay), not the session's yet
    let previewing = false
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
    // The latest Edit state each asset's session adopted or its render produced, dropped once Asset detail brings one
    // as new
    const latestEdit = new Map()
    // The render each asset waits for: { token, editVersion }, a token never reused
    const renderTokens = new Map()
    let renderFollows = 0
    let view = null
    // The pending answer of `beforeLeave` (a leave asked meanwhile does not wait for it), and whether Asset detail's
    // close was approved, so its guard lets it through
    let leaving = null
    let closeApproved = false

    const cropTool = createCropTool({
        recipe: () => working,
        upright: () => upright,
        preview,
        commit,
        commitSoon: (recipe) => commitSoon(recipe, "key"),
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

    /** Binds the page-wide controls once: the toolbar's toggle, the stage's bar, Asset detail's closing */
    function start() {
        controller = { connect }
        // How `global.js`'s Escape chain and keys reach the warning, the Crop tool and the changes held
        registerEditKeys({
            isLeaveDialogOpen,
            closeLeaveDialog,
            isCropToolOpen,
            hasHeldEdit: () => drawsAsset(subject?.assetId) && held(),
            escapeEdit,
        })
        dock.start()
        cropTool.start()
        detailPanel.onLayoutChange(() => {
            if (panelEl()) {
                dock.layOut()
            }
        })

        document.addEventListener("click", (event) => {
            if (!(event.target instanceof Element)) {
                return
            }
            if (event.target.closest(`[${Const.attributes.editPanelToggle}]`)) {
                toggle()
            } else if (event.target.closest(`[${Const.attributes.editDone}]`)) {
                done()
            } else if (
                event.target.closest(`[${Const.attributes.editCancel}]`)
            ) {
                cancel()
            }
        })

        // Closing Asset detail with a change pending asks first; a close that bypasses the guard (another dialog
        // replacing Asset detail) ends the asset below, saving held changes as Done
        setCloseGuard(ModalHost.assetDetail, guardClose)
        // Leaving the page asks too, also for a save another asset's session still has on its way
        window.addEventListener("beforeunload", (event) => {
            if (
                hasPending() ||
                [...sessions.values()].some((kept) => kept.hasUnsaved())
            ) {
                event.preventDefault()
                event.returnValue = ""
            }
        })
        window.addEventListener("pageshow", reloadSessionsOnShow)
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
     * Asset detail shows, or starts loading, an asset, before its media: what was open on the last asset ends, and
     * while the panel is open the stage draws the coming asset's Edit and its session starts
     */
    function showAsset({ assetId, mediaType, edit, editable, refusal }) {
        if (subject && subject.assetId !== assetId) {
            leaveAsset()
        }
        // An Edit from the server as new as the one a session left (saved elsewhere since, or read after it) replaces it
        const known = latestEdit.get(assetId)
        if (known && edit && !isNewerEditState(known, edit)) {
            latestEdit.delete(assetId)
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
     * The stage showed `assetId`: while the panel edits it, the canvas's size is the upright original's, a canvas that
     * could not draw makes the asset uneditable, and a session Edit the stage did not draw (one still saving) is drawn
     */
    function afterShown(assetId) {
        if (subject?.assetId !== assetId) {
            return
        }
        // A session answered after Asset detail read the asset: the stage shows its newer Edit
        const known = latestEdit.get(assetId)
        if (known && isNewerEditState(known, editStage.current()?.edit)) {
            subject.edit = known
            editStage.adoptEdit(assetId, known, {
                present: !drawsAsset(assetId),
            })
        }
        if (!open || !canEdit()) {
            return
        }
        const shown = editStage.current()
        if (refuseIfUndrawable(assetId)) {
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

    /**
     * A canvas that could not draw `assetId` (no WebGL2, an original the browser cannot decode or turns otherwise than the
     * server) makes it one the panel cannot edit, saying why; returns whether it did
     */
    function refuseIfUndrawable(assetId) {
        const shown = editStage.current()
        if (subject?.assetId !== assetId || !shown?.drawFailed) {
            return false
        }
        subject.undrawable =
            shown.drawRefusal ??
            (isRendererSupported() ? UNDECODABLE_REFUSAL : NO_WEBGL_REFUSAL)
        editStage.setEditing(false)
        stopSession()
        syncToggle()
        push()
        return true
    }

    /**
     * Ends what was open on the asset shown: the tools, Undo and Redo, Before/after and the blink. Changes still held,
     * which the warning did not ask about (another dialog replacing Asset detail), are saved as Done
     */
    function leaveAsset() {
        done()
        cropTool.close()
        session?.clearUndo()
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
     * The toolbar's toggle: on an asset no Edit may change, `aria-disabled` rather than disabled, so a keyboard or
     * screen reader still reaches the reason in its title
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
     * `e` (`fromKey`) and the toolbar's toggle: opens or closes the panel. On an asset it cannot edit, the toggle does
     * nothing (its title says why) and `e` says why in the snackbar
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
        if (open) {
            beforeLeave().then((ok) => ok && open && setOpen(false))
        } else {
            setOpen(true)
        }
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
            // The stage shows the Edit as the session last left it, when that is newer than the one it holds
            const latest = subject && latestEdit.get(subject.assetId)
            if (latest && isNewerEditState(latest, editStage.current()?.edit)) {
                editStage.adoptEdit(subject.assetId, latest, { present: false })
            }
            editStage.setEditing(false)
            stopSession()
        }
        push()
    }

    /** The session of the asset shown, picked up when one is still at work on it, else a new one reading the Edit */
    function startSession() {
        const { assetId, edit } = subject
        if (session?.assetId === assetId) {
            return
        }
        stopSession()
        session = sessions.get(assetId) ?? createSession(assetId, edit)
        sessions.set(assetId, session)
        const snapshot = session.snapshot()
        previewing = false
        working = snapshot.recipe
        sessionRecipe = snapshot.recipe
        upright = upright ?? uprightOf(snapshot.state)
        unsubscribe = session.subscribe(onSessionChange)
        // An idle session kept for a render, or a new one, reads the Edit: it may have been saved elsewhere meanwhile
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

    /** A session leaves the map once it is idle and not the one shown, so the next visit reads the Edit afresh */
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

    /** A new session of `assetId`, forgotten whenever it goes idle unless shown or following a render */
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
            onAdopted: (state) => adopted(assetId, state),
        })
        created.subscribe((snapshot) => {
            if (!snapshot.busy) {
                forgetWhenIdle(assetId)
            }
        })
        return created
    }

    /** The Edit routes of an asset, each resolving to the state; a 409's state rejects as `conflictState` */
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
            save: ({ base, recipe }) => answer(http.put(url, { base, recipe })),
        }
    }

    /**
     * A server state the session of `assetId` adopted, now the asset's latest Edit: the stage takes it at once (and
     * shows it, unless the panel draws this asset), and the grid's cells follow its render
     */
    function adopted(assetId, state) {
        if (isNewerEditState(latestEdit.get(assetId) ?? state, state)) {
            return
        }
        latestEdit.set(assetId, state)
        if (subject?.assetId === assetId) {
            subject.edit = state
        }
        editStage
            .adoptEdit(assetId, state, { present: !drawsAsset(assetId) })
            // The first state with the server's upright size has the stage check the canvas against it
            .then(() => drawsAsset(assetId) && refuseIfUndrawable(assetId))
        followRender(assetId, state)
    }

    /** The grid's cells and the stage follow the render of `state`'s edit version, once, however many times it is adopted */
    function followRender(assetId, state) {
        const followed = renderTokens.get(assetId)
        if (state.renderedVersion === state.editVersion) {
            // A wait for an earlier edit version ends: this one is rendered already
            if (followed) {
                renderTokens.delete(assetId)
                forgetWhenIdle(assetId)
            }
            swapGridPreviews({
                repoId: context.getRepoId(),
                assetId,
                rev: state.rev,
            })
            return
        }
        if (
            state.editVersion === 0 ||
            followed?.editVersion === state.editVersion
        ) {
            return
        }
        const token = ++renderFollows
        renderTokens.set(assetId, { token, editVersion: state.editVersion })
        refreshAfterRender({
            repoId: context.getRepoId(),
            assetId,
            editVersion: state.editVersion,
            isCancelled: () => renderTokens.get(assetId)?.token !== token,
        })
            .then((status) => {
                if (!status || renderTokens.get(assetId)?.token !== token) {
                    return
                }
                const rendered = {
                    ...latestEdit.get(assetId),
                    renderedVersion: status.renderedVersion,
                    rev: status.rev,
                }
                latestEdit.set(assetId, rendered)
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
     * The session changed: its own change of the Edit (Undo, Redo, Cancel, a conflict, a failure) is drawn; the panel's
     * own change on its way stays on screen
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
                cancelWaitingCommit()
                working = snapshot.recipe
                previewing = false
                cropTool.sync()
                present()
            }
        }
        push()
    }

    /**
     * The stage's drawing options; `changing` (a change shown, not yet the session's) keeps the identity mid-drag on
     * the canvas
     */
    function liveOptions() {
        return {
            wholeFrame: cropTool.isOpen(),
            split: splitOn && !toolOpen() ? splitAt : null,
            changing: previewing,
        }
    }

    /** Whether the Crop tool or the Level tool is up: Before/after is off meanwhile */
    function toolOpen() {
        return cropTool.isOpen() || cropTool.levelOn()
    }

    /**
     * Whether changes are held for Done or Cancel: the Edit shown is not the one saved, or a change waits out its delay
     */
    function held() {
        return Boolean(
            session &&
            working &&
            sessionSnapshot &&
            (commitWaiting || !sameRecipe(working, sessionSnapshot.saved)),
        )
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

    /** Whether a change may be made now: the Edit is shown */
    function changeable() {
        return Boolean(working)
    }

    /** A change shown on the stage and not saved: a slider's drag, a curve point's, a gesture of the Crop tool */
    function preview(recipe) {
        if (!changeable()) {
            return
        }
        previewing = true
        working = normalize(recipe)
        cropTool.sync()
        requestDraw()
        push()
    }

    /** A change made, once shown: a slider's release, a button, a gesture's end. It is held, one step of Undo, until Done */
    function commit(recipe) {
        if (!changeable() || !session) {
            return
        }
        cancelWaitingCommit()
        preview(recipe)
        previewing = false
        session.commit(working)
        push()
    }

    /**
     * A change shown at once and made once no other comes for a moment (`via` "key": a control's keys, so a held key is
     * one change; "click": a click on a slider's track, so a double-click's reset makes no step in between)
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

    /** The change waiting (`commitSoon`) made now: before Undo or Redo, Done, or leaving the asset */
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

    /** The component's changes of the Edit, made from the Edit shown (`adjust.js`), each one change */
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

    /** The Edit shown with a change of `kind` applied, neither shown nor saved; unchanged before the asset is drawn */
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
        if (session) {
            flushCommit()
            session.undo()
        }
    }

    function redo() {
        if (session) {
            flushCommit()
            session.redo()
        }
    }

    /** Revert to original: the identity as one change, held as any other, which Undo takes back */
    function revert() {
        if (working) {
            commit(normalize(null))
        }
    }

    /** `c` and the Crop button: opens the Crop tool on the Edit shown, or closes it; neither saves anything */
    function toggleCropTool() {
        if (!drawsAsset(subject?.assetId) || !upright) {
            return
        }
        if (cropTool.isOpen()) {
            cropTool.close()
        } else if (changeable()) {
            closeSplit()
            cropTool.open()
        }
        present()
        push()
    }

    /** Before/after and the Clipping mask go, while a tool puts more than the Edit's result on the canvas */
    function closeSplit() {
        splitOn = false
        syncSplit()
        hideClipping()
    }

    /** Done: the changes held saved as one change, the Crop and Level tools closed */
    function done() {
        if (!session) {
            return
        }
        flushCommit()
        const toolWasOpen = toolOpen()
        cropTool.close()
        previewing = false
        session.done()
        if (toolWasOpen) {
            present()
        }
        push()
    }

    /** Cancel: the Edit saved shown again, the Crop and Level tools closed */
    function cancel() {
        cancelWaitingCommit()
        cropTool.close()
        previewing = false
        session?.cancel()
        // A change only waiting out its delay was never the session's: the Edit the session shows is put back
        const shown = session?.snapshot().recipe
        if (shown) {
            working = shown
            sessionRecipe = shown
        }
        present()
        push()
    }

    /**
     * The panel's Escape (`global.js`, after the warning): the Level tool off, else the changes held cancelled, or the
     * warning about them, else the Crop tool closed; answers whether it took the key
     */
    function escapeEdit() {
        if (!drawsAsset(subject?.assetId)) {
            return false
        }
        if (cropTool.levelOn()) {
            cropTool.setLevel(false)
            push()
            return true
        }
        if (held()) {
            if (!leaving) {
                leaving = ask(HELD_MESSAGE)
                    .then((choice) => {
                        if (choice === LeaveChoice.save) {
                            done()
                        } else if (choice === LeaveChoice.discard) {
                            cancel()
                        }
                        return false
                    })
                    .finally(() => (leaving = null))
            }
            return true
        }
        if (cropTool.isOpen()) {
            toggleCropTool()
            return true
        }
        return false
    }

    /** Whether a change is pending: changes held, or a save the server has not answered */
    function hasPending() {
        return held() || Boolean(session?.hasUnsaved())
    }

    /**
     * Asked before Asset detail closes or steps away, or the panel closes: resolves true to go on. A save on its way
     * alone is waited for a moment first; a change still pending makes the warning ask. A leave asked while another is
     * being asked about stays.
     */
    function beforeLeave() {
        if (leaving) {
            return Promise.resolve(false)
        }
        if (!hasPending()) {
            return Promise.resolve(true)
        }
        leaving = decideLeave().finally(() => (leaving = null))
        return leaving
    }

    async function decideLeave() {
        if (!held()) {
            await Promise.race([
                session.settled(),
                new Promise((resolve) => setTimeout(resolve, SAVE_SETTLE_MS)),
            ])
            if (!hasPending()) {
                return true
            }
        }
        const holding = held()
        const choice = await ask(holding ? HELD_MESSAGE : SAVE_PENDING_MESSAGE)
        if (choice === LeaveChoice.stay) {
            return false
        }
        if (choice === LeaveChoice.save) {
            done()
        } else if (holding) {
            // The warning spoke of the changes held alone: a save on its way, which it did not mention, finishes
            cancel()
        } else {
            cancelWaitingCommit()
            session?.discard()
            working = session?.snapshot().recipe ?? working
            sessionRecipe = working
            previewing = false
        }
        return true
    }

    /**
     * The warning, asked with `message`: a change waiting out its delay waits on while it is open, so neither the save it
     * offers nor the discard is decided for it, and its delay starts again if the answer is to stay
     */
    async function ask(message) {
        clearTimeout(commitTimer)
        const choice = await askToLeave({ message })
        if (choice === LeaveChoice.stay && commitWaiting) {
            commitTimer = setTimeout(flushCommit, KEY_COMMIT_DELAY_MS)
        }
        return choice
    }

    /**
     * Asset detail's close guard (`modal.js`): with a change pending the close is held for the warning's answer, and the
     * guard answers a promise of whether Asset detail closed after all
     */
    function guardClose() {
        if (closeApproved || !hasPending()) {
            closeApproved = false
            return true
        }
        return beforeLeave().then((ok) => {
            if (ok && getActiveModalHost() === ModalHost.assetDetail) {
                closeApproved = true
                closeModal()
            }
            return ok
        })
    }

    /**
     * The page is shown again from the browser's cache (`pageshow`, `persisted`): every session reads the Edit again, which
     * may have been saved elsewhere while the page was away
     */
    function reloadSessionsOnShow(event) {
        if (event.persisted) {
            sessions.forEach((kept) => kept.load())
        }
    }

    /**
     * The Level (draw line) button: the Level tool on or off, in the Crop tool when it is open, else over the edited image;
     * the Crop tool stays as it is
     */
    function toggleLevel() {
        if (!drawsAsset(subject?.assetId) || !upright || !changeable()) {
            return
        }
        const on = !cropTool.levelOn()
        if (on) {
            closeSplit()
        }
        cropTool.setLevel(on)
        present()
        push()
    }

    /** `o` in the Crop tool */
    function cycleCropGuide() {
        if (cropTool.isOpen()) {
            cropTool.cycleGuide()
        }
    }

    /** `x` in the Crop tool: the lock's orientation swapped, one change */
    function swapCropAspect() {
        if (cropTool.isOpen() && working && upright) {
            commit(swappedOrientation(working, upright))
        }
    }

    /**
     * Auto-level: the server finds the Straighten that levels the image's lines under the Edit shown's turn and flips;
     * with no agreeing lines (`angle` null) nothing changes. A refusal the server explains (a 503 while every slot is
     * taken, a 400 for an unreadable original) is shown in its words
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
            const status = error?.response?.status
            const reason = error?.response?.data?.error
            if (status === 503 && reason) {
                showWarningSnackBar(reason)
            } else {
                showErrorSnackBar(
                    status === 400 && reason ? reason : AUTO_FAILED_MESSAGE,
                )
            }
        } finally {
            autoBusy = false
            push()
        }
    }

    /** Before/after: the divider over the stage, Before left of it */
    function toggleSplit() {
        if (!drawsAsset(subject?.assetId) || toolOpen()) {
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
            status: state?.status ?? "idle",
            canUndo: ready && Boolean(state?.canUndo),
            canRedo: ready && Boolean(state?.canRedo),
            held: ready && held(),
            heldCount: ready ? heldCountNow() : 0,
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
        const shown = snapshot()
        view?.sync(shown)
        syncBar(shown)
    }

    /**
     * The stage's bar: shown while changes are held or the Crop tool is open, the Crop tool's guide and size while it is,
     * and the count of the changes held and Done only while any are
     */
    function syncBar({ ready, held: holding, heldCount, cropToolOpen }) {
        const barEl = document.getElementById(BAR_ID)
        if (!barEl) {
            return
        }
        barEl.hidden = !ready || (!holding && !cropToolOpen)
        barEl.toggleAttribute("data-crop", cropToolOpen)
        const doneEl = barEl.querySelector(`[${Const.attributes.editDone}]`)
        if (doneEl) {
            doneEl.disabled = !holding
        }
        const countEl = barEl.querySelector(
            `[${Const.attributes.editHeldCount}]`,
        )
        if (countEl) {
            countEl.hidden = !holding
            countEl.textContent = `${heldCount} ${heldCount === 1 ? "edit" : "edits"} pending`
        }
    }

    /**
     * How many changes are held: the session's, and a change waiting out its delay or on its way (a drag); at least one
     * while any is held
     */
    function heldCountNow() {
        if (!held()) {
            return 0
        }
        const counted = sessionSnapshot?.heldCount ?? 0
        const onItsWay =
            commitWaiting ||
            (previewing && !sameRecipe(working, sessionSnapshot?.recipe))
        return Math.max(1, counted + (onItsWay ? 1 : 0))
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
            done,
            cancel,
            change,
            changeOf,
            dragOf,
            undo,
            redo,
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
        done,
        undo,
        redo,
        beforeLeave,
        isOpen: () => open,
    }
}
