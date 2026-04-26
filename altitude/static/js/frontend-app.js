import { Const } from "./constants.js"
import { Folder } from "./models/folder.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "./common/snackbar.js"
import {
    dragged,
    dragMoveListener,
    setFixedPositionWhileDragging,
} from "./common/dragon-drop.js"
import { createAssetActions } from "./assets/asset-actions.js"
import { showAssetDetailModal } from "./common/modal.js"
import { hydrateAppFragments } from "./fragments/index.js"
import { handleViewSettingChanged } from "./fragments/search-results.js"
import {
    handleFolderAfterRequest,
    handleFolderBeforeRequest,
    isFolderRequest,
} from "./listeners/htmx-folders.js"
import {
    handlePeopleAfterRequest,
    handlePeopleEscapeKeyPressed,
} from "./listeners/htmx-people-inline-editor.js"
import { registerAppEventListeners } from "./listeners/index.js"
import { setImgSrcAndWait } from "./search-results/detail-navigator.js"
import "./search-results/dragon-drop.js"

export class FrontendApp {
    constructor({ Alpine, context }) {
        this.Alpine = Alpine
        this.context = context
        this.started = false
        this.shadowResultsSyncToken = 0
        this.lazyImageObserver = null
        this.assetActions = createAssetActions({
            Alpine,
            context,
            reloadNav: this.reloadNav.bind(this),
        })
    }

    start() {
        if (this.started) {
            return this
        }

        this.started = true
        this.initializeStores()
        this.registerEventListeners()
        this.Alpine.start()
        this.bindBatchOpsDragDrop()
        this.bindPeopleDragDrop()
        this.bindFolderDragDrop()
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
        registerAppEventListeners(this)
    }

    handleBeforeRequest(event) {
        const requestPath = event.detail.pathInfo.requestPath

        if (!isFolderRequest({ app: this, requestPath })) {
            return
        }

        handleFolderBeforeRequest({ app: this, event })
    }

