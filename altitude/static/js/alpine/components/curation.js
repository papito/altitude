import { Alpine } from "../../lib/alpine.esm.min.js"
import { reportRestore } from "../../assets/asset-actions.js"
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
 * whose IDs it posts, so they never sit in a URL. Neither of the last two has a next set.
 *
 * Every action is saved as it happens, so closing loses only the skips and the undo entry. Exit and
 * Esc close it through `closeModal` (`global.js` handles Esc for every host), and this component
 * notices the host going, then announces what the session left changed
 * (`Const.events.curationClosed`) and removes the shell. The keys it binds are document-level, so they
 * work wherever focus is inside the host, and are ignored while a text field has focus.
 */

const Phase = {
    // No set has been read yet
    loading: "loading",
    set: "set",
    // A Sequence opened alone has too few frames left
    tooFew: "tooFew",
    // The queue's IDs ran out
    end: "end",
}

// How much of the pane a zoomed Face's box fills, so its surroundings stay in view
const ZOOMED_BOX_SHARE = 0.5

const PINNED_IMAGE_ID = "curationStagePinned"
const FOCUSED_IMAGE_ID = "curationStageFocused"

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

/** A Sequence's fragment, or null when it answers with no content: fewer than two frames left */
async function readSequenceSet(sequenceId) {
    const response = await http.get(
        repoPath("htmx/curation", `/sequence/${sequenceId}`),
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
 * Loads the originals a set's fragment opens on, so the next set paints at once. Their URLs never
 * change, so the browser serves them from its cache when the set opens.
 */
function preloadOpeningFrames(html) {
    const setEl = new DOMParser()
        .parseFromString(html, "text/html")
        .querySelector(`[${Const.attributes.openingFocusedId}]`)

    for (const frameId of [
        setEl?.dataset.openingPinnedId,
        setEl?.dataset.openingFocusedId,
    ]) {
        if (frameId) {
            new Image().src = fileUrl(frameId)
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
    let isBusy = false
    let isClosed = false
    // What the session leaves changed, net of its undos, for the grid beneath
    const recycledIds = new Set()
    const clearedSequenceIds = new Set()

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

        // The set shown, in the rail's order: `{ id, fileName, bundleKind, inAlbumOrLocation }`
        frames: [],
        // A frame's Bundle by its ID; a Contender has none
        bundleOf: {},
        focusedId: null,
        pinnedId: null,
        pickIds: [],
        // Whether each Bundle of the set is open, by its kind
        openBundles: {},
        zoomPersonId: null,

        // What Curation walks: the queue's IDs and where it stands in them, or else one Sequence
        // or a selection's IDs, which have no next set
        queueIds: [],
        position: 0,
        sequenceId: null,
        selectionIds: null,

        // The last mutation, for Undo: `{ keepIds, recycleIds, sequenceOf, position }` of a Resolve or
        // a Keep all, `{ recycleIds, position }` of a recycled Bundle
        undoEntry: null,

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

        get showsEnd() {
            return this.phase === Phase.end
        },

        get isAllDone() {
            return !Alpine.store(Const.state.sequencesToCurate).hasAny
        },

        // The stage shows the pinned frame beside the focused one, or one frame when they are the same
        get showsPinnedPane() {
            return this.pinnedId !== null && this.pinnedId !== this.focusedId
        },

        // The frames kept: the Picks, or with none the focused frame
        get keepIds() {
            return this.pickIds.length > 0
                ? this.pickIds
                : [this.focusedId].filter(Boolean)
        },

        get resolveLabel() {
            const kept = this.keepIds.length
            return `Keep ${kept}, recycle ${this.frames.length - kept}`
        },

        // The set's footer, when a frame to be recycled is in an album or a Location
        get recycledInAlbumOrLocationNote() {
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

        fileNameOf(frameId) {
            return this.frames.find((frame) => frame.id === frameId)?.fileName
        },

        isPick(frameId) {
            return this.pickIds.includes(frameId)
        },

        isBundleOpen(kind) {
            return this.openBundles[kind] === true
        },

        isFrameShown(frameId) {
            const kind = this.bundleOf[frameId]
            return !kind || this.isBundleOpen(kind)
        },

        frameClasses(frameId) {
            return {
                focused: this.focusedId === frameId,
                pinned: this.pinnedId === frameId,
                pick: this.isPick(frameId),
            }
        },

        columnClasses(frameId) {
            return {
                focused: this.focusedId === frameId,
                pinned: this.pinnedId === frameId,
            }
        },

        bundleToggleLabel(kind) {
            return this.isBundleOpen(kind) ? "Hide" : "Show"
        },

        pinLabel(frameId) {
            return this.pinnedId === frameId ? "Unpin" : "Pin"
        },

        pickLabel(frameId) {
            return this.isPick(frameId) ? "Picked" : "Pick"
        },

        focusFrame(frameId) {
            this.focusedId = frameId

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

        /** Moves the focused frame through the rail's order, past the frames of a collapsed Bundle */
        moveFocus(step) {
            const from = this.frames.findIndex(
                (frame) => frame.id === this.focusedId,
            )
            for (
                let index = from + step;
                from >= 0 && index >= 0 && index < this.frames.length;
                index += step
            ) {
                if (this.isFrameShown(this.frames[index].id)) {
                    this.focusFrame(this.frames[index].id)
                    return
                }
            }
        },

        togglePin(frameId) {
            this.pinnedId = this.pinnedId === frameId ? null : frameId
        },

        togglePick(frameId) {
            this.pickIds = this.isPick(frameId)
                ? this.pickIds.filter((id) => id !== frameId)
                : [...this.pickIds, frameId]
        },

        toggleBundle(kind) {
            this.openBundles[kind] = !this.isBundleOpen(kind)
        },

        zoomToPerson(personId) {
            this.zoomPersonId = this.zoomPersonId === personId ? null : personId
            this.applyZoom()
        },

        /**
         * Applies the zoom to both panes, for the frames they show. It runs when a crop is clicked, when
         * an image has loaded, since its natural size is what the box is held against, and when a pane
         * resizes. A frame with no Face of the Person shows whole.
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
                imageEl.style.transform =
                    box && imageEl.naturalWidth && imageEl.clientWidth
                        ? zoomTransform(imageEl, box)
                        : ""
            }
        },

        /**
         * Puts a set's fragment on show and reads its frames. `keepView` is for the same set read again:
         * what the user had pinned, focused, picked and opened stays as far as its frames are still in
         * the set, and anything else opens as a new set does.
         */
        showSet(html, { keepView = false } = {}) {
            if (isClosed) {
                return
            }

            const hostEl = document.getElementById("curationSet")
            hostEl.innerHTML = html
            const setEl = hostEl.querySelector(
                `[${Const.attributes.openingFocusedId}]`,
            )

            const frames = [
                ...hostEl.querySelectorAll(`[${Const.attributes.frameId}]`),
            ].map((frameEl) => ({
                id: frameEl.dataset.frameId,
                fileName: frameEl.dataset.fileName,
                bundleKind: frameEl.dataset.bundleKind ?? null,
                inAlbumOrLocation: frameEl.dataset.inAlbumOrLocation === "true",
            }))
            const isInSet = (frameId) =>
                frames.some((frame) => frame.id === frameId)
            const kept = (frameId, opening) =>
                keepView && isInSet(frameId) ? frameId : opening

            this.frames = frames
            this.bundleOf = Object.fromEntries(
                frames.map((frame) => [frame.id, frame.bundleKind]),
            )
            this.pinnedId = kept(
                this.pinnedId,
                setEl?.dataset.openingPinnedId ?? null,
            )
            this.focusedId = kept(
                this.focusedId,
                setEl?.dataset.openingFocusedId ?? null,
            )
            this.pickIds = keepView ? this.pickIds.filter(isInSet) : []
            this.zoomPersonId = keepView ? this.zoomPersonId : null

            // A Bundle collapses only in a set large enough, and the frames on the stage stay findable
            const collapses = setEl?.dataset.bundlesCollapse === "true"
            const wasOpen = keepView ? this.openBundles : {}
            this.openBundles = Object.fromEntries(
                frames
                    .filter((frame) => frame.bundleKind)
                    .map(({ bundleKind }) => [
                        bundleKind,
                        !collapses ||
                            wasOpen[bundleKind] === true ||
                            bundleKind === this.bundleOf[this.focusedId] ||
                            bundleKind === this.bundleOf[this.pinnedId],
                    ]),
            )

            this.phase = Phase.set
            this.watchStage()
            this.keepFocusInside()
        },

        // The fragment's images are new, and a pane resizing changes what a zoom has to do
        watchStage() {
            stageObserver.disconnect()
            for (const imageId of [PINNED_IMAGE_ID, FOCUSED_IMAGE_ID]) {
                const imageEl = document.getElementById(imageId)
                if (imageEl) {
                    stageObserver.observe(imageEl)
                }
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
            document.getElementById("curationSet").replaceChildren()
            stageObserver.disconnect()
            this.frames = []
            this.bundleOf = {}
            this.focusedId = null
            this.pinnedId = null
            this.pickIds = []
            this.phase = phase
        },

        /** Reads what the component is opened on, at its current position, and shows it */
        async openSet(options) {
            if (this.selectionIds) {
                this.showSet(await readSelectionSet(this.selectionIds), options)
                return
            }

            if (this.sequenceId) {
                const html = await readSequenceSet(this.sequenceId)
                if (html) {
                    this.showSet(html, options)
                } else if (!isClosed) {
                    this.showNoSet(Phase.tooFew)
                }
                return
            }

            // A Sequence gone meanwhile answers with no content, and is passed over without comment;
            // the set after it is a new one, whatever was asked of the set read
            const startedAt = this.position
            while (!isClosed && this.position < this.queueIds.length) {
                const html = await readSequenceSet(this.queueIds[this.position])
                if (html) {
                    this.showSet(
                        html,
                        this.position === startedAt ? options : undefined,
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
            return this.exclusively(async () => {
                const response = await http.get(
                    repoPath("api/curation", "/queue"),
                )
                this.queueIds = response.data.sequenceIds
                this.position = 0
                await this.openSet()
            })
        },

        /**
         * Runs one action at a time: a key pressed while a set is being resolved or read does nothing.
         * A failure is reported and leaves the set on show. An action finishing after Curation closed
         * announces what it changed, which closing could not know yet.
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

        /** Keeps the frames named and recycles the others, then goes on to the next set */
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

                if (
                    recycleIds.length > 0 ||
                    Object.keys(sequenceOf).length > 0
                ) {
                    this.undoEntry = {
                        keepIds,
                        recycleIds,
                        sequenceOf,
                        position: this.position,
                    }
                    recycleIds.forEach((id) => recycledIds.add(id))
                    Object.values(sequenceOf).forEach((id) =>
                        clearedSequenceIds.add(id),
                    )
                }
                announceChanged()

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

        /**
         * Recycles a Bundle's frames through the grid's own endpoint, leaving a Pick in it, and reads the
         * set again, so its Bundles are recomputed against the frames left.
         */
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

            return this.exclusively(async () => {
                await http.delete(repoPath("api/asset", "/move"), {
                    data: { assetIds },
                })

                this.undoEntry = {
                    recycleIds: assetIds,
                    position: this.position,
                }
                assetIds.forEach((id) => recycledIds.add(id))
                announceChanged()

                await this.openSet({ keepView: true })
            })
        },

        /**
         * Undoes the last mutation and reads its set again, wherever the walk has gone since. A Resolve
         * or a Keep all goes through `reopen`, which also puts the Picks back in the Sequences it cleared;
         * a recycled Bundle has no Pick and is a plain restore. A frame whose content was imported again
         * stays in the trash, as with any restore.
         */
        undo() {
            const entry = this.undoEntry
            if (!entry) {
                return
            }

            return this.exclusively(async () => {
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

                this.position = entry.position
                await this.openSet()
            })
        },

        /**
         * The keys, bound while Curation is the active host: ← → move the focused frame, C pins it, K
         * picks it, Enter resolves, A keeps all, R recycles its Bundle, S skips and Z undoes. Esc is
         * `global.js`'s. A key held down repeats only the arrows, so it cannot resolve set after set, and
         * Enter on a focused control presses that control.
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

            const key = event.key.toLowerCase()
            if (key === "enter" && event.target.closest?.("button, a")) {
                return
            }

            const repeatable = {
                arrowleft: () => this.moveFocus(-1),
                arrowright: () => this.moveFocus(1),
            }
            const once = {
                c: () => this.focusedId && this.togglePin(this.focusedId),
                k: () => this.focusedId && this.togglePick(this.focusedId),
                enter: () => this.resolve(),
                a: () => this.keepAll(),
                r: () => this.recycleFocusedBundle(),
                s: () => this.skip(),
                z: () => this.undo(),
            }
            const action =
                Object.hasOwn(repeatable, key) && !isBusy
                    ? repeatable[key]
                    : Object.hasOwn(once, key) && !event.repeat
                      ? once[key]
                      : null

            if (action) {
                event.preventDefault()
                action()
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
