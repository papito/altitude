import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import {
    getActiveModalHost,
    getModalOpenId,
    isModalOpenActive,
    ModalHost,
    setAssetDetailSize,
} from "../common/modal.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import {
    clearMediaSession,
    describeInMediaSession,
    unloadVideo,
} from "../common/video-playback.js"
import { assetIdOf } from "./cells.js"
import { loadNextPage } from "./infinite-scroll.js"
import { isSpacer, reloadSpacer } from "./page-window.js"
import { getHttpErrorMessage, http } from "../http/client.js"
import { loadMediaChrome } from "../common/vendor-loader.js"
import { originalUrl, previewUrl } from "../common/content-urls.js"

// A Video shorter than this loops, like a Live Photo; a longer one stops at the end
const LOOP_MAX_DURATION_SECONDS = 10

// Pending load listeners per media element, removed when a newer `src` supersedes the load
const pendingMediaLoads = new WeakMap()

/**
 * Sets the element's `src` and resolves once it can be shown: an image once loaded, a video once
 * its metadata (its size and duration) is in, so playback needs nothing more than the ranges the
 * player asks for. A later call on the same element supersedes the wait, which rejects with an
 * `AbortError`.
 */
export function setMediaSrcAndWait({ mediaEl, url }) {
    pendingMediaLoads.get(mediaEl)?.()
    const loadedEvent = mediaEl.tagName === "VIDEO" ? "loadedmetadata" : "load"

    return new Promise((resolve, reject) => {
        const onLoad = () => {
            cleanup()
            resolve(mediaEl)
        }
        const onError = (e) => {
            cleanup()
            reject(e)
        }

        const cleanup = () => {
            pendingMediaLoads.delete(mediaEl)
            mediaEl.removeEventListener(loadedEvent, onLoad)
            mediaEl.removeEventListener("error", onError)
        }

        pendingMediaLoads.set(mediaEl, () => {
            cleanup()
            reject(new DOMException("A later load took over", "AbortError"))
        })
        mediaEl.addEventListener(loadedEvent, onLoad, { once: true })
        mediaEl.addEventListener("error", onError, { once: true })

        // Important: set src AFTER listeners are attached
        mediaEl.src = url
    })
}

/**
 * Starts a Video that was just shown. A browser that refuses playback with sound here (Safari,
 * without a gesture on the element) plays it muted instead, and the mute button unmutes it; one
 * that refuses even that (iPhone in Low Power Mode) leaves it paused with its controls.
 * `AbortError` means navigation, closing, or a pause interrupted the play. `isCurrent` tells
 * whether the Video is still the one the modal shows.
 */
async function playShownVideo({ videoEl, isCurrent }) {
    try {
        await videoEl.play()
    } catch (error) {
        if (error.name === "AbortError" || !isCurrent()) {
            return
        }

        if (error.name === "NotAllowedError") {
            if (videoEl.muted) {
                console.debug(
                    "Autoplay was not allowed, the video waits for its play button",
                )
                return
            }

            console.debug(
                "Playback with sound was not allowed, playing the video muted",
            )
            // Set on the element, not through media-chrome, so the forced mute is not stored as the
            // user's preference
            videoEl.muted = true
            return playShownVideo({ videoEl, isCurrent })
        }

        console.error("Error playing the video", error)
        showErrorSnackBar("Could not play the video")
    }
}

