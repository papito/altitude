import { Const } from "./constants.js"
import { showErrorSnackBar } from "./common/snackbar.js"
import {
    getRequestPath,
    getResponseStatus,
    isRequestSuccessful,
} from "./common/htmx-events.js"
import { createAssetActions } from "./assets/asset-actions.js"
import { bindAppDragDrop } from "./dragdrop/index.js"
import { hydrateAppFragments } from "./fragments/index.js"
import { isDialogOperationRequest } from "./fragments/dialog-operations.js"
import { isModalOpenRequest } from "./common/modal.js"
import {
    handleFolderAfterRequest,
    isFolderRequest,
} from "./listeners/htmx-folders.js"
import { isTrashPurgeRequest } from "./listeners/htmx-routes.js"
import {
    handlePeopleAfterRequest,
    handlePeopleEscapeKeyPressed,
} from "./listeners/htmx-people-inline-editor.js"
import { registerAppEventListeners } from "./listeners/index.js"
import { createSearchDetailCoordinator } from "./search-results/detail-navigator.js"
import { initializeFrontendStores } from "./stores/app-stores.js"
import "./search-results/dragon-drop.js"

export class FrontendApp {
    constructor({ Alpine, context }) {
        this.Alpine = Alpine
        this.context = context
        this.started = false
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

        /*
         * Hydrate any declarative app fragments already present in the initial page HTML.
         *
         * This is safe to call with `document` even though many HTMX fragments are loaded later:
         *
         * 1. `hydrateAppFragments()` only initializes fragment roots that already exist in the
         *    current DOM, so on first load it hydrates just the initial page content.
         * 2. Fragments injected later by HTMX are handled separately in `handleAfterSettle()`,
         *    which re-runs hydration for the newly swapped nodes only.
         * 3. Individual fragment hydrators are written to be idempotent (using `data-app-*`
         *    bound flags where needed), so re-hydrating overlapping DOM is harmless.
         */
        this.hydrateFragments(document)

        return this
    }

    initializeStores() {
        this.Alpine.store(Const.context.repoId, "")
        this.Alpine.store(Const.context.gridMetadataFields, new Set())

        initializeFrontendStores({ Alpine: this.Alpine })
    }

    registerEventListeners() {
        registerAppEventListeners(this)
    }

    handleAfterRequest(event) {
        const requestPath = getRequestPath(event)
        const status = getResponseStatus(event)

        // Modal opens and the operations submitted from dialogs are settled by `listeners/dialogs.js`
        if (isModalOpenRequest(event) || isDialogOperationRequest(event)) {
            return
        }

        if (handlePeopleAfterRequest({ app: this, event })) {
            return
        }

        if (isFolderRequest({ app: this, requestPath })) {
            handleFolderAfterRequest({ app: this, event })
            return
        }

        if (isTrashPurgeRequest(requestPath)) {
            if (!isRequestSuccessful(event)) {
                showErrorSnackBar(
                    `Error for request to ${requestPath}. HTTP ${status}`,
                )
                return
            }

            this.reloadNav()
        }
    }

    handleEscapeKeyPressed() {
        handlePeopleEscapeKeyPressed()
    }

    /**
     * Runs once per swap with the nodes htmx just inserted: hydrate app fragments among
     * them, and initialize Alpine on the folder nav warning so its `x-show` binding
     * applies without waiting for Alpine's mutation observer.
     */
    handleAfterSettle(event) {
        event.detail.newContent.forEach((node) => {
            if (!(node instanceof Element)) {
                return
            }

            if (node.id === "folderNavWarning") {
                this.Alpine.initTree(node)
            }

            this.hydrateFragments(node)
        })
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
}
