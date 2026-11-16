import { Alpine } from "./lib/alpine.esm.min.js"
import { Const } from "./constants.js"
import { refreshAlbumCounts } from "./common/album-list.js"
import { refreshLocationCounts } from "./common/location-list.js"
import { refreshFolderCounts } from "./common/folder-tree.js"
import { createAssetActions } from "./assets/asset-actions.js"
import { bindAppDragDrop } from "./dragdrop/index.js"
import { hydrateAppFragments } from "./fragments/index.js"
import { openCurationInLocation } from "./listeners/curation.js"
import { handlePeopleEscapeKeyPressed } from "./listeners/people.js"
import { reloadTriageSummary } from "./listeners/triage.js"
import { registerAppEventListeners } from "./listeners/index.js"
import { createDetailFullscreen } from "./search-results/detail-fullscreen.js"
import { createDetailPanel } from "./search-results/detail-panel.js"
import { createSearchDetailCoordinator } from "./search-results/detail-navigator.js"
import { createDetailUrl } from "./search-results/detail-url.js"
import { bindSearchInput } from "./search-results/search-input.js"
import { bindSearchPanel } from "./search-results/search-panel.js"
import { initializeFrontendStores } from "./stores/app-stores.js"
import { createEditPanel } from "./edit/edit-panel.js"
import { createEditStage } from "./edit/edit-stage.js"

/** The composition root; feature logic lives in the modules it composes. */
export class FrontendApp {
    constructor({ context }) {
        this.context = context
        this.started = false
        this.assetActions = createAssetActions({
            context,
            reloadNav: this.reloadNav.bind(this),
            reloadFolderCounts: this.reloadFolderCounts.bind(this),
            reloadAlbumCounts: this.reloadAlbumCounts.bind(this),
            reloadLocationCounts: this.reloadLocationCounts.bind(this),
        })
        this.detailUrl = createDetailUrl({ context })
        this.detailPanel = createDetailPanel({
            context,
            assetActions: this.assetActions,
            detailUrl: this.detailUrl,
        })
        this.editStage = createEditStage({
            context,
            detailPanel: this.detailPanel,
        })
        this.editPanel = createEditPanel({
            context,
            editStage: this.editStage,
            detailPanel: this.detailPanel,
        })
        this.searchDetailCoordinator = createSearchDetailCoordinator({
            context,
            detailPanel: this.detailPanel,
            editStage: this.editStage,
            editPanel: this.editPanel,
        })
        this.detailFullscreen = createDetailFullscreen()
    }

    start() {
        if (this.started) {
            return this
        }

        this.started = true
        initializeFrontendStores()
        registerAppEventListeners(this)
        this.detailUrl.start()
        this.detailPanel.start()
        this.detailFullscreen.start()
        this.editStage.start()
        this.editPanel.start()
        Alpine.start()
        bindAppDragDrop(this)
        bindSearchInput()
        bindSearchPanel()

        // The initial page's fragments; later swaps hydrate their own nodes on `htmx:after:settle`.
        // Hydrators are idempotent, so overlapping DOM is harmless.
        this.hydrateFragments(document)

        return this
    }

    openCurationInLocation() {
        openCurationInLocation(this)
    }

    handleEscapeKeyPressed() {
        handlePeopleEscapeKeyPressed()
    }

    hydrateFragments(root) {
        hydrateAppFragments({ root, app: this })
    }

    dispatch(eventName, detail = {}) {
        document.body.dispatchEvent(new CustomEvent(eventName, { detail }))
    }

    reloadNav() {
        // `current` marks the shown view's entry again; the quick search form survives (`hx-preserve`)
        const view = Alpine.store(Const.state.searchParams).view
        htmx.ajax(
            "GET",
            `/htmx/nav/r/${this.context.getRepoId()}?current=${view}`,
            {
                swap: "innerHTML",
                target: "nav",
            },
        )
        // What changes the nav's counts can change the Triage summary's
        this.reloadTriageSummary()
    }

    reloadFolderCounts() {
        refreshFolderCounts(this.context.getRepoId())
    }

    reloadAlbumCounts() {
        refreshAlbumCounts(this.context.getRepoId())
    }

    reloadLocationCounts() {
        refreshLocationCounts(this.context.getRepoId())
        // A membership change can put a triaged asset in its first Location or take it out of its last
        this.reloadTriageSummary()
    }

    reloadTriageSummary() {
        reloadTriageSummary()
    }
}