/**
 * Coordinates previous/next navigation and image loading inside the asset-detail modal.
 *
 * The results grid is the modal's source of truth: next and previous move between the grid's
 * `.cell`s in document order, skipping the group headers of a grouped grid, and a cell removed from
 * the grid drops out of navigation with it. A page folded into a spacer (js/search-results/page-window.js)
 * is reloaded as the walk meets it (`reloadSpacer`), whichever way it steps, and the step continues
 * into it. At the end of the loaded grid, next loads the following page exactly as the scroll
 * observer does (`loadNextPage`) and continues into it; at the true end, and at the true first cell,
 * nothing happens.
 *
 * What is remembered is the cell the modal was opened from or stepped to, not the asset: a Location
 * grouping holds an asset in a cell under each of its Locations, and stepping from the cell keeps
 * the walk in the group the user was looking at. A detail opened with no cell (a map pin, an
 * asset's URL) has nowhere to step to.
 *
 * Every media request gets its own token. Only the latest request for the still-active
 * asset-detail open may change the media, box size, title, or loading state; superseded or
 * orphaned completions are ignored, and none can reopen a modal.
 *
 * The fragment holds an `<img>` and a media-chrome player (a `<media-controller>` around the
 * `<video>` and its control bar); the asset's media type decides which one is shown and loaded, and
 * the other is hidden and unloaded. The player is hidden while any asset loads. A shown Video
 * starts playing, muted when the browser refuses sound, loops when it is a short clip, and is
 * described to the OS media controls; it is paused when navigation moves on.
 *
 * The box is fitted from the shown element's size (`naturalWidth`, `videoWidth`), not the stored
 * one: the stored size is the file header's, while a browser turns an image upright by its EXIF
 * orientation. The Detail panel (`detail-panel.js`) is told of each asset as its load starts, under
 * the same request token, so it empties, loads the asset's details while they are wanted and drops a
 * superseded answer, and of the shown size, which its dimensions row and the Face highlights read.
 *
 * A still image is shown by the Edit's stage (`edit/edit-stage.js`), which picks its source by the
 * asset's Edit (the original, the Display image, or the Edit drawn on the canvas), fits the box and
 * tells the Detail panel; a Video is shown here. The asset's Edit, whether the account may edit
 * it and why not come with the fragment (`data-app-image-detail-edit`, `-editable`,
 * `-edit-refusal`) on opening and with the asset's JSON (`edit`, `editable`, `editRefusal`) on a
 * step. A step asks the Edit panel (`edit/edit-panel.js`) first, which warns of a change pending
 * and may keep the asset shown; the panel is told of each asset as its load starts, before the
 * stage, so it ends what was open on the last one and has the stage draw this one's Edit while it
 * is open, and again once a still image is shown.
 */
