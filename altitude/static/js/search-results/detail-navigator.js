import {Const} from "../constants.js";
import {showAssetDetailModal} from "../common/modal.js";

/**
 * When any kind of search action fires, populate the shadow results store with the new result set.
 * Shadow results are used for arrow keys navigation.
 */
export function initDetailNavigator(){
    document.body.addEventListener(
        "htmx:afterRequest",
        function (evt) {
            const requestPath = evt.detail.pathInfo.requestPath
            const status = evt.detail.xhr.status

            if (status !== 200 || !requestPath.startsWith("/htmx/search/r")) {
                return
            }

            fetch(requestPath, {
                method: "GET",
                headers: {
                    "Content-Type": "application/json",
                    "HX-Current-URL": window.location.href,
                },
            })
                .then((response) => {
                    if (!response.ok) {
                        console.error(`Error fetching search results: ${response.statusText}`)
                        return
                    }
                    return response.json()
                })
                .then((data) => {
                    if (!data) return
                    const store = Alpine.store(Const.state.shadowResults)
                    store.append(data.ids)
                    store.page = data.page
                    store.totalPages = data.totalPages
                    console.debug(`Setting search URL to ${requestPath}`)
                    Alpine.store(Const.state.searchUrl).set(requestPath)                })
                .catch((response) => {
                    console.error(`Error fetching search results: ${response.status}, ${response.statusText}`)
                })
        })

}

// Spinner logic
export function setImgSrcAndWait(img, url) {
    return new Promise((resolve, reject) => {
        const onLoad = () => {
            cleanup();
            resolve(img);
        };
        const onError = (e) => {
            cleanup();
            reject(e);
        };

        const cleanup = () => {
            Alpine.store(Const.state.imageDetailLoading).value = false
            img.removeEventListener("load", onLoad);
            img.removeEventListener("error", onError);
        };

        img.addEventListener("load", onLoad, { once: true });
        img.addEventListener("error", onError, { once: true });

        // Important: set src AFTER listeners are attached
        img.src = url;
    });
}

function loadAssetDetail(assetId) {
    const repoId = window.ctx.getRepoId()

    Alpine.store(Const.state.imageDetailLoading).value = true

    fetch(`/htmx/asset/r/${repoId}/modals/asset-detail/${assetId}`, {
        method: "GET",
        headers: {
            "Content-Type": "application/json",
        },
    })
        .then((response) => {
            if (!response.ok) {
                console.error(`Error loading asset detail: ${response.statusText}`)
                Alpine.store(Const.state.imageDetailLoading).value = false
                return null
            }
            return response.json()
        })
        .then(async (assetData) => {
            if (!assetData) return

            const img = document.querySelector('#imageDetailModalContent img')

            await setImgSrcAndWait(img, `/content/r/${repoId}/file/${assetId}`);

            showAssetDetailModal({ title: assetData.file_name, width: assetData.width, height: assetData.height })

            document.body.dispatchEvent(new CustomEvent(Const.events.detailShown, {
                detail: { assetId },
            }))
        })
        .catch((err) => {
            console.error(`Error loading asset detail: ${err}`)
            Alpine.store(Const.state.imageDetailLoading).value = false
        })
}

function fetchShadowSearchResults(pageNum) {
    const searchUrl = Alpine.store(Const.state.searchUrl).url
    if (!searchUrl) return Promise.resolve(null)

    const url = new URL(searchUrl, window.location.origin)
    url.searchParams.set("p", pageNum)

    return fetch(url.toString(), {
        method: "GET",
        headers: {
            "Content-Type": "application/json",
            "HX-Current-URL": window.location.href,
        },
    })
        .then((response) => {
            if (!response.ok) {
                console.error(`Error fetching search results: ${response.statusText}`)
                return null
            }
            return response.json()
        })
        .catch((response) => {
            console.error(`Error fetching search results: ${response.status}, ${response.statusText}`)
            return null
        })
}

document.body.addEventListener(Const.events.showNext, (event) => {
    const store = Alpine.store(Const.state.shadowResults)
    const idx = store.items.indexOf(store.currentAssetId)
    const nextId = idx !== -1 && idx + 1 < store.items.length ? store.items[idx + 1] : null

    if (nextId) {
        store.currentAssetId = nextId
        loadAssetDetail(nextId)
        console.log("showNext", nextId)
        return
    }

    if (store.page < store.totalPages) {
        const nextPage = store.page + 1
        fetchShadowSearchResults(nextPage).then((data) => {
            if (!data) return
            store.append(data.ids)
            store.page = nextPage
            store.totalPages = data.totalPages
            document.body.dispatchEvent(new CustomEvent(Const.events.showNext))
        })
    }
})

document.body.addEventListener(Const.events.showPrevious, (event) => {
    const store = Alpine.store(Const.state.shadowResults)
    const idx = store.items.indexOf(store.currentAssetId)
    const prevId = idx > 0 ? store.items[idx - 1] : null

    if (prevId) {
        store.currentAssetId = prevId
        loadAssetDetail(prevId)
        console.log("showPrevious", prevId)
        return
    }

    if (store.page > 1) {
        const prevPage = store.page - 1
        fetchShadowSearchResults(prevPage).then((data) => {

            if (!data) return
            store.prepend(data.ids)
            store.page = prevPage
            store.totalPages = data.totalPages
            document.body.dispatchEvent(new CustomEvent(Const.events.showPrevious))
        })
    }
})

document.body.addEventListener(Const.events.detailShown, (event) => {
    Alpine.store(Const.state.shadowResults).currentAssetId = event.detail.assetId
})
