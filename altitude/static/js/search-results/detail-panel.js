/**
 * The Detail panel of Asset detail (the asset's People and Details, `htmx/detail_panel`) and the Face
 * highlights drawn from its People rows. The fragment loads only while the panel is open or the
 * highlights are on, and an answer that arrives after a step or a close is dropped.
 *
 * A correction that leaves the viewed Person with no Face on the asset keeps the asset shown; its
 * cells leave the grid on stepping away or closing. A merge involving the viewed Person, or naming a
 * Person the results title shows as a chip, runs the search again on closing, not on a step.
 */
import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { ensureChoiceMenus } from "../common/choice-menus.js"
import { createDock } from "../common/dock.js"
import { getActiveModalHost, ModalHost } from "../common/modal.js"
import { showErrorSnackBar, showSuccessSnackBar } from "../common/snackbar.js"
import { readStoredObject, writeStoredFields } from "../common/stored-state.js"
import { getHttpErrorMessage, http } from "../http/client.js"
import { mapBox } from "../edit/geometry.js"
import { createFaceCorrections } from "./face-corrections.js"
import {
    createFaceHighlights,
    FRAME_TIME_TOLERANCE_MS,
    LAYER_ID,
} from "./face-highlights.js"
import { showsPersonChip } from "./filtered-title.js"
import { reloadPersonTitle } from "./person-title.js"
import { runSearch } from "./search.js"

export const DEFAULT_WIDTH_SHARE = 30
export const DEFAULT_HEIGHT_SHARE = 35

const VIEW_ID = "imageDetailView"
const STAGE_ID = "imageDetailStage"
const CONTENT_ID = "detailPanelContent"
const PEOPLE_ID = "detailPanelPeople"
const DIVIDER_ID = "detailPanelDivider"