export function createSearchDetailCoordinator({
    context,
    detailPanel,
    editStage,
    editPanel,
}) {
    // The cell the modal shows, or is loading: where navigation starts from
    let currentCell = null
    let imageRequestToken = 0

    function isAssetDetailActive() {
        return getActiveModalHost() === ModalHost.assetDetail
    }

    /** The current cell while it is still in the grid */
    function currentCellEl() {
        return currentCell?.isConnected ? currentCell : null
    }

    /** The nearest `.cell` or page spacer among the siblings in `direction`, past any group header */
    function siblingStopOf(cellEl, direction) {
        let el = cellEl[direction]

        while (el && !el.classList.contains("cell") && !isSpacer(el)) {
            el = el[direction]
        }

        return el
    }

    function showCell(cellEl) {
        currentCell = cellEl
        loadAssetDetail(assetIdOf(cellEl), cellEl)
    }

    /**
     * Shows the nearest cell in `direction`. A folded page met on the way is reloaded first, and at
     * the edge of the loaded grid `loadBeyond`, if given, brings the page beyond it in; either way
     * the step then looks again, since a page whose assets are gone brings no cell, only the
     * spacer after it, if any. The page may still be arriving for a detail view that moved on.
     */
    async function step({ direction, loadBeyond = null }) {
        // A change pending in the Edit panel is asked about first, and staying is no step
        if (
            !isAssetDetailActive() ||
            !(await editPanel.beforeLeave()) ||
            !isAssetDetailActive()
        ) {
            return
        }

        const openId = getModalOpenId()
        const cellEl = currentCellEl()

        if (!cellEl) {
            return
        }

        let targetEl = siblingStopOf(cellEl, direction)

        while (targetEl ? isSpacer(targetEl) : loadBeyond !== null) {
            try {
                await (targetEl ? reloadSpacer(targetEl) : loadBeyond(cellEl))
            } catch (error) {
                console.error("Error loading a page of results", error)
                return
            }

            if (!isModalOpenActive(openId) || currentCell !== cellEl) {
                return
            }

            const el = siblingStopOf(cellEl, direction)

            // Nothing came in: a failed reload left its spacer, or the grid has no page beyond
            if (el === targetEl) {
                return
            }

            targetEl = el
        }

        if (targetEl) {
            showCell(targetEl)
        }
    }

    /** At the last loaded cell, the next page is loaded as the scroll observer loads it */
    function handleShowNext() {
        return step({
            direction: "nextElementSibling",
            loadBeyond: loadNextPage,
        })
    }

    function handleShowPrevious() {
        return step({ direction: "previousElementSibling" })
    }

    /**
     * Whether the image request `token` is still the latest one for a displayed asset-detail open.
     */
    function isCurrentImageRequest({ token, openId }) {
        return token === imageRequestToken && isModalOpenActive(openId)
    }

    /**
     * Makes `cellEl` (the cell the asset is shown in, or null for a detail opened from no cell) the
     * navigation origin immediately, then loads the media into the element its type calls for,
     * unloading the other. If the request is still current when the media is ready, applies the
     * asset's size and title, and starts a Video: looping when it is a short clip, muted when the
     * browser refuses sound, and described to the OS media controls. A still image goes to the
     * Edit's stage with its `edit` and `editable`, which sizes the box itself.
     */
    async function showMedia({
        openId,
        imgEl,
        videoEl,
        mediaType,
        url,
        title,
        width,
        height,
        assetId,
        edit = null,
        editable = false,
        editRefusal = null,
        cellEl = null,
        token = ++imageRequestToken,
    }) {
        const loading = Alpine.store(Const.state.imageDetailLoading)
        // Navigation starts from the requested cell while its media is still loading.
        currentCell = cellEl
        loading.value = true

        const isVideo = mediaType === "video"
        const mediaEl = isVideo ? videoEl : imgEl
        // A Video shows as its player: the media-chrome controller holding the <video> and its controls
        const playerEl = videoEl.closest("media-controller")
        const shownEl = isVideo ? playerEl : imgEl
        const isCurrent = () => isCurrentImageRequest({ token, openId })

        detailPanel.showAsset({ assetId, isCurrent })
        editPanel.showAsset({
            assetId,
            mediaType,
            edit,
            editable,
            refusal: editRefusal,
        })

        // The player leaves the screen until the next Video is ready, so an emptied player never
        // shows in the previous frame
        unloadVideo(videoEl)
        clearMediaSession()
        playerEl.hidden = true
        if (isVideo) {
            imgEl.removeAttribute("src")
            imgEl.hidden = true
            // Nothing of an Edit stays on the stage: its canvas, its toggle and its badge
            editStage.clear()
        }

        try {
            if (isVideo) {
                // The player's controls are defined by media-chrome, loaded by the first Video alongside it
                await Promise.all([
                    setMediaSrcAndWait({ mediaEl, url }),
                    loadMediaChrome(),
                ])

                if (!isCurrent()) {
                    return
                }

                shownEl.hidden = false
                // The size as shown, upright; the stored one stands in for a player that reports none
                const size =
                    videoEl.videoWidth && videoEl.videoHeight
                        ? {
                              width: videoEl.videoWidth,
                              height: videoEl.videoHeight,
                          }
                        : { width, height }
                setAssetDetailSize(size)
                detailPanel.setShownSize(size)
            } else if (
                !(await editStage.showImage({
                    assetId,
                    edit,
                    editable,
                    width,
                    height,
                    url,
                    isCurrent,
                }))
            ) {
                return
            }

            if (!isCurrent()) {
                return
            }

            if (!isVideo) {
                editPanel.afterShown(assetId)
            }
            Alpine.store(Const.state.modal).title = title
            loading.value = false

            if (isVideo) {
                // The element is reused across navigation; an unknown duration (NaN) does not loop.
                // A Video is never edited, so its artwork is the original's Preview
                videoEl.loop = videoEl.duration < LOOP_MAX_DURATION_SECONDS
                // Not awaited: the media is shown, whenever playback starts
                playShownVideo({ videoEl, isCurrent })
                describeInMediaSession({
                    videoEl,
                    title,
                    artworkUrl: previewUrl(context.getRepoId(), assetId),
                    play: () => playShownVideo({ videoEl, isCurrent }),
                    previous: currentCell && handleShowPrevious,
                    next: currentCell && handleShowNext,
                })
            }
        } catch (error) {
            if (!isCurrent()) {
                return
            }

            console.error("Error loading asset media", error)
            loading.value = false
            showErrorSnackBar(
                isVideo
                    ? "Could not load the video"
                    : "Could not load the image",
            )
        }
    }

    /**
     * Navigates the active asset-detail modal to `assetId`, shown by `cellEl`: fetches the asset's
     * metadata, then its media. Both steps are skipped if the request is superseded or the modal
     * goes away. A playing video is paused first, and the Detail panel is told at once, so the last
     * asset's details and Face highlights leave as this one starts loading; its details load from
     * `showMedia`, once the metadata is in, so a failed fetch is one error and no request the
     * panel would have to drop.
     */
    async function loadAssetDetail(assetId, cellEl) {
        const repoId = context.getRepoId()
        const imgEl = document.querySelector("#imageDetailModalContent img")
        const videoEl = document.querySelector("#imageDetailModalContent video")

        if (!imgEl || !videoEl || !isAssetDetailActive()) {
            return
        }

        videoEl.pause()

        const openId = getModalOpenId()
        const token = ++imageRequestToken
        Alpine.store(Const.state.imageDetailLoading).value = true
        detailPanel.showAsset({
            assetId,
            isCurrent: () => isCurrentImageRequest({ token, openId }),
            load: false,
        })

        let assetData
        try {
            const response = await http.get(
                `/htmx/asset/r/${repoId}/modals/asset-detail/${assetId}`,
            )
            assetData = response.data
        } catch (error) {
            if (!isCurrentImageRequest({ token, openId })) {
                return
            }

            console.error(
                `Error loading asset detail: ${getHttpErrorMessage(error)}`,
            )
            Alpine.store(Const.state.imageDetailLoading).value = false
            showErrorSnackBar("Could not load the asset")
            return
        }

        if (!assetData || !isCurrentImageRequest({ token, openId })) {
            return
        }

        await showMedia({
            openId,
            imgEl,
            videoEl,
            mediaType: assetData.asset_type?.media_type,
            url: originalUrl(repoId, assetId),
            title: assetData.file_name,
            width: assetData.width,
            height: assetData.height,
            assetId,
            edit: assetData.edit ?? null,
            editable: assetData.editable === true,
            editRefusal: assetData.editRefusal ?? null,
            cellEl,
            token,
        })
    }

    /** Plays or pauses the shown Video (Space outside the player); does nothing on an image or while a Video loads */
    function togglePlayback() {
        const videoEl = document.querySelector("#imageDetailModalContent video")

        if (
            !isAssetDetailActive() ||
            !videoEl ||
            videoEl.closest("media-controller").hidden
        ) {
            return
        }

        if (!videoEl.paused) {
            videoEl.pause()
            return
        }

        const token = imageRequestToken
        const openId = getModalOpenId()
        playShownVideo({
            videoEl,
            isCurrent: () => isCurrentImageRequest({ token, openId }),
        })
    }

    return {
        currentCellEl,
        handleShowNext,
        handleShowPrevious,
        showMedia,
        loadAssetDetail,
        togglePlayback,
    }
}
