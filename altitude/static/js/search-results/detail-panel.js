/**
 * The Detail panel of Asset detail (`views/htmx/view_image_detail_modal.scala.html`): the docked
 * panel holding the asset's People and its Details, which the server renders as
 * `htmx/detail_panel` and this module loads into `#detailPanelContent`; and the Face highlights
 * drawn from its People rows (`face-highlights.js`).
 *
 * The panel is open or closed (`m`, the toolbar's toggle), docked left, right, top or bottom of
 * the media (the Dock side menu in its header, `common/dock.js`: right until a pick, 30 % of the
 * width or 35 % of the height until a drag, never following the viewport's orientation). The Face
 * highlights are on or off (`p`, the toolbar's other toggle). All of it is remembered per browser
 * under `Const.localStore.assetDetailView` as `{ panelOpen, highlightsOn, dock: { side?, widthShare,
 * heightShare } }`, the dock's part written by the dock itself. By default the panel is closed and
 * the highlights are off. The address bar follows the panel and the asset (`detail-url.js`), and
 * when an open starts on an asset's URL, that URL says whether the panel is open, for that open
 * alone: only a toggle is remembered.
 *
 * The fragment loads when the panel is open or the highlights are on: on open, on each step, and
 * when either is switched on with the asset's details not loaded yet. The coordinator
 * (`detail-navigator.js`) names the asset shown through `showAsset` with its request's `isCurrent`,
 * so a response that arrives after a step or a close is dropped, as a superseded media load is; a
 * step names it twice, first with `load: false` as it starts, which only empties what the last
 * asset left, and again once the asset's metadata is in, which loads the fragment, so a failed
 * asset fetch costs no request here. The
 * Details' dimensions row reads the shown element's size, which the coordinator hands over once the
 * media is shown: a browser turns an image upright by its EXIF orientation, while the stored size is
 * the header's. The Face highlights are placed in that size too, from each row's `data-face-box`.
 *
 * The selection is one Face at most, the state the corrections act on, held here and never
 * remembered: a click on a People row's button or on a Face highlight selects that Face, a click on
 * the selected one clears it, and a step, `p` and each open clear it. A click on a Face highlight
 * also opens the panel when it is closed, as `m` would, and brings the row into view. With the
 * highlights on every Face shows, the selected one emphasised; off, the selected one alone. On a
 * Video a row's click seeks to the Face's Frame time and pauses, and a Face shows only while the
 * player is paused within `FRAME_TIME_TOLERANCE_MS` of its Frame time, so the layer is drawn again
 * on `pause`, `play` and `seeked`, and holds nothing during playback.
 *
 * The corrections, Change and Remove under the selected row, are `face-corrections.js`'s (the two
 * clicks, the requests) and the combobox's (`alpine/components/face-combobox.js`); this module takes
 * their outcome: the People section a correction answers with replaces the one shown, the selection
 * clears, the Face highlights are drawn again from the new rows, the result is a success snackbar
 * (with the line about a Person the correction deleted) and `peopleChanged` reloads the People tab.
 * A stale section (a 404 or 409) is reloaded with the server's message shown. When the viewed Person
 * (the one Person the search names, `searchParams.viewedPersonId()`; a search of several people has
 * none, and its grid waits for the next search) has no Face left on the asset, Asset detail stays on it and the grid
 * waits: on stepping away, once the step has picked its target, or on closing, the asset's cells
 * leave the grid and the results title is read again, as its Hide and Discard links depend on the
 * count, or, when the viewed Person was deleted, the results go back to the whole repository.
 *
 * A Change on a Face of a Person with no name can name or merge that Person as a whole. When the
 * viewed Person is the one named, the results title is read again at once and shows the name. When
 * the viewed Person is the one merged, every cell of the grid is the destination's now, so the cells
 * stay, the destination stands in for the viewed Person in later corrections, and on closing (not on
 * a step) the results become the destination's (`runSearch` with their `personId`). When the viewed
 * Person is the destination, the same search on closing reads their results again, so the grid gains
 * the merged Person's assets, and the total and the title's count-dependent links follow. Naming or
 * merging another Person changes nothing in the grid.
 *
 * The side and share are written on the fragment (`data-detail-dock-side`, `--detail-panel-share`)
 * and whether the panel is open as `data-app-detail-panel-open`; `view_image_detail_modal.css`
 * lays the grid out from them. The picks are `[data-app-detail-dock-side]`, the panel's own, since
 * the explorer's dock listens for its picks on the same body.
 */
