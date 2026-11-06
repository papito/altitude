import { Alpine } from "../../lib/alpine.esm.min.js"
import { reportRestore } from "../../assets/asset-actions.js"
import { attachLoupe } from "../../common/loupe.js"
import { clippingMask, readLuminance } from "../../common/luminance.js"
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
 * Curation (`htmx/curation.scala.html`, `htmx/curation_set.scala.html`): the state of the modal, on
 * its shell, so it outlives each set's fragment. It renders nothing itself: the server renders a
 * Curation set, this component puts that fragment in `#curationSet`, reads its frames from their
 * `data-*`, and the fragment's bindings read the state back.
 *
 * It is opened on one of three things (`requestCuration`, from `listeners/curation.js`): the queue,
 * whose Sequence IDs it walks, requesting one set's fragment at a time; one Sequence; or a selection,
 * whose IDs it posts, so they never sit in a URL. Neither of the last two has a next set: Skip, and a
 * Keep all that changes nothing, close Curation, and a Resolve or a Keep all that changes something
 * stays open on a report of what it did, with Undo and Exit.
 *
 * A set is never left on show past its outcome: a failed read of a set says so in place of any set
 * (`reading`), with Try again, so what was saved cannot be acted on a second time.
 *
 * A frame on the stage can be recycled at once (`recycleFrame`), as a Bundle can: it goes to the trash
 * through the grid's own endpoint, the set is read again, and Undo brings it back onto the stage.
 *
 * Every action is saved as it happens, so closing loses only the skips and the undo entry. Exit and
 * Esc close it through `closeModal` (`global.js` handles Esc for every host), and this component
 * notices the host going, then announces what the session left changed
 * (`Const.events.curationClosed`) and removes the shell. The keys it binds are document-level, so they
 * work wherever focus is inside the host, and are ignored while a text field has focus.
 *
 * The Loupe (`common/loupe.js`) is attached over the stage's panes with each set's fragment, refreshed
 * when an image settles or the zoom is applied, and detached with the stage observer.
 *
 * The Histogram under each pane and the Clipping blink over it are read in the browser from the
 * original the pane has loaded, through `common/luminance.js`, once the image settles and shows:
 * one pass, off the main thread for its decode, gives the strip's bins and the class of every
 * pixel, at the levels and the box the set's root carries from the import's measurement, and the
 * blink's mask is painted from the classes when it is drawn. The read is cached by source for the
 * set on show, in flight or done, and dropped with the set, so an arrow back and forth, or a frame
 * moving from the focused pane to the pinned one, costs nothing. The blink is one switch for the
 * stage (`clippingBlink`), on for both panes and through the arrows and the next sets, and off on
 * every open: a new shell starts it off.
 */

const Phase = {
    // No set has been read yet
    loading: "loading",
    set: "set",
    // A Sequence opened alone or a selection has too few frames left
    tooFew: "tooFew",
    // A selection or a Sequence opened alone was resolved: what was kept and recycled
    report: "report",
    // The queue's IDs ran out
    end: "end",
    // A read failed: no set is on show to act on, and Try again runs that read again
    unread: "unread",
}

// How much of the pane a zoomed Face's box fills, so its surroundings stay in view
const ZOOMED_BOX_SHARE = 0.5

// The Loupe's ladder: original pixels per screen pixel, so 1 is 1:1; the footer's slider has a stop on each rung
const LOUPE_LEVELS = [1, 1.5, 2, 2.5, 3, 3.5, 4]

const PINNED_IMAGE_ID = "curationStagePinned"
const FOCUSED_IMAGE_ID = "curationStageFocused"
const PINNED_LOUPE_ID = "curationLoupePinned"
const FOCUSED_LOUPE_ID = "curationLoupeFocused"
const PINNED_CLIPPING_ID = "curationClippingPinned"
const FOCUSED_CLIPPING_ID = "curationClippingFocused"

// Each pane's Clipping canvas, by the ID of the pane's image
const CLIPPING_ID_OF = {
    [PINNED_IMAGE_ID]: PINNED_CLIPPING_ID,
    [FOCUSED_IMAGE_ID]: FOCUSED_CLIPPING_ID,
}

