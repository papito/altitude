import { Const } from "./constants.js"
import { Folder } from "./models/folder.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "./common/snackbar.js"
import { showAssetDetailModal } from "./common/modal.js"
import { setImgSrcAndWait } from "./search-results/detail-navigator.js"
import "./search-results/dragon-drop.js"

const placeholderImageData =
    "data:image/gif;base64,R0lGODlhAQABAIAAAP///wAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw=="

export class FrontendApp {
    constructor({ Alpine, context }) {
        this.Alpine = Alpine
        this.context = context
        this.started = false
        this.shadowResultsSyncToken = 0
        this.lazyImageObserver = null
    }

    start() {
        if (this.started) {
            return this
        }

        this.started = true
        this.initializeStores()
        this.registerEventListeners()
        this.Alpine.start()
        this.hydrateFragments(document)

        return this
    }

    initializeStores() {
        this.Alpine.store(Const.context.repoId, "")
        this.Alpine.store(Const.context.gridMetadataFields, new Set())

        this.Alpine.store(Const.state.selectedAssets, {
            items: new Map(),

            get isEmpty() {
                return this.items.size === 0
            },

            get size() {
                return this.items.size
            },

            reset() {
                document.body.dispatchEvent(
                    new CustomEvent(Const.events.deselectAll),
                )
            },

            contains(id) {
                return this.items.has(id)
            },
        })

        this.Alpine.store(Const.state.resultsTotal, {
            count: 0,

            set(n) {
                this.count = n
            },

            increment(n = 1) {
                this.count += n
            },

            decrement(n = 1) {
                this.count = Math.max(0, this.count - n)
            },
        })

        this.Alpine.store(Const.state.searchUrl, {
            url: null,

            set(url) {
                this.url = url
            },
        })

        this.Alpine.store(Const.state.shadowResults, {
            items: [],
            page: 1,
            totalPages: 0,
            currentAssetId: null,

            replace(items, page, totalPages) {
                this.items = items
                this.page = page
                this.totalPages = totalPages
            },

            reset(page = 1, totalPages = 0) {
                this.items = []
                this.page = page
                this.totalPages = totalPages
                this.currentAssetId = null
            },

            prepend(newItems) {
                this.items = newItems.concat(this.items)
            },

            append(newItems) {
                this.items = this.items.concat(newItems)
            },
        })

        this.Alpine.store(Const.state.currentView, {
            view: null,

            setViewName(view) {
                this.view = view
            },

            isDefaultViewView() {
                return this.view === Const.views.repository
            },

            isTriageView() {
                return this.view === Const.views.triage
            },

            isTrashBinView() {
                return this.view === Const.views.trashbin
            },
        })

        this.Alpine.store(Const.state.imageDetailLoading, {
            value: false,
        })
    }

