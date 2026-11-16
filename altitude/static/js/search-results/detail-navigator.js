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

// A Video shorter than this loops, like a Live Photo
const LOOP_MAX_DURATION_SECONDS = 10

// Pending load listeners per media element, removed when a newer `src` supersedes the load
const pendingMediaLoads = new WeakMap()

/**
 * Sets `src` and resolves once the element can be shown: an image once loaded, a video once its
 * metadata (size, duration) is in. A later call on the same element rejects this wait with an
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
 * Starts a Video that was just shown. A browser that refuses sound without a gesture (Safari) plays
 * it muted; one that refuses even that (iPhone in Low Power Mode) leaves it paused. `AbortError`
 * means navigation, closing or a pause interrupted the play.
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
 * Previous/next navigation and media loading inside the asset-detail modal.
 *
 * The grid is the source of truth: a step moves to the next `.cell` in document order past group
 * headers, reloads a folded page spacer it meets, and past the last loaded cell loads the next page.
 * The cell is remembered, not the asset, since a Location grouping shows one asset under several
 * groups and the walk stays in the group the user was in. Every media request gets a token; only the
 * latest one for the still-active open may touch the modal.
 *
 * The box is fitted from the shown element's size, not the stored one: the stored size is the file
 * header's, while the browser turns an image upright by its EXIF orientation.
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
     * Shows the nearest cell in `direction`, reloading a folded page met on the way or, at the edge
     * of the loaded grid, loading the page beyond with `loadBeyond`. It looks again after each load,
     * since a page whose assets are gone brings only the spacer after it.
     */
    async function step({ direction, loadBeyond = null }) {
        // The Edit panel may keep the asset shown when a change is pending
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

    function handleShowNext() {
        return step({
            direction: "nextElementSibling",
            loadBeyond: loadNextPage,
        })
    }

    function handleShowPrevious() {
        return step({ direction: "previousElementSibling" })
    }

    function isCurrentImageRequest({ token, openId }) {
        return token === imageRequestToken && isModalOpenActive(openId)
    }

    /**
     * Loads the media into the element its type calls for and unloads the other. A still image goes
     * to the Edit's stage, which sizes the box itself. `cellEl` is null for a detail opened from no
     * cell (a map pin, an asset's URL), which has nowhere to step to.
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
        // A Video shows as its player, the media-chrome controller around the <video>
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
            editStage.clear()
        }

        try {
            if (isVideo) {
                // media-chrome loads lazily, with the first Video
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
     * Fetches the asset's metadata, then shows its media, unless the request is superseded. The
     * Detail panel empties at once but loads the details only from `showMedia`, so a failed fetch
     * makes a single error.
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

    /** Plays or pauses the shown Video; does nothing on an image or while a Video loads (its player is hidden) */
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
