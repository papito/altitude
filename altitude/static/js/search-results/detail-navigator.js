import { Const } from "../constants.js"
import {
    getActiveModalHost,
    getModalOpenId,
    isModalOpenActive,
    ModalHost,
    setAssetDetailSize,
} from "../common/modal.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { getHttpErrorMessage, http } from "../http/client.js"

// Pending load listeners per image element, removed when a newer `src` supersedes the load
const pendingImageLoads = new WeakMap()

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
 * Coordinates the shadow search results (asset IDs mirroring the grid, fetched as JSON) with
 * previous/next navigation and image loading inside the asset-detail modal.
 *
 * Every image request gets its own token. Only the latest request for the still-active
 * asset-detail open may change the image, box size, title, loading state, or dispatch
 * `detailShown`; superseded or orphaned completions are ignored, and none can reopen a modal.
 */
export function createSearchDetailCoordinator({ Alpine, context, dispatch }) {
    let shadowResultsSyncToken = 0
    let imageRequestToken = 0

    async function syncShadowResultsFromSearchUrl() {
        const searchUrl = Alpine.store(Const.state.searchUrl).url
        if (!searchUrl) {
            return
        }

        const token = ++shadowResultsSyncToken
        const store = Alpine.store(Const.state.shadowResults)
        const data = await fetchSearchResultsJson(searchUrl)

        if (!data || token !== shadowResultsSyncToken) {
            return
        }

        store.replace(data.ids, data.page, data.totalPages)
    }

    async function appendShadowResultsForRequestPath(requestPath) {
        const store = Alpine.store(Const.state.shadowResults)
        const data = await fetchSearchResultsJson(requestPath)

        if (!data) {
            return
        }

        store.append(data.ids)
        store.page = data.page
        store.totalPages = data.totalPages
    }

    async function fetchSearchResultsJson(url) {
        try {
            const response = await http.get(url, {
                headers: {
                    "Content-Type": "application/json",
                    "HX-Current-URL": window.location.href,
                },
            })

            return response.data
        } catch (error) {
            console.error(
                `Error fetching search results: ${getHttpErrorMessage(error)}`,
            )
            return null
        }
    }

    function isAssetDetailActive() {
        return getActiveModalHost() === ModalHost.assetDetail
    }

    async function handleShowNext() {
        if (!isAssetDetailActive()) {
            return
        }

        const openId = getModalOpenId()
        const store = Alpine.store(Const.state.shadowResults)
        const currentIdx = store.items.indexOf(store.currentAssetId)
        const nextId =
            currentIdx !== -1 && currentIdx + 1 < store.items.length
                ? store.items[currentIdx + 1]
                : null

        if (nextId) {
            store.currentAssetId = nextId
            loadAssetDetail(nextId)
            return
        }

        if (store.page < store.totalPages) {
            const nextPage = store.page + 1
            const data = await fetchShadowSearchResultsPage(nextPage)

            // The page may have been fetched for a detail view that is no longer showing
            if (!data || !isModalOpenActive(openId)) {
                return
            }

            store.append(data.ids)
            store.page = nextPage
            store.totalPages = data.totalPages
            dispatch(Const.events.showNext)
        }
    }

    async function handleShowPrevious() {
        if (!isAssetDetailActive()) {
            return
        }

        const openId = getModalOpenId()
        const store = Alpine.store(Const.state.shadowResults)
        const currentIdx = store.items.indexOf(store.currentAssetId)
        const previousId = currentIdx > 0 ? store.items[currentIdx - 1] : null

        if (previousId) {
            store.currentAssetId = previousId
            loadAssetDetail(previousId)
            return
        }

        if (store.page > 1) {
            const previousPage = store.page - 1
            const data = await fetchShadowSearchResultsPage(previousPage)

            if (!data || !isModalOpenActive(openId)) {
                return
            }

            store.prepend(data.ids)
            store.page = previousPage
            store.totalPages = data.totalPages
            dispatch(Const.events.showPrevious)
        }
    }

    async function fetchShadowSearchResultsPage(pageNum) {
        const searchUrl = Alpine.store(Const.state.searchUrl).url
        if (!searchUrl) {
            return null
        }

        const url = new URL(searchUrl, window.location.origin)
        url.searchParams.set("p", pageNum)

        return await fetchSearchResultsJson(url.toString())
    }

    /**
     * Whether the image request `token` is still the latest one for a displayed asset-detail open.
     */
    function isCurrentImageRequest({ token, openId }) {
        return token === imageRequestToken && isModalOpenActive(openId)
    }

    /**
     * Loads `url` into the asset-detail image and, if this request is still current when the
     * image arrives, applies the asset's size and title and marks it as the current asset.
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
        syncShadowResultsFromSearchUrl,
        appendShadowResultsForRequestPath,
        fetchSearchResultsJson,
        handleShowNext,
        handleShowPrevious,
        fetchShadowSearchResultsPage,
        showImage,
        loadAssetDetail,
    }
}