    registerEventListeners() {
        document.body.addEventListener(
            Const.events.confirmPersonMerge,
            (event) => {
                const mergeSourceId = event.detail["mergeSourceId"]
                const mergeDestId = event.detail["mergeDestId"]

                if (mergeSourceId === mergeDestId) {
                    return
                }

                htmx.ajax(
                    "GET",
                    `/htmx/people/r/${this.context.getRepoId()}/modals/merge`,
                    {
                        swap: "innerHTML",
                        target: "#modalContent",
                        values: { ...event.detail },
                    },
                )
            },
        )

        document.body.addEventListener(Const.events.personMerged, (event) => {
            const mergeSourceId = event.detail["mergeSourceId"]
            const sourcePersonEl = htmx.find(`#person-${mergeSourceId}`)

            if (sourcePersonEl) {
                sourcePersonEl.remove()
            }

            showSuccessSnackBar("Person merged successfully")
        })

        document.body.addEventListener(
            Const.events.personNameEdited,
            (event) => {
                const personId = event.detail["personId"]
                const newPersonName = event.detail["newPersonName"]
                const personNameEl = htmx.find(`#person-${personId} .name a`)

                if (!personNameEl) {
                    return
                }

                personNameEl.textContent = newPersonName
                personNameEl.classList.remove("unknown")
            },
        )

        document.body.addEventListener(
            Const.events.personCoverFaceSet,
            (event) => {
                const personId = event.detail["personId"]
                const faceId = event.detail["faceId"]
                const imageEl = htmx.find(`#person-${personId} .image img`)

                if (!imageEl) {
                    return
                }

                imageEl.src = `/content/r/${this.context.getRepoId()}/face/${faceId}`
            },
        )

        document.body.addEventListener(
            Const.events.personMarkedAsBadMatch,
            (event) => {
                const personId = event.detail["personId"]
                const personEl = htmx.find(`#person-${personId}`)

                if (personEl) {
                    personEl.remove()
                }
            },
        )

        document.body.addEventListener(Const.events.assetMoved, (event) => {
            const assetId = event.detail["assetId"]
            const folderId = event.detail["folderId"]
            const selectedAssetsStore = this.Alpine.store(
                Const.state.selectedAssets,
            )

            if (
                !selectedAssetsStore.isEmpty &&
                selectedAssetsStore.contains(assetId)
            ) {
                this.dispatch(Const.events.batchAssetsMoved, { folderId })
                return
            }

            this.moveAssets({ folderId, assetIds: [assetId] })
        })

        document.body.addEventListener(
            Const.events.batchAssetsMoved,
            (event) => {
                const folderId = event.detail["folderId"]
                const selectedAssetsStore = this.Alpine.store(
                    Const.state.selectedAssets,
                )

                this.moveAssets({
                    folderId,
                    assetIds: Array.from(selectedAssetsStore.items.keys()),
                })
            },
        )

        document.body.addEventListener(Const.events.assetTrashed, (event) => {
            const assetId = event.detail["assetId"]
            const selectedAssetsStore = this.Alpine.store(
                Const.state.selectedAssets,
            )

            if (
                !selectedAssetsStore.isEmpty &&
                selectedAssetsStore.contains(assetId)
            ) {
                this.dispatch(Const.events.batchAssetsRecycled)
                return
            }

            this.recycleAssets({ assetIds: [assetId] })
        })

        document.body.addEventListener(
            Const.events.batchAssetsRecycled,
            () => {
                const selectedAssetsStore = this.Alpine.store(
                    Const.state.selectedAssets,
                )

                this.recycleAssets({
                    assetIds: Array.from(selectedAssetsStore.items.keys()),
                })
            },
        )

        document.body.addEventListener(Const.events.batchAssetsPurged, () => {
            const selectedAssetsStore = this.Alpine.store(
                Const.state.selectedAssets,
            )

            this.purgeAssets({
                assetIds: Array.from(selectedAssetsStore.items.keys()),
            })
        })

        document.body.addEventListener(Const.events.batchAssetsRestored, () => {
            const selectedAssetsStore = this.Alpine.store(
                Const.state.selectedAssets,
            )

            this.restoreAssets({
                assetIds: Array.from(selectedAssetsStore.items.keys()),
            })
        })

        document.body.addEventListener(
            Const.events.viewSettingChanged,
            (event) => {
                this.handleViewSettingChanged(event)
            },
        )

        document.body.addEventListener(Const.events.deselectAll, () => {
            const selectedAssetsStore = this.Alpine.store(
                Const.state.selectedAssets,
            )
            selectedAssetsStore.items.forEach((asset) => {
                asset.deselect()
            })
            selectedAssetsStore.items.clear()
        })

        document.body.addEventListener(Const.events.showNext, () => {
            this.handleShowNext()
        })

        document.body.addEventListener(Const.events.showPrevious, () => {
            this.handleShowPrevious()
        })

        document.body.addEventListener(Const.events.detailShown, (event) => {
            this.Alpine.store(Const.state.shadowResults).currentAssetId =
                event.detail.assetId
        })

        document.body.addEventListener("htmx:afterRequest", (event) => {
            this.handleAfterRequest(event)
        })

        document.body.addEventListener("htmx:afterSwap", (event) => {
            this.handleAfterSwap(event)
        })
    }

