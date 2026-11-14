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
 * Curation (`htmx/curation.scala.html`, `htmx/curation_set.scala.html`): the state of the modal, on
 * its shell, so it outlives each set's fragment. It renders nothing itself: the server renders a
 * Curation set, this component puts that fragment in `#curationSet`, reads its frames from their
 * `data-*`, and the fragment's bindings read the state back.
 *
 * It is opened on one of three things (`requestCuration`, from `listeners/curation.js`): the queue,
 * whose rows it reads into the Sequence rail (`htmx/curation_queue.scala.html`) and walks, requesting
 * one set's fragment at a time and painting the rail's current and done rows itself; one Sequence; or
 * a selection, whose IDs it posts, so they never sit in a URL. Neither of the last two has a next set
 * or a Sequence rail: a Keep all that changes nothing closes Curation, a Resolve or a Keep all that
 * changes something stays open on a report of what it did, with Undo and Exit, and Exit is the one
 * way to leave either without an outcome.
 *
 * On the queue a row is open or done: done once its set is resolved or kept, or once its Sequence
 * answers with no content (`doneIds`), and open otherwise. The walk moves between open rows only
 * (`move`, `readQueueFrom`): to the nearest open row below or above, to a clicked row, and after an
 * outcome to the nearest open row below, else the first open row from the top, else the end state,
 * which comes only when every row is done. A row that answers with no content when reached becomes
 * done and the move goes on in its direction; a move that finds nothing stays on the set on show.
 * The latest move wins: a move puts the walk on its row at once and a read it has overtaken is
 * dropped unseen, while the writes (Resolve, Keep all, Recycle, Undo, Try again and Start again)
 * and the moves never run at the same time. Leaving a set by a move, or by an Undo that reads
 * another set, remembers what the user made of it (`rememberedViews`), restored when the walk
 * returns, until its row is done or the queue is read again. In the end state no row is current,
 * and the rail's header reads the count alone.
 *
 * A set is never left on show past its outcome: a failed read of a set says so in place of any set
 * (`reading`), with Try again, so what was saved cannot be acted on a second time.
 *
 * A frame on the stage can be recycled at once (`recycleFrame`), as a Bundle can: it goes to the trash
 * through the grid's own endpoint, the set is read again, and Undo brings it back onto the stage.
 *
 * Every action is saved as it happens, so closing loses only the undo entry. Exit and
 * Esc close it through `closeModal` (`global.js` handles Esc for every host), and this component
 * notices the host going, then announces what the session left changed
 * (`Const.events.curationClosed`) and removes the shell. The keys it binds are document-level, so they
 * work wherever focus is inside the host, and are ignored while a text field has focus.
 *
 * The Loupe (`common/loupe.js`) is attached over the stage's panes with each set's fragment, refreshed
 * when an image settles or the zoom is applied, and detached with the stage observer.
 *
 * The Histogram under each pane is the frame's own, measured at import: its rail item carries the
 * bins and the two fractions of the strip's corners in its `data-*`, so the strip shows with the
 * frame, whether or not its image has loaded. The Clipping blink over a pane is read in the browser
 * from the original the pane has loaded, through `common/clipping.js`, and only while the blink is
 * on: one pass, off the main thread for its decode, gives the class of every pixel, at the levels
 * and the box the set's root carries from the import's measurement, and the mask is painted from
 * the classes. The read is cached by source for the set on show, in flight or done, and dropped
 * with the set, so an arrow back and forth, or a frame moving from the focused pane to the pinned
 * one, costs nothing. The blink is one switch for the stage (`clippingBlink`), on for both panes
 * and through the arrows and the next sets, and off on every open: a new shell starts it off.
 * The H key shows or hides both strips (`showsHistogram`), a choice kept for the next open; the
 * strip is the blink's only switch, so hiding it turns the blink off. The Q key and the header's
 * Sequences button show or hide the Sequence rail (`showsSequenceRail`), a choice kept the same way;
 * the rail hidden changes nothing about the walk.
 */

const Phase = {
    // No set has been read yet
    loading: "loading",
    set: "set",
    // A Sequence opened alone or a selection has too few frames left
    tooFew: "tooFew",
    // A selection or a Sequence opened alone was resolved: what was kept and recycled
    report: "report",
    // Every row of the queue is done
    end: "end",
    // A read failed: no set is on show to act on, and Try again runs that read again
    unread: "unread",
}

