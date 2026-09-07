import { Const } from "../constants.js"
import {
    getActiveModalHost,
    getModalOpenId,
    isModalOpenActive,
    ModalHost,
    setAssetDetailSize,
} from "../common/modal.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { loadNextPage } from "../fragments/search-results.js"
import { getHttpErrorMessage, http } from "../http/client.js"

// Pending load listeners per image element, removed when a newer `src` supersedes the load
const pendingImageLoads = new WeakMap()

// A cell of the results grid is `#asset-<id>` (htmx/result_cell.scala.html)
const CELL_ID_PREFIX = "asset-"

export function setImgSrcAndWait({ img, url }) {
    pendingImageLoads.get(img)?.()

    return new Promise((resolve, reject) => {
        const onLoad = () => {
            cleanup()
            resolve(img)
        }
        const onError = (e) => {
            cleanup()
            reject(e)
        }

        const cleanup = () => {
            pendingImageLoads.delete(img)
            img.removeEventListener("load", onLoad)
            img.removeEventListener("error", onError)
        }

        pendingImageLoads.set(img, cleanup)
        img.addEventListener("load", onLoad, { once: true })
        img.addEventListener("error", onError, { once: true })

        // Important: set src AFTER listeners are attached
        img.src = url
    })
}

/**
 * Coordinates previous/next navigation and image loading inside the asset-detail modal.
 *
 * The results grid is the modal's source of truth: next and previous move between the grid's
 * `.cell`s in document order, skipping the date headers of a grouped grid, and a cell removed from
 * the grid drops out of navigation with it. At the end of the loaded grid, next loads the following
 * page exactly as the scroll observer does (`loadNextPage`) and continues into it; at the true end,
 * and at the first cell, nothing happens.
 *
 * Every image request gets its own token. Only the latest request for the still-active
 * asset-detail open may change the image, box size, title, loading state, or dispatch
 * `detailShown`; superseded or orphaned completions are ignored, and none can reopen a modal.
 */
export function createSearchDetailCoordinator({ Alpine, context, dispatch }) {
    // The asset the modal shows, or is loading: where navigation starts from
    let currentAssetId = null
    let imageRequestToken = 0

    function isAssetDetailActive() {
        return getActiveModalHost() === ModalHost.assetDetail
    }

    function currentCellEl() {
        return currentAssetId
            ? document.getElementById(`${CELL_ID_PREFIX}${currentAssetId}`)
            : null
    }

    /** The nearest `.cell` among the siblings in `direction`, past any date header */
    function siblingCellOf(cellEl, direction) {
        let el = cellEl[direction]

        while (el && !el.classList.contains("cell")) {
            el = el[direction]
        }

        return el
    }

    function showCell(cellEl) {
        const assetId = cellEl.id.slice(CELL_ID_PREFIX.length)

        currentAssetId = assetId
        loadAssetDetail(assetId)
    }

    async function handleShowNext() {
        if (!isAssetDetailActive()) {
            return
        }

        const openId = getModalOpenId()
        const originId = currentAssetId
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

            if (!isModalOpenActive(openId) || currentAssetId !== originId) {
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
     * Makes `assetId` the navigation origin immediately, then loads its image. If the request
     * is still current when the image arrives, applies the asset's size and title.
     */
    async function showImage({
        openId,
        imgEl,
        url,
        title,
        width,
        height,
        assetId,
        token = ++imageRequestToken,
    }) {
        const loading = Alpine.store(Const.state.imageDetailLoading)
        // Navigation starts from the requested asset while its image is still loading.
        currentAssetId = assetId
        loading.value = true

        try {
            await setImgSrcAndWait({ img: imgEl, url })

            if (!isCurrentImageRequest({ token, openId })) {
                return
            }

            setAssetDetailSize({ width, height })
            Alpine.store(Const.state.modal).title = title
            loading.value = false
            dispatch(Const.events.detailShown, { assetId })
        } catch (error) {
            if (!isCurrentImageRequest({ token, openId })) {
                return
            }

            console.error("Error loading asset image", error)
            loading.value = false
            showErrorSnackBar("Could not load the image")
        }
    }

    /**
     * Navigates the active asset-detail modal to `assetId`: fetches the asset's metadata, then
     * its image. Both steps are skipped if the request is superseded or the modal goes away.
     */
    async function loadAssetDetail(assetId) {
        const repoId = context.getRepoId()
        const imgEl = document.querySelector("#imageDetailModalContent img")

        if (!imgEl || !isAssetDetailActive()) {
            return
        }

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

        await showImage({
            openId,
            imgEl,
            url: `/content/r/${repoId}/file/${assetId}`,
            title: assetData.file_name,
            width: assetData.width,
            height: assetData.height,
            assetId,
            token,
        })
    }

    return {
        handleShowNext,
        handleShowPrevious,
        showImage,
        loadAssetDetail,
    }
}
