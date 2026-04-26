import { Const } from "./constants.js"
import { Folder } from "./models/folder.js"
import { showErrorSnackBar } from "./common/snackbar.js"
import { createAssetActions } from "./assets/asset-actions.js"
import { bindAppDragDrop } from "./dragdrop/index.js"
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
import { createSearchDetailCoordinator } from "./search-results/detail-navigator.js"
import "./search-results/dragon-drop.js"

export class FrontendApp {
    constructor({ Alpine, context }) {
        this.Alpine = Alpine
        this.context = context
        this.started = false
        this.lazyImageObserver = null
        this.assetActions = createAssetActions({
            Alpine,
            context,
            reloadNav: this.reloadNav.bind(this),
        })
        this.searchDetailCoordinator = createSearchDetailCoordinator({
            Alpine,
            context,
            dispatch: this.dispatch.bind(this),
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
        bindAppDragDrop(this)
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
        this.searchDetailCoordinator.syncShadowResultsFromSearchUrl()
    }

    appendShadowResultsForRequestPath(requestPath) {
        this.searchDetailCoordinator.appendShadowResultsForRequestPath(requestPath)
    }

    fetchSearchResultsJson(url) {
        return this.searchDetailCoordinator.fetchSearchResultsJson(url)
    }

    handleShowNext() {
        this.searchDetailCoordinator.handleShowNext()
    }

    handleShowPrevious() {
        this.searchDetailCoordinator.handleShowPrevious()
    }

    fetchShadowSearchResultsPage(pageNum) {
        return this.searchDetailCoordinator.fetchShadowSearchResultsPage(pageNum)
    }

    loadAssetDetail(assetId) {
        this.searchDetailCoordinator.loadAssetDetail(assetId)
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


