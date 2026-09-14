import { Alpine } from "./lib/alpine.esm.min.js"
import { refreshAlbumCounts } from "./common/album-list.js"
import { refreshLocationCounts } from "./common/location-list.js"
import { refreshFolderCounts } from "./common/folder-tree.js"
import { createAssetActions } from "./assets/asset-actions.js"
import { bindAppDragDrop } from "./dragdrop/index.js"
import { hydrateAppFragments } from "./fragments/index.js"
import { handlePeopleEscapeKeyPressed } from "./listeners/people.js"
import { registerAppEventListeners } from "./listeners/index.js"
import { createSearchDetailCoordinator } from "./search-results/detail-navigator.js"
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
        this.searchDetailCoordinator = createSearchDetailCoordinator({
            context,
        })
    }

    start() {
        if (this.started) {
            return this
        }

        this.started = true
        initializeFrontendStores()
        registerAppEventListeners(this)
        Alpine.start()
        bindAppDragDrop(this)

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
        htmx.ajax("GET", `/htmx/nav/r/${this.context.getRepoId()}`, {
            swap: "innerHTML",
            target: "nav",
        })
    }

    reloadFolderCounts() {
        refreshFolderCounts(this.context.getRepoId())
    }

    reloadAlbumCounts() {
        refreshAlbumCounts(this.context.getRepoId())
    }

    reloadLocationCounts() {
        refreshLocationCounts(this.context.getRepoId())
    }
}
