/**
 * Asset detail's stage for a still image: which source fills the media box, and Show original.
 *
 * The source is the original in the `<img>` (not edited, or Show original on), the Display image in
 * the `<img>` (edited and rendered at its edit version), or the WebGL canvas drawn from the original
 * (a render pending or failed, and every asset while the Edit panel is open). A canvas that cannot
 * draw falls back to the original, and a Display image that does not load to the canvas.
 *
 * Every presentation has a token, so one overtaken by a later one changes nothing.
 */
import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import {
    getActiveModalHost,
    ModalHost,
    setAssetDetailSize,
} from "../common/modal.js"
import { displayUrl } from "../common/content-urls.js"
import { setMediaSrcAndWait } from "../search-results/detail-navigator.js"
import { editedSize, frameSize, outputSize } from "./geometry.js"
import { isIdentity, normalize } from "./recipe.js"
import { createRenderer, decodeUpright } from "./renderer.js"

const ORIENTATION_REFUSAL =
    "This browser turns this photo otherwise than the server, so it cannot draw its Edit"

const CANVAS_ID = "editCanvas"
const STAGE_ID = "imageDetailStage"
const RESIZE_SETTLE_MS = 150

export const Source = Object.freeze({
    original: "original",
    display: "display",
    canvas: "canvas",
})

/** An asset's Edit from its JSON (`EditController.stateJson`); null for an asset never edited */
export function parseEdit(json) {
    if (!json) {
        return null
    }
    try {
        return JSON.parse(json)
    } catch (error) {
        console.error("The asset's Edit is not JSON", error)
        return null
    }
}

/** Whether the asset's Edit changes a pixel */
export function isEdited(edit) {
    return Boolean(edit?.edited)
}

/** Whether the edited images are stored: rendered at the edit version, and rev 0 means the server could not render it */
export function hasDisplayImage(edit) {
    return (
        isEdited(edit) &&
        edit.renderedVersion === edit.editVersion &&
        edit.rev > 0
    )
}

/** The region of the upright original the canvas shows: the crop, or the whole warped frame for the Crop tool */
function regionOf(upright, recipe, { wholeFrame = false } = {}) {
    return wholeFrame
        ? frameSize(upright.width, upright.height, recipe)
        : editedSize(upright.width, upright.height, recipe)
}

