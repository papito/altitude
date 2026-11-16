/**
 * Asset detail's URL (`/r/:repoId/asset/:assetId`, `?panel=open` while the Detail panel is open), so
 * it can be reloaded, bookmarked or sent. It carries no search.
 *
 * Opening from the results pushes one history entry; stepping and the toggle replace it after
 * `URL_DEBOUNCE_MS`, since browsers limit history calls. While the URL names an asset, the search URL
 * the server sends (`HX-Replace-Url`) is held and applied on closing, or the first search of a page
 * loaded on an asset's URL would overwrite it. htmx's history restore is always cancelled: nothing
 * pushes history through htmx, so every traversal within the page is this module's.
 */
import { Alpine } from "../lib/alpine.esm.min.js"
import {
    closeModal,
    getActiveModalHost,
    ModalHost,
    takeHeldClose,
} from "../common/modal.js"

const ASSET_PATH = /^\/r\/([^/]+)\/asset\/([^/]+)\/?$/
const PANEL_PARAM = "panel"
const PANEL_OPEN = "open"
const URL_DEBOUNCE_MS = 300

/** The repository and asset the browser's URL names, or null on any other URL */
function assetInLocation() {
    const match = ASSET_PATH.exec(window.location.pathname)
    return match ? { repoId: match[1], assetId: match[2] } : null
}

function isAssetDetailActive() {
    return getActiveModalHost() === ModalHost.assetDetail
}

export function createDetailUrl({ context }) {
    // Whether the URL names the asset shown, until Asset detail closes
    let following = false
    // Whether this page added the history entry the URL is on, so the results are behind it
    let ownsEntry = false
    // The results' URL, as the server last pushed it while the URL named an asset
    let heldSearchUrl = null
    let wasActive = false
    // The replacement waiting for the asset to stay; never outlives the entry it was meant for
    let replaceTimer = null
    // The URL of the asset shown
    let shownUrl = null

    function start() {
        // Before any response can arrive: the page's first search must not rewrite this URL
        following = assetInLocation() !== null

        // Every path that closes the host ends here: the close control, Escape, the backdrop
        Alpine.effect(() => {
            const isActive = isAssetDetailActive()
            if (wasActive && !isActive) {
                stopFollowing()
            }
            wasActive = isActive
        })

        document.addEventListener("htmx:before:history:update", (event) => {
            if (following) {
                event.preventDefault()
                heldSearchUrl = event.detail.history.path
            }
        })

        document.addEventListener("htmx:before:history:restore", (event) =>
            event.preventDefault(),
        )
        window.addEventListener("popstate", handleTraversal)
    }

    /** Opens Asset detail on the asset the browser's URL names, if any */
    function openAssetInLocation() {
        const asset = assetInLocation()
        if (!asset) {
            return
        }

        htmx.ajax(
            "GET",
            `/htmx/asset/r/${asset.repoId}/modals/asset-detail/${asset.assetId}`,
            { target: "#imageDetailModalContent", swap: "innerHTML" },
        )
    }

    /** Whether the URL says the Detail panel is open, or null when it names no asset */
    function panelOpenInLocation() {
        if (!assetInLocation()) {
            return null
        }

        const params = new URLSearchParams(window.location.search)
        return params.get(PANEL_PARAM) === PANEL_OPEN
    }

    /** The URL names `assetId` and the panel's state from now on */
    function show({ assetId, panelOpen }) {
        const query = panelOpen ? `?${PANEL_PARAM}=${PANEL_OPEN}` : ""
        const url = `/r/${context.getRepoId()}/asset/${assetId}${query}`

        shownUrl = url
        if (following) {
            clearTimeout(replaceTimer)
            replaceTimer = setTimeout(() => replaceUrl(url), URL_DEBOUNCE_MS)
            return
        }

        following = true
        ownsEntry = true
        heldSearchUrl = null
        history.pushState(null, "", url)
    }

    function replaceUrl(url) {
        history.replaceState(history.state, "", url)
    }

    /** Asset detail closed, or could not open: the URL goes back to the results */
    function stopFollowing() {
        if (!following) {
            return
        }

        following = false
        clearTimeout(replaceTimer)

        if (ownsEntry) {
            // Lands in `handleTraversal`, which applies a held URL
            history.back()
            return
        }

        replaceUrl(heldSearchUrl ?? `/r/${context.getRepoId()}`)
        heldSearchUrl = null
    }

    /** A failed open of an asset the URL names leaves the results showing under the results' URL */
    function openFailed() {
        if (!isAssetDetailActive()) {
            stopFollowing()
        }
    }

    /**
     * Back or Forward within this page. Forward onto an asset's entry reopens it, with no previous or
     * next. Back onto the results closes Asset detail, unless this page was loaded on the asset's
     * URL: the results behind that entry are another search, so they load for real.
     */
    function handleTraversal() {
        clearTimeout(replaceTimer)

        if (assetInLocation()) {
            if (!isAssetDetailActive()) {
                following = true
                ownsEntry = true
                heldSearchUrl = null
                openAssetInLocation()
            }
            return
        }

        if (following) {
            following = false

            if (!ownsEntry) {
                window.location.reload()
                return
            }

            closeModal()
            // The Edit panel may hold the close to warn of unsaved changes; staying puts the entry back
            takeHeldClose()?.then((closed) => {
                if (!closed && isAssetDetailActive() && shownUrl) {
                    following = true
                    ownsEntry = true
                    history.pushState(null, "", shownUrl)
                }
            })
        }

        if (heldSearchUrl) {
            replaceUrl(heldSearchUrl)
            heldSearchUrl = null
        }
    }

    return { start, openAssetInLocation, panelOpenInLocation, show, openFailed }
}