import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { ensureChoiceMenus } from "../common/choice-menus.js"
import { createDock } from "../common/dock.js"
import { getActiveModalHost, ModalHost } from "../common/modal.js"
import { showErrorSnackBar, showSuccessSnackBar } from "../common/snackbar.js"
import { readStoredObject, writeStoredFields } from "../common/stored-state.js"
import { getHttpErrorMessage, http } from "../http/client.js"
import { createFaceCorrections } from "./face-corrections.js"
import {
    createFaceHighlights,
    FRAME_TIME_TOLERANCE_MS,
    LAYER_ID,
} from "./face-highlights.js"
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

    // The asset Asset detail shows or loads, with whether its request is still the current one
    let subject = null
    // The shown media element's size, once the media is shown
    let shownSize = null
    // The asset whose fragment is in the panel, or on its way there
    let loadedAssetId = null
    // The selected Face, or null; the one state the corrections act on, never remembered
    let selectedFaceId = null
    // An asset the viewed Person has no Face left on after a correction: its cells leave the grid
    // on stepping away or closing, with whether the Person was deleted
    let leaving = null
    // The Person the viewed Person was merged into from the panel, or the viewed Person when another
    // was merged into them: the results are searched for them on closing
    let mergedInto = null

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
    })

    function viewEl() {
        return document.getElementById(VIEW_ID)
    }

    function contentEl() {
        return document.getElementById(CONTENT_ID)
    }

    /** The shown Video's element, or null while an image is shown or nothing is */
    function shownVideoEl() {
        const videoEl = document.querySelector(`#${STAGE_ID} video`)
        return videoEl && !videoEl.closest("media-controller").hidden
            ? videoEl
            : null
    }

    /**
     * Binds the page-wide controls once: the dock's, the toolbar's toggles, the People rows and the
     * Face highlights
     */
    function start() {
        dock.start()
        corrections.bind()

        // Closing Asset detail is when an asset the viewed Person left leaves the grid, and when the
        // results of a viewed Person merged away become the destination's. The flush runs in a
        // microtask, outside the effect's tracking, so the effect follows the active host alone and
        // not the stores the removal and the search write
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
     * A new Asset detail fragment arrived: its Dock side menu is built so the dock finds its
     * trigger, the panel is open as the URL says when it names an asset and as remembered when it
     * does not, the panel is laid out and its divider bound, and its player's state changes redraw
     * the Face highlights. Nothing of the previous fragment is loaded in it.
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
     * Asset detail shows, or starts loading, `assetId`; `isCurrent` tells whether that request is
     * still the latest for the active open. The selection goes, the Face highlights are emptied
     * at once, the panel empties when the asset is another, so the last asset's details are never
     * read as this one's, and the fragment loads if it is wanted, unless `load` is false: the
     * coordinator's first call of a step, before the asset's own fetch has succeeded. The URL
     * moves to the asset at that same point, so it never names one that could not be read.
     */
    function showAsset({ assetId, isCurrent, load = true }) {
        // The step has picked its target by now, so the cells of the asset it left may go
        if (leaving && leaving.assetId !== assetId) {
            flushLeaving()
        }

        subject = { assetId, isCurrent }
        shownSize = null
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

    /** The shown media element's size, which the Details' dimensions and the Face highlights read */
    function setShownSize({ width, height }) {
        shownSize = { width, height }
        writeDimensions()
        drawHighlights()
    }

    /** Opens or closes the panel, remembers it, puts it in the URL, and loads the shown asset's details when opening on them unread */
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
    }

    /** Shows or hides the Face highlights (`p`), remembers it, clears the selection, and loads the rows when they are not in yet */
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

    /**
     * Selects `faceId`, or clears the selection when it is the selected one. On a Video the player
     * seeks to the Face's Frame time and pauses, so its highlight shows.
     */
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

    /** A Face highlight's click: selects as a row does, opens the panel if it is closed and brings the row into view */
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

    /** Whether the fragment is needed: while the panel is open or the Face highlights are on, which read its rows */
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

    /** The People section in the panel, or null */
    function sectionEl() {
        return contentEl()?.querySelector(`#${PEOPLE_ID}`) ?? null
    }

    /**
     * A correction answered: the new People section takes the shown one's place, the selection
     * clears and the Face highlights follow the new rows, the result is reported, the People tab
     * reloads, and the asset is noted as leaving the grid when the viewed Person has no Face left
     * on it. A viewed Person named (`namedPersonId`, from the request) has the results title read
     * again at once; a merge of the viewed Person (`mergedPersonId`), or into them (`mergedIntoId`),
     * notes the destination, whose results are searched on closing, at once if Asset detail closed
     * while the request was out: the grid becomes the destination's, or gains what the merged
     * Person had. An answer for an asset no longer shown changes nothing in the panel but is still
     * reported, and still names or merges the viewed Person.
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

    /** A correction found the section stale: the server's message is shown and the panel loads the asset's details again */
    function reloadSection(message) {
        showErrorSnackBar(message)
        selectedFaceId = null
        corrections.selectionChanged()
        loadedAssetId = null
        loadIfWanted()
    }

    /** The combobox's X: the row's original state, with nothing selected, and focus back on the row */
    function cancelChange(faceId) {
        if (selectedFaceId === faceId) {
            selectFace(faceId)
        }
        rowOf(faceId)
            ?.querySelector(`[${Const.attributes.faceRow}]`)
            ?.focus({ preventScroll: true })
    }

    /**
     * Whether the asset still belongs in the grid, the viewed Person's results: when they have no
     * Face left on it, or were deleted by the correction, its cells leave on stepping away or
     * closing; a later correction that gives them a Face back keeps it, unless they were deleted.
     * Once the viewed Person is merged away, their destination is the one whose Faces count.
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
     * The asset the viewed Person left leaves the grid, and the walk goes on from the cells around
     * it; the results title is read again, as its Hide and Discard links depend on the count, or
     * the results go back to the whole repository when the Person was deleted. A detail opened from
     * a map pin has no cell, so only the title. On `closing`, the results of a viewed Person merged
     * away become the destination's, and those of a viewed destination are read again; until then
     * the title, the merged Person's or the destination's before the merge, is left for that search
     * to replace.
     */
    function flushLeaving({ closing = false } = {}) {
        const left = leaving
        const destinationId = closing ? mergedInto : null
        leaving = null
        if (closing) {
            mergedInto = null
        }

        if (left) {
            assetActions.removeAssetsFromGrid([left.assetId])
        }

        // The search goes on without the viewed Person, or with their destination in their place, and
        // keeps every other filter of a filtered search
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
        } else if (left && !mergedInto) {
            reloadPersonTitle(context.getRepoId(), left.personId)
        }
    }

    /** The fragment's open state, which the stylesheet lays the grid out from, and the toggles' pressed state */
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

    /** The Details' dimensions row shows the shown element's size, the header's until the media is shown */
    function writeDimensions() {
        const dimensionsEl = contentEl()?.querySelector(
            `[${Const.attributes.detailDimensions}]`,
        )
        if (dimensionsEl && shownSize) {
            dimensionsEl.textContent = `${shownSize.width} × ${shownSize.height} px`
        }
    }

    /** The People rows in the panel, in the server's order */
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

    /** What the rows say of each Face: its Person's name, whether Hidden, its box in the shown pixels and its Frame time */
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

    /** The rows' selection buttons read the selection */
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

    /**
     * Whether `face` is on the shown media now: always on an image, and on a Video only while the
     * player is paused within the tolerance of its Frame time, never during playback.
     */
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

    /** Draws the Face highlights the setting, the selection and a Video's time call for, once the media is shown */
    function drawHighlights() {
        if (!shownSize) {
            highlights.clear()
            return
        }

        const videoEl = shownVideoEl()
        highlights.show({
            faces: faces().filter(
                (face) =>
                    (state.highlightsOn || face.faceId === selectedFaceId) &&
                    isOnShownFrame(face, videoEl),
            ),
            selectedFaceId,
            size: shownSize,
        })
    }

    /** The least the panel and the stage may be squeezed to along the axis, in px: the `core.css` tokens, registered as lengths */
    function panelMinimums(vertical) {
        const styles = getComputedStyle(viewEl() ?? document.documentElement)
        const token = (name) => parseFloat(styles.getPropertyValue(name))

        return {
            panel: token(
                vertical
                    ? "--detail-panel-min-height"
                    : "--detail-panel-min-width",
            ),
            content: token(
                vertical
                    ? "--detail-stage-min-height"
                    : "--detail-stage-min-width",
            ),
        }
    }

    /** The remembered settings, or the defaults where they are missing or unreadable: a closed panel, highlights off */
    function rememberedState() {
        const saved = readStoredObject(Const.localStore.assetDetailView)

        return {
            panelOpen: saved.panelOpen === true,
            highlightsOn: saved.highlightsOn === true,
        }
    }

    /**
     * Writes the changed setting under the key, leaving the rest as it is: the dock's part, which
     * the dock writes, and a remembered panel state that an asset's URL is overriding
     */
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
    }
}
