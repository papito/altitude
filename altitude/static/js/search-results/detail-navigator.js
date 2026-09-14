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
import { assetIdOf } from "./cells.js"
import { loadNextPage } from "./infinite-scroll.js"
import { getHttpErrorMessage, http } from "../http/client.js"

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

/** Stops and unloads a video, so nothing keeps playing or downloading behind the next asset */
function unloadVideo(videoEl) {
    videoEl.pause()
    videoEl.removeAttribute("src")
    videoEl.load()
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
 * The fragment holds an `<img>` and a `<video>`; the asset's media type decides which one is shown
 * and loaded, and the other is hidden and unloaded. A video is paused when navigation moves on.
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
     * asset's size and title.
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
        cellEl = null,
        token = ++imageRequestToken,
    }) {
        const loading = Alpine.store(Const.state.imageDetailLoading)
        // Navigation starts from the requested cell while its media is still loading.
        currentCell = cellEl
        loading.value = true

        const isVideo = mediaType === "video"
        const mediaEl = isVideo ? videoEl : imgEl
        unloadVideo(videoEl)
        if (!isVideo) {
            videoEl.hidden = true
        } else {
            imgEl.removeAttribute("src")
            imgEl.hidden = true
        }

        try {
            await setMediaSrcAndWait({ mediaEl, url })

            if (!isCurrentImageRequest({ token, openId })) {
                return
            }

            mediaEl.hidden = false
            setAssetDetailSize({ width, height })
            Alpine.store(Const.state.modal).title = title
            loading.value = false
        } catch (error) {
            if (!isCurrentImageRequest({ token, openId })) {
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
            cellEl,
            token,
        })
    }

    return {
        handleShowNext,
        handleShowPrevious,
        showMedia,
        loadAssetDetail,
    }
}