// The Histogram's `viewBox` in the set's fragment (`0 0 64 16`), the space its bars are drawn in, and the
// gap between two bars as a share of a bin's width
const HISTOGRAM_BOX = { width: 64, height: 16 }
const HISTOGRAM_BAR_GAP = 0.12

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

/** "1 frame", "3 frames" */
function framesText(count) {
    return count === 1 ? "1 frame" : `${count} frames`
}

/** A Sequence's fragment, or null when it answers with no content: fewer than two frames left */
async function readSequenceSet(sequenceId) {
    const response = await http.get(
        repoPath(
            "htmx/curation",
            `/sequence/${encodeURIComponent(sequenceId)}`,
        ),
        { responseType: "text" },
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

/**
 * Loads a frame's original ahead of its pane. An original's URL never changes, so the browser serves
 * it from its cache when the pane asks for it.
 */
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
 * The transform of an image filling its pane under `object-fit: contain`, from its top left corner,
 * that centers a Face's box. A box is in upright pixels, and so are the displayed image's
 * `naturalWidth` and `naturalHeight`, while `asset.width` and `height` come from the file's header.
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

/** `level` when it is a rung of the ladder, else the first rung */
function validLoupeLevel(level) {
    return LOUPE_LEVELS.includes(level) ? level : LOUPE_LEVELS[0]
}

/** The Loupe level last chosen; the first rung where none is stored, it is off the ladder or storage is blocked */
function rememberedLoupeLevel() {
    try {
        return validLoupeLevel(
            Number(localStorage.getItem(Const.localStore.loupeLevel)),
        )
    } catch {
        return LOUPE_LEVELS[0]
    }
}

/**
 * The Bundle kinds whose details the rail shows, as last chosen; none where nothing is stored, what is
 * stored is no list or storage is blocked
 */
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

/** Remembers the Bundle kinds whose details show; a blocked storage forgets them and nothing else */
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

/** Remembers the Loupe level; a blocked storage forgets it and nothing else */
function saveLoupeLevel(level) {
    try {
        localStorage.setItem(Const.localStore.loupeLevel, String(level))
    } catch {
        // Nothing to do: the level holds for the session
    }
}

/**
 * The `d` of a Histogram's path from its bins, in `HISTOGRAM_BOX`: one bar per bin, a gap wide of
 * its neighbours, as high as the bin's fraction over the tallest bin's, so the histogram's shape
 * fills the strip whatever the exposure; an empty bin draws no bar. One path rather than a rect per
 * bin keeps the fragment at one element, as the strip's stroke and fill are one rule. Rounded to two
 * decimals to keep the string short. Empty with no pixel counted (a guard: a read always has some)
 */
function barsPath(bins) {
    const tallest = Math.max(...bins)
    if (!(tallest > 0)) {
        return ""
    }

    const { width, height } = HISTOGRAM_BOX
    const binWidth = width / bins.length
    const barWidth = binWidth * (1 - HISTOGRAM_BAR_GAP)
    const round = (value) => Math.round(value * 100) / 100
    return bins
        .map((bin, index) => {
            if (bin === 0) {
                return ""
            }
            const x = round(index * binWidth + (binWidth - barWidth) / 2)
            const top = round(height - (height * bin) / tallest)
            return `M${x},${top}h${round(barWidth)}V${height}H${x}Z`
        })
        .join("")
}

/** A fraction as the strip's corner label, "14.2%" */
function percentLabel(fraction) {
    return `${(fraction * 100).toFixed(1)}%`
}

/** What a pane's strip shows of a read: the bars' path and the two corner labels */
function histogramOf(read) {
    return {
        path: barsPath(read.bins),
        crushed: percentLabel(read.crushed),
        blown: percentLabel(read.blown),
    }
}

/**
 * The Clipping mask's colors, which the stylesheet gives the canvas through its theme tokens as its
 * `color` (the blown highlights) and its `border-color` (the crushed shadows): the computed style
 * resolves them, where the custom properties would come back unresolved. Read at each draw, so a
 * mask drawn is in the theme of its moment
 */
function clippingColors(canvasEl) {
    const style = getComputedStyle(canvasEl)
    return { blownColor: style.color, crushedColor: style.borderColor }
}

/**
 * Puts a set's fragment in the set's host, `#curationSet`, or empties it. The old fragment's Alpine
 * trees are destroyed before the component's state changes, so none of their loops is queued to
 * run on the new set's state: Alpine's observer, which destroys a removed tree in a later
 * microtask, then finds nothing left to release
 */
function replaceSetHost(hostEl, html = "") {
    for (const childEl of [...hostEl.children]) {
        Alpine.destroyTree(childEl)
    }
    hostEl.innerHTML = html
}

/**
 * "shift+1" or "shift+2" for a keydown of the 1 or 2 key with Shift held, else null. Shift makes the
 * key type a symbol that differs by layout ("!" on a US one), so the key is told by its position. On a
 * layout whose digits are on the shifted level (AZERTY) the same press types the digit, which stays
 * the Pick's key.
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
    // The Loupe over the set on show, attached with its fragment
    let loupe = null
    let isBusy = false
    let isClosed = false
    // The read Try again runs
    let retryRead = null
    // The focused frame's neighbours being preloaded, by frame ID
    const neighbourPreloads = new Map()
    // The measurement's levels and box from the set's root: `{ clippedLevel, crushedLevel, fitBox }`
    let measurement = null
    // The luminance reads of the set on show by the image's `currentSrc`: `{ bins, classes, width,
    // height }`, or the promise of one still in flight. A new map for each set, so a read finishing
    // after its set has gone is told apart. Closure state on purpose: the classes are a byte for each
    // of a million pixels, which no binding reads
    let luminanceReads = new Map()
    // The source each Clipping canvas holds the mask of, by the canvas's ID, so a mask is not drawn twice
    const drawnSrcs = new Map()
    // What the session leaves changed, net of its undos, for the grid beneath
    const recycledIds = new Set()
    const clearedSequenceIds = new Set()

    /**
     * Preloads these frames and stops any other still loading, so a held arrow key does not queue
     * originals ahead of the frame on show.
     */
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

        // The set shown, in the rail's order: `{ id, fileName, bundleKind, inAlbumOrLocation, problems }`,
        // `problems` naming what is wrong with the frame, its Bundle first and then its chips
        frames: [],
        // A frame's Bundle by its ID; a Contender has none
        bundleOf: {},
        focusedId: null,
        pinnedId: null,
        pickIds: [],
        zoomPersonId: null,
        // The Bundle kinds whose details the rail shows, a Bundle's reason line and its frames' reasons: every
        // other Bundle's are hidden. They hold across sets and opens
        detailKinds: rememberedDetailKinds(),
        // The Loupe's level, a rung of `LOUPE_LEVELS`: the footer's slider and the wheel over a pane move it, and it
        // holds across sets and opens
        loupeLevel: rememberedLoupeLevel(),
        // What each stage image has settled on, loaded or failed, by the image's ID: the `src` it holds
        // once its `load` or `error` has fired. Until a pane's frame is that one, the pane is loading
        settledSrcs: {},
        // The `src` each stage image settled on with no natural size, a failed image, by the image's ID
        unreadableSrcs: {},
        // Each stage image's Histogram by the image's ID, `{ path, crushed, blown }`: the bars' `d` and the
        // corner labels; null with no read
        histograms: {},
        // The Clipping blink's one switch for the stage; off on every open, as a new shell starts here
        clippingBlink: false,

        // What Curation walks: the queue's IDs and where it stands in them, or else one Sequence
        // or a selection's IDs, which have no next set
        queueIds: [],
        position: 0,
        sequenceId: null,
        selectionIds: null,

        // The last mutation, for Undo, with the Sequence of the queue it was made on: `{ keepIds,
        // recycleIds, sequenceOf, sequenceId }` of a Resolve or a Keep all, `{ recycleIds, sequenceId,
        // view, stage }` of a recycled Bundle or frame, with the view its set was left in; `stage` is
        // a recycled frame's alone, the pinned and the focused frame it was recycled from
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

        // Alpine calls this when the shell is removed
        destroy() {
            document.removeEventListener("keydown", onKeydown)
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

        // The report, the too-few state and the unread state offer Undo beside what they say
        get showsHeaderUndo() {
            return (
                this.undoEntry !== null &&
                !this.showsReport &&
                !this.showsTooFew &&
                !this.showsUnread
            )
        },

        // The Sequence of the queue the walk stands on; nothing past its end, or on anything else
        get walkSequenceId() {
            return this.queueIds[this.position] ?? null
        },

        get isAllDone() {
            return !Alpine.store(Const.state.sequencesToCurate).hasAny
        },

        // The stage shows the pinned frame beside the focused one, or one frame when they are the same
        get showsPinnedPane() {
            return this.pinnedId !== null && this.pinnedId !== this.focusedId
        },

        // The frame in the left pane, which is the only frame when one is on the stage
        get leftFrameId() {
            return this.showsPinnedPane ? this.pinnedId : this.focusedId
        },

        // The frame in the right pane; none when one frame is on the stage
        get rightFrameId() {
            return this.showsPinnedPane ? this.focusedId : null
        },

        // The frames kept: the Picks, or with none the frame on the stage when it is the only one. With
        // two on the stage and no Pick nothing is, so a frame is never recycled on a guess
        get keepIds() {
            if (this.pickIds.length > 0) {
                return this.pickIds
            }
            return this.showsPinnedPane ? [] : [this.focusedId].filter(Boolean)
        },

        // Resolve's label in two parts, since only the part naming a frame truncates
        get keptLabel() {
            const kept = this.keepIds
            return `Keep ${kept.length === 1 ? this.fileNameOf(kept[0]) : kept.length}`
        },

        get recycleLabel() {
            return `, recycle ${this.frames.length - this.keepIds.length}`
        },

        // The footer holds the note's row whenever this is so, so a frame's Pick does not shrink the panes
        // mid-comparison
        get hasFrameInAlbumOrLocation() {
            return this.frames.some((frame) => frame.inAlbumOrLocation)
        },

        // The set's footer, when a frame to be recycled is in an album or a Location. Empty while nothing
        // is to be kept, since nothing is to be recycled yet and every frame would count
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
         * A pane reuses its image, and the browser keeps painting the old picture until the new one is
         * available, so a pane is loading from the moment its frame changes: it hides its image and
         * shows the spinner, and never shows one frame under another's name.
         */
        isPaneLoading(imageId, frameId) {
            return (
                frameId !== null &&
                this.settledSrcs[imageId] !== fileUrl(frameId)
            )
        },

        /** A pane's image settled for this frame with no natural size: the browser could not decode it */
        isImageUnreadable(imageId, frameId) {
            return (
                frameId !== null &&
                this.unreadableSrcs[imageId] === fileUrl(frameId)
            )
        },

        /** A pane's Histogram, `{ path, crushed, blown }`; null while the pane loads or its image has no read */
        histogramShown(imageId, frameId) {
            if (this.isPaneLoading(imageId, frameId)) {
                return null
            }
            return this.histograms[imageId] ?? null
        },

        /** The `d` of a pane's Histogram bars; empty while the pane loads or its image has no read */
        histogramPath(imageId, frameId) {
            return this.histogramShown(imageId, frameId)?.path ?? ""
        },

        /** The strip's left corner, the fraction of the frame's pixels crushed, "0.5%"; empty with no read */
        crushedLabel(imageId, frameId) {
            return this.histogramShown(imageId, frameId)?.crushed ?? ""
        },

        /** The strip's right corner, the fraction of the frame's pixels blown, "14.2%"; empty with no read */
        blownLabel(imageId, frameId) {
            return this.histogramShown(imageId, frameId)?.blown ?? ""
        },

        /** Whether a pane's Clipping canvas shows: the blink is on, and the pane shows a readable image */
        showsClippingBlink(imageId, frameId) {
            return (
                this.clippingBlink &&
                !this.isPaneLoading(imageId, frameId) &&
                !this.isImageUnreadable(imageId, frameId)
            )
        },

        /**
         * An image's `load` or `error`. A failed image shows as the browser shows one, with its `alt`. The
         * Loupe re-reads once Alpine has shown the image: the frame under a resting pointer changed.
         * A loaded image is read for its Histogram and its Clipping classes here, the one moment a
         * pane's picture changes.
         */
        onImageSettled(event) {
            this.settledSrcs[event.target.id] = event.target.getAttribute("src")
            this.applyZoom()
            this.$nextTick(() => loupe?.refresh())
            this.readPane(event.target)
        },

        /**
         * Reads a settled image's luminance into the pane's Histogram, from the set's cache or afresh,
         * once Alpine has shown the image, so the read's decode never holds up the pane's paint, and
         * draws its Clipping mask when the read is in while the blink is on. A read finishing after
         * its set has gone, or after the pane's image has moved on, fills only the cache it was
         * started for. A failed image has no pixels to read: it is noted as unreadable and leaves the
         * strip empty. A read that fails (a tainted canvas, or a canvas the browser refuses) leaves the
         * strip empty too, and breaks nothing else of the settle.
         */
        readPane(imageEl) {
            const imageId = imageEl.id
            if (!(imageEl.naturalWidth > 0 && imageEl.naturalHeight > 0)) {
                this.unreadableSrcs[imageId] = imageEl.getAttribute("src")
                this.histograms[imageId] = null
                this.drawClipping(imageId)
                return
            }
            delete this.unreadableSrcs[imageId]

            // Until the read is in, the strip is empty and the canvas blank, so the pane's last frame does
            // not show over this one; a read already cached shows at once
            const src = imageEl.currentSrc
            const reads = luminanceReads
            const cached = reads.get(src)
            if (!cached || cached instanceof Promise) {
                this.histograms[imageId] = null
            }
            if (this.clippingBlink) {
                this.drawClipping(imageId)
            }
            this.$nextTick(async () => {
                let read
                try {
                    read = await this.luminanceOf(imageEl)
                } catch (error) {
                    console.debug(
                        `Could not read the luminance of a Curation frame: ${error.message}`,
                    )
                }
                if (reads !== luminanceReads || imageEl.currentSrc !== src) {
                    return
                }

                this.histograms[imageId] = read ? histogramOf(read) : null
                if (this.clippingBlink) {
                    this.drawClipping(imageId)
                }
            })
        },

        /**
         * A loaded image's read, from the set's cache by its source: the read done, or the promise of
         * one in flight, which a second settle of the same source awaits too; else started and cached as
         * its promise, replaced by the read when it is in. A failed read leaves the cache, so the next
         * settle of its source tries again
         */
        luminanceOf(imageEl) {
            const src = imageEl.currentSrc
            const reads = luminanceReads
            let read = reads.get(src)
            if (!read) {
                read = readLuminance(imageEl, {
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
         * Paints the mask of the pane's image into the pane's Clipping canvas, at the read's own size,
         * which `object-fit: contain` lays over the painted picture pixel for pixel, in the colors the
         * canvas has at this moment. A canvas that already holds the mask of that source is left alone,
         * and one whose image has no read in the cache yet (unreadable, a read that failed, or one
         * still in flight, which draws when it is in) is blanked, so no earlier frame's mask stays over it
         */
        drawClipping(imageId) {
            const canvasEl = document.getElementById(CLIPPING_ID_OF[imageId])
            const imageEl = document.getElementById(imageId)
            if (!canvasEl || !imageEl) {
                return
            }

            const src = imageEl.currentSrc
            const read = luminanceReads.get(src)
            if (!read || read instanceof Promise) {
                canvasEl.width = 0
                canvasEl.height = 0
                drawnSrcs.delete(canvasEl.id)
                return
            }
            if (drawnSrcs.get(canvasEl.id) === src) {
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

        /**
         * The Histogram button's click: flips the blink, and on draws the mask of every pane whose read
         * is in; a pane whose read is still in flight draws when it is
         */
        toggleClippingBlink() {
            this.clippingBlink = !this.clippingBlink
            if (this.clippingBlink) {
                for (const imageId of [PINNED_IMAGE_ID, FOCUSED_IMAGE_ID]) {
                    this.drawClipping(imageId)
                }
            }
        },

        fileNameOf(frameId) {
            return this.frames.find((frame) => frame.id === frameId)?.fileName
        },

        problemsOf(frameId) {
            return (
                this.frames.find((frame) => frame.id === frameId)?.problems ??
                []
            )
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

        /**
         * The frame next to the focused one in the rail's order, past the frame `exceptId` names
         */
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

        /** The frames an arrow key goes to next paint at once, as the next set's opening frames do */
        preloadNeighbours() {
            preloadOnly(
                [this.neighbourOf(-1), this.neighbourOf(1)].filter(Boolean),
            )
        },

        /**
         * The focused frame becomes the pinned frame, replacing the one before it, and focus moves on to
         * the next frame, or the previous one at the end, so two frames stay on the stage. The pin only
         * ever moves to another frame: the pinned frame on its own has nothing to do here, and an arrow
         * goes back to the two.
         */
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
         * Applies the zoom to both panes, for the frames they show. It runs when a crop is clicked, when
         * an image has loaded, since its natural size is what the box is held against, and before the
         * pane shows it, and when a pane resizes. A frame with no Face of the Person shows whole. The
         * pane's Clipping canvas takes the same transform as its image, so the mask follows the zoom.
         * Each moves the pixels under a resting pointer, so the Loupe re-reads after the transform is
         * written.
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

        /** What the user has made of the set on show, which a later read of that same set can keep */
        currentView() {
            return {
                pinnedId: this.pinnedId,
                focusedId: this.focusedId,
                pickIds: this.pickIds,
                zoomPersonId: this.zoomPersonId,
            }
        },

        /**
         * The view a set is read again in once a frame on its stage is recycled. Focus moves on from the
         * focused frame to the next frame, or the previous one at the end, never to the pinned
         * frame. With two frames on the stage, the one left is the pinned frame, as after a Pin, so the
         * frame coming in is on the right and two stay on the stage; with none to come in, the one left is
         * there alone. A frame alone on the stage hands that neighbour its place, pinned or not. A role
         * with no frame to take it is left undefined, which opens as a new set does. A frame that is not
         * on the stage changes nothing of it.
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
         * Puts a set's fragment on show and reads its frames. `view` is for the same set read again: what
         * the user had pinned, focused, picked and opened stays as far as its frames
         * are still in the set, and anything else opens as a new set does. A selection with too few frames
         * left answers with a fragment saying so instead of a set, and the too-few state of the shell says
         * the same.
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
            // The fragment's images and canvases are new: they have settled on nothing, read nothing and
            // drawn nothing, and the set's reads are its own
            this.settledSrcs = {}
            this.unreadableSrcs = {}
            this.histograms = {}
            luminanceReads = new Map()
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

        /**
         * The fragment's images and lenses are new: a pane resizing changes what a zoom has to do, and
         * the Loupe rides over the panes whose image and lens are both there
         */
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
                // The wheel's step reaches the slider through the state
                saveLevel: (level) => this.setLoupeLevel(level),
            })
        },

        /** The slider's and the wheel's one setter: the state, the storage and the lenses on show */
        setLoupeLevel(level) {
            this.loupeLevel = validLoupeLevel(level)
            saveLoupeLevel(this.loupeLevel)
            loupe?.setLevel(this.loupeLevel)
        },

        get loupeLevelLabel() {
            return `${Math.round(this.loupeLevel * 100)}%`
        },

        // The slider's stops are the ladder's rungs
        get loupeRange() {
            return {
                min: LOUPE_LEVELS[0],
                max: LOUPE_LEVELS[LOUPE_LEVELS.length - 1],
                step: LOUPE_LEVELS[1] - LOUPE_LEVELS[0],
            }
        },

        // The control that had focus may have left with the previous set's fragment
        keepFocusInside() {
            const shellEl = document.getElementById("curation")
            if (!shellEl.contains(document.activeElement)) {
                shellEl.focus()
            }
        },

        /**
         * Hands focus back to the shell after a pointer's click on a control, so Enter resolves instead of
         * pressing that control again. A control pressed from the keyboard (a click with no `detail`) keeps
         * it, for a Tab to go on from.
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
            luminanceReads = new Map()
            drawnSrcs.clear()
            this.frames = []
            this.bundleOf = {}
            this.focusedId = null
            this.pinnedId = null
            this.pickIds = []
            this.phase = phase
            this.keepFocusInside()
        },

        /** Reads what the component is opened on, at its current position, and shows it */
        openSet(options) {
            const openedAt = this.position
            return this.reading(() => this.readSet(options, openedAt))
        },

        /**
         * Runs a read of a set. Whatever a failure interrupts, it leaves no set on show: an outcome may
         * have been saved before this read, and its set must not be acted on a second time. The state
         * that says so keeps the walk's position and the undo entry, and Try again runs the same read.
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
         * `options` hold for the set at the position `openedAt`. A Sequence gone meanwhile answers with no
         * content and is passed over without comment, and the set after it is a new one, whatever was
         * asked of the set read.
         */
        async readSet(options, openedAt) {
            if (this.selectionIds) {
                this.showSet(await readSelectionSet(this.selectionIds), options)
                return
            }

            if (this.sequenceId) {
                const html = await readSequenceSet(this.sequenceId)
                if (html) {
                    this.showSet(html, options)
                } else {
                    this.showNoSet(Phase.tooFew)
                }
                return
            }

            while (!isClosed && this.position < this.queueIds.length) {
                const html = await readSequenceSet(this.queueIds[this.position])
                if (html) {
                    this.showSet(
                        html,
                        this.position === openedAt ? options : undefined,
                    )
                    this.preloadNextSet()
                    return
                }
                this.position++
            }

            if (!isClosed) {
                await Alpine.store(Const.state.sequencesToCurate).refresh()
                this.showNoSet(Phase.end)
            }
        },

        /** Preloads the next set's opening frames; a failure costs only the head start */
        async preloadNextSet() {
            const nextId = this.queueIds[this.position + 1]
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

        /** Calls the queue endpoint afresh and walks it from its start */
        startQueue() {
            return this.exclusively(() =>
                this.reading(async () => {
                    const response = await http.get(
                        repoPath("api/curation", "/queue"),
                    )
                    this.queueIds = response.data.sequenceIds
                    this.position = 0
                    await this.readSet(undefined, 0)
                }),
            )
        },

        /**
         * Runs one action at a time: a key pressed while a set is being resolved or read does nothing.
         * A failed write is reported and leaves the set on show, nothing having been saved; a failed read
         * never reaches this (`reading`). An action finishing after Curation closed announces what it
         * changed, which closing could not know yet.
         */
        async exclusively(action) {
            if (isBusy || isClosed) {
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

        /** The next set of the queue. Nothing else has one, so Curation closes */
        async nextSet() {
            if (isClosed) {
                return
            }

            if (!this.isQueue) {
                closeModal()
                return
            }

            this.position++
            await this.openSet()
        },

        resolve() {
            return this.settle(this.keepIds)
        },

        keepAll() {
            return this.settle(this.frames.map((frame) => frame.id))
        },

        /**
         * Keeps the frames named and recycles the others; with none named, as while the footer's hint shows
         * or with no set on show, it does nothing. The server's answer says whether a set changed:
         * nothing recycled and no Sequence cleared is a Keep all on part of a Sequence, or on frames of none.
         * The queue goes on to its next set either way. With no next set, an outcome that changed nothing
         * closes Curation, as a Skip does, and any other stays open on its report, keeping its undo entry.
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
                        sequenceId: this.walkSequenceId,
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

                await this.nextSet()
            })
        },

        /** Skip changes nothing: the next set opens, or Curation closes */
        skip() {
            if (this.frames.length === 0) {
                return
            }

            return this.exclusively(() => this.nextSet())
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

        /**
         * Recycles a frame on the stage at once, a Pick or not, and the next frame takes its place
         * (`viewWithout`). With no frame named, as for the right pane's key with one frame on the stage,
         * it does nothing.
         */
        recycleFrame(frameId) {
            if (!frameId) {
                return
            }

            return this.recycleFrames([frameId], frameId)
        },

        /**
         * Recycles frames of the set on show through the grid's own endpoint and reads the set again,
         * so its Bundles are recomputed against the frames left. The set keeps its view, or, when
         * `stageFrameId` names the frame recycled from the stage, the view without that frame, and
         * the undo entry then holds the stage it was recycled from.
         */
        recycleFrames(assetIds, stageFrameId = null) {
            return this.exclusively(async () => {
                await http.delete(repoPath("api/asset", "/move"), {
                    data: { assetIds },
                })

                const view = this.currentView()
                this.undoEntry = {
                    recycleIds: assetIds,
                    sequenceId: this.walkSequenceId,
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
         * Undoes the last mutation and reads its set again by its own ID, wherever the walk has gone
         * since. A Resolve or a Keep all goes through `reopen`, which also puts the Picks back in the
         * Sequences it cleared, and its set opens as a new one; a recycled Bundle or frame has no Pick
         * and is a plain restore, and its set keeps the view: as it is if the set is still on show, else
         * as it was left. A frame recycled from the stage is back on it either way, beside the frame it
         * was recycled from (`stage`). A frame whose content was imported again stays in the trash, as
         * with any restore.
         */
        undo() {
            const entry = this.undoEntry
            if (!entry) {
                return
            }

            return this.exclusively(async () => {
                const isOnShow =
                    this.showsSet && entry.sequenceId === this.walkSequenceId
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
                if (entry.sequenceId) {
                    this.returnToSequence(entry.sequenceId)
                }
                // A state with no set no longer holds, whatever the read answers
                if (!this.showsSet) {
                    this.phase = Phase.loading
                }
                await this.openSet({ view })
            })
        },

        /**
         * Puts the walk on a Sequence undone. Start again may have replaced the list it was walked in, and
         * a Sequence resolved is in no new list, so it goes in where the walk stands, which goes on from
         * there after it.
         */
        returnToSequence(sequenceId) {
            const index = this.queueIds.indexOf(sequenceId)
            if (index >= 0) {
                this.position = index
            } else {
                this.queueIds.splice(this.position, 0, sequenceId)
            }
        },

        /**
         * The keys, bound while Curation is the active host: ← ↑ move the focused frame back through the
         * rail's order and → ↓ move it on, 1 toggles the left frame as a Pick, or the only frame when one is
         * on the stage, and 2 the right frame, with nothing to do when one is on the stage. Shift+1 and
         * Shift+2 recycle that same frame at once. P pins
         * the focused frame and moves on, and does nothing on the pinned frame. Enter resolves,
         * which does nothing while no frame is to be kept (`keepIds`), A keeps all, R recycles the focused
         * frame's Bundle, S skips and Z undoes. Esc is `global.js`'s. A key held down repeats only the arrows,
         * so it cannot resolve set after set, and Enter on a focused control presses that control. While a
         * set is being resolved or read an arrow moves nothing, and still does not scroll the rail. With no
         * set on show there are no frames and no focused frame, so only Z and Esc do anything.
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
            if (key === "enter" && event.target.closest?.("button, a")) {
                return
            }

            const repeatable = {
                arrowleft: () => this.moveFocus(-1),
                arrowup: () => this.moveFocus(-1),
                arrowright: () => this.moveFocus(1),
                arrowdown: () => this.moveFocus(1),
            }
            const once = {
                p: () => this.pinFocused(),
                1: () => this.leftFrameId && this.togglePick(this.leftFrameId),
                2: () =>
                    this.rightFrameId && this.togglePick(this.rightFrameId),
                "shift+1": () => this.recycleFrame(this.leftFrameId),
                "shift+2": () => this.recycleFrame(this.rightFrameId),
                enter: () => this.resolve(),
                a: () => this.keepAll(),
                r: () => this.recycleFocusedBundle(),
                s: () => this.skip(),
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