    handleAfterRequest(event) {
        const requestPath = event.detail.pathInfo.requestPath
        const status = event.detail.xhr.status

        if (this.isTrashPurgeRequest(requestPath)) {
            if (event.detail.successful === false) {
                showErrorSnackBar(
                    `Error for request to ${requestPath}. HTTP ${status}`,
                )
                return
            }

            this.reloadNav()
            return
        }

        if (status !== 200 || !requestPath.startsWith("/htmx/search/r")) {
            return
        }

        this.Alpine.store(Const.state.searchUrl).set(requestPath)

        if (!event.target.classList?.contains("last-cell")) {
            this.syncShadowResultsFromSearchUrl()
        }
    }

    isTrashPurgeRequest(requestPath) {
        return (
            requestPath.startsWith("/htmx/trash//r/") &&
            requestPath.endsWith("/purge")
        )
    }

    handleAfterSwap(event) {
        this.hydrateFragments(event.detail.target)
    }

    hydrateFragments(root) {
        this.findFragmentRoots(root, "search-results").forEach((fragmentEl) => {
            this.hydrateSearchResultsFragment(fragmentEl)
        })
    }

    findFragmentRoots(root, fragmentName) {
        if (!(root instanceof Element || root instanceof Document)) {
            return []
        }

        const selector = `[data-app-fragment="${fragmentName}"]`
        const roots = []

        if (root instanceof Element && root.matches(selector)) {
            roots.push(root)
        }

        roots.push(...root.querySelectorAll(selector))

        return roots
    }

    hydrateSearchResultsFragment(fragmentEl) {
        const resultsTotal = Number(fragmentEl.dataset.resultsTotal || 0)
        const contentElement = document.getElementById("content")
        const assetsElement = fragmentEl.querySelector("#assets")

        if (contentElement) {
            contentElement.scrollTo({ top: 0, behavior: "auto" })
        }

        this.Alpine.store(Const.state.selectedAssets).reset()
        this.Alpine.store(Const.state.resultsTotal).set(resultsTotal)
        this.Alpine.store(Const.state.shadowResults).reset()

        if (!assetsElement) {
            return
        }

        this.bindSearchResultsInfiniteScroll(assetsElement)
        this.bindSearchResultsLazyLoad(assetsElement)
        this.applyGridMetadataVisibilityToAllCells(assetsElement)
        this.syncShadowResultsFromSearchUrl()
    }

    bindSearchResultsInfiniteScroll(assetsElement) {
        if (assetsElement.dataset.appInfiniteScrollBound === "true") {
            return
        }

        assetsElement.dataset.appInfiniteScrollBound = "true"

        assetsElement.addEventListener("htmx:beforeRequest", (event) => {
            if (!event.target.classList.contains("last-cell")) {
                return
            }

            if (event.detail.target.getAttribute("data-hx-revealed")) {
                event.preventDefault()
            } else {
                console.debug(
                    "Loading more: %s",
                    event.detail.pathInfo.requestPath,
                )
            }
        })

        assetsElement.addEventListener("htmx:afterRequest", (event) => {
            if (!event.target.classList.contains("last-cell")) {
                return
            }

            event.detail.target.setAttribute("data-hx-revealed", "true")

            if (event.detail.successful) {
                this.appendShadowResultsForRequestPath(
                    event.detail.pathInfo.requestPath,
                )
            }
        })
    }