// How much of the pane a zoomed Face's box fills, so its surroundings stay in view
const ZOOMED_BOX_SHARE = 0.5

// The Loupe's ladder: original pixels per screen pixel, so 1 is 1:1; the wheel over a pane steps a rung
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

/** "1 frame", "3 frames" */
function framesText(count) {
    return count === 1 ? "1 frame" : `${count} frames`
}

/** The queue's rows, the fragment the Sequence rail lists, in the order the queue is walked */
async function readQueueRows() {
    const response = await http.get(repoPath("htmx/curation", "/queue"), {
        responseType: "text",
    })
    return response.data
}

/**
 * A Sequence's fragment, or null when it answers with no content: fewer than two frames left. A
 * move's read passes its `signal`, so a later move can abort it instead of waiting for its answer
 */
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

/** Whether the stage shows the Histogram strips, as last chosen; shown where nothing is stored or storage is blocked */
function rememberedHistogramShown() {
    try {
        return (
            localStorage.getItem(Const.localStore.curationHistogram) !== "false"
        )
    } catch {
        return true
    }
}

/** Remembers whether the Histogram strips show; a blocked storage forgets it and nothing else */
function saveHistogramShown(shown) {
    try {
        localStorage.setItem(Const.localStore.curationHistogram, String(shown))
    } catch {
        // Nothing to do: the choice holds for the session
    }
}

/** Whether the Sequence rail shows, as last chosen; shown where nothing is stored or storage is blocked */
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

