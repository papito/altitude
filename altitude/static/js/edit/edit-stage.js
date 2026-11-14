/**
 * Asset detail's stage for a still image (`views/htmx/view_image_detail_modal.scala.html`): which
 * source fills the media box, and Show original (`CONTEXT.md`).
 *
 * The source:
 * - **The original** in the `<img>`: an asset that is not edited (its head step changes no pixel),
 *   and an edited one while Show original is on.
 * - **The Display image** in the `<img>` (`/content/r/:repo/display/:id?v=<rev>`): an edited asset
 *   whose head is rendered, its natural size the edited aspect.
 * - **The canvas** (`#editCanvas`), drawn by the WebGL renderer (`renderer.js`) from the original,
 *   fetched once per asset and decoded upright: an edited asset whose render is pending (the head is
 *   not the step rendered), one the server could not render (rendered, with no revision: the
 *   Display image is never asked for at revision 0), and any asset while the Edit panel draws it
 *   (`setEditing`, `setLive`). A canvas that cannot be drawn (no WebGL2, an original the browser
 *   cannot decode) falls back to the original, and a Display image that does not load to the canvas.
 *   The canvas is drawn at the device pixels of the box it is shown in, at most the size of what it
 *   shows (the crop, or the whole frame for the Crop tool, `wholeFrame`), from a source fitted to
 *   that size, and drawn again, refitted, when the stage's size changes; it is shown once drawn, so
 *   no earlier drawing is ever seen in the new box.
 *
 * Whatever is shown, the box is fitted to its size (`setAssetDetailSize`) and the Detail panel is
 * handed the upright original's size with the Edit the stage shows, if it changes a pixel
 * (`setShownSize`): its Dimensions row reads "W × H px · edited w × h" and its Face highlights are
 * mapped through the Edit's geometry (`geometry.mapBox`), or hidden while the whole frame is drawn.
 * The upright size is the decoded original's, or, for the Display image, the server's
 * (`uprightWidth`, `uprightHeight` of the Edit), since a browser turns the original by its EXIF
 * orientation while the stored size is the file header's.
 *
 * **Show original**, on an edited asset only, for every viewer: the toolbar's toggle
 * (`data-app-show-original-toggle`, `aria-pressed`), which stays on until pressed again or Asset
 * detail steps to another asset, or a change is drawn from the Edit panel (`setLive`); and `\` held
 * (`global.js`, `Const.events.showOriginalHeld`, handled in `listeners/edit.js`), for as long as it is
 * held, released too when the window loses focus. While it is on, the original is in the `<img>` at
 * the upright size, the Face highlights are unmapped, the Dimensions row has the original's size
 * alone and the title's badge (`data-app-edit-badge`) reads "Original" in place of "Edited". The
 * toggle, the badge and the key legend's `\` entry (`data-app-show-original-key`) are shown on an
 * edited asset alone.
 *
 * Every presentation has a token, so one overtaken by a later one (a step, a toggle, a new Edit)
 * changes nothing; whoever waits for the asset to be shown waits for the latest presentation
 * (`settled`), and learns from its own `isCurrent` whether the asset is still the one Asset detail
 * shows.
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

const CANVAS_ID = "editCanvas"
const STAGE_ID = "imageDetailStage"
// How long the stage waits for its size to settle before drawing the canvas again
const RESIZE_SETTLE_MS = 150

export const Source = Object.freeze({
    original: "original",
    display: "display",
    canvas: "canvas",
})

/**
 * The Edit at an asset's head as Asset detail's fragment and JSON carry it
 * (`EditController.headJson`: `head`, `rendered`, `rev`, `label`, `edited`, `width`, `height`,
 * `uprightWidth`, `uprightHeight`, `recipe`), from the fragment's `data-app-image-detail-edit`; null
 * for an asset never edited
 */
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

/** Whether the asset's head step changes a pixel */
export function isEdited(edit) {
    return Boolean(edit?.edited)
}

/** Whether the head's renditions are stored: rendered, at a revision (0 when the server could not render it) */
export function hasDisplayImage(edit) {
    return isEdited(edit) && edit.rendered === edit.head && edit.rev > 0
}

/**
 * The region the canvas shows of the upright original under `recipe`, in pixels: the crop (the
 * edited size), or the whole warped frame with `wholeFrame` (the Crop tool)
 */
function regionOf(upright, recipe, { wholeFrame = false } = {}) {
    return wholeFrame
        ? frameSize(upright.width, upright.height, recipe)
        : editedSize(upright.width, upright.height, recipe)
}