    bindSearchResultsLazyLoad(assetsElement) {
        if (assetsElement.dataset.appLazyLoadBound === "true") {
            return
        }

        assetsElement.dataset.appLazyLoadBound = "true"

        const observer = this.getLazyImageObserver()

        assetsElement.addEventListener("htmx:load", (event) => {
            const imgEl = event.target.querySelector("img")
            if (imgEl) {
                observer.observe(imgEl)
            }

            this.showOrHideAssetGridMetadata(event.target)
        })

        assetsElement.querySelectorAll(".cell").forEach((cellEl) => {
            const imgEl = cellEl.querySelector("img")
            if (imgEl) {
                observer.observe(imgEl)
            }

            this.showOrHideAssetGridMetadata(cellEl)
        })
    }

    getLazyImageObserver() {
        if (this.lazyImageObserver) {
            return this.lazyImageObserver
        }

        const observerOptions = {
            root: null,
            rootMargin: "0px 100% 0px 100%",
            threshold: [0, 1],
        }

        this.lazyImageObserver = new IntersectionObserver((entries) => {
            entries.forEach((entry) => {
                const imgEl = entry.target

                if (entry.isIntersecting) {
                    if (
                        imgEl.getAttribute("src") ===
                        imgEl.getAttribute(Const.attributes.dataSrc)
                    ) {
                        return
                    }

                    if (imgEl.hasAttribute(Const.attributes.dataSrc)) {
                        imgEl.src = imgEl.getAttribute(Const.attributes.dataSrc)
                    }

                    return
                }

                if (imgEl.getAttribute("src") === placeholderImageData) {
                    return
                }

                if (imgEl.hasAttribute(Const.attributes.dataSrc)) {
                    imgEl.src = placeholderImageData
                }
            })
        }, observerOptions)

        return this.lazyImageObserver
    }

    applyGridMetadataVisibilityToAllCells(assetsElement) {
        assetsElement.querySelectorAll(".cell").forEach((cellEl) => {
            this.showOrHideAssetGridMetadata(cellEl)
        })
    }

    showOrHideAssetGridMetadata(cellEl) {
        const showFields = this.context.getGridMetadataFields()
        const metadata = cellEl.querySelector(".metadata")

        if (!metadata) {
            return
        }

        const toShowFieldsSelectorStr = Array.from(showFields)
            .map((fieldName) => `.metadata > div.${fieldName}`)
            .join(", ")

        metadata.style.display = toShowFieldsSelectorStr.length ? "grid" : "none"

        if (!toShowFieldsSelectorStr.length) {
            return
        }

        cellEl.querySelectorAll(".metadata > div").forEach((divEl) => {
            divEl.style.display = "none"
        })

        cellEl.querySelectorAll(toShowFieldsSelectorStr).forEach((divEl) => {
            divEl.style.display = "block"
        })
    }

    handleViewSettingChanged(event) {
        const fieldName = event.detail["fieldName"]
        const checked = event.detail["checked"]

        document
            .querySelectorAll(`.metadata > div.${fieldName}`)
            .forEach((divEl) => {
                divEl.style.display = checked ? "block" : "none"
            })

        const showFields = this.context.getGridMetadataFields()
        const metadataDisplay = showFields.size === 0 ? "none" : "grid"

        document.querySelectorAll("#assets .metadata").forEach((divEl) => {
            divEl.style.display = metadataDisplay
        })
    }

    syncShadowResultsFromSearchUrl() {
        const searchUrl = this.Alpine.store(Const.state.searchUrl).url
        if (!searchUrl) {
            return
        }

        const token = ++this.shadowResultsSyncToken
        const store = this.Alpine.store(Const.state.shadowResults)

        this.fetchSearchResultsJson(searchUrl)
            .then((data) => {
                if (!data || token !== this.shadowResultsSyncToken) {
                    return
                }

                store.replace(data.ids, data.page, data.totalPages)
            })
            .catch((error) => {
                console.error(`Error fetching search results: ${error}`)
            })
    }

