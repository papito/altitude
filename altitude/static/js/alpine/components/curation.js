import { Alpine } from "../../lib/alpine.esm.min.js"
import { reportRestore } from "../../assets/asset-actions.js"
import { attachLoupe } from "../../common/loupe.js"
import { clippingMask, readClipping } from "../../common/clipping.js"
import {
    channelPaths,
    HISTOGRAM_CHANNELS,
    NO_PATHS,
    percentLabel,
} from "../../common/histogram.js"
import {
    closeModal,
    getActiveModalHost,
    ModalHost,
} from "../../common/modal.js"
import { showErrorSnackBar } from "../../common/snackbar.js"
import { isTextEditingTarget } from "../../common/text-editing.js"
import { Const } from "../../constants.js"
import { context } from "../../context.js"
import { getHttpErrorMessage, http } from "../../http/client.js"

/**
 * Curation's state, on the modal's shell so it outlives each set's fragment: the server renders a set,
 * this component swaps it into `#curationSet` and reads its frames from their `data-*`, and the
 * fragment's bindings read the state back. It opens on the queue, one Sequence, or a selection (whose
 * IDs are posted, never put in a URL); only the queue has a next set and a Sequence rail.
 *
 * On the queue the walk visits open rows only, and a row whose Sequence answers with no content becomes
 * done. The latest move wins: an overtaken read is aborted and dropped unseen. Writes and moves never
 * run at the same time, and a failed read leaves no set on show, so a saved outcome cannot be acted on
 * twice. Every action is saved as it happens, so closing loses only the undo entry.
 */

const Phase = {
    // No set has been read yet
    loading: "loading",
    set: "set",
    // A Sequence opened alone or a selection has too few frames left
    tooFew: "tooFew",
    // A selection or a lone Sequence was resolved: says what was kept and recycled
    report: "report",
    // Every row of the queue is done
    end: "end",
    // A read failed: no set is on show, and Try again repeats the read
    unread: "unread",
}

// How much of the pane a zoomed Face's box fills, so its surroundings stay in view
const ZOOMED_BOX_SHARE = 0.5

// The Loupe's ladder in original pixels per screen pixel (1 is 1:1); the wheel over a pane steps a rung
const LOUPE_LEVELS = [0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4]

const PINNED_IMAGE_ID = "curationStagePinned"
const FOCUSED_IMAGE_ID = "curationStageFocused"
const PINNED_LOUPE_ID = "curationLoupePinned"
const FOCUSED_LOUPE_ID = "curationLoupeFocused"
const HISTOGRAM_IDS = ["curationHistogramPinned", "curationHistogramFocused"]
const PINNED_CLIPPING_ID = "curationClippingPinned"
const FOCUSED_CLIPPING_ID = "curationClippingFocused"

// Each pane's Clipping canvas, by the ID of the pane's image
const CLIPPING_ID_OF = {
    [PINNED_IMAGE_ID]: PINNED_CLIPPING_ID,
    [FOCUSED_IMAGE_ID]: FOCUSED_CLIPPING_ID,
}

// What the next shell to load is opened on; the shell's request carries no parameters
let requestedEntry = {}

/** Names what the next Curation shell opens on: `{ sequenceId }`, `{ assetIds }`, or nothing for the queue */
export function requestCuration(entry) {
    requestedEntry = entry
}

function repoPath(prefix, rest) {
    return `/${prefix}/r/${context.getRepoId()}${rest}`
}

function fileUrl(frameId) {
    return frameId ? repoPath("content", `/file/${frameId}`) : null
}

function framesText(count) {
    return count === 1 ? "1 frame" : `${count} frames`
}

/** The Sequence rail's rows, in the order the queue is walked */
async function readQueueRows() {
    const response = await http.get(repoPath("htmx/curation", "/queue"), {
        responseType: "text",
    })
    return response.data
}

/** A Sequence's fragment, or null when it has fewer than two frames left; `signal` lets a later move abort it */
async function readSequenceSet(sequenceId, signal = undefined) {
    const response = await http.get(
        repoPath(
            "htmx/curation",
            `/sequence/${encodeURIComponent(sequenceId)}`,
        ),
        { responseType: "text", signal },
    )
    return response.status === 204 ? null : response.data
}

async function readSelectionSet(assetIds) {
    const response = await http.post(
        repoPath("htmx/curation", "/selection"),
        { assetIds: assetIds.join(",") },
        { responseType: "text" },
    )
    return response.data
}

/** Loads a frame's original ahead of its pane; its URL never changes, so the pane gets it from the cache */
function preloadFrame(frameId) {
    const image = new Image()
    image.src = fileUrl(frameId)
    return image
}

/** Loads the originals a set's fragment opens on, so the next set paints at once */
function preloadOpeningFrames(html) {
    const setEl = new DOMParser()
        .parseFromString(html, "text/html")
        .querySelector(`[${Const.attributes.openingFocusedId}]`)

    for (const frameId of [
        setEl?.dataset.openingPinnedId,
        setEl?.dataset.openingFocusedId,
    ]) {
        if (frameId) {
            preloadFrame(frameId)
        }
    }
}

/**
 * The transform, from the image's top left, that centers a Face's box in a pane under `object-fit:
 * contain`. It uses the image's natural size, upright like the box, not `asset.width` and `height`,
 * which come from the file's header.
 */
function zoomTransform(imageEl, box) {
    const paneWidth = imageEl.clientWidth
    const paneHeight = imageEl.clientHeight
    const { naturalWidth, naturalHeight } = imageEl
    const fit = Math.min(paneWidth / naturalWidth, paneHeight / naturalHeight)
    const scale = Math.max(
        1,
        ZOOMED_BOX_SHARE *
            Math.min(
                paneWidth / (box.width * fit),
                paneHeight / (box.height * fit),
            ),
    )
    const boxCenterX =
        (paneWidth - naturalWidth * fit) / 2 + (box.x + box.width / 2) * fit
    const boxCenterY =
        (paneHeight - naturalHeight * fit) / 2 + (box.y + box.height / 2) * fit
    const left = paneWidth / 2 - scale * boxCenterX
    const top = paneHeight / 2 - scale * boxCenterY

    return `translate(${left}px, ${top}px) scale(${scale})`
}

function validLoupeLevel(level) {
    return LOUPE_LEVELS.includes(level) ? level : LOUPE_LEVELS[0]
}

/** The Loupe level last chosen, else the first rung */
function rememberedLoupeLevel() {
    try {
        return validLoupeLevel(
            Number(localStorage.getItem(Const.localStore.loupeLevel)),
        )
    } catch {
        return LOUPE_LEVELS[0]
    }
}

