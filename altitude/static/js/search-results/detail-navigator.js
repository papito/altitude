import { Const } from "../constants.js"
import { showAssetDetailModal } from "../common/modal.js"

export function setImgSrcAndWait(img, url) {
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
            Alpine.store(Const.state.imageDetailLoading).value = false
            img.removeEventListener("load", onLoad)
            img.removeEventListener("error", onError)
        }

        img.addEventListener("load", onLoad, { once: true })
        img.addEventListener("error", onError, { once: true })

        // Important: set src AFTER listeners are attached
        img.src = url
    })
}

export function createSearchDetailCoordinator({ Alpine, context, dispatch }) {
    let shadowResultsSyncToken = 0

    function syncShadowResultsFromSearchUrl() {
        const searchUrl = Alpine.store(Const.state.searchUrl).url
        if (!searchUrl) {
            return
        }

        const token = ++shadowResultsSyncToken
        const store = Alpine.store(Const.state.shadowResults)

        fetchSearchResultsJson(searchUrl)
            .then((data) => {
                if (!data || token !== shadowResultsSyncToken) {
                    return
                }

                store.replace(data.ids, data.page, data.totalPages)
            })
            .catch((error) => {
                console.error(`Error fetching search results: ${error}`)
            })
    }

    function appendShadowResultsForRequestPath(requestPath) {
        const store = Alpine.store(Const.state.shadowResults)

        fetchSearchResultsJson(requestPath)
            .then((data) => {
                if (!data) {
                    return
                }

                store.append(data.ids)
                store.page = data.page
                store.totalPages = data.totalPages
            })
            .catch((error) => {
                console.error(`Error fetching search results: ${error}`)
            })
    }

    function fetchSearchResultsJson(url) {
        return fetch(url, {
            method: "GET",
            headers: {
                "Content-Type": "application/json",
                "HX-Current-URL": window.location.href,
            },
        }).then((response) => {
            if (!response.ok) {
                console.error(
                    `Error fetching search results: ${response.statusText}`,
                )
                return null
            }

            return response.json()
        })
    }

    function handleShowNext() {
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
            fetchShadowSearchResultsPage(nextPage).then((data) => {
                if (!data) {
                    return
                }

                store.append(data.ids)
                store.page = nextPage
                store.totalPages = data.totalPages
                dispatch(Const.events.showNext)
            })
        }
    }

    function handleShowPrevious() {
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
            fetchShadowSearchResultsPage(previousPage).then((data) => {
                if (!data) {
                    return
                }

                store.prepend(data.ids)
                store.page = previousPage
                store.totalPages = data.totalPages
                dispatch(Const.events.showPrevious)
            })
        }
    }

    function fetchShadowSearchResultsPage(pageNum) {
        const searchUrl = Alpine.store(Const.state.searchUrl).url
        if (!searchUrl) {
            return Promise.resolve(null)
        }

        const url = new URL(searchUrl, window.location.origin)
        url.searchParams.set("p", pageNum)

        return fetchSearchResultsJson(url.toString())
    }

    function loadAssetDetail(assetId) {
        const repoId = context.getRepoId()
        const imgEl = document.querySelector("#imageDetailModalContent img")

        if (!imgEl) {
            return
        }

        Alpine.store(Const.state.imageDetailLoading).value = true

        fetch(`/htmx/asset/r/${repoId}/modals/asset-detail/${assetId}`, {
            method: "GET",
            headers: {
                "Content-Type": "application/json",
            },
        })
            .then((response) => {
                if (!response.ok) {
                    console.error(
                        `Error loading asset detail: ${response.statusText}`,
                    )
                    Alpine.store(Const.state.imageDetailLoading).value = false
                    return null
                }

                return response.json()
            })
            .then(async (assetData) => {
                if (!assetData) {
                    return
                }

                await setImgSrcAndWait(
                    imgEl,
                    `/content/r/${repoId}/file/${assetId}`,
                )

                showAssetDetailModal({
                    title: assetData.file_name,
                    width: assetData.width,
                    height: assetData.height,
                })

                dispatch(Const.events.detailShown, { assetId })
            })
            .catch((error) => {
                console.error(`Error loading asset detail: ${error}`)
                Alpine.store(Const.state.imageDetailLoading).value = false
            })
    }

    return {
        syncShadowResultsFromSearchUrl,
        appendShadowResultsForRequestPath,
        fetchSearchResultsJson,
        handleShowNext,
        handleShowPrevious,
        fetchShadowSearchResultsPage,
        loadAssetDetail,
    }
}