    handleAfterRequest(event) {
        const requestPath = event.detail.pathInfo.requestPath
        const status = event.detail.xhr.status

        if (handlePeopleAfterRequest({ app: this, event })) {
            return
        }

        if (isFolderRequest({ app: this, requestPath })) {
            handleFolderAfterRequest({ app: this, event })
            return
        }

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

    handleEscapeKeyPressed() {
        handlePeopleEscapeKeyPressed()
    }

    handleHtmxLoad(event) {
        if (event.target.id === "folderNavWarning") {
            this.Alpine.initTree(event.target)
        }
    }

    handleAfterSwap(event) {
        this.hydrateFragments(event.detail.target)
    }

    bindBatchOpsDragDrop() {
        const dragHandleEl = document.querySelector("#batchOps button.drag-drop")
        if (!dragHandleEl) {
            return
        }

        interact("#batchOps button.drag-drop").draggable({
            inertia: true,
            autoScroll: true,

            listeners: {
                move: dragMoveListener,
                start: () => {
                    const selectedAssetsStore = this.Alpine.store(
                        Const.state.selectedAssets,
                    )

                    selectedAssetsStore.items.forEach((asset) => {
                        asset.drag()
                    })
                },
                end: (event) => {
                    const selectedAssetsStore = this.Alpine.store(
                        Const.state.selectedAssets,
                    )

                    selectedAssetsStore.items.forEach((asset) => {
                        asset.drop()
                    })

                    dragged(event)
                },
            },
        })
    }

    bindPeopleDragDrop() {
        interact("#people .drag-drop, #person .drag-drop").draggable({
            inertia: true,
            autoScroll: true,

            listeners: {
                move: dragMoveListener,
                start: setFixedPositionWhileDragging,
                end: dragged,
            },
        })

        interact("#people .dropzone, #person.dropzone").dropzone({
            accept: "#person .drag-drop, #people .drag-drop",
            overlap: 0.75,

            ondropactivate: (event) => {
                event.target.classList.add("drop-active")
            },

            ondragenter: (event) => {
                const draggableElement = event.relatedTarget
                const dropzoneElement = event.target

                dropzoneElement.classList.add("drop-target")
                draggableElement.classList.add("can-drop")
            },

            ondragleave: (event) => {
                event.target.classList.remove("drop-target")
                event.relatedTarget.classList.remove("can-drop")
            },

            ondrop: (event) => {
                const draggableElement = event.relatedTarget
                const dropzoneElement = event.target

                dropzoneElement.classList.remove("drop-active")
                dropzoneElement.classList.remove("drop-target")
                draggableElement.classList.remove("can-drop")

                const mergeSourceId = draggableElement.getAttribute(
                    Const.attributes.personId,
                )
                const mergeDestId = dropzoneElement.getAttribute(
                    Const.attributes.personId,
                )

                console.debug(`Merging ${mergeSourceId} into ${mergeDestId}`)
                this.dispatch(Const.events.confirmPersonMerge, {
                    mergeSourceId,
                    mergeDestId,
                })
            },

            ondropdeactivate: (event) => {
                event.target.classList.remove("drop-active")
                event.target.classList.remove("drop-target")
            },
        })
    }

    bindFolderDragDrop() {
        interact("#rootFolderList .drag-drop").draggable({
            inertia: true,
            autoScroll: true,

            listeners: {
                move: dragMoveListener,
                end: dragged,
            },
        })

        interact("#rootFolderList .dropzone").dropzone({
            accept: "#rootFolderList .drag-drop, #assets .drag-drop, #batchOps .drag-drop",
            overlap: 0.2,

            ondropactivate: (event) => {
                event.target.classList.add("drop-active")
            },

            ondragenter: (event) => {
                const draggableElement = event.relatedTarget
                const dropzoneElement = event.target

                dropzoneElement.classList.add("drop-target")
                draggableElement.classList.add("can-drop")
            },

            ondragleave: (event) => {
                event.target.classList.remove("drop-target")
                event.relatedTarget.classList.remove("can-drop")
            },

            ondrop: (event) => {
                const draggableElement = event.relatedTarget
                const dropzoneElement = event.target
                const movedFolderId = draggableElement.getAttribute(
                    Const.attributes.folderId,
                )
                const movedAssetId = draggableElement.getAttribute(
                    Const.attributes.assetId,
                )
                const isBatchMover = draggableElement.parentNode.classList.contains(
                    "batch-mover",
                )
                const newParentId = dropzoneElement.getAttribute(
                    Const.attributes.folderId,
                )

                dropzoneElement.classList.remove("drop-active")
                dropzoneElement.classList.remove("drop-target")
                draggableElement.classList.remove("can-drop")

                if (movedFolderId) {
                    console.debug(`Moved folder ${movedFolderId} to ${newParentId}`)
                    this.dispatch(Const.events.folderMoved, {
                        movedFolderId,
                        newParentId,
                    })
                }

                if (movedAssetId) {
                    console.debug(`Moved asset ${movedAssetId} to ${newParentId}`)
                    this.dispatch(Const.events.assetMoved, {
                        assetId: movedAssetId,
                        folderId: newParentId,
                    })
                }

                if (isBatchMover) {
                    console.debug(`Batch moving assets to folder ${newParentId}`)
                    this.dispatch(Const.events.batchAssetsMoved, {
                        folderId: newParentId,
                    })
                }
            },

            ondropdeactivate: (event) => {
                event.target.classList.remove("drop-active")
                event.target.classList.remove("drop-target")
            },
        })
    }

    hydrateFragments(root) {
        hydrateAppFragments({ root, app: this })
    }

    closeFolderContextMenu(folderId) {
        if (!folderId) {
            return
        }

        try {
            new Folder(folderId).closeContextMenu()
        } catch (error) {
            console.debug(`Unable to close folder context menu for ${folderId}`, error)
        }
    }

    handleViewSettingChanged(event) {
        handleViewSettingChanged({ event, context: this.context })
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

    moveAssets({ folderId, assetIds }) {
        this.assetActions.moveAssets({ folderId, assetIds })
    }

    recycleAssets({ assetIds }) {
        this.assetActions.recycleAssets({ assetIds })
    }

    purgeAssets({ assetIds }) {
        this.assetActions.purgeAssets({ assetIds })
    }

    restoreAssets({ assetIds }) {
        this.assetActions.restoreAssets({ assetIds })
    }
}


