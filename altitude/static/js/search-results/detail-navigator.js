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
import { getHttpErrorMessage, http } from "../http/client.js"

// A Video shorter than this loops, like a Live Photo; a longer one stops at the end
const LOOP_MAX_DURATION_SECONDS = 10

// Pending load listeners per media element, removed when a newer `src` supersedes the load
const pendingMediaLoads = new WeakMap()

/**
 * Sets the element's `src` and resolves once it can be shown: an image once loaded, a video once
 * its metadata (its size and duration) is in, so playback needs nothing more than the ranges the
 * player asks for.
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

        pendingMediaLoads.set(mediaEl, cleanup)
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
 * the grid drops out of navigation with it. At the end of the loaded grid, next loads the following
 * page exactly as the scroll observer does (`loadNextPage`) and continues into it; at the true end,
 * and at the first cell, nothing happens.
 *
 * What is remembered is the cell the modal was opened from or stepped to, not the asset: a Location
 * grouping holds an asset in a cell under each of its Locations, and stepping from the cell keeps
 * the walk in the group the user was looking at. A detail opened with no cell (a map pin) has
 * nowhere to step to.
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
 */
export function createSearchDetailCoordinator({ context }) {
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

    /** The nearest `.cell` among the siblings in `direction`, past any group header */
    function siblingCellOf(cellEl, direction) {
        let el = cellEl[direction]

        while (el && !el.classList.contains("cell")) {
            el = el[direction]
        }

        return el
    }

    function showCell(cellEl) {
        currentCell = cellEl
        loadAssetDetail(assetIdOf(cellEl), cellEl)
    }

    async function handleShowNext() {
        if (!isAssetDetailActive()) {
            return
        }

        const openId = getModalOpenId()
        const cellEl = currentCellEl()

        if (!cellEl) {
            return
        }

        let nextCellEl = siblingCellOf(cellEl, "nextElementSibling")

        if (!nextCellEl) {
            // The end of the loaded grid: the current cell is its last, and carries the continuation
            // if there is one. The page may still be arriving for a detail view that moved on.
            try {
                await loadNextPage(cellEl)
            } catch (error) {
                console.error("Error loading the next page of results", error)
                return
            }

            if (!isModalOpenActive(openId) || currentCell !== cellEl) {
                return
            }

            nextCellEl = siblingCellOf(cellEl, "nextElementSibling")
        }

        if (nextCellEl) {
            showCell(nextCellEl)
        }
    }

    function handleShowPrevious() {
        if (!isAssetDetailActive()) {
            return
        }

        const cellEl = currentCellEl()
        const previousCellEl =
            cellEl && siblingCellOf(cellEl, "previousElementSibling")

        if (previousCellEl) {
            showCell(previousCellEl)
        }
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
     * browser refuses sound, and described to the OS media controls.
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

        // The player leaves the screen until the next Video is ready, so an emptied player never
        // shows in the previous frame
        unloadVideo(videoEl)
        clearMediaSession()
        playerEl.hidden = true
        if (isVideo) {
            imgEl.removeAttribute("src")
            imgEl.hidden = true
        }

        try {
            await setMediaSrcAndWait({ mediaEl, url })

            if (!isCurrent()) {
                return
            }

            shownEl.hidden = false
            setAssetDetailSize({ width, height })
            Alpine.store(Const.state.modal).title = title
            loading.value = false

            if (isVideo) {
                // The element is reused across navigation; an unknown duration (NaN) does not loop
                videoEl.loop = videoEl.duration < LOOP_MAX_DURATION_SECONDS
                // Not awaited: the media is shown, whenever playback starts
                playShownVideo({ videoEl, isCurrent })
                describeInMediaSession({
                    videoEl,
                    title,
                    artworkUrl: `/content/r/${context.getRepoId()}/preview/${assetId}`,
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
     * goes away. A playing video is paused first.
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
            url: `/content/r/${repoId}/file/${assetId}`,
            title: assetData.file_name,
            width: assetData.width,
            height: assetData.height,
            assetId,
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
        handleShowNext,
        handleShowPrevious,
        showMedia,
        loadAssetDetail,
        togglePlayback,
    }
}
