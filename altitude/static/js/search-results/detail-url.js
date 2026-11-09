/**
 * The address bar of Asset detail: while it is open the URL names the asset shown and whether its
 * Detail panel is open (`/r/:repoId/asset/:assetId`, with `?panel=open` while it is), so the URL
 * can be reloaded, bookmarked or sent. It carries no search: opened from its URL, Asset detail shows
 * over the default results with no previous or next, as a map pin's does.
 *
 * Opening from the results adds one history entry; stepping and the panel's toggle replace it, so
 * Back closes Asset detail whatever was walked through, and closing any other way goes back over
 * that entry. A page that was loaded on an asset's URL added no entry: closing replaces the URL with
 * the results', and Back leaves the page as it would any other.
 *
 * While the URL names an asset, the search URL the server pushes back (`HX-Replace-Url`) is held
 * here, not applied, and becomes the URL on closing: the first search of a page loaded on an
 * asset's URL would otherwise rewrite it at once.
 *
 * htmx would answer a traversal within the page by fetching the URL into the body. Nothing here
 * pushes history through htmx, so a traversal within the page is always this module's, and htmx's
 * restore is cancelled.
 */
import { Alpine } from "../lib/alpine.esm.min.js"
import { closeModal, getActiveModalHost, ModalHost } from "../common/modal.js"

const ASSET_PATH = /^\/r\/([^/]+)\/asset\/([^/]+)\/?$/
const PANEL_PARAM = "panel"
const PANEL_OPEN = "open"

/** The repository and asset the browser's URL names, or null on any other URL */
function assetInLocation() {
    const match = ASSET_PATH.exec(window.location.pathname)
    return match ? { repoId: match[1], assetId: match[2] } : null
}

function isAssetDetailActive() {
    return getActiveModalHost() === ModalHost.assetDetail
}

export function createDetailUrl({ context }) {
    // Whether the URL names the asset shown, from the first asset shown (or a page loaded on an
    // asset's URL) until Asset detail closes
    let following = false
    // Whether this page added the history entry the URL is on, so that the results are behind it
    let ownsEntry = false
    // The results' URL, as the server last pushed it while the URL named an asset
    let heldSearchUrl = null
    let wasActive = false

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

    /** Asset detail on the asset the browser's URL names, if it names one */
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

    /**
     * Whether the Detail panel is open according to the URL when it names an asset, or null when
     * it does not and the panel goes by what this browser remembers
     */
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

        if (following) {
            replaceUrl(url)
            return
        }

        following = true
        ownsEntry = true
        heldSearchUrl = null
        history.pushState(null, "", url)
    }

    /**
     * Safari throws on more than 100 replacements in 10 seconds, which a held arrow key can reach.
     * The URL then lags until the next step, which must not stop the step itself.
     */
    function replaceUrl(url) {
        try {
            history.replaceState(history.state, "", url)
        } catch (error) {
            console.debug(`The URL was not updated: ${error.message}`)
        }
    }

    /** Asset detail closed, or could not open: the URL goes back to the results */
    function stopFollowing() {
        if (!following) {
            return
        }

        following = false

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
     * Back or Forward within this page. Forward onto an asset's entry opens it again, with no
     * previous or next since the cell it was opened from is not known. Back onto the results closes
     * Asset detail, unless this page was loaded on the asset's URL, a reload of an entry an earlier
     * page added: the results behind it are then another search than the one on screen, so
     * they are loaded for real.
     */
    function handleTraversal() {
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
        }

        if (heldSearchUrl) {
            replaceUrl(heldSearchUrl)
            heldSearchUrl = null
        }
    }

    return { start, openAssetInLocation, panelOpenInLocation, show, openFailed }
}