export function createEditStage({ context, detailPanel }) {
    // The asset shown, as `showImage` received it
    let subject = null
    // Show original: the toolbar's toggle, and `\` held
    let originalToggled = false
    let originalHeld = false
    // Whether the Edit panel is open (the canvas then draws every asset), and the Edit it draws with
    // the renderer's options
    let editing = false
    let live = null
    // The original decoded for the canvas, once per asset: { assetId, bitmap: Promise }
    let decoded = null
    // Made on the first draw into the fragment's canvas
    let renderer = null
    // What the stage shows now, for a redraw on a resize: { source, recipe, upright, behind }
    let shown = null
    // Why the canvas could not draw the asset shown, or null; the Edit panel cannot edit such an asset
    let drawFailure = null
    // The latest presentation's token and its promise
    let presentToken = 0
    let latest = Promise.resolve(false)
    let resizeTimer = 0
    let resizeObserver = null

    /** Binds the toolbar's toggle, the release of a held `\` and the clearing on closing, once */
    function start() {
        document.addEventListener("click", (event) => {
            if (
                event.target instanceof Element &&
                event.target.closest(`[${Const.attributes.showOriginalToggle}]`)
            ) {
                toggleShowOriginal()
            }
        })
        // A key released while the window had no focus sends no keyup
        window.addEventListener("blur", () => holdOriginal(false))
        // Clears only on the change away from Asset detail, so showing an asset while another host is
        // active keeps its presentation; in a microtask, outside the effect's tracking
        let wasActive = false
        Alpine.effect(() => {
            const active = getActiveModalHost() === ModalHost.assetDetail
            if (wasActive && !active) {
                queueMicrotask(clear)
            }
            wasActive = active
        })
        syncControls()
    }

    /** A new fragment's canvas is a new element: the last renderer goes, and the stage's size is watched for redraws */
    function attach(fragmentEl) {
        renderer?.destroy()
        renderer = null
        shown = null
        resizeObserver?.disconnect()
        const stageEl = fragmentEl.querySelector(`#${STAGE_ID}`)
        if (stageEl && "ResizeObserver" in window) {
            resizeObserver = new ResizeObserver(redrawWhenSettled)
            resizeObserver.observe(stageEl)
        }
    }

    function canvasEl() {
        return document.getElementById(CANVAS_ID)
    }

    function imgEl() {
        return document.querySelector(`#${STAGE_ID} img`)
    }

    /**
     * Shows a still image. Stepping to another asset releases the Show original toggle and drops the
     * last decoded original. Resolves, once the latest presentation settles, to whether the asset is
     * still the one shown; rejects when not even the original loads.
     */
    function showImage({
        assetId,
        edit,
        editable = false,
        width,
        height,
        url,
        isCurrent,
    }) {
        if (subject?.assetId !== assetId) {
            originalToggled = false
            live = null
            shown = null
            drawFailure = null
            dropDecoded()
        }
        subject = { assetId, edit, editable, width, height, url, isCurrent }
        present()
        return settled(isCurrent)
    }

    /**
     * Waits for the latest presentation, following any later one that takes over, and answers whether
     * `isCurrent`'s asset is still shown; the latest's failure is thrown while that asset is current
     */
    async function settled(isCurrent) {
        for (;;) {
            const awaited = latest
            try {
                await awaited
            } catch (error) {
                if (awaited === latest && isCurrent()) {
                    throw error
                }
            }
            if (awaited === latest || !isCurrent()) {
                return isCurrent()
            }
        }
    }

    /** Presents the shown asset again after a change of the stage's own; a failure is logged and the stage keeps what it shows */
    function presentAgain() {
        if (!subject) {
            return Promise.resolve(false)
        }
        const { isCurrent } = subject
        present()
        return settled(isCurrent).catch((error) => {
            console.error("Could not show the asset's image", error)
            return false
        })
    }

    /** The Edit drawn now: the Edit panel's, or the asset's */
    function recipeShown() {
        return (
            live?.recipe ??
            (subject?.edit ? normalize(subject.edit.recipe) : null)
        )
    }

    function showsOriginal() {
        return originalToggled || originalHeld
    }

    function sourceNow() {
        const { edit } = subject
        if (live || editing) {
            return showsOriginal() && isEdited(edit)
                ? Source.original
                : Source.canvas
        }
        if (!isEdited(edit) || showsOriginal()) {
            return Source.original
        }
        return hasDisplayImage(edit) ? Source.display : Source.canvas
    }

    /**
     * The upright original's size as the server read it, or null when the asset has no Edit yet or the
     * server could not read it. The stored size is the file header's, before the EXIF orientation.
     */
    function serverUpright() {
        const { edit } = subject
        return edit?.uprightWidth > 0 && edit?.uprightHeight > 0
            ? { width: edit.uprightWidth, height: edit.uprightHeight }
            : null
    }

    /** The upright original's size as the server read it, or the stored size when it read none */
    function storedUpright() {
        return (
            serverUpright() ?? { width: subject.width, height: subject.height }
        )
    }

    /**
     * Throws when the browser decoded the original a quarter turn from the server's upright size: an
     * Edit's geometry is in the server's upright frame, so the canvas would draw it on the wrong one.
     * The error's `refusal` says why the Edit panel cannot edit the asset.
     */
    function checkOrientation(bitmap) {
        const expected = serverUpright()
        if (
            expected &&
            expected.width !== expected.height &&
            bitmap.width === expected.height &&
            bitmap.height === expected.width
        ) {
            const error = new Error(
                `The browser decoded the original as ${bitmap.width} × ${bitmap.height}, a quarter turn from the server's ` +
                    `upright ${expected.width} × ${expected.height}`,
            )
            error.refusal = ORIENTATION_REFUSAL
            throw error
        }
    }

    /** Starts a presentation that overtakes any on its way */
    function present() {
        const token = ++presentToken
        latest = presentNow(token)
        return latest
    }

    /**
     * Fills the stage with the source called for (falling back Display image, canvas, original), then
     * fits the box and tells the Detail panel. Resolves to whether this presentation is still the
     * latest; a load overtaken by a later one is no failure.
     */
    async function presentNow(token) {
        const current = () =>
            token === presentToken && subject !== null && subject.isCurrent()
        if (!subject) {
            return false
        }
        syncControls()

        let source = sourceNow()
        let result = null
        if (source === Source.display) {
            try {
                result = await showInImg(
                    displayUrl(
                        context.getRepoId(),
                        subject.assetId,
                        subject.edit.rev,
                    ),
                    current,
                    { width: subject.edit.width, height: subject.edit.height },
                )
            } catch (error) {
                if (!current()) {
                    return false
                }
                console.warn(
                    "The Display image did not load; the Edit is drawn from the original",
                    error,
                )
                source = Source.canvas
            }
        }
        if (source === Source.canvas && current()) {
            try {
                result = await showInCanvas(current)
            } catch (error) {
                if (!current()) {
                    return false
                }
                console.warn(
                    "The Edit could not be drawn; the original is shown",
                    error,
                )
                drawFailure = error
                source = Source.original
            }
        }
        if (source === Source.original && current()) {
            try {
                result = await showInImg(subject.url, current, storedUpright())
            } catch (error) {
                if (!current()) {
                    return false
                }
                throw error
            }
        }

        if (!result || !current()) {
            return false
        }

        // The Display image is downscaled (long edge at most 3840), so the upright size is the server's
        const upright =
            source === Source.display ? storedUpright() : result.upright
        const recipe = source === Source.original ? null : result.recipe
        const options = source === Source.canvas ? (live?.options ?? {}) : {}
        shown = { source, recipe, upright, behind: Boolean(result.behind) }
        setAssetDetailSize(result.size)
        detailPanel.setShownSize({
            ...upright,
            // Only an Edit that changes a pixel is shown in the Dimensions row and maps the Face highlights
            edit:
                recipe && !isIdentity(recipe)
                    ? {
                          recipe,
                          ...editedSize(upright.width, upright.height, recipe),
                      }
                    : null,
            // The Crop tool shows the whole frame, where no Face highlight is drawn
            highlights: !options.wholeFrame,
        })
        syncControls()
        return true
    }

    /**
     * Loads `url` into the `<img>` and shows it, hiding the canvas. Its natural size is the box's and,
     * for the original, the upright size; `fallback` stands in when it reports none. Null when overtaken.
     */
    async function showInImg(url, current, fallback) {
        const img = imgEl()
        await setMediaSrcIfNew(img, url)
        if (!current()) {
            return null
        }
        canvasEl()?.setAttribute("hidden", "")
        img.hidden = false
        const size =
            img.naturalWidth && img.naturalHeight
                ? { width: img.naturalWidth, height: img.naturalHeight }
                : fallback
        return {
            size,
            upright: size,
            recipe: subject.edit ? normalize(subject.edit.recipe) : null,
        }
    }

    /** The original, fetched and decoded upright, once per asset */
    function decodedOriginal() {
        const { assetId, url } = subject
        if (decoded?.assetId !== assetId) {
            dropDecoded()
            decoded = {
                assetId,
                bitmap: fetch(url, { credentials: "same-origin" })
                    .then((response) => {
                        if (!response.ok) {
                            throw new Error(
                                `The original answered ${response.status}`,
                            )
                        }
                        return response.blob()
                    })
                    .then(decodeUpright),
            }
            // A failed decode is tried again on the next presentation
            const attempt = decoded
            attempt.bitmap.catch(() => {
                if (decoded === attempt) {
                    decoded = null
                }
            })
        }
        return decoded.bitmap
    }

    /** Lets the decoded original go and clears the renderer's source, so nothing of the last asset is drawn again */
    function dropDecoded() {
        if (!decoded) {
            return
        }
        renderer?.clearSource()
        decoded.bitmap.then((bitmap) => bitmap.close()).catch(() => {})
        decoded = null
    }

    /**
     * The canvas's draw size for `region`: the box the stage fits it into, in device pixels, at most
     * the region; null while the stage has no size
     */
    function outputFor(region) {
        const stage = document.getElementById(STAGE_ID)?.getBoundingClientRect()
        if (!stage) {
            return null
        }
        const width = Math.min(
            stage.width,
            (stage.height * region.width) / region.height,
        )
        const height = (width * region.height) / region.width
        const devicePixels = Math.ceil(
            Math.max(width, height) * (window.devicePixelRatio || 1),
        )
        return devicePixels > 0
            ? outputSize(region.width, region.height, devicePixels)
            : null
    }

    /**
     * Draws the Edit shown from `bitmap` and shows the canvas. The source is fitted to the output
     * first and the Edit read after that wait, so a change made meanwhile is the one drawn. Nothing is
     * drawn while the stage has no size: the resize that gives it one draws. Null when overtaken.
     */
    async function drawCanvas(bitmap, current) {
        checkOrientation(bitmap)
        const upright = { width: bitmap.width, height: bitmap.height }
        const fitFor = regionOf(
            upright,
            recipeShown() ?? normalize(null),
            live?.options,
        )
        setAssetDetailSize(fitFor)
        const fitOutput = outputFor(fitFor)
        if (fitOutput) {
            await renderer.setSource(bitmap, {
                longEdge: Math.ceil(
                    (Math.max(upright.width, upright.height) *
                        Math.max(fitOutput.width, fitOutput.height)) /
                        Math.max(fitFor.width, fitFor.height),
                ),
            })
        }
        if (!current()) {
            return null
        }

        const recipe = recipeShown() ?? normalize(null)
        const options = live?.options ?? {}
        const region = regionOf(upright, recipe, options)
        setAssetDetailSize(region)
        const output = outputFor(region)
        if (output) {
            renderer.render(recipe, { ...output, ...options })
        }
        let behind = isBehindImage(recipe, options)
        if (behind) {
            try {
                await setMediaSrcIfNew(imgEl(), subject.url)
            } catch (error) {
                if (!current()) {
                    return null
                }
                console.warn(
                    "The original did not load; the canvas shows the Edit",
                    error,
                )
                behind = false
            }
            if (!current()) {
                return null
            }
        }
        const canvas = canvasEl()
        imgEl().hidden = !behind
        canvas.toggleAttribute(Const.attributes.editCanvasBehind, behind)
        if (output) {
            canvas.hidden = false
        }
        return { size: region, recipe, upright, behind }
    }

    /**
     * Whether the canvas draws `recipe` behind the original's `<img>`: the identity with no change on its way, outside
     * the Crop tool and Before/after. The browser color-manages the `<img>` while the canvas draws the values as sRGB, so
     * an unchanged asset looks as it does with the panel closed; the canvas behind still feeds the Histogram and Clipping.
     */
    function isBehindImage(recipe, options) {
        return (
            isIdentity(recipe) &&
            !options.wholeFrame &&
            (options.split ?? null) === null &&
            !options.changing
        )
    }

    /** Loads `url` into `img` unless it holds it loaded already */
    async function setMediaSrcIfNew(img, url) {
        if (!(
            img.getAttribute("src") === url &&
            img.complete &&
            img.naturalWidth
        )) {
            await setMediaSrcAndWait({ mediaEl: img, url })
        }
    }

    /** Draws the Edit on the canvas from the decoded original. Null when overtaken. */
    async function showInCanvas(current) {
        const bitmap = await decodedOriginal()
        if (!current()) {
            return null
        }
        const canvas = canvasEl()
        if (!canvas) {
            throw new Error("The stage has no canvas")
        }
        if (!renderer) {
            renderer = createRenderer(canvas, { onRestored: redraw })
        }
        return drawCanvas(bitmap, current)
    }

    /** Redraws the canvas at the stage's size now, when it shows the Edit; a presentation started meanwhile takes over */
    async function redraw() {
        if (
            !renderer ||
            !subject ||
            shown?.source !== Source.canvas ||
            !decoded
        ) {
            return
        }
        const token = presentToken
        const current = () =>
            token === presentToken && subject !== null && subject.isCurrent()
        try {
            await drawCanvas(await decoded.bitmap, current)
        } catch (error) {
            console.warn("The Edit could not be drawn again", error)
        }
    }

    function redrawWhenSettled() {
        clearTimeout(resizeTimer)
        resizeTimer = setTimeout(redraw, RESIZE_SETTLE_MS)
    }

    /**
     * The toggle, the badge and the key legend show on an edited asset alone; a held `\` leaves the
     * toggle unpressed, and the badge says what the stage shows
     */
    function syncControls() {
        const edited = isEdited(subject?.edit)
        const original =
            showsOriginal() || (shown && shown.source === Source.original)
        document
            .querySelectorAll(`[${Const.attributes.showOriginalToggle}]`)
            .forEach((buttonEl) => {
                buttonEl.hidden = !edited
                buttonEl.setAttribute(
                    "aria-pressed",
                    String(edited && originalToggled),
                )
            })
        document
            .querySelectorAll(`[${Const.attributes.editBadge}]`)
            .forEach((badgeEl) => {
                badgeEl.hidden = !edited
                badgeEl.textContent = original ? "Original" : "Edited"
            })
        document
            .querySelectorAll(`[${Const.attributes.showOriginalKey}]`)
            .forEach((keyEl) => (keyEl.hidden = !edited))
    }

    function canShowOriginal() {
        return Boolean(subject) && isEdited(subject.edit)
    }

    function toggleShowOriginal() {
        if (!canShowOriginal()) {
            return
        }
        originalToggled = !originalToggled
        presentAgain()
    }

    /** Releases the toggle as the Edit panel opens; a held `\` stays held. Presents nothing: the opening presents. */
    function releaseShowOriginal() {
        originalToggled = false
        syncControls()
    }

    function holdOriginal(held) {
        if (originalHeld === held) {
            return
        }
        originalHeld = held
        if (canShowOriginal()) {
            presentAgain()
        }
    }

    /**
     * The Edit panel opened or closed: while open the canvas draws every asset. With `present` false
     * nothing is drawn now, for a caller about to show another asset.
     */
    function setEditing(open, { present = true } = {}) {
        editing = open
        if (!open) {
            live = null
        }
        return present ? presentAgain() : Promise.resolve(false)
    }

    /**
     * Draws the Edit panel's Edit (null: back to the asset's source) with the renderer's options
     * (`wholeFrame` for the Crop tool, `split` for Before/after). Releases the Show original toggle so
     * the change is seen.
     */
    function setLive(recipe, options = {}) {
        live = recipe ? { recipe: normalize(recipe), options } : null
        originalToggled = false
        return presentAgain()
    }

    /**
     * The asset's Edit changed (a save, a render done). With `present` false (the Edit panel draws its
     * own meanwhile) only the controls follow it, and the next presentation shows it.
     */
    function adoptEdit(assetId, edit, { present = true } = {}) {
        if (subject?.assetId !== assetId) {
            return Promise.resolve(false)
        }
        // The first Edit to carry the server's upright size redraws the canvas, so the bitmap is checked against it
        const uprightLearned =
            !serverUpright() &&
            edit?.uprightWidth > 0 &&
            shown?.source === Source.canvas
        subject = { ...subject, edit }
        if (!present && !uprightLearned) {
            syncControls()
            return Promise.resolve(true)
        }
        return presentAgain()
    }

    /**
     * What the stage shows, or null before an asset or once Asset detail closed. The Edit panel cannot
     * edit a `drawFailed` asset; `drawRefusal` says why when the stage knows.
     */
    function current() {
        return subject
            ? {
                  assetId: subject.assetId,
                  edit: subject.edit,
                  editable: subject.editable,
                  source: shown?.source ?? null,
                  recipe: shown?.recipe ?? null,
                  upright: shown?.upright ?? null,
                  drawFailed: drawFailure !== null,
                  drawRefusal: drawFailure?.refusal ?? null,
                  showsOriginal: showsOriginal(),
                  behindImage: shown?.behind ?? false,
              }
            : null
    }

    function rendererOf() {
        return renderer
    }

    /** Asset detail closed, or shows a Video: nothing of the asset is kept, and the canvas is hidden */
    function clear() {
        canvasEl()?.setAttribute("hidden", "")
        canvasEl()?.removeAttribute(Const.attributes.editCanvasBehind)
        // A presentation still on its way changes nothing
        presentToken++
        latest = Promise.resolve(false)
        subject = null
        shown = null
        live = null
        drawFailure = null
        originalToggled = false
        originalHeld = false
        dropDecoded()
        syncControls()
    }

    return {
        start,
        attach,
        showImage,
        toggleShowOriginal,
        releaseShowOriginal,
        holdOriginal,
        setEditing,
        setLive,
        adoptEdit,
        current,
        renderer: rendererOf,
        redraw,
        clear,
    }
}