/** Remembers whether the Sequence rail shows; a blocked storage forgets it and nothing else */
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
 * The paths of a frame's Histogram by channel, `{ luminance, red, green, blue }`, from its
 * `data-histogram`: the Base64 of a byte per bin, the channels in the order of
 * `HISTOGRAM_CHANNELS`, each bin over the tallest of the four, as the server scales them
 * (`common/histogram.js` draws them)
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
    // The Clipping reads of the set on show by the image's `currentSrc`: `{ classes, width, height }`,
    // or the promise of one still in flight. A new map for each set, so a read finishing after its
    // set has gone is told apart. Closure state on purpose: the classes are a byte for each of a
    // million pixels, which no binding reads
    let clippingReads = new Map()
    // The Histogram paths of the set's frames that have been on the stage, by frame ID, built once
    // each from the frame's `histogram`. Closure state: a frame's paths are a few kilobytes of
    // text that only the pane showing it binds
    const histogramPathsOf = new Map()
    // The source each Clipping canvas holds the mask of, by the canvas's ID, so a mask is not drawn twice
    const drawnSrcs = new Map()
    // The one click listener of the Sequence rail's rows
    let onQueueClick = null
    // What the user made of each set a move left (`currentView`), by its Sequence, restored when the
    // walk returns to it; dropped once its row is done, and cleared when the queue is read again
    const rememberedViews = new Map()
    // The latest read of the queue's rows takes a new token; an older read still in flight finds its
    // token stale when its answer comes and drops it, so the last move is the set that opens. The
    // controller aborts that older read as the token advances, so it holds nothing up
    let moveToken = 0
    let moveAbort = null
    // The moves reading a set, so no write starts while one is
    let movesInFlight = 0
    // The direction of the last move, which the next set is preloaded in
    let lastDirection = 1
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
        // The Loupe's level, a rung of `LOUPE_LEVELS`: the wheel over a pane steps it, and it holds across sets
        // and opens
        loupeLevel: rememberedLoupeLevel(),
        // What each stage image has settled on, loaded or failed, by the image's ID: the `src` it holds
        // once its `load` or `error` has fired. Until a pane's frame is that one, the pane is loading
        settledSrcs: {},
        // The `src` each stage image settled on with no natural size, a failed image, by the image's ID
        unreadableSrcs: {},
        // The Clipping blink's one switch for the stage; off on every open, as a new shell starts here
        clippingBlink: false,
        // Whether the stage shows the Histogram strips: the H key switches them, and the choice holds
        // across sets and opens
        showsHistogram: rememberedHistogramShown(),
        // Whether the Sequence rail shows on the queue: the Q key and the header's button switch it,
        // and the choice holds across opens
        showsSequenceRail: rememberedSequenceRailShown(),

        // What Curation walks: the queue's IDs and where it stands in them, or else one Sequence
        // or a selection's IDs, which have no next set
        queueIds: [],
        position: 0,
        sequenceId: null,
        selectionIds: null,
        // The Sequences of the queue whose row is done: resolved, kept or gone under two frames. The
        // walk passes over them, and the rail dims them with a mark. Reactive, since the footer's
        // Previous and Next read whether an open row is left on their side
        doneIds: new Set(),
        // The Sequence of the set on show, which the position runs ahead of while a move is reading;
        // null with no set on show, and on a selection
        shownSequenceId: null,

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
            // The rail follows the walk: wherever the position is set, the rows are painted again
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

        // Alpine calls this when the shell is removed
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

        // The report, the too-few state and the unread state offer Undo beside what they say
        get showsHeaderUndo() {
            return (
                this.undoEntry !== null &&
                !this.showsReport &&
                !this.showsTooFew &&
                !this.showsUnread
            )
        },

        // Whether an open row is left above or below the walk's position, which Previous and Next go to
        get hasPreviousSequence() {
            return this.isQueue && this.openRowFrom(this.position - 1, -1) >= 0
        },

        get hasNextSequence() {
            return this.isQueue && this.openRowFrom(this.position + 1, 1) >= 0
        },

        get isAllDone() {
            return !Alpine.store(Const.state.sequencesToCurate).hasAny
        },

        // The Sequence rail's header, "4 of 12": where the walk stands among the rows. In the end state
        // no row is current (the position is -1) and it reads the count alone, "12 of 12"; with no rows
        // it reads nothing
        get railPosition() {
            const count = this.queueIds.length
            if (count === 0) {
                return ""
            }
            const current = this.position < 0 ? count : this.position + 1
            return `${current} of ${count}`
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

        /** The set's frame of the ID, as read from its rail item; undefined for no frame */
        frameOf(frameId) {
            return this.frames.find((frame) => frame.id === frameId)
        },

        /**
         * The paths of a frame's Histogram by channel, `{ luminance, red, green, blue }`, which the
         * strip draws (`includes/histogram`); empty ones for a pane with no frame and a frame not
         * measured. They are the frame's, so they show while the frame's image loads
         */
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

        /** The strip's left corner, the fraction of the frame's pixels crushed, "0.5%"; empty for a frame not measured */
        crushedLabel(frameId) {
            return this.frameOf(frameId)?.crushed ?? ""
        },

        /** The strip's right corner, the fraction of the frame's pixels blown, "14.2%"; empty for a frame not measured */
        blownLabel(frameId) {
            return this.frameOf(frameId)?.blown ?? ""
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
         * An image's `load` or `error`. A failed image shows as the browser shows one, with its `alt`,
         * and is noted as unreadable: it has no pixels for the Clipping blink. The Loupe re-reads once
         * Alpine has shown the image: the frame under a resting pointer changed. While the blink is
         * on, the pane's mask is drawn here, the one moment a pane's picture changes.
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
         * A loaded image's Clipping read, from the set's cache by its source: the read done, or the
         * promise of one in flight, which a second draw of the same source awaits too; else started
         * and cached as its promise, replaced by the read when it is in. A failed read leaves the
         * cache, so the next draw of its source tries again
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
         * Paints the mask of the pane's image into the pane's Clipping canvas, at the read's own size,
         * which `object-fit: contain` lays over the painted picture pixel for pixel, in the colors the
         * canvas has at this moment. A canvas that already holds the mask of that source is left alone.
         * Any other is blanked first, so no earlier frame's mask stays over the image, and stays blank
         * for an image with no pixels to read. The read is the set's cached one or starts here, once
         * Alpine has shown the image, so its decode never holds up the pane's paint. It paints only
         * if, when it is in, the set and the pane's image are the ones it was started for, the
         * blink is still on and no other draw has painted it; a read that fails (a tainted canvas, or a canvas the browser refuses)
         * leaves the canvas blank and breaks nothing else.
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

        /**
         * The Histogram button's click: flips the blink, and on draws the mask of both panes, each
         * when its read is in
         */
        toggleClippingBlink() {
            this.clippingBlink = !this.clippingBlink
            if (this.clippingBlink) {
                for (const imageId of [PINNED_IMAGE_ID, FOCUSED_IMAGE_ID]) {
                    this.drawClipping(imageId)
                }
            }
        },

        /**
         * The H key: shows or hides both panes' Histogram strips and remembers which. The strip is the
         * Clipping blink's only switch, so hiding it turns the blink off, and a strip that held focus
         * hands it to the shell, since a hidden control cannot keep it. Nothing with no set on show,
         * where the change would not be seen
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
                // What the stage's strip shows of the frame, as the import measured it: its Histogram's
                // bytes in Base64, and the labels of the fractions of its pixels crushed and blown
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
            // The fragment's images and canvases are new: they have settled on nothing and drawn nothing,
            // and the set's reads and Histogram paths are its own
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
                // The wheel's step holds for the next set's Loupe, and for the next open
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

        /** Reads what the component is opened on, at its current position, and shows it */
        openSet(options) {
            return this.reading(() => this.readSet(options))
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
         * Reads the set the walk stands on. On the queue it is the move after an outcome from the row at
         * the position, which holds for a set read again (a recycling, an Undo) as for the set after a
         * Resolve, whose row is done by then: the nearest open row at or below the position, else the
         * first open row from the top, else the end state. `options` hold for the row the read starts
         * on; the row after a row passed over opens in the view remembered for it, if any, else as a
         * new set.
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
         * Starts a read of the queue: the token the read is told apart by, and the signal that aborts
         * it once a later read starts, so an overtaken move holds nothing up while its answer is on
         * the way. The writes and the moves never read at the same time, so the one controller is
         * always the latest read's
         */
        nextMoveToken() {
            moveAbort?.abort()
            moveAbort = new AbortController()
            return { token: ++moveToken, signal: moveAbort.signal }
        },

        /**
         * Remembers what the user made of the set on show (`currentView`), for the walk's return to
         * it; nothing with no set on show, and nothing for a done row, whose set is never returned to
         */
        rememberShownView() {
            if (
                this.showsSet &&
                this.shownSequenceId &&
                !this.doneIds.has(this.shownSequenceId)
            ) {
                rememberedViews.set(this.shownSequenceId, this.currentView())
            }
        },

        /**
         * The index of the nearest open row from `index` in the direction of `step`, `index` itself
         * included; -1 with none
         */
        openRowFrom(index, step) {
            for (; index >= 0 && index < this.queueIds.length; index += step) {
                if (!this.doneIds.has(this.queueIds[index])) {
                    return index
                }
            }
            return -1
        },

        /**
         * Walks the queue's open rows from `startIndex` (-1 for none in that direction) in the
         * direction of `step`, putting the walk on each row as it is read: the first that answers a set
         * is shown, in the view remembered for it or in `options`' view on the row the read started on,
         * and one that answers with no content is marked done and passed over. With `wrap`, the move
         * after an outcome, the rows are tried again from the top once the direction is exhausted, and
         * with every row done the end state shows, the count read afresh; without it, the move of N, P
         * or a click, a direction exhausted leaves the walk on the set on show and its row, or, with no
         * set on show, where the walk stood before the move (`fromIndex`). A read whose `token` a later
         * move has overtaken is dropped before it shows anything, and aborted through `signal`. The set
         * on show is remembered as the set read replaces it, so what the user made of it while the
         * read was on its way is kept too.
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

        /**
         * A row is done: its set was resolved or kept, or its Sequence answered with no content. The
         * walk passes over it, what the user made of its set is forgotten, and the rail dims it
         */
        markDone(sequenceId) {
            this.doneIds.add(sequenceId)
            rememberedViews.delete(sequenceId)
            this.paintRail()
        },

        /**
         * Moves the walk to the row at `index` (nothing for -1, as when no open row is left in the
         * direction) and reads its set, going on in the direction of `step` past a row that answers
         * with no content. The walk stands on the row at once, so the rail's highlight moves before
         * the read answers, and the set left remembers what the user made of it: here, so a failed read,
         * which replaces the set with the unread state, loses nothing, and again as the set read
         * replaces it (`readQueueFrom`), with whatever the user did meanwhile. The latest move wins:
         * the token tells a read overtaken by a later move to drop its answer, and the controller
         * aborts it, so the last move is the set that opens and no dead read holds the writes up; no
         * move starts while a write runs, as no write starts while a move reads (`exclusively`). A
         * failed read shows the unread state, as any does, and the walk still moves from there
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
                    // An overtaken read fails unseen too, aborted or not: the later move's answer is
                    // what shows
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

        /**
         * Preloads the opening frames of the next open row in the direction of the last move, the set
         * N or P goes to; a failure costs only the head start
         */
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
         * Reads the queue's rows afresh into the Sequence rail and walks them from the start, every row
         * open again and nothing remembered of the sets: Start again offers a Sequence over the window
         * once more, with the Sequences imported meanwhile
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
         * Reads the queue's rows into the Sequence rail's host and takes the walk's IDs from them, in
         * their order; nothing binds them. A done mark of a Sequence no longer listed goes with it, and
         * with `reset`, Start again's, every mark and every remembered view goes. Nothing changes until
         * the rows are in, so a failed read leaves the state and the rows painted as they were
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

        /**
         * Paints the Sequence rail's rows for the walk: the row at the position is the current one,
         * kept scrolled into view (none in the end state, whose position is -1), and a row of a done
         * Sequence is done. Direct DOM writes rather than a binding per row, so a long queue costs
         * nothing per move
         */
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

        /**
         * A click on a row of the Sequence rail moves the walk to it (`move`), going on away from the
         * walk's position past a row that answers with no content; the current row and a done row do
         * nothing
         */
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

        /**
         * The Q key and the header's Sequences button: shows or hides the Sequence rail and remembers
         * which. The queue alone has one, so nothing happens elsewhere. A hidden row cannot be scrolled
         * to, so the rail shown is painted again once Alpine has shown it, and the current row is in view
         */
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
         * Runs one action at a time: a key pressed while a set is being resolved or read does nothing,
         * and nothing starts while a move is reading (`move`), as no move starts during an action.
         * A failed write is reported and leaves the set on show, nothing having been saved; a failed read
         * never reaches this (`reading`). An action finishing after Curation closed announces what it
         * changed, which closing could not know yet.
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

        /**
         * The set after an outcome: on the queue the move `readSet` makes from the row the walk stands
         * on, which the outcome left done. Nothing else has a next set, so Curation closes
         */
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
         * Keeps the frames named and recycles the others; with none named, as while the footer's hint shows
         * or with no set on show, it does nothing. The server's answer says whether a set changed:
         * nothing recycled and no Sequence cleared is a Keep all on part of a Sequence, or on frames of none.
         * On the queue the set's row is done either way, changed or not, and the walk goes on to its next
         * set. With no next set, an outcome that changed nothing closes Curation, and any other stays open
         * on its report, keeping its undo entry.
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
         * Undoes the last mutation and reads its set again by its own ID, wherever the walk has gone
         * since: on the queue its row is open again and the walk stands on it (`returnToSequence`), and
         * another set on show is remembered as a move remembers it. The return and the read are one
         * read (`reading`), so a failure in either shows the unread state and Try again runs both. A
         * Resolve or a Keep all goes through `reopen`, which also puts the Picks back in the
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
         * Puts the walk on a Sequence undone, its row open again. Start again may have replaced the rows
         * it was walked in, and a Sequence resolved was in no new list; undone, it is in the queue
         * again, so the rows are read afresh, which lists it with its cover, its count and its date,
         * and the done marks of the rows still listed stay. Were it still absent, the walk would stand
         * on the first row and read from there. A failed read of the rows is its caller's to report
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
         * The keys, bound while Curation is the active host: ← ↑ move the focused frame back through the
         * rail's order and → ↓ move it on, 1 toggles the left frame as a Pick, or the only frame when one is
         * on the stage, and 2 the right frame, with nothing to do when one is on the stage. Shift+1 and
         * Shift+2 recycle that same frame at once. Space pins the focused frame and moves on, and does
         * nothing on the pinned frame. Enter resolves, which does nothing while no frame is to be kept
         * (`keepIds`), A keeps all, R recycles the focused frame's Bundle, H shows or hides the Histogram
         * strips, Q the Sequence rail, N and P move the walk to the nearest open row below or above (the
         * queue alone) and Z undoes. Esc is `global.js`'s. A key held down repeats only the arrows, so it
         * cannot resolve set after set or run through the queue, and Enter or Space on a focused control
         * presses that control. While a set is being resolved or read an arrow moves nothing, and still
         * does not scroll the rail. With no set on show there are no frames and no focused frame, and H
         * leaves the strips as they are, so only N, P, Q, Z and Esc do anything: the unread state is left
         * by a move as by Undo.
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
