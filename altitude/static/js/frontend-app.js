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

/**
 * The composition root: initializes the stores, creates the feature coordinators, registers the
 * listeners, starts Alpine, binds drag/drop, and hydrates the fragments of the initial page.
 * Feature logic lives in the modules it composes.
 */
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
        this.searchDetailCoordinator = createSearchDetailCoordinator({
            context,
            detailPanel: this.detailPanel,
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
        Alpine.start()
        bindAppDragDrop(this)
        bindSearchInput()
        bindSearchPanel()

        /*
         * Hydrate any declarative app fragments already present in the initial page HTML.
         *
         * This is safe to call with `document` even though many HTMX fragments are loaded later:
         *
         * 1. `hydrateAppFragments()` only initializes fragment roots that already exist in the
         *    current DOM, so on first load it hydrates just the initial page content.
         * 2. Fragments injected later by HTMX are hydrated from `htmx:after:settle`
         *    (`listeners/htmx-requests.js`) for the newly swapped nodes only.
         * 3. Individual fragment hydrators are written to be idempotent (using `data-app-*`
         *    bound flags where needed), so re-hydrating overlapping DOM is harmless.
         */
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
        // `current`: the view shown, whose entry the reload marks again (the reload keeps the quick
        // search form, `hx-preserve`)
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

    /** Reloads the Triage summary in Triage, once for every change however many paths ask (listeners/triage.js) */
    reloadTriageSummary() {
        reloadTriageSummary()
    }
}