    appendShadowResultsForRequestPath(requestPath) {
        const store = this.Alpine.store(Const.state.shadowResults)

        this.fetchSearchResultsJson(requestPath)
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

    fetchSearchResultsJson(url) {
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

    handleShowNext() {
        const store = this.Alpine.store(Const.state.shadowResults)
        const currentIdx = store.items.indexOf(store.currentAssetId)
        const nextId =
            currentIdx !== -1 && currentIdx + 1 < store.items.length
                ? store.items[currentIdx + 1]
                : null

        if (nextId) {
            store.currentAssetId = nextId
            this.loadAssetDetail(nextId)
            return
        }

        if (store.page < store.totalPages) {
            const nextPage = store.page + 1
            this.fetchShadowSearchResultsPage(nextPage).then((data) => {
                if (!data) {
                    return
                }

                store.append(data.ids)
                store.page = nextPage
                store.totalPages = data.totalPages
                this.dispatch(Const.events.showNext)
            })
        }
    }

    handleShowPrevious() {
        const store = this.Alpine.store(Const.state.shadowResults)
        const currentIdx = store.items.indexOf(store.currentAssetId)
        const previousId = currentIdx > 0 ? store.items[currentIdx - 1] : null

        if (previousId) {
            store.currentAssetId = previousId
            this.loadAssetDetail(previousId)
            return
        }

        if (store.page > 1) {
            const previousPage = store.page - 1
            this.fetchShadowSearchResultsPage(previousPage).then((data) => {
                if (!data) {
                    return
                }

                store.prepend(data.ids)
                store.page = previousPage
                store.totalPages = data.totalPages
                this.dispatch(Const.events.showPrevious)
            })
        }
    }

    fetchShadowSearchResultsPage(pageNum) {
        const searchUrl = this.Alpine.store(Const.state.searchUrl).url
        if (!searchUrl) {
            return Promise.resolve(null)
        }

        const url = new URL(searchUrl, window.location.origin)
        url.searchParams.set("p", pageNum)

        return this.fetchSearchResultsJson(url.toString())
    }

    loadAssetDetail(assetId) {
        const repoId = this.context.getRepoId()
        const imgEl = document.querySelector("#imageDetailModalContent img")

        if (!imgEl) {
            return
        }

        this.Alpine.store(Const.state.imageDetailLoading).value = true

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
                    this.Alpine.store(Const.state.imageDetailLoading).value =
                        false
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

                this.dispatch(Const.events.detailShown, { assetId })
            })
            .catch((error) => {
                console.error(`Error loading asset detail: ${error}`)
                this.Alpine.store(Const.state.imageDetailLoading).value = false
            })
    }

    dispatch(eventName, detail = {}) {
        document.body.dispatchEvent(new CustomEvent(eventName, { detail }))
    }

    reloadNav() {
        htmx.ajax("GET", `/htmx/nav/r/${this.context.getRepoId()}`, {
            swap: "innerHTML",
            target: "nav",
        })
    }

    removeTriageStyling(assetIds) {
        for (const assetId of assetIds) {
            const cellEl = htmx.find(`#asset-${assetId}`)
            if (!cellEl) {
                continue
            }

            cellEl.removeAttribute("alt-is-triaged")

            const marker = cellEl.querySelector(".triage-marker")
            if (marker) {
                marker.remove()
            }
        }
    }

    removeAssetsFromGrid(assetIds) {
        let removedCount = 0

        for (const assetId of assetIds) {
            const el = htmx.find(`#asset-${assetId}`)
            if (!el) {
                continue
            }

            el.remove()
            removedCount++
        }

        if (removedCount > 0) {
            this.Alpine.store(Const.state.resultsTotal).decrement(removedCount)
        }
    }

    shouldRemoveFromGrid(destinationFolderId) {
        if (this.Alpine.store(Const.state.currentView).isTriageView()) {
            return true
        }

        const viewedFolderId = this.context.getCurrentFolderId()
        if (!viewedFolderId) {
            return false
        }

        try {
            const destFolder = new Folder(destinationFolderId)
            return !destFolder.isDescendantOrSelf(viewedFolderId)
        } catch {
            console.debug(
                `Destination folder ${destinationFolderId} not in DOM, assuming outside viewed subtree`,
            )
            return true
        }
    }