export function createDetailPanel({ context, assetActions, detailUrl }) {
    // The remembered settings but the dock's, which the dock keeps
    const state = rememberedState()

    // The asset shown or loading, with its request's `isCurrent`
    let subject = null
    // The shown media's upright size, the Edit the stage shows of it, if any, and whether the Face
    // highlights may be drawn on it (not on the Crop tool's whole frame)
    let shownSize = null
    let shownEdit = null
    let highlightsShown = true
    // The asset whose fragment is in the panel, or on its way there
    let loadedAssetId = null
    // The one state the corrections act on, never remembered
    let selectedFaceId = null
    // An asset the viewed Person has no Face left on: its cells leave the grid on stepping away or
    // closing
    let leaving = null
    // The merge destination of the viewed Person, or the viewed Person when another was merged into
    // them: their results are searched on closing
    let mergedInto = null
    // A Person shown as a chip in the results title was named here, so the search runs again on
    // closing to show the name
    let hasStaleChip = false
    // Told when the panel's side changes or it opens or closes (the Edit panel, docked opposite)
    const layoutListeners = new Set()

    const highlights = createFaceHighlights()
    const corrections = createFaceCorrections({
        context,
        sectionEl,
        afterEdit,
        reloadSection,
        cancelChange,
    })

    const dock = createDock({
        storage: { key: Const.localStore.assetDetailView, field: "dock" },
        pickAttribute: Const.attributes.detailDockSide,
        layout: {
            sideAttribute: "data-detail-dock-side",
            shareProperty: "--detail-panel-share",
        },
        container: viewEl,
        sizeEl: viewEl,
        divider: () => document.getElementById(DIVIDER_ID),
        defaults: {
            side: () => Const.dockSides.right,
            widthShare: DEFAULT_WIDTH_SHARE,
            heightShare: DEFAULT_HEIGHT_SHARE,
        },
        minimums: panelMinimums,
        orientsDivider: true,
        onSideChange: announceLayout,
    })

    function announceLayout() {
        layoutListeners.forEach((listener) => listener())
    }

    function viewEl() {
        return document.getElementById(VIEW_ID)
    }

    function contentEl() {
        return document.getElementById(CONTENT_ID)
    }

    function shownVideoEl() {
        const videoEl = document.querySelector(`#${STAGE_ID} video`)
        return videoEl && !videoEl.closest("media-controller").hidden
            ? videoEl
            : null
    }

    /** Binds the page-wide controls once */
    function start() {
        dock.start()
        corrections.bind()

        // Closing Asset detail flushes what the corrections left pending. The flush runs in a
        // microtask, outside the effect's tracking, so the effect follows the active host alone and
        // not the stores the flush writes
        Alpine.effect(() => {
            if (getActiveModalHost() !== ModalHost.assetDetail) {
                queueMicrotask(() => flushLeaving({ closing: true }))
            }
        })

        document.addEventListener("click", (event) => {
            const target = event.target
            if (!(target instanceof Element)) {
                return
            }

            if (target.closest(`[${Const.attributes.detailPanelToggle}]`)) {
                toggle()
            } else if (
                target.closest(`[${Const.attributes.faceHighlightsToggle}]`)
            ) {
                toggleHighlights()
            } else if (target.closest(`[${Const.attributes.faceRow}]`)) {
                selectFace(
                    target
                        .closest(`[${Const.attributes.faceId}]`)
                        .getAttribute(Const.attributes.faceId),
                )
            } else if (
                target.closest(`#${LAYER_ID} [${Const.attributes.faceId}]`)
            ) {
                selectFromHighlight(
                    target
                        .closest(`[${Const.attributes.faceId}]`)
                        .getAttribute(Const.attributes.faceId),
                )
            }
        })

        syncToggleButtons()
    }

    /**
     * A new Asset detail fragment arrived. Its choice menus are built first so the dock finds its
     * trigger; the panel opens as the URL says, else as remembered.
     */
    function attach(fragmentEl) {
        ensureChoiceMenus(fragmentEl)
        loadedAssetId = null
        state.panelOpen =
            detailUrl.panelOpenInLocation() ?? rememberedState().panelOpen
        applyOpenState()
        dock.layOut()
        dock.bindDivider(fragmentEl.querySelector(`#${DIVIDER_ID}`))

        const videoEl = fragmentEl.querySelector("video")
        for (const type of ["pause", "play", "seeked"]) {
            videoEl?.addEventListener(type, drawHighlights)
        }
    }

    /**
     * Asset detail shows, or starts loading, `assetId`. The panel empties when the asset is another,
     * so the last asset's details are never read as this one's. With `load` false (a step's first
     * call, before the asset's fetch has succeeded) nothing loads and the URL stays, so it never
     * names an asset that could not be read.
     */
    function showAsset({ assetId, isCurrent, load = true }) {
        // The step has picked its target by now, so the cells of the asset it left may go
        if (leaving && leaving.assetId !== assetId) {
            flushLeaving()
        }

        subject = { assetId, isCurrent }
        shownSize = null
        shownEdit = null
        highlightsShown = true
        selectedFaceId = null
        corrections.selectionChanged()
        highlights.clear()
        if (loadedAssetId !== assetId) {
            loadedAssetId = null
            contentEl()?.replaceChildren()
        } else {
            paintRows()
        }
        if (load) {
            detailUrl.show({ assetId, panelOpen: state.panelOpen })
            loadIfWanted()
        }
    }

    /**
     * The shown media's upright size, which the dimensions row and the Face highlights read. `edit`
     * is the Edit the stage shows in its place (`{ recipe, width, height }`), or null when it changes
     * no pixel; `highlights` is false while the stage draws the whole frame (the Crop tool).
     */
    function setShownSize({ width, height, edit = null, highlights = true }) {
        shownSize = { width, height }
        shownEdit = edit
        highlightsShown = highlights
        writeDimensions()
        drawHighlights()
    }

    function toggle() {
        state.panelOpen = !state.panelOpen
        remember({ panelOpen: state.panelOpen })
        applyOpenState()
        if (subject) {
            detailUrl.show({
                assetId: subject.assetId,
                panelOpen: state.panelOpen,
            })
        }

        if (state.panelOpen) {
            // The divider's bounds change with the panel shown
            dock.layOut()
            loadIfWanted()
        }
        announceLayout()
    }

    function toggleHighlights() {
        state.highlightsOn = !state.highlightsOn
        selectedFaceId = null
        corrections.selectionChanged()
        remember({ highlightsOn: state.highlightsOn })
        syncToggleButtons()
        paintRows()
        loadIfWanted()
        drawHighlights()
    }

    /** Selects `faceId`, or clears it; on a Video the player seeks to its Frame time and pauses */
    function selectFace(faceId) {
        selectedFaceId = selectedFaceId === faceId ? null : faceId
        corrections.selectionChanged()
        paintRows()

        const face = selectedFaceId && faces().find((f) => f.faceId === faceId)
        const videoEl = shownVideoEl()
        if (face && videoEl && face.frameTimeMs !== null) {
            videoEl.currentTime = face.frameTimeMs / 1000
            videoEl.pause()
        }

        drawHighlights()
    }

    /** Selects as a row does, then opens the panel and brings the row into view */
    function selectFromHighlight(faceId) {
        selectFace(faceId)

        if (selectedFaceId !== faceId) {
            return
        }

        if (!state.panelOpen) {
            toggle()
        }
        rowOf(faceId)?.scrollIntoView({ block: "nearest" })
    }

    /** The Face highlights read the fragment's rows, so they need it too */
    function wantsFragment() {
        return state.panelOpen || state.highlightsOn
    }

    async function loadIfWanted() {
        const hostEl = contentEl()
        if (
            !subject ||
            !hostEl ||
            !wantsFragment() ||
            loadedAssetId === subject.assetId
        ) {
            return
        }

        const { assetId, isCurrent } = subject
        // Claimed before the request, so a second call while it is in flight loads nothing again
        loadedAssetId = assetId

        try {
            const response = await http.get(
                `/htmx/asset/r/${context.getRepoId()}/detail-panel/${assetId}`,
            )

            if (!isCurrent() || subject?.assetId !== assetId) {
                return
            }

            hostEl.innerHTML = response.data
            htmx.process(hostEl)
            writeDimensions()
            paintRows()
            drawHighlights()
        } catch (error) {
            if (!isCurrent() || subject?.assetId !== assetId) {
                return
            }

            loadedAssetId = null
            console.error(
                `Error loading the Detail panel: ${getHttpErrorMessage(error)}`,
            )
            showErrorSnackBar("Could not load the asset's details")
        }
    }

    function sectionEl() {
        return contentEl()?.querySelector(`#${PEOPLE_ID}`) ?? null
    }

    /**
     * A correction answered. A rename or merge of the viewed Person is noted even when the asset is
     * no longer shown, and searched at once if Asset detail closed while the request was out; the
     * panel itself changes only while the asset is still shown.
     */
    function afterEdit({
        sectionEl: newSectionEl,
        message,
        deletedPersonId,
        deletedPersonMessage,
        mergedPersonId,
        mergedIntoId,
        namedPersonId,
    }) {
        showSuccessSnackBar(
            deletedPersonMessage
                ? `${message}. ${deletedPersonMessage}`
                : message,
        )
        document.body.dispatchEvent(
            new CustomEvent(Const.events.peopleChanged, {
                detail: { personId: deletedPersonId },
            }),
        )

        const searchedPersonId = Alpine.store(
            Const.state.searchParams,
        ).viewedPersonId()
        if (namedPersonId && namedPersonId === searchedPersonId) {
            reloadPersonTitle(context.getRepoId(), namedPersonId)
        }
        if (namedPersonId && showsPersonChip(namedPersonId)) {
            hasStaleChip = true
            if (getActiveModalHost() !== ModalHost.assetDetail) {
                flushLeaving({ closing: true })
            }
        }
        if (
            mergedPersonId &&
            [mergedPersonId, mergedIntoId].includes(searchedPersonId)
        ) {
            mergedInto = mergedIntoId
            if (getActiveModalHost() !== ModalHost.assetDetail) {
                flushLeaving({ closing: true })
            }
        }

        const shownSectionEl = sectionEl()
        if (
            !shownSectionEl ||
            newSectionEl.getAttribute(Const.attributes.appAssetId) !==
                subject?.assetId
        ) {
            return
        }

        shownSectionEl.replaceWith(newSectionEl)
        selectedFaceId = null
        corrections.selectionChanged()
        paintRows()
        drawHighlights()
        noteViewedPerson(newSectionEl, deletedPersonId)
        newSectionEl
            .querySelector(`[${Const.attributes.faceRow}]`)
            ?.focus({ preventScroll: true })
    }

    /** A correction found the section stale: shows the server's message and loads the section again */
    function reloadSection(message) {
        showErrorSnackBar(message)
        selectedFaceId = null
        corrections.selectionChanged()
        loadedAssetId = null
        loadIfWanted()
    }

    /** The combobox was cancelled: deselects the row and returns focus to it */
    function cancelChange(faceId) {
        if (selectedFaceId === faceId) {
            selectFace(faceId)
        }
        rowOf(faceId)
            ?.querySelector(`[${Const.attributes.faceRow}]`)
            ?.focus({ preventScroll: true })
    }

    /**
     * Marks the asset leaving when the viewed Person (or their merge destination) has no Face left on
     * it or was deleted. A later correction that gives a Face back keeps it, unless they were deleted.
     */
    function noteViewedPerson(newSectionEl, deletedPersonId) {
        const personId =
            mergedInto ??
            Alpine.store(Const.state.searchParams).viewedPersonId()
        if (!personId) {
            leaving = null
            return
        }

        const deleted = leaving?.deleted || deletedPersonId === personId
        const hasFace = newSectionEl.querySelector(
            `[${Const.attributes.faceId}][${Const.attributes.personId}="${CSS.escape(personId)}"]`,
        )

        leaving =
            deleted || !hasFace
                ? { assetId: subject.assetId, personId, deleted }
                : null
    }

    /**
     * Removes the cells of the asset the viewed Person left and reads the results title again, since
     * its links depend on the count; a deleted Person sends the results back to the whole repository.
     * Only on `closing` does a pending merge or stale chip run the search again; until then a pending
     * merge leaves the title for that search to replace.
     */
    function flushLeaving({ closing = false } = {}) {
        const left = leaving
        const destinationId = closing ? mergedInto : null
        const namedInTitle = closing && hasStaleChip
        leaving = null
        if (closing) {
            mergedInto = null
            hasStaleChip = false
        }

        if (left) {
            assetActions.removeAssetsFromGrid([left.assetId])
        }

        // The search drops the viewed Person, or puts their destination in their place, and keeps
        // every other filter
        const searchParams = Alpine.store(Const.state.searchParams)
        if (left?.deleted) {
            mergedInto = null
            searchParams.replaceId(
                "personId",
                searchParams.viewedPersonId(),
                null,
            )
            runSearch()
        } else if (destinationId) {
            searchParams.replaceId(
                "personId",
                searchParams.viewedPersonId(),
                destinationId,
            )
            runSearch()
        } else if (namedInTitle) {
            runSearch()
        } else if (left && !mergedInto) {
            reloadPersonTitle(context.getRepoId(), left.personId)
        }
    }

    /** The stylesheet lays the grid out from the fragment's open attribute */
    function applyOpenState() {
        viewEl()?.toggleAttribute(
            Const.attributes.detailPanelOpen,
            state.panelOpen,
        )
        syncToggleButtons()
    }

    function syncToggleButtons() {
        const pressed = {
            [Const.attributes.detailPanelToggle]: state.panelOpen,
            [Const.attributes.faceHighlightsToggle]: state.highlightsOn,
        }
        for (const [attribute, value] of Object.entries(pressed)) {
            document
                .querySelectorAll(`[${attribute}]`)
                .forEach((buttonEl) =>
                    buttonEl.setAttribute("aria-pressed", String(value)),
                )
        }
    }

    /** The server's header size stays until the media is shown; then the upright shown size, plus the edited one */
    function writeDimensions() {
        const dimensionsEl = contentEl()?.querySelector(
            `[${Const.attributes.detailDimensions}]`,
        )
        if (dimensionsEl && shownSize) {
            const edited = shownEdit
                ? ` · edited ${shownEdit.width} × ${shownEdit.height}`
                : ""
            dimensionsEl.textContent = `${shownSize.width} × ${shownSize.height} px${edited}`
        }
    }

    function rowEls() {
        return [
            ...(contentEl()?.querySelectorAll(
                `#${PEOPLE_ID} [${Const.attributes.faceId}]`,
            ) ?? []),
        ]
    }

    function rowOf(faceId) {
        return rowEls().find(
            (rowEl) => rowEl.getAttribute(Const.attributes.faceId) === faceId,
        )
    }

    function faces() {
        return rowEls().map((rowEl) => {
            const [x, y, width, height] = rowEl.dataset.faceBox
                .split(",")
                .map(Number)
            const frameTime = rowEl.dataset.frameTimeMs
            return {
                faceId: rowEl.getAttribute(Const.attributes.faceId),
                name: rowEl.dataset.personName,
                hidden: rowEl.dataset.personHidden === "true",
                box: { x, y, width, height },
                frameTimeMs: frameTime === undefined ? null : Number(frameTime),
            }
        })
    }

    function paintRows() {
        rowEls().forEach((rowEl) =>
            rowEl
                .querySelector(`[${Const.attributes.faceRow}]`)
                ?.setAttribute(
                    "aria-pressed",
                    String(
                        rowEl.getAttribute(Const.attributes.faceId) ===
                            selectedFaceId,
                    ),
                ),
        )
    }

    /** On a Video, a Face is on the frame only while the player is paused within the tolerance of its Frame time */
    function isOnShownFrame(face, videoEl) {
        if (!videoEl) {
            return true
        }
        return (
            videoEl.paused &&
            face.frameTimeMs !== null &&
            Math.abs(videoEl.currentTime * 1000 - face.frameTimeMs) <=
                FRAME_TIME_TOLERANCE_MS
        )
    }

    function drawHighlights() {
        if (!shownSize || !highlightsShown) {
            highlights.clear()
            return
        }

        const videoEl = shownVideoEl()
        highlights.show({
            faces: faces()
                .filter(
                    (face) =>
                        (state.highlightsOn ||
                            face.faceId === selectedFaceId) &&
                        isOnShownFrame(face, videoEl),
                )
                .map((face) => ({ ...face, box: shownBoxOf(face.box) }))
                .filter((face) => face.box !== null),
            selectedFaceId,
        })
    }

    /** A Face's box in the upright original's pixels as fractions of the shown media, or null when the Edit's crop leaves it out */
    function shownBoxOf(box) {
        if (shownEdit) {
            return mapBox(
                box,
                shownEdit.recipe,
                shownSize.width,
                shownSize.height,
            )
        }
        return {
            x: box.x / shownSize.width,
            y: box.y / shownSize.height,
            width: box.width / shownSize.width,
            height: box.height / shownSize.height,
        }
    }

    /**
     * The least the panel and the stage may be squeezed to along the axis, in px, from the `core.css`
     * tokens; the stage's share also holds the Edit panel and its divider, docked opposite
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
                vertical
                    ? "--detail-panel-min-height"
                    : "--detail-panel-min-width",
            ),
            content:
                token(
                    vertical
                        ? "--detail-stage-min-height"
                        : "--detail-stage-min-width",
                ) +
                sizeOf("editPanel") +
                sizeOf("editPanelDivider"),
        }
    }

    function rememberedState() {
        const saved = readStoredObject(Const.localStore.assetDetailView)

        return {
            panelOpen: saved.panelOpen === true,
            highlightsOn: saved.highlightsOn === true,
        }
    }

    /** Writes only the changed fields, keeping the dock's part and a panel state an asset's URL is overriding */
    function remember(fields) {
        writeStoredFields(Const.localStore.assetDetailView, fields)
    }

    return {
        start,
        attach,
        showAsset,
        setShownSize,
        toggle,
        toggleHighlights,
        dockSide: () => dock.side(),
        isOpen: () => state.panelOpen,
        /** Calls `listener` after the panel's side changes and after it opens or closes */
        onLayoutChange: (listener) => layoutListeners.add(listener),
        /** Lays the panel out again within its current bounds, as when the Edit panel opens or closes */
        layOut: () => dock.layOut(),
    }
}