/** The Bundle kinds whose details the rail shows, as last chosen, else none */
function rememberedDetailKinds() {
    try {
        const kinds = JSON.parse(
            localStorage.getItem(Const.localStore.curationDetails),
        )
        return Array.isArray(kinds) ? kinds : []
    } catch {
        return []
    }
}

function saveDetailKinds(kinds) {
    try {
        localStorage.setItem(
            Const.localStore.curationDetails,
            JSON.stringify(kinds),
        )
    } catch {
        // Nothing to do: the kinds hold for the session
    }
}

function saveLoupeLevel(level) {
    try {
        localStorage.setItem(Const.localStore.loupeLevel, String(level))
    } catch {
        // Nothing to do: the level holds for the session
    }
}

/** Whether the stage shows the Histogram strips, as last chosen, else shown */
function rememberedHistogramShown() {
    try {
        return (
            localStorage.getItem(Const.localStore.curationHistogram) !== "false"
        )
    } catch {
        return true
    }
}

function saveHistogramShown(shown) {
    try {
        localStorage.setItem(Const.localStore.curationHistogram, String(shown))
    } catch {
        // Nothing to do: the choice holds for the session
    }
}

/** Whether the Sequence rail shows, as last chosen, else shown */
function rememberedSequenceRailShown() {
    try {
        return (
            localStorage.getItem(Const.localStore.curationSequenceRail) !==
            "false"
        )
    } catch {
        return true
    }
}

function saveSequenceRailShown(shown) {
    try {
        localStorage.setItem(
            Const.localStore.curationSequenceRail,
            String(shown),
        )
    } catch {
        // Nothing to do: the choice holds for the session
    }
}

/**
 * Decodes `data-histogram`: Base64 of a byte per bin, the channels in `HISTOGRAM_CHANNELS` order, each
 * bin scaled by the server to the tallest of the four
 */
function histogramPaths(base64) {
    const bytes = Uint8Array.from(atob(base64), (char) => char.charCodeAt(0))
    const binCount = bytes.length / HISTOGRAM_CHANNELS.length
    return channelPaths(
        Object.fromEntries(
            HISTOGRAM_CHANNELS.map((channel, index) => [
                channel,
                bytes.subarray(index * binCount, (index + 1) * binCount),
            ]),
        ),
    )
}

/**
 * The mask's colors, from the canvas's computed `color` (blown) and `border-color` (crushed), which
 * resolve the theme tokens; read at each draw, so a mask follows the current theme
 */
function clippingColors(canvasEl) {
    const style = getComputedStyle(canvasEl)
    return { blownColor: style.color, crushedColor: style.borderColor }
}

/**
 * Puts a set's fragment in `#curationSet`, or empties it. The old fragment's Alpine trees are destroyed
 * before the state changes, so none of their loops runs on the new set's state (Alpine's observer would
 * destroy them only in a later microtask).
 */
function replaceSetHost(hostEl, html = "") {
    for (const childEl of [...hostEl.children]) {
        Alpine.destroyTree(childEl)
    }
    hostEl.innerHTML = html
}

/**
 * "shift+1" or "shift+2" for the 1 or 2 key with Shift, else null. Shift types a symbol that differs by
 * layout, so the key is told by its position; on AZERTY, whose digits are shifted, the press types the
 * digit and stays the Pick's key.
 */
function recycleKeyOf(event) {
    const digit = /^Digit([12])$/.exec(event.code)?.[1]
    return event.shiftKey && digit && event.key !== digit
        ? `shift+${digit}`
        : null
}

function faceBoxOf(personId, frameId) {
    const cropEl = document.querySelector(
        `#curationFaces [${Const.attributes.personId}="${CSS.escape(personId)}"][${Const.attributes.columnFrameId}="${CSS.escape(frameId)}"]`,
    )
    if (!cropEl) {
        return null
    }

    const [x, y, width, height] = cropEl.dataset.faceBox.split(",").map(Number)
    return { x, y, width, height }
}

