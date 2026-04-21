import {Const} from "../constants.js";
import {showErrorSnackBar, showSuccessSnackBar} from "../common/snackbar.js";

export function initDetailNavigator(){
    document.body.addEventListener(
        "htmx:afterRequest",
        function (evt) {
            const requestPath = evt.detail.pathInfo.requestPath
            const status = evt.detail.xhr.status

            if (status !== 200 || !requestPath.startsWith("/htmx/search/r")) {
                return
            }

            console.debug(`Setting search URL to ${requestPath}`)
            Alpine.store(Const.state.searchUrl).set(requestPath)
        })

}

function fetchSearchPage(pageNum) {
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
        console.log("showNext", nextId)
        return
    }

    if (store.page < store.totalPages) {
        const nextPage = store.page + 1
        fetchSearchPage(nextPage).then((data) => {
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
        console.log("showPrevious", prevId)
        return
    }

    if (store.page > 1) {
        const prevPage = store.page - 1
        fetchSearchPage(prevPage).then((data) => {

            if (!data) return
            store.prepend(data.ids)
            store.page = prevPage
            store.totalPages = data.totalPages
            document.body.dispatchEvent(new CustomEvent(Const.events.showPrevious))
        })
    }
})

document.body.addEventListener(Const.events.detailShown, (event) => {
    console.log("detailShown");
    Alpine.store(Const.state.shadowResults).currentAssetId = event.detail.assetId

    const searchUrl = Alpine.store(Const.state.searchUrl).url
    if (!searchUrl) return

    fetch(searchUrl, {
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
            store.reset(data.page, data.totalPages)
            store.append(data.ids)
        })
        .catch((response) => {
            console.error(`Error fetching search results: ${response.status}, ${response.statusText}`)
        })
})