export function createEditStage({ context, detailPanel }) {
    // The asset shown: its ID, its Edit at the head, whether the account may edit it, the stored
    // size, the original's URL and its request's `isCurrent`
    let subject = null
    // Show original: the toolbar's toggle, and `\` held
    let originalToggled = false
    let originalHeld = false
    // The Edit panel: whether it is open, so the canvas draws every asset, and the Edit it draws now
    // with the renderer's options
    let editing = false
    let live = null
    // The original fetched and decoded for the canvas, once per asset: { assetId, bitmap: Promise }
    let decoded = null
    // The renderer of the fragment's canvas, made on the first draw
    let renderer = null
    // What the stage shows now, for a redraw on a resize: { source, recipe, upright }
    let shown = null
    // Why the canvas could not draw the asset shown (no WebGL2, an original the browser cannot decode), or null: the
    // Edit panel cannot edit it
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
        // Closing Asset detail lets the decoded original go: on the change from Asset detail to
        // another host or none alone, so an open of Asset detail while another host is active
        // keeps its presentation. In a microtask, outside the effect's tracking
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

    /**
     * A new Asset detail fragment arrived: its canvas is a new element, so the renderer of the last
     * one goes, and the stage's size is watched so the canvas is drawn again at the size shown
     */
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
     * Shows the still image `assetId` (Asset detail's coordinator, `detail-navigator.js`): its
     * `edit` (`parseEdit`'s shape, or null), whether the account may edit it (`editable`, which the
     * Edit panel reads through `current`), its stored `width` × `height` and its original's `url`;
     * `isCurrent` tells whether the request is still the latest. Stepping to another asset releases
     * the Show original toggle and drops the last asset's decoded original. Resolves once the latest
     * presentation of the stage has settled, to whether the asset is still the one Asset detail
     * shows; rejects when not even the original could be loaded.
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
     * Waits for the stage's latest presentation, following a later one that takes over, and answers
     * whether the asset of `isCurrent` is still shown; a failure of the latest is thrown while it is
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

    /** The shown asset's presentation awaited for a change of the stage's own, a failure logged: the stage keeps what it shows */
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

    /** The Edit drawn now: the Edit panel's, or the head's */
    function recipeShown() {
        return (
            live?.recipe ??
            (subject?.edit ? normalize(subject.edit.recipe) : null)
        )
    }

    function showsOriginal() {
        return originalToggled || originalHeld
    }

    /** The source the stage calls for now */
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
     * The upright original's size as the server read it (the Edit's `uprightWidth` and
     * `uprightHeight`), or the stored size when the asset has no Edit or the server could not read it
     */
    function storedUpright() {
        const { edit, width, height } = subject
        return edit?.uprightWidth > 0 && edit?.uprightHeight > 0
            ? { width: edit.uprightWidth, height: edit.uprightHeight }
            : { width, height }
    }

    /** Starts a presentation that overtakes any on its way */
    function present() {
        const token = ++presentToken
        latest = presentNow(token)
        return latest
    }

    /**
     * Fills the stage with the source called for, falling back from the Display image to the
     * canvas and from the canvas to the original, then fits the box and tells the Detail panel.
     * Resolves to whether this presentation is still the latest; a load overtaken by a later one
     * (an AbortError of `setMediaSrcAndWait`) is no failure.
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

        // The Display image's natural size is at most 3840 on its long edge, so the upright size is the server's
        const upright =
            source === Source.display ? storedUpright() : result.upright
        const recipe = source === Source.original ? null : result.recipe
        const options = source === Source.canvas ? (live?.options ?? {}) : {}
        shown = { source, recipe, upright }
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
     * Loads `url` into the `<img>` and shows it, hiding the canvas: its natural size is both the
     * box's and, for the original, the upright size; `fallback` stands in for an image that reports
     * none. Null when overtaken.
     */
    async function showInImg(url, current, fallback) {
        const img = imgEl()
        const known =
            img.getAttribute("src") === url && img.complete && img.naturalWidth
        if (!known) {
            await setMediaSrcAndWait({ mediaEl: img, url })
        }
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

    /** The original, fetched and decoded upright once per asset */
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

    /** Lets the decoded original go, and clears the canvas, so nothing of the last asset is drawn again */
    function dropDecoded() {
        if (!decoded) {
            return
        }
        renderer?.clearSource()
        decoded.bitmap.then((bitmap) => bitmap.close()).catch(() => {})
        decoded = null
    }

    /**
     * The canvas's draw size for a `region`: the box the stage fits it into (at the region's aspect,
     * as the stylesheet fits the media) in device pixels, at most the region; null while the stage
     * has no size, when a draw would be at the region's full size for nothing to see
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
     * Draws the Edit shown into the canvas from `bitmap` and shows it, hiding the `<img>`: the box
     * takes the region's size, the source is fitted to the output's long edge first (the upright
     * original's long edge in the proportion of the output's to the region's), and the Edit is read
     * after that wait, so a change made meanwhile is the one drawn. Nothing is drawn while the stage
     * has no size: the resize that gives it one draws. Answers the box's size and the Edit drawn,
     * or null when overtaken.
     */
    async function drawCanvas(bitmap, current) {
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
        imgEl().hidden = true
        if (output) {
            renderer.render(recipe, { ...output, ...options })
            canvasEl().hidden = false
        }
        return { size: region, recipe, upright }
    }

    /** The Edit drawn on the canvas from the decoded original, the renderer made on the first draw. Null when overtaken. */
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

    /**
     * Draws the canvas again at the size the stage gives it now, the source refitted, when it shows
     * the Edit; a presentation that starts meanwhile takes over
     */
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
     * The toggle, the badge and the key legend shown on an edited asset alone; the toggle pressed
     * while it is on (a held `\` leaves it as it is), and the badge saying what the stage shows
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

    /** Whether Show original can act: an edited asset is shown */
    function canShowOriginal() {
        return Boolean(subject) && isEdited(subject.edit)
    }

    /** The toolbar's toggle: the original until pressed again or Asset detail steps to another asset */
    function toggleShowOriginal() {
        if (!canShowOriginal()) {
            return
        }
        originalToggled = !originalToggled
        presentAgain()
    }

    /**
     * The Show original toggle released, as the Edit panel opens, so the Edit is what the stage shows; a held `\` stays
     * held. Nothing is presented: the opening presents.
     */
    function releaseShowOriginal() {
        originalToggled = false
        syncControls()
    }

    /** `\` held or released: the original for as long as it is held */
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
     * The Edit panel opened or closed: while it is open the canvas draws every asset, from the
     * head's Edit until `setLive` gives another. Resolves as `showImage` does; with `present`
     * false nothing is drawn now, for a caller about to show another asset, which draws.
     */
    function setEditing(open, { present = true } = {}) {
        editing = open
        if (!open) {
            live = null
        }
        return present ? presentAgain() : Promise.resolve(false)
    }

    /**
     * The Edit panel's Edit, drawn on the canvas (null: back to the head's source), with the
     * renderer's options (`wholeFrame` for the Crop tool, `split` for Before/after). A change
     * releases the Show original toggle, so the change is seen. The decoded original and the
     * fitted source are kept, so a change on the canvas is drawn within a few microtasks. Resolves
     * as `showImage` does.
     */
    function setLive(recipe, options = {}) {
        live = recipe ? { recipe: normalize(recipe), options } : null
        originalToggled = false
        return presentAgain()
    }

    /**
     * The asset's Edit at its head changed (a step saved, Undo, a render done), in
     * `parseEdit`'s shape: the stage shows it as its source now calls for. With `present` false
     * (the Edit panel draws its own Edit meanwhile) only the toggle, the badge and the key legend
     * follow it, and the next presentation shows it.
     */
    function adoptEdit(assetId, edit, { present = true } = {}) {
        if (subject?.assetId !== assetId) {
            return Promise.resolve(false)
        }
        subject = { ...subject, edit }
        if (!present) {
            syncControls()
            return Promise.resolve(true)
        }
        return presentAgain()
    }

    /**
     * What the stage shows: the asset, its Edit at the head, whether the account may edit it, the
     * source, the Edit drawn, the upright size, and whether the canvas failed to draw the asset
     * (`drawFailed`, which the Edit panel cannot edit); null before an asset or once Asset detail
     * closed
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
                  showsOriginal: showsOriginal(),
              }
            : null
    }

    /** The renderer of the fragment's canvas, made when the stage first drew into it; null until then */
    function rendererOf() {
        return renderer
    }

    /** Asset detail closed, or shows a Video: nothing of the asset is kept, and the canvas is hidden */
    function clear() {
        canvasEl()?.setAttribute("hidden", "")
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