export function curation() {
    // Closure state rather than component data: nothing reactive reads any of it
    let onKeydown = null
    let stageObserver = null
    // The Loupe over the set on show
    let loupe = null
    let isBusy = false
    let isClosed = false
    // The read Try again runs
    let retryRead = null
    // The focused frame's neighbours being preloaded, by frame ID
    const neighbourPreloads = new Map()
    // The measurement's levels and box from the set's root: `{ clippedLevel, crushedLevel, fitBox }`
    let measurement = null
    // The set's Clipping reads by image `currentSrc`, done or still a promise. A new map per set, so a
    // read finishing after its set has gone is told apart. Not reactive: a byte per pixel
    let clippingReads = new Map()
    // The Histogram paths of the set's frames, by frame ID, built once each. Not reactive: a few
    // kilobytes per frame that only its pane binds
    const histogramPathsOf = new Map()
    // The source each Clipping canvas holds the mask of, by the canvas's ID, so a mask is not drawn twice
    const drawnSrcs = new Map()
    let onQueueClick = null
    // What the user made of each set a move left, by Sequence, restored when the walk returns; dropped
    // once its row is done or the queue is read again
    const rememberedViews = new Map()
    // Each queue read takes a new token and aborts the one before; an older read that still answers
    // finds its token stale and drops it, so the last move is the set that opens
    let moveToken = 0
    let moveAbort = null
    // The moves reading a set, so no write starts while one is
    let movesInFlight = 0
    // The last move's direction, in which the next set is preloaded
    let lastDirection = 1
    // What the session leaves changed, net of its undos, for the grid beneath
    const recycledIds = new Set()
    const clearedSequenceIds = new Set()

    /** Preloads these frames and stops any others, so a held arrow key does not queue originals ahead of the one on show */
    function preloadOnly(frameIds) {
        for (const [frameId, image] of neighbourPreloads) {
            if (!frameIds.includes(frameId)) {
                image.src = ""
                neighbourPreloads.delete(frameId)
            }
        }
        for (const frameId of frameIds) {
            if (!neighbourPreloads.has(frameId)) {
                neighbourPreloads.set(frameId, preloadFrame(frameId))
            }
        }
    }

    function detachLoupe() {
        loupe?.detach()
        loupe = null
    }

    function announceClosed() {
        document.body.dispatchEvent(
            new CustomEvent(Const.events.curationClosed, {
                detail: {
                    recycledIds: [...recycledIds],
                    clearedSequenceIds: [...clearedSequenceIds],
                },
            }),
        )
    }

    function announceChanged() {
        document.body.dispatchEvent(
            new CustomEvent(Const.events.curationChanged),
        )
    }

    return {
        phase: Phase.loading,

        // The set shown, in rail order; a frame's `problems` lists its Bundle first, then its chips
        frames: [],
        // A frame's Bundle by ID; a Contender has none
        bundleOf: {},
        focusedId: null,
        pinnedId: null,
        pickIds: [],
        zoomPersonId: null,
        // The Bundle kinds whose reason lines the rail shows; held across sets and opens
        detailKinds: rememberedDetailKinds(),
        // A rung of `LOUPE_LEVELS`, held across sets and opens
        loupeLevel: rememberedLoupeLevel(),
        // The `src` each stage image last fired `load` or `error` for, by image ID; a pane is loading
        // until this is its frame's
        settledSrcs: {},
        // The `src` each stage image failed to decode, by image ID
        unreadableSrcs: {},
        // One switch for the whole stage; off on every open
        clippingBlink: false,
        // Held across opens
        showsHistogram: rememberedHistogramShown(),
        // Held across opens
        showsSequenceRail: rememberedSequenceRailShown(),

        // What Curation walks: the queue's IDs and position, else one Sequence or a selection
        queueIds: [],
        position: 0,
        sequenceId: null,
        selectionIds: null,
        // The queue's Sequences resolved, kept or gone under two frames. Reactive, since Previous and
        // Next read whether an open row is left on their side
        doneIds: new Set(),
        // The set on show's Sequence, which `position` runs ahead of while a move reads; null with no set
        // on show and on a selection
        shownSequenceId: null,

        // The last mutation, for Undo: a Resolve or Keep all (with `keepIds`), or a recycling, which keeps
        // the view its set was left in and, for a stage frame, the stage it left
        undoEntry: null,
        reportText: "",

        init() {
            const entry = requestedEntry
            requestedEntry = {}

            onKeydown = (event) => this.handleKey(event)
            document.addEventListener("keydown", onKeydown)
            stageObserver = new ResizeObserver(() => this.applyZoom())

            this.$watch("$store.modal.activeHost", (host) => {
                if (host !== ModalHost.curation) {
                    this.onClosed()
                }
            })
            // The rail follows the walk
            this.$watch("position", () => this.paintRail())
            onQueueClick = (event) => this.onQueueRowClick(event)
            document
                .getElementById("curationQueueHost")
                .addEventListener("click", onQueueClick)

            Alpine.store(Const.state.sequencesToCurate).refresh()

            if (entry.assetIds) {
                this.selectionIds = entry.assetIds
            } else if (entry.sequenceId) {
                this.sequenceId = entry.sequenceId
            }

            if (this.isQueue) {
                this.startQueue()
            } else {
                this.exclusively(() => this.openSet())
            }
        },

        destroy() {
            document.removeEventListener("keydown", onKeydown)
            document
                .getElementById("curationQueueHost")
                ?.removeEventListener("click", onQueueClick)
            stageObserver.disconnect()
            detachLoupe()
        },

        get isQueue() {
            return !this.sequenceId && !this.selectionIds
        },

        get showsSet() {
            return this.phase === Phase.set
        },

        get showsTooFew() {
            return this.phase === Phase.tooFew
        },

        get showsReport() {
            return this.phase === Phase.report
        },

        get showsEnd() {
            return this.phase === Phase.end
        },

        get showsUnread() {
            return this.phase === Phase.unread
        },

        // The report, too-few and unread states show their own Undo
        get showsHeaderUndo() {
            return (
                this.undoEntry !== null &&
                !this.showsReport &&
                !this.showsTooFew &&
                !this.showsUnread
            )
        },

        // Whether Previous and Next have an open row to go to
        get hasPreviousSequence() {
            return this.isQueue && this.openRowFrom(this.position - 1, -1) >= 0
        },

        get hasNextSequence() {
            return this.isQueue && this.openRowFrom(this.position + 1, 1) >= 0
        },

        get isAllDone() {
            return !Alpine.store(Const.state.sequencesToCurate).hasAny
        },

        // "4 of 12"; in the end state (position -1) it reads "12 of 12", and nothing with no rows
        get railPosition() {
            const count = this.queueIds.length
            if (count === 0) {
                return ""
            }
            const current = this.position < 0 ? count : this.position + 1
            return `${current} of ${count}`
        },

        // The pinned frame beside the focused one, unless they are the same
        get showsPinnedPane() {
            return this.pinnedId !== null && this.pinnedId !== this.focusedId
        },

        // The left pane's frame, or the only one on the stage
        get leftFrameId() {
            return this.showsPinnedPane ? this.pinnedId : this.focusedId
        },

        get rightFrameId() {
            return this.showsPinnedPane ? this.focusedId : null
        },

        // The Picks, else the frame alone on the stage. With two on the stage and no Pick nothing is kept,
        // so a frame is never recycled on a guess
        get keepIds() {
            if (this.pickIds.length > 0) {
                return this.pickIds
            }
            return this.showsPinnedPane ? [] : [this.focusedId].filter(Boolean)
        },

        // Resolve's label is in two parts, since only the part naming a frame truncates
        get keptLabel() {
            const kept = this.keepIds
            return `Keep ${kept.length === 1 ? this.fileNameOf(kept[0]) : kept.length}`
        },

        get recycleLabel() {
            return `, recycle ${this.frames.length - this.keepIds.length}`
        },

        // The footer reserves the note's row whenever this holds, so a Pick does not shrink the panes
        get hasFrameInAlbumOrLocation() {
            return this.frames.some((frame) => frame.inAlbumOrLocation)
        },

        // Empty while nothing is to be kept, since every frame would count
        get recycledInAlbumOrLocationNote() {
            if (this.keepIds.length === 0) {
                return ""
            }

            const count = this.frames.filter(
                (frame) =>
                    frame.inAlbumOrLocation && !this.keepIds.includes(frame.id),
            ).length

            if (count === 0) {
                return ""
            }
            return count === 1
                ? "1 frame to recycle is in an album or Location: recycling takes it out, and a restore does not add it back"
                : `${count} frames to recycle are in an album or Location: recycling takes them out, and a restore does not add them back`
        },

        fileUrl,

        /**
         * A pane reuses its image, and the browser keeps painting the old picture until the new one is in,
         * so a pane is loading from the moment its frame changes and never shows one frame under another's
         * name.
         */
        isPaneLoading(imageId, frameId) {
            return (
                frameId !== null &&
                this.settledSrcs[imageId] !== fileUrl(frameId)
            )
        },

        /** Whether the browser could not decode the pane's image for this frame */
        isImageUnreadable(imageId, frameId) {
            return (
                frameId !== null &&
                this.unreadableSrcs[imageId] === fileUrl(frameId)
            )
        },

        frameOf(frameId) {
            return this.frames.find((frame) => frame.id === frameId)
        },

        /** Empty paths for no frame or one not measured; they are the frame's, so they show while its image loads */
        histogramOf(frameId) {
            const histogram = this.frameOf(frameId)?.histogram
            if (!histogram) {
                return NO_PATHS
            }
            if (!histogramPathsOf.has(frameId)) {
                histogramPathsOf.set(frameId, histogramPaths(histogram))
            }
            return histogramPathsOf.get(frameId)
        },

        /** The strip's left corner, the fraction of pixels crushed; empty for a frame not measured */
        crushedLabel(frameId) {
            return this.frameOf(frameId)?.crushed ?? ""
        },

        /** The strip's right corner, the fraction of pixels blown; empty for a frame not measured */
        blownLabel(frameId) {
            return this.frameOf(frameId)?.blown ?? ""
        },

        showsClippingBlink(imageId, frameId) {
            return (
                this.clippingBlink &&
                !this.isPaneLoading(imageId, frameId) &&
                !this.isImageUnreadable(imageId, frameId)
            )
        },

        /**
         * An image's `load` or `error`. A failed image is noted as unreadable, having no pixels for the
         * blink. The Loupe re-reads once Alpine has shown the image, and the mask is drawn here, the one
         * moment a pane's picture changes.
         */
        onImageSettled(event) {
            const imageEl = event.target
            const src = imageEl.getAttribute("src")
            this.settledSrcs[imageEl.id] = src
            if (imageEl.naturalWidth > 0 && imageEl.naturalHeight > 0) {
                delete this.unreadableSrcs[imageEl.id]
            } else {
                this.unreadableSrcs[imageEl.id] = src
            }
            this.applyZoom()
            this.$nextTick(() => loupe?.refresh())
            if (this.clippingBlink) {
                this.drawClipping(imageEl.id)
            }
        },

        /**
         * The image's Clipping read, cached for the set by source; cached as its promise while in flight,
         * so a second draw awaits the same read. A failed read leaves the cache, so the next draw retries.
         */
        clippingOf(imageEl) {
            const src = imageEl.currentSrc
            const reads = clippingReads
            let read = reads.get(src)
            if (!read) {
                read = readClipping(imageEl, {
                    fitBox: measurement.fitBox,
                    clippedLevel: measurement.clippedLevel,
                    crushedLevel: measurement.crushedLevel,
                })
                reads.set(src, read)
                read.then(
                    (result) => reads.set(src, result),
                    () => reads.delete(src),
                )
            }
            return read
        },

        /**
         * Paints the pane's mask at the read's own size, which `object-fit: contain` lays over the picture
         * pixel for pixel. Any other frame's mask is blanked first. The read starts once Alpine has shown
         * the image, so its decode never holds up the paint, and paints only if the set, the image and the
         * blink are unchanged when it is in. A failed read (a tainted canvas) leaves the canvas blank.
         */
        async drawClipping(imageId) {
            const canvasEl = document.getElementById(CLIPPING_ID_OF[imageId])
            const imageEl = document.getElementById(imageId)
            if (!canvasEl || !imageEl) {
                return
            }

            const src = imageEl.currentSrc
            if (drawnSrcs.get(canvasEl.id) === src) {
                return
            }
            canvasEl.width = 0
            canvasEl.height = 0
            drawnSrcs.delete(canvasEl.id)
            if (!(imageEl.naturalWidth > 0 && imageEl.naturalHeight > 0)) {
                return
            }

            const reads = clippingReads
            let read
            try {
                await this.$nextTick()
                read = await this.clippingOf(imageEl)
            } catch (error) {
                console.debug(
                    `Could not read the clipping of a Curation frame: ${error.message}`,
                )
                return
            }
            // A second draw of the same source, awaiting the same read, may have painted meanwhile
            if (
                reads !== clippingReads ||
                imageEl.currentSrc !== src ||
                !this.clippingBlink ||
                drawnSrcs.get(canvasEl.id) === src
            ) {
                return
            }

            // Setting the size clears the bitmap
            canvasEl.width = read.width
            canvasEl.height = read.height
            canvasEl
                .getContext("2d")
                .putImageData(
                    clippingMask(read, clippingColors(canvasEl)),
                    0,
                    0,
                )
            drawnSrcs.set(canvasEl.id, src)
        },

        toggleClippingBlink() {
            this.clippingBlink = !this.clippingBlink
            if (this.clippingBlink) {
                for (const imageId of [PINNED_IMAGE_ID, FOCUSED_IMAGE_ID]) {
                    this.drawClipping(imageId)
                }
            }
        },

        /**
         * The strip is the blink's only switch, so hiding it turns the blink off and hands a strip's focus
         * to the shell. Does nothing with no set on show, where the change would not be seen.
         */
        toggleHistogram() {
            if (!this.showsSet) {
                return
            }

            this.showsHistogram = !this.showsHistogram
            saveHistogramShown(this.showsHistogram)
            if (!this.showsHistogram) {
                this.clippingBlink = false
                if (HISTOGRAM_IDS.includes(document.activeElement?.id)) {
                    document.getElementById("curation").focus()
                }
            }
        },

        fileNameOf(frameId) {
            return this.frameOf(frameId)?.fileName
        },

        problemsOf(frameId) {
            return this.frameOf(frameId)?.problems ?? []
        },

        isPick(frameId) {
            return this.pickIds.includes(frameId)
        },

        bundleHoldsPick(kind) {
            return this.pickIds.some((id) => this.bundleOf[id] === kind)
        },

        showsDetails(kind) {
            return this.detailKinds.includes(kind)
        },

        columnClasses(frameId) {
            return {
                focused: this.focusedId === frameId,
                pinned: this.pinnedId === frameId,
            }
        },

        keepLabel(frameId) {
            if (!this.showsPinnedPane) {
                return "Keep"
            }
            return frameId === this.leftFrameId ? "Keep left" : "Keep right"
        },

        keepKey(frameId) {
            return frameId === this.rightFrameId ? "2" : "1"
        },

        // The key that recycles a frame on the stage is its Keep key with Shift held
        recycleKey(frameId) {
            return `Shift+${this.keepKey(frameId)}`
        },

        focusFrame(frameId) {
            this.focusedId = frameId
            this.preloadNeighbours()

            // Its rail entry, and its column of the face strip, whose first match is the column's head
            for (const selector of [
                `#curationRail [${Const.attributes.frameId}="${CSS.escape(frameId)}"]`,
                `#curationFaces [${Const.attributes.columnFrameId}="${CSS.escape(frameId)}"]`,
            ]) {
                document
                    .querySelector(selector)
                    ?.scrollIntoView({ block: "nearest", inline: "nearest" })
            }
        },

        /** The focused frame's neighbour in rail order, skipping `exceptId` */
        neighbourOf(step, exceptId = null) {
            const from = this.frames.findIndex(
                (frame) => frame.id === this.focusedId,
            )
            for (
                let index = from + step;
                from >= 0 && index >= 0 && index < this.frames.length;
                index += step
            ) {
                const frameId = this.frames[index].id
                if (frameId !== exceptId) {
                    return frameId
                }
            }
            return null
        },

        moveFocus(step) {
            const frameId = this.neighbourOf(step)
            if (frameId) {
                this.focusFrame(frameId)
            }
        },

        /** Preloads the frames an arrow key goes to next */
        preloadNeighbours() {
            preloadOnly(
                [this.neighbourOf(-1), this.neighbourOf(1)].filter(Boolean),
            )
        },

        /** Pins the focused frame and moves focus on (next, else previous), so two frames stay on the stage */
        pinFocused() {
            if (!this.focusedId || this.focusedId === this.pinnedId) {
                return
            }

            this.pinnedId = this.focusedId
            const nextId = this.neighbourOf(1) ?? this.neighbourOf(-1)
            if (nextId) {
                this.focusFrame(nextId)
            }
        },

        togglePick(frameId) {
            this.pickIds = this.isPick(frameId)
                ? this.pickIds.filter((id) => id !== frameId)
                : [...this.pickIds, frameId]
        },

        toggleDetails(kind) {
            this.detailKinds = this.showsDetails(kind)
                ? this.detailKinds.filter((shown) => shown !== kind)
                : [...this.detailKinds, kind]
            saveDetailKinds(this.detailKinds)
        },

        zoomToPerson(personId) {
            this.zoomPersonId = this.zoomPersonId === personId ? null : personId
            this.applyZoom()
        },

        /**
         * Runs on a crop's click, on an image's load (the box is held against its natural size) and on a
         * pane's resize. A frame with no Face of the Person shows whole. The Clipping canvas takes the
         * image's transform, and the Loupe re-reads after it.
         */
        applyZoom() {
            const panes = [
                [PINNED_IMAGE_ID, this.pinnedId],
                [FOCUSED_IMAGE_ID, this.focusedId],
            ]
            for (const [imageId, frameId] of panes) {
                const imageEl = document.getElementById(imageId)
                if (!imageEl) {
                    continue
                }

                const box =
                    this.zoomPersonId &&
                    frameId &&
                    faceBoxOf(this.zoomPersonId, frameId)
                const transform =
                    box && imageEl.naturalWidth && imageEl.clientWidth
                        ? zoomTransform(imageEl, box)
                        : ""
                imageEl.style.transform = transform
                const canvasEl = document.getElementById(
                    CLIPPING_ID_OF[imageId],
                )
                if (canvasEl) {
                    canvasEl.style.transform = transform
                }
            }
            loupe?.refresh()
        },

        /** What the user made of the set on show, which a later read of the same set can keep */
        currentView() {
            return {
                pinnedId: this.pinnedId,
                focusedId: this.focusedId,
                pickIds: this.pickIds,
                zoomPersonId: this.zoomPersonId,
            }
        },

        /**
         * The view a set is read again in after a stage frame is recycled. Focus moves to the next frame,
         * else the previous, never the pinned one. With two on the stage, the one left becomes pinned, as
         * after a Pin, and the incoming frame is on the right; a lone frame hands its neighbour its place.
         * A role left with no frame is undefined, which opens as a new set does.
         */
        viewWithout(frameId) {
            const view = this.currentView()
            const { pinnedId, focusedId } = view
            if (frameId !== pinnedId && frameId !== focusedId) {
                return view
            }

            const nextId =
                this.neighbourOf(1, pinnedId) ?? this.neighbourOf(-1, pinnedId)
            // The frame of the two on the stage that stays
            let leftId = null
            if (this.showsPinnedPane) {
                leftId = frameId === pinnedId ? focusedId : pinnedId
            }
            const newPinnedId =
                leftId ?? (frameId === pinnedId ? nextId : pinnedId)

            return {
                ...view,
                pinnedId: newPinnedId ?? undefined,
                focusedId: nextId ?? leftId ?? undefined,
            }
        },

        /**
         * Shows a set's fragment and reads its frames. `view`, for the same set read again, keeps what was
         * pinned, focused, picked and zoomed as far as those frames remain. A fragment holding no set (too
         * few frames left) shows the too-few state.
         */
        showSet(html, { view = null } = {}) {
            if (isClosed) {
                return
            }

            const hostEl = document.getElementById("curationSet")
            replaceSetHost(hostEl, html)
            const setEl = hostEl.querySelector(
                `[${Const.attributes.openingFocusedId}]`,
            )
            if (!setEl) {
                this.showNoSet(Phase.tooFew)
                return
            }

            const frames = [
                ...hostEl.querySelectorAll(`[${Const.attributes.frameId}]`),
            ].map((frameEl) => ({
                id: frameEl.dataset.frameId,
                fileName: frameEl.dataset.fileName,
                bundleKind: frameEl.dataset.bundleKind ?? null,
                inAlbumOrLocation: frameEl.dataset.inAlbumOrLocation === "true",
                problems: frameEl.dataset.problems?.split(",") ?? [],
                // The import's measurement: the Histogram's bytes in Base64, and the crushed and blown
                // fractions
                histogram: frameEl.dataset.histogram ?? null,
                crushed: percentLabel(frameEl.dataset.crushedShadows),
                blown: percentLabel(frameEl.dataset.clippedHighlights),
            }))
            const isInSet = (frameId) =>
                frames.some((frame) => frame.id === frameId)
            const kept = (frameId, opening) =>
                frameId !== undefined && (frameId === null || isInSet(frameId))
                    ? frameId
                    : opening

            this.frames = frames
            this.bundleOf = Object.fromEntries(
                frames.map((frame) => [frame.id, frame.bundleKind]),
            )
            this.pinnedId = kept(
                view?.pinnedId,
                setEl.dataset.openingPinnedId ?? null,
            )
            this.focusedId = kept(
                view?.focusedId,
                setEl.dataset.openingFocusedId ?? null,
            )
            this.pickIds = view ? view.pickIds.filter(isInSet) : []
            this.zoomPersonId = view?.zoomPersonId ?? null
            // The fragment's images and canvases are new, and the set's reads and Histogram paths its own
            this.settledSrcs = {}
            this.unreadableSrcs = {}
            clippingReads = new Map()
            histogramPathsOf.clear()
            drawnSrcs.clear()
            measurement = {
                clippedLevel: Number(setEl.dataset.clippedLevel),
                crushedLevel: Number(setEl.dataset.crushedLevel),
                fitBox: Number(setEl.dataset.measurementBox),
            }

            this.phase = Phase.set
            this.watchStage()
            this.keepFocusInside()
            this.preloadNeighbours()
        },

        /** Observes the new images' resizes, which change a zoom, and attaches the Loupe over the panes */
        watchStage() {
            stageObserver.disconnect()
            detachLoupe()

            const panes = []
            for (const [imageId, lensId] of [
                [PINNED_IMAGE_ID, PINNED_LOUPE_ID],
                [FOCUSED_IMAGE_ID, FOCUSED_LOUPE_ID],
            ]) {
                const imageEl = document.getElementById(imageId)
                const lensEl = document.getElementById(lensId)
                if (imageEl) {
                    stageObserver.observe(imageEl)
                }
                if (imageEl && lensEl) {
                    // The pane, which takes the pointer, holds the image and the lens
                    panes.push({
                        paneEl: lensEl.parentElement,
                        imageEl,
                        lensEl,
                    })
                }
            }
            loupe = attachLoupe({
                panes,
                levels: LOUPE_LEVELS,
                readLevel: () => this.loupeLevel,
                saveLevel: (level) => {
                    this.loupeLevel = level
                    saveLoupeLevel(level)
                },
            })
        },

        // The control that had focus may have left with the previous set's fragment
        keepFocusInside() {
            const shellEl = document.getElementById("curation")
            if (!shellEl.contains(document.activeElement)) {
                shellEl.focus()
            }
        },

        /**
         * After a pointer click on a control, focus returns to the shell so Enter resolves instead of
         * pressing it again. A keyboard press (a click with no `detail`) keeps focus, for Tab to go on from.
         */
        refocusAfterPointer(event) {
            if (event.detail > 0) {
                document.getElementById("curation").focus()
            }
        },

        showNoSet(phase) {
            if (isClosed) {
                return
            }

            replaceSetHost(document.getElementById("curationSet"))
            stageObserver.disconnect()
            detachLoupe()
            preloadOnly([])
            clippingReads = new Map()
            histogramPathsOf.clear()
            drawnSrcs.clear()
            this.frames = []
            this.bundleOf = {}
            this.focusedId = null
            this.pinnedId = null
            this.pickIds = []
            this.shownSequenceId = null
            this.phase = phase
            this.keepFocusInside()
        },

        /** Reads and shows what Curation is opened on, at its current position */
        openSet(options) {
            return this.reading(() => this.readSet(options))
        },

        /**
         * A failed read leaves no set on show, since an outcome saved before it must not be acted on twice.
         * The walk's position and the undo entry stay, and Try again repeats the read.
         */
        async reading(read) {
            try {
                await read()
            } catch (error) {
                if (isClosed) {
                    return
                }

                showErrorSnackBar(
                    `Error in Curation: ${getHttpErrorMessage(error)}`,
                )
                retryRead = () => this.reading(read)
                this.showNoSet(Phase.unread)
            }
        },

        tryAgain() {
            return this.exclusively(retryRead)
        },

        /**
         * Reads the set the walk stands on. On the queue: the nearest open row at or below the position,
         * else the first open row from the top, else the end state. `options` apply to the starting row.
         */
        async readSet(options) {
            if (this.selectionIds) {
                this.showSet(await readSelectionSet(this.selectionIds), options)
                return
            }

            if (this.sequenceId) {
                const html = await readSequenceSet(this.sequenceId)
                if (html) {
                    this.shownSequenceId = this.sequenceId
                    this.showSet(html, options)
                } else {
                    this.showNoSet(Phase.tooFew)
                }
                return
            }

            await this.readQueueFrom(this.openRowFrom(this.position, 1), 1, {
                wrap: true,
                options,
                ...this.nextMoveToken(),
            })
        },

        /**
         * A new token and abort signal for a queue read, aborting the previous one. Writes and moves never
         * read at the same time, so the one controller is always the latest read's.
         */
        nextMoveToken() {
            moveAbort?.abort()
            moveAbort = new AbortController()
            return { token: ++moveToken, signal: moveAbort.signal }
        },

        /** Remembers the shown set's view for the walk's return; not for a done row, which is never returned to */
        rememberShownView() {
            if (
                this.showsSet &&
                this.shownSequenceId &&
                !this.doneIds.has(this.shownSequenceId)
            ) {
                rememberedViews.set(this.shownSequenceId, this.currentView())
            }
        },

        /** The nearest open row from `index` (included) in the direction of `step`, or -1 */
        openRowFrom(index, step) {
            for (; index >= 0 && index < this.queueIds.length; index += step) {
                if (!this.doneIds.has(this.queueIds[index])) {
                    return index
                }
            }
            return -1
        },

        /**
         * Walks the open rows from `startIndex` in the direction of `step`, putting the walk on each as it
         * reads: the first with a set shows (in `options`' view on the starting row, else its remembered
         * view), and one with no content is marked done. With `wrap` (after an outcome) the walk restarts
         * from the top once the direction runs out, then shows the end state. Without it (N, P, a click),
         * running out leaves the walk on the set on show, or with none at `fromIndex`. The set replaced is
         * remembered here, so what the user did while the read was on its way is kept.
         */
        async readQueueFrom(
            startIndex,
            step,
            { wrap, options, token, signal, fromIndex },
        ) {
            const openedId = this.queueIds[startIndex]
            let index = startIndex
            let wrapped = false

            while (!isClosed) {
                if (index < 0) {
                    if (!wrap || wrapped) {
                        break
                    }
                    wrapped = true
                    index = this.openRowFrom(0, 1)
                    continue
                }

                const sequenceId = this.queueIds[index]
                this.position = index
                const html = await readSequenceSet(sequenceId, signal)
                if (token !== moveToken || isClosed) {
                    return
                }

                if (html) {
                    const view =
                        (sequenceId === openedId ? options?.view : undefined) ??
                        rememberedViews.get(sequenceId) ??
                        null
                    if (sequenceId !== this.shownSequenceId) {
                        this.rememberShownView()
                    }
                    this.shownSequenceId = sequenceId
                    this.showSet(html, { view })
                    lastDirection = step
                    this.preloadNextSet()
                    return
                }
                this.markDone(sequenceId)
                index = this.openRowFrom(index + step, step)
            }

            if (isClosed) {
                return
            }
            if (wrap) {
                // Every row is done, so none is current
                this.position = -1
                await Alpine.store(Const.state.sequencesToCurate).refresh()
                this.showNoSet(Phase.end)
                return
            }
            // Nothing further in that direction: the set on show keeps its row, or, with none on show
            // (the unread state), the walk stands where the move started
            const shownIndex = this.queueIds.indexOf(this.shownSequenceId)
            this.position = shownIndex >= 0 ? shownIndex : fromIndex
        },

        /** A row is done once its set is resolved or kept, or its Sequence answers with no content */
        markDone(sequenceId) {
            this.doneIds.add(sequenceId)
            rememberedViews.delete(sequenceId)
            this.paintRail()
        },

        /**
         * Moves the walk to the row at `index` (nothing for -1) and reads its set, going on past empty rows.
         * The rail's highlight moves at once. The view left is remembered here, so a failed read loses
         * nothing, and again in `readQueueFrom` with whatever the user did meanwhile. No move starts while a
         * write runs.
         */
        move(index, step) {
            if (index < 0 || isBusy || isClosed || !this.isQueue) {
                return
            }

            this.rememberShownView()
            movesInFlight++
            const fromIndex = this.position
            this.position = index
            return this.reading(async () => {
                const { token, signal } = this.nextMoveToken()
                try {
                    await this.readQueueFrom(index, step, {
                        wrap: false,
                        token,
                        signal,
                        fromIndex,
                    })
                } catch (error) {
                    // An overtaken read fails unseen too: the later move's answer is what shows
                    if (token === moveToken) {
                        throw error
                    }
                }
            }).finally(() => {
                movesInFlight--
            })
        },

        /** N: the nearest open row below the walk's position */
        moveNext() {
            return this.move(this.openRowFrom(this.position + 1, 1), 1)
        },

        /** P: the nearest open row above the walk's position */
        movePrevious() {
            return this.move(this.openRowFrom(this.position - 1, -1), -1)
        },

        /** Preloads the opening frames of the next open row in the last move's direction; a failure costs only the head start */
        async preloadNextSet() {
            const nextId =
                this.queueIds[
                    this.openRowFrom(
                        this.position + lastDirection,
                        lastDirection,
                    )
                ]
            if (!nextId) {
                return
            }

            try {
                const html = await readSequenceSet(nextId)
                if (html) {
                    preloadOpeningFrames(html)
                }
            } catch (error) {
                console.debug(
                    `Could not preload the next Curation set: ${getHttpErrorMessage(error)}`,
                )
            }
        },

        /**
         * Rereads the rows and walks from the start, every row open and no view remembered, so Start again
         * also picks up the Sequences imported meanwhile
         */
        startQueue() {
            return this.exclusively(() =>
                this.reading(async () => {
                    await this.loadQueueRows({ reset: true })
                    if (isClosed) {
                        return
                    }

                    this.position = 0
                    // The watch paints on a change alone, and the position may already have been 0
                    this.paintRail()
                    await this.readSet(undefined)
                }),
            )
        },

        /**
         * Reads the rows into the rail and takes the walk's IDs from them. A Sequence no longer listed loses
         * its done mark; `reset` drops every mark and view. Nothing changes until the rows are in, so a
         * failed read leaves everything as it was.
         */
        async loadQueueRows({ reset = false } = {}) {
            const html = await readQueueRows()
            if (isClosed) {
                return
            }

            if (reset) {
                this.doneIds.clear()
                rememberedViews.clear()
            }
            const hostEl = document.getElementById("curationQueueHost")
            hostEl.innerHTML = html
            this.queueIds = [
                ...hostEl.querySelectorAll(
                    `[${Const.attributes.railSequenceId}]`,
                ),
            ].map((rowEl) => rowEl.dataset.railSequenceId)
            for (const sequenceId of [...this.doneIds]) {
                if (!this.queueIds.includes(sequenceId)) {
                    this.doneIds.delete(sequenceId)
                }
            }
            this.paintRail()
        },

        /** Direct DOM writes rather than a binding per row, so a long queue costs nothing per move */
        paintRail() {
            const rowEls = document.querySelectorAll(
                `#curationQueueHost [${Const.attributes.railSequenceId}]`,
            )
            rowEls.forEach((rowEl, index) => {
                const isCurrent = index === this.position
                rowEl.classList.toggle("current", isCurrent)
                rowEl.classList.toggle(
                    "done",
                    this.doneIds.has(rowEl.dataset.railSequenceId),
                )
                if (isCurrent) {
                    rowEl.setAttribute("aria-current", "true")
                    rowEl.scrollIntoView({ block: "nearest" })
                } else {
                    rowEl.removeAttribute("aria-current")
                }
            })
        },

        /** Moves the walk to the clicked row; the current row and a done row do nothing */
        onQueueRowClick(event) {
            const rowEl = event.target.closest(
                `[${Const.attributes.railSequenceId}]`,
            )
            const sequenceId = rowEl?.dataset.railSequenceId
            const index = this.queueIds.indexOf(sequenceId)
            if (
                index < 0 ||
                index === this.position ||
                this.doneIds.has(sequenceId)
            ) {
                return
            }

            this.move(index, index > this.position ? 1 : -1)
        },

        /** A hidden row cannot be scrolled to, so the rail is repainted once Alpine has shown it */
        toggleSequenceRail() {
            if (!this.isQueue) {
                return
            }

            this.showsSequenceRail = !this.showsSequenceRail
            saveSequenceRailShown(this.showsSequenceRail)
            if (this.showsSequenceRail) {
                this.$nextTick(() => this.paintRail())
            }
        },

        /**
         * Runs one action at a time, and none while a move reads. A failed write is reported and leaves the
         * set on show, nothing saved. An action finishing after Curation closed announces what it changed,
         * which closing could not know yet.
         */
        async exclusively(action) {
            if (isBusy || isClosed || movesInFlight > 0) {
                return
            }

            isBusy = true
            try {
                await action()
            } catch (error) {
                showErrorSnackBar(
                    `Error in Curation: ${getHttpErrorMessage(error)}`,
                )
            } finally {
                isBusy = false
                if (isClosed) {
                    announceClosed()
                }
            }
        },

        onClosed() {
            isClosed = true
            if (!isBusy) {
                announceClosed()
            }
            // The originals on the stage stop loading, and the component goes with the shell
            preloadOnly([])
            document.getElementById("curationModalContent")?.replaceChildren()
        },

        exit() {
            closeModal()
        },

        /** The set after an outcome; off the queue there is none, so Curation closes */
        async nextSet() {
            if (isClosed) {
                return
            }

            if (!this.isQueue) {
                closeModal()
                return
            }

            await this.openSet()
        },

        resolve() {
            return this.settle(this.keepIds)
        },

        keepAll() {
            return this.settle(this.frames.map((frame) => frame.id))
        },

        /**
         * Keeps the frames named and recycles the rest; nothing with none named. Nothing recycled and no
         * Sequence cleared means nothing changed (a Keep all on part of a Sequence, or on frames of none).
         * On the queue the row is done either way; off it, a change stays open on its report and no change
         * closes Curation.
         */
        settle(keepIds) {
            if (keepIds.length === 0) {
                return
            }

            return this.exclusively(async () => {
                const recycleIds = this.frames
                    .map((frame) => frame.id)
                    .filter((id) => !keepIds.includes(id))
                const response = await http.post(
                    repoPath("api/curation", "/resolve"),
                    { keepIds, recycleIds },
                )
                const { sequenceOf } = response.data
                const changed =
                    recycleIds.length > 0 || Object.keys(sequenceOf).length > 0

                if (changed) {
                    this.undoEntry = {
                        keepIds,
                        recycleIds,
                        sequenceOf,
                        sequenceId: this.shownSequenceId,
                    }
                    recycleIds.forEach((id) => recycledIds.add(id))
                    Object.values(sequenceOf).forEach((id) =>
                        clearedSequenceIds.add(id),
                    )
                }
                announceChanged()

                if (changed && !this.isQueue) {
                    this.reportText = `Kept ${framesText(keepIds.length)}, recycled ${framesText(recycleIds.length)}`
                    this.showNoSet(Phase.report)
                    return
                }

                if (this.isQueue) {
                    this.markDone(this.shownSequenceId)
                }
                await this.nextSet()
            })
        },

        /** Recycles a Bundle's frames, leaving a Pick in it; the set keeps its view */
        recycleBundle(kind) {
            const assetIds = this.frames
                .filter(
                    (frame) =>
                        frame.bundleKind === kind && !this.isPick(frame.id),
                )
                .map((frame) => frame.id)
            if (assetIds.length === 0) {
                return
            }

            return this.recycleFrames(assetIds)
        },

        /** Recycles a stage frame at once, Pick or not; nothing with no frame, as for an empty right pane */
        recycleFrame(frameId) {
            if (!frameId) {
                return
            }

            return this.recycleFrames([frameId], frameId)
        },

        /**
         * Recycles through the grid's own endpoint and rereads the set so its Bundles are recomputed. The
         * set keeps its view, or for `stageFrameId` the view without that frame.
         */
        recycleFrames(assetIds, stageFrameId = null) {
            return this.exclusively(async () => {
                await http.delete(repoPath("api/asset", "/move"), {
                    data: { assetIds },
                })

                const view = this.currentView()
                this.undoEntry = {
                    recycleIds: assetIds,
                    sequenceId: this.shownSequenceId,
                    view,
                    stage: stageFrameId
                        ? { pinnedId: view.pinnedId, focusedId: view.focusedId }
                        : null,
                }
                assetIds.forEach((id) => recycledIds.add(id))
                announceChanged()

                await this.openSet({
                    view: stageFrameId ? this.viewWithout(stageFrameId) : view,
                })
            })
        },

        /**
         * Undoes the last mutation and rereads its set wherever the walk has gone; on the queue the walk
         * returns to its reopened row. The return and the read are one `reading`, so Try again runs both.
         * A Resolve or Keep all goes through `reopen`, which also restores the Picks of the Sequences it
         * cleared, and opens as a new set. A recycling is a plain restore that keeps the view, with a stage
         * frame back beside the one it left. A frame whose content was imported again stays in the trash.
         */
        undo() {
            const entry = this.undoEntry
            if (!entry) {
                return
            }

            return this.exclusively(async () => {
                const isOnShow =
                    this.showsSet && entry.sequenceId === this.shownSequenceId
                const response = entry.keepIds
                    ? await http.post(repoPath("api/curation", "/reopen"), {
                          keepIds: entry.keepIds,
                          recycleIds: entry.recycleIds,
                          sequenceOf: entry.sequenceOf,
                      })
                    : await http.put(repoPath("api/asset", "/restore"), {
                          assetIds: entry.recycleIds,
                      })
                const { restored, duplicates } = response.data

                if (restored.length > 0 || duplicates.length > 0) {
                    reportRestore(response.data)
                }

                this.undoEntry = null
                restored.forEach((id) => recycledIds.delete(id))
                Object.values(entry.sequenceOf ?? {}).forEach((id) =>
                    clearedSequenceIds.delete(id),
                )
                announceChanged()

                const view =
                    entry.view &&
                    (isOnShow
                        ? { ...this.currentView(), ...entry.stage }
                        : entry.view)
                await this.reading(async () => {
                    if (this.isQueue) {
                        if (!isOnShow) {
                            this.rememberShownView()
                        }
                        await this.returnToSequence(entry.sequenceId)
                    }
                    // A state with no set no longer holds, whatever the read answers
                    if (!this.showsSet) {
                        this.phase = Phase.loading
                    }
                    await this.readSet({ view })
                })
            })
        },

        /**
         * Puts the walk on an undone Sequence's reopened row. Start again may have replaced the rows without
         * it, so then they are reread; were it still absent, the walk stands on the first row.
         */
        async returnToSequence(sequenceId) {
            this.doneIds.delete(sequenceId)
            let index = this.queueIds.indexOf(sequenceId)
            if (index < 0) {
                await this.loadQueueRows()
                index = this.queueIds.indexOf(sequenceId)
            }
            this.position = Math.max(index, 0)
            this.paintRail()
        },

        /**
         * Bound on the document while Curation is the active host. Arrows move focus through the rail; 1 and
         * 2 toggle the left and right frame as a Pick (1 is the only frame when one is on the stage), and
         * Shift+1 or Shift+2 recycles it. Space pins, Enter resolves, A keeps all, R recycles the focused
         * frame's Bundle, H toggles the strips, Q the rail, N and P move the walk, Z undoes; Esc is
         * `global.js`'s. Only the arrows repeat, so a held key cannot resolve set after set, and while busy
         * an arrow moves nothing but still does not scroll the rail.
         */
        handleKey(event) {
            if (
                getActiveModalHost() !== ModalHost.curation ||
                isTextEditingTarget(event.target) ||
                event.ctrlKey ||
                event.metaKey ||
                event.altKey
            ) {
                return
            }

            const key = recycleKeyOf(event) ?? event.key.toLowerCase()
            // A focused control takes its own activation keys
            if (
                (key === "enter" || key === " ") &&
                event.target.closest?.("button, a")
            ) {
                return
            }

            const repeatable = {
                arrowleft: () => this.moveFocus(-1),
                arrowup: () => this.moveFocus(-1),
                arrowright: () => this.moveFocus(1),
                arrowdown: () => this.moveFocus(1),
            }
            const once = {
                " ": () => this.pinFocused(),
                1: () => this.leftFrameId && this.togglePick(this.leftFrameId),
                2: () =>
                    this.rightFrameId && this.togglePick(this.rightFrameId),
                "shift+1": () => this.recycleFrame(this.leftFrameId),
                "shift+2": () => this.recycleFrame(this.rightFrameId),
                enter: () => this.resolve(),
                a: () => this.keepAll(),
                r: () => this.recycleFocusedBundle(),
                h: () => this.toggleHistogram(),
                q: () => this.toggleSequenceRail(),
                n: () => this.moveNext(),
                p: () => this.movePrevious(),
                z: () => this.undo(),
            }

            if (Object.hasOwn(repeatable, key)) {
                event.preventDefault()
                if (!isBusy) {
                    repeatable[key]()
                }
            } else if (Object.hasOwn(once, key) && !event.repeat) {
                event.preventDefault()
                once[key]()
            }
        },

        recycleFocusedBundle() {
            const kind = this.bundleOf[this.focusedId]
            if (kind) {
                this.recycleBundle(kind)
            }
        },
    }
}