    shouldResetSelectedAssets(assetIds) {
        return (
            assetIds.length > 1 ||
            (assetIds.length === 1 &&
                this.Alpine.store(Const.state.selectedAssets).contains(assetIds[0]))
        )
    }

    moveAssets({ folderId, assetIds }) {
        const newParentFolder = new Folder(folderId)
        const payload = { assetIds, folderId }

        fetch(`/api/asset/r/${this.context.getRepoId()}/move`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    showErrorSnackBar(`Error moving assets: ${response.statusText}`)
                    return
                }

                const successMessage = `${
                    assetIds.length > 1 ? "Assets" : "Asset"
                } moved to folder "${newParentFolder.name()}"`
                showSuccessSnackBar(successMessage)

                this.removeTriageStyling(assetIds)

                if (this.shouldRemoveFromGrid(folderId)) {
                    this.removeAssetsFromGrid(assetIds)
                }

                if (this.shouldResetSelectedAssets(assetIds)) {
                    this.Alpine.store(Const.state.selectedAssets).reset()
                }

                this.reloadNav()
            })
            .catch((error) => {
                showErrorSnackBar(`Error moving assets: ${error}`)
            })
    }

    recycleAssets({ assetIds }) {
        const payload = { assetIds }

        fetch(`/api/asset/r/${this.context.getRepoId()}/move`, {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    showErrorSnackBar(
                        `Error moving assets to trash: ${response.statusText}`,
                    )
                    return
                }

                const successMessage = `${
                    assetIds.length > 1 ? "Assets" : "Asset"
                } moved to the trash bin`
                showSuccessSnackBar(successMessage)

                this.removeAssetsFromGrid(assetIds)

                if (this.shouldResetSelectedAssets(assetIds)) {
                    this.Alpine.store(Const.state.selectedAssets).reset()
                }

                this.reloadNav()
            })
            .catch((error) => {
                showErrorSnackBar(`Error moving assets to trash: ${error}`)
            })
    }

    purgeAssets({ assetIds }) {
        const payload = { assetIds }

        fetch(`/api/asset/r/${this.context.getRepoId()}/purge`, {
            method: "DELETE",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    showErrorSnackBar(
                        `Error purging assets: ${response.statusText}`,
                    )
                    return
                }

                const successMessage = `${
                    assetIds.length > 1 ? "Assets" : "Asset"
                } permanently deleted`
                showSuccessSnackBar(successMessage)

                this.removeAssetsFromGrid(assetIds)
                this.Alpine.store(Const.state.selectedAssets).reset()
                this.reloadNav()
            })
            .catch((error) => {
                showErrorSnackBar(`Error purging assets: ${error}`)
            })
    }

    restoreAssets({ assetIds }) {
        const payload = { assetIds }

        fetch(`/api/asset/r/${this.context.getRepoId()}/restore`, {
            method: "PUT",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload),
        })
            .then((response) => {
                if (!response.ok) {
                    if (response.status === 409) {
                        showWarningSnackBar(
                            "Cannot restore: a non-recycled asset with the same content already exists",
                        )
                    } else {
                        showErrorSnackBar(
                            `Error restoring assets: ${response.statusText}`,
                        )
                    }
                    return
                }

                const successMessage = `${
                    assetIds.length > 1 ? "Assets" : "Asset"
                } restored`
                showSuccessSnackBar(successMessage)

                this.removeAssetsFromGrid(assetIds)
                this.Alpine.store(Const.state.selectedAssets).reset()
                this.reloadNav()
            })
            .catch((error) => {
                showErrorSnackBar(`Error restoring assets: ${error}`)
            })
    }
}


