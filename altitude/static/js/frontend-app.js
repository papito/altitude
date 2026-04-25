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
import { closeModal, showAssetDetailModal, showModal } from "./common/modal.js"
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
        document.body.addEventListener(Const.events.folderMoved, (event) => {
            const movedFolderId = event.detail["movedFolderId"]
            const newParentId = event.detail["newParentId"]

            if (newParentId === movedFolderId) {
                return
            }

            const movedFolder = new Folder(movedFolderId)
            const newParent = new Folder(newParentId)
            const oldParent = movedFolder.parent()

            htmx.ajax(
                "put",
                `/htmx/folder/r/${this.context.getRepoId()}/move?movedFolderId=${movedFolderId}&newParentId=${newParentId}`,
                {
                    swap: "none",
                    handler: (response) => {
                        const status = response["htmx-internal-data"].xhr.status

                        if (status === 200) {
                            movedFolder.closeContextMenu()
                            movedFolder.clearChildren()

                            newParent.incrementNumOfChildren()
                            oldParent.decrementNumOfChildren()

                            showSuccessSnackBar(
                                `Folder ${movedFolder.name()} moved into "${newParent.name()}"`,
                            )

                            if (newParent.isExpanded()) {
                                newParent.addChild(movedFolder)
                                movedFolder.collapse()
                            } else {
                                movedFolder.remove()
                            }

                            newParent.updateVisualState()
                            oldParent.updateVisualState()
                        } else if (status === 409) {
                            showWarningSnackBar(
                                response["htmx-internal-data"].xhr.responseText,
                            )
                        } else {
                            showErrorSnackBar(
                                `Error moving folder "${movedFolder.name()}". Status: ${status}`,
                            )
                        }
                    },
                },
            )
        })

        document.body.addEventListener(Const.events.folderAdded, (event) => {
            const parentFolder = new Folder(event.detail["parentId"])
            parentFolder.incrementNumOfChildren()
            parentFolder.updateVisualState()
        })

        document.body.addEventListener(Const.events.folderDeleted, (event) => {
            const folder = new Folder(event.detail["id"])
            const parent = folder.parent()

            parent.decrementNumOfChildren()
            parent.updateVisualState()

            showSuccessSnackBar(`Folder "${folder.name()}" deleted`)
            folder.remove()
        })

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

        document.body.addEventListener(Const.events.escapeKeyPressed, () => {
            this.handleEscapeKeyPressed()
        })

        document.body.addEventListener("htmx:afterRequest", (event) => {
            this.handleAfterRequest(event)
        })

        document.body.addEventListener("htmx:afterSwap", (event) => {
            this.handleAfterSwap(event)
        })

        document.body.addEventListener("htmx:beforeRequest", (event) => {
            this.handleBeforeRequest(event)
        })

        document.body.addEventListener("htmx:load", (event) => {
            this.handleHtmxLoad(event)
        })
    }

    handleBeforeRequest(event) {
        const requestPath = event.detail.pathInfo.requestPath

        if (!this.isFolderRequest(requestPath)) {
            return
        }

        if (
            requestPath ===
            `/htmx/folder/r/${this.context.getRepoId()}/context-menu`
        ) {
            const folderId = event.detail.target.getAttribute(
                Const.attributes.folderId,
            )
            const folder = new Folder(folderId)

            if (folder.isMenuExpanded()) {
                folder.closeContextMenu()
                event.preventDefault()
            } else {
                document
                    .querySelectorAll("#rootFolderList .menu")
                    .forEach((menuEl) => {
                        Folder.closeContextMenu(menuEl)
                    })
            }
        }

        if (
            requestPath === `/htmx/folder/r/${this.context.getRepoId()}/children`
        ) {
            const url = new URL(
                "https://dummy.com" + event.detail.pathInfo.finalRequestPath,
            )
            const folderId = url.searchParams.get("parentId")
            const folder = new Folder(folderId)

            if (folder.isRoot) {
                return
            }

            if (folder.isExpanded()) {
                folder.collapse()
                event.preventDefault()
                return
            }

            if (folder.numOfChildren() === 0) {
                event.preventDefault()
                const currentView = this.Alpine.store(Const.state.currentView)
                if (
                    currentView.isTriageView() ||
                    currentView.isTrashBinView()
                ) {
                    return
                }

                folder.folderNameEl().click()
                return
            }

            folder.expand()
        }
    }

    handleAfterRequest(event) {
        const requestPath = event.detail.pathInfo.requestPath
        const status = event.detail.xhr.status
        const discardPersonElement = this.getDiscardPersonElement(event)
        const personNameEditorElement = this.getPersonNameEditorElement(event)

        if (this.isPersonNameEditRequest(requestPath, personNameEditorElement)) {
            this.handlePersonNameEditAfterRequest(event, personNameEditorElement)
            return
        }

        if (this.isDiscardPersonRequest(requestPath, discardPersonElement)) {
            if (event.detail.successful === false) {
                return
            }

            this.dispatch(Const.events.personMarkedAsBadMatch, {
                personId: discardPersonElement.getAttribute(
                    Const.attributes.personId,
                ),
            })
            return
        }

        if (this.isFolderRequest(requestPath)) {
            this.handleFolderAfterRequest(event)
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

    handleFolderAfterRequest(event) {
        const requestPath = event.detail.pathInfo.requestPath
        const status = event.detail.xhr.status

        if (event.detail.successful === false) {
            showErrorSnackBar(
                `Error for request to ${requestPath}. HTTP ${status}`,
            )
            return
        }

        if (
            requestPath ===
            `/htmx/folder/r/${this.context.getRepoId()}/context-menu`
        ) {
            const folder = new Folder(
                event.target.getAttribute(Const.attributes.folderId),
            )
            folder.showContextMenu()

            if (!folder.isExpanded() && !folder.isRoot) {
                folder.htmxExpandChildrenAction()
            }
        }

        if (
            requestPath === `/htmx/folder/r/${this.context.getRepoId()}/children` ||
            requestPath === `/htmx/folder/r/${this.context.getRepoId()}/add`
        ) {
            const folder = new Folder(
                event.target.getAttribute(Const.attributes.folderId),
            )
            folder.expand()
        }
    }

    isTrashPurgeRequest(requestPath) {
        return (
            requestPath.startsWith("/htmx/trash//r/") &&
            requestPath.endsWith("/purge")
        )
    }

    isFolderRequest(requestPath) {
        return requestPath.startsWith(`/htmx/folder/r/${this.context.getRepoId()}/`)
    }

    getDiscardPersonElement(event) {
        return event.target?.closest?.("#markAsBadMatch") ?? null
    }

    isDiscardPersonRequest(requestPath, discardPersonElement) {
        return (
            requestPath.startsWith(`/htmx/people/r/${this.context.getRepoId()}/p/`) &&
            discardPersonElement !== null
        )
    }

    getPersonNameEditorElement(event) {
        return event.target?.closest?.("#editPersonName") ?? null
    }

    isPersonNameEditRequest(requestPath, personNameEditorElement) {
        return (
            requestPath.startsWith(`/htmx/people/r/${this.context.getRepoId()}/p/`) &&
            requestPath.endsWith("/name/edit") &&
            personNameEditorElement !== null
        )
    }

    handlePersonNameEditAfterRequest(event, personNameEditorElement) {
        if (event.detail.successful === false) {
            return
        }

        if (event.detail.xhr.responseText.includes('id="editPersonName"')) {
            return
        }

        const responseEl = document.createElement("div")
        responseEl.innerHTML = event.detail.xhr.responseText
        const newPersonName = responseEl.textContent?.trim() || ""

        this.dispatch(Const.events.personNameEdited, {
            personId: personNameEditorElement.dataset.appPersonId,
            newPersonName,
        })
    }

    handleEscapeKeyPressed() {
        const personNameEditorElement = document.querySelector(
            '[data-app-fragment="person-name-editor"]',
        )
        if (!personNameEditorElement) {
            return
        }

        htmx.ajax("GET", personNameEditorElement.dataset.appRestoreUrl, {
            swap: "innerHTML",
            target: "#personName",
        })
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
        this.findFragmentRoots(root, "person-name-editor").forEach(
            (fragmentEl) => {
                this.hydratePersonNameEditorFragment(fragmentEl)
            },
        )

        this.findFragmentRoots(root, "modal").forEach((fragmentEl) => {
            this.hydrateModalFragment(fragmentEl)
        })

        this.findFragmentRoots(root, "image-detail").forEach((fragmentEl) => {
            this.hydrateImageDetailFragment(fragmentEl)
        })

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

    hydrateModalFragment(fragmentEl) {
        showModal({
            minWidthPx: fragmentEl.dataset.appModalMinWidth,
            title: fragmentEl.dataset.appModalTitle,
        })

        this.initializeModalFragment(fragmentEl)
        this.focusFragmentElement(
            fragmentEl,
            fragmentEl.dataset.appModalAutofocusSelector,
            fragmentEl.dataset.appModalSelectOnFocus === "true",
        )
        this.bindModalFragment(fragmentEl)
    }

    initializeModalFragment(fragmentEl) {
        if (fragmentEl.dataset.appModalKind === "view-settings") {
            this.initializeViewSettingsModalFragment(fragmentEl)
        }
    }

    initializeViewSettingsModalFragment(fragmentEl) {
        const showFields = this.context.getGridMetadataFields()

        fragmentEl
            .querySelectorAll('input[type="checkbox"]')
            .forEach((checkboxEl) => {
                checkboxEl.checked = showFields.has(checkboxEl.value)
            })

        if (fragmentEl.dataset.appViewSettingsBound === "true") {
            return
        }

        fragmentEl.dataset.appViewSettingsBound = "true"

        fragmentEl.addEventListener("change", (event) => {
            const checkboxEl = event.target
            if (
                !(checkboxEl instanceof HTMLInputElement) ||
                checkboxEl.type !== "checkbox"
            ) {
                return
            }

            const fieldName = checkboxEl.value
            const checked = checkboxEl.checked

            if (checked) {
                this.context.addGridMetadataField(fieldName)
            } else {
                this.context.removeGridMetadataField(fieldName)
            }

            this.dispatch(Const.events.viewSettingChanged, {
                fieldName,
                checked,
            })
        })
    }

    focusFragmentElement(fragmentEl, selector, selectOnFocus = false) {
        if (!selector) {
            return
        }

        const focusEl = fragmentEl.matches(selector)
            ? fragmentEl
            : fragmentEl.querySelector(selector)

        if (!focusEl) {
            return
        }

        focusEl.focus()

        if (selectOnFocus && typeof focusEl.select === "function") {
            focusEl.select()
        }
    }

    bindModalFragment(fragmentEl) {
        if (fragmentEl.dataset.appModalBound === "true") {
            return
        }

        fragmentEl.dataset.appModalBound = "true"

        fragmentEl.addEventListener("htmx:afterRequest", (event) => {
            if (event.detail.successful !== true) {
                return
            }

            this.handleModalFragmentSuccess(fragmentEl, event)
        })
    }

    handleModalFragmentSuccess(fragmentEl, event) {
        this.dispatchModalFragmentSuccessEvent(fragmentEl, event)
        this.runModalFragmentSuccessAction(fragmentEl)

        if (fragmentEl.dataset.appModalCloseOnSuccess !== "false") {
            closeModal()
        }
    }

    dispatchModalFragmentSuccessEvent(fragmentEl, event) {
        const eventKey = fragmentEl.dataset.appModalSuccessEvent
        if (!eventKey) {
            return
        }

        const eventName = Const.events[eventKey]
        if (!eventName) {
            console.warn(`Unknown modal success event key: ${eventKey}`)
            return
        }

        this.dispatch(
            eventName,
            this.buildModalFragmentSuccessDetail(fragmentEl, event),
        )
    }

    buildModalFragmentSuccessDetail(fragmentEl, event) {
        return {
            ...this.parseModalFragmentDetail(
                fragmentEl.dataset.appModalSuccessDetail,
            ),
            ...this.parseModalFragmentTargetDetail(fragmentEl, event),
        }
    }

    parseModalFragmentTargetDetail(fragmentEl, event) {
        const detail = {}

        Object.entries(fragmentEl.dataset).forEach(([key, value]) => {
            const prefix = "appModalSuccessDetailTargetAttr"
            if (!key.startsWith(prefix)) {
                return
            }

            const detailKey = this.datasetKeySuffixToDetailKey(
                key.slice(prefix.length),
            )

            detail[detailKey] =
                event.target?.getAttribute?.(value) ?? detail[detailKey]
        })

        return detail
    }

    datasetKeySuffixToDetailKey(keySuffix) {
        if (!keySuffix) {
            return ""
        }

        return keySuffix.charAt(0).toLowerCase() + keySuffix.slice(1)
    }

    runModalFragmentSuccessAction(fragmentEl) {
        const action = fragmentEl.dataset.appModalSuccessAction
        if (!action) {
            return
        }

        if (action === "close-folder-context-menu") {
            this.closeFolderContextMenu(fragmentEl.dataset.appModalFolderId)
            return
        }

        console.warn(`Unknown modal success action: ${action}`)
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

    parseModalFragmentDetail(jsonValue) {
        if (!jsonValue) {
            return {}
        }

        try {
            return JSON.parse(jsonValue)
        } catch (error) {
            console.error("Error parsing modal success detail", error)
            return {}
        }
    }

    hydratePersonNameEditorFragment(fragmentEl) {
        this.focusFragmentElement(
            fragmentEl,
            fragmentEl.dataset.appAutofocusSelector,
            fragmentEl.dataset.appSelectOnFocus === "true",
        )
    }

    hydrateImageDetailFragment(fragmentEl) {
        if (fragmentEl.dataset.appImageDetailBound === "true") {
            return
        }

        fragmentEl.dataset.appImageDetailBound = "true"

        const imgEl = fragmentEl.querySelector("img")
        if (!imgEl) {
            return
        }

        this.Alpine.store(Const.state.imageDetailLoading).value = true

        setImgSrcAndWait(imgEl, fragmentEl.dataset.appImageDetailUrl)
            .then(() => {
                showAssetDetailModal({
                    title: fragmentEl.dataset.appImageDetailTitle,
                    width: Number(fragmentEl.dataset.appImageDetailWidth),
                    height: Number(fragmentEl.dataset.appImageDetailHeight),
                })

                this.dispatch(Const.events.detailShown, {
                    assetId: fragmentEl.dataset.appImageDetailAssetId,
                })
            })
            .catch((error) => {
                console.error("Error loading asset image", error)
            })
            .finally(() => {
                this.Alpine.store(Const.state.imageDetailLoading).value = false
            })
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


