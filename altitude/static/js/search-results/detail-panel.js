/**
 * The Detail panel of Asset detail (`views/htmx/view_image_detail_modal.scala.html`): the docked
 * panel holding the asset's People, its Details and its Metadata, which the server renders as
 * `htmx/detail_panel` and this module loads into `#detailPanelContent`.
 *
 * The panel is open or closed (`m`, the toolbar's toggle), docked left, right, top or bottom of
 * the media (the Dock side menu in its header, `common/dock.js`: right until a pick, 30 % of the
 * width or 35 % of the height until a drag, never following the viewport's orientation), and its
 * Metadata groups are open or closed one by one. All of it is remembered per browser under
 * `Const.localStore.assetDetailView` as `{ panelOpen, highlightsOn, dock: { side?, widthShare,
 * heightShare }, openGroups: [...] }`, the dock's part written by the dock itself; `highlightsOn`
 * is the Face highlights' (`p`), kept here so the key holds the whole view's settings. By default
 * the panel is closed.
 *
 * The fragment loads when the panel is open: on open, on each step, and when the panel is opened
 * on an asset whose details are not loaded yet. The coordinator (`detail-navigator.js`) names the
 * asset shown through `showAsset` with its request's `isCurrent`, so a response that arrives
 * after a step or a close is dropped, as a superseded media load is. The Details' dimensions row
 * reads the shown element's size, which the coordinator hands over once the media is shown: a
 * browser turns an image upright by its EXIF orientation, while the stored size is the header's.
 *
 * The side and share are written on the fragment (`data-detail-dock-side`, `--detail-panel-share`)
 * and whether the panel is open as `data-app-detail-panel-open`; `view_image_detail_modal.css`
 * lays the grid out from them. The picks are `[data-app-detail-dock-side]`, the panel's own, since
 * the explorer's dock listens for its picks on the same body.
 */
import { Const } from "../constants.js"
import { ensureChoiceMenus } from "../common/choice-menus.js"
import { createDock } from "../common/dock.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { getHttpErrorMessage, http } from "../http/client.js"

export const DEFAULT_WIDTH_SHARE = 30
export const DEFAULT_HEIGHT_SHARE = 35

const VIEW_ID = "imageDetailView"
const CONTENT_ID = "detailPanelContent"
const DIVIDER_ID = "detailPanelDivider"

export function createDetailPanel({ context }) {
    // The remembered settings but the dock's, which the dock keeps
    const state = rememberedState()

    // The asset Asset detail shows or loads, with whether its request is still the current one
    let subject = null
    // The shown media element's size, once the media is shown
    let shownSize = null
    // The asset whose fragment is in the panel, or on its way there
    let loadedAssetId = null

    const dock = createDock({
        storage: { key: Const.localStore.assetDetailView, field: "dock" },
        pickAttribute: Const.attributes.detailDockSide,
        layout: {
            sideAttribute: "data-detail-dock-side",
            shareProperty: "--detail-panel-share",
        },
        container: viewEl,
        sizeEl: viewEl,
        divider: () => document.getElementById(DIVIDER_ID),
        defaults: {
            side: () => Const.dockSides.right,
            widthShare: DEFAULT_WIDTH_SHARE,
            heightShare: DEFAULT_HEIGHT_SHARE,
        },
        minimums: panelMinimums,
        orientsDivider: true,
    })

    function viewEl() {
        return document.getElementById(VIEW_ID)
    }

    function contentEl() {
        return document.getElementById(CONTENT_ID)
    }

    /** Binds the page-wide controls once: the dock's, the toolbar's toggle and the Metadata groups' memory */
    function start() {
        dock.start()

        document.addEventListener("click", (event) => {
            if (
                event.target.closest?.(
                    `[${Const.attributes.detailPanelToggle}]`,
                )
            ) {
                toggle()
            }
        })

        // A <details>' `toggle` does not bubble, so it is caught in the capture phase
        document.addEventListener("toggle", rememberGroup, true)

        syncToggleButtons()
    }

    /**
     * A new Asset detail fragment arrived: its Dock side menu is built so the dock finds its
     * trigger, the panel is laid out as remembered and its divider bound. Nothing of the previous
     * fragment is loaded in it.
     */
    function attach(fragmentEl) {
        ensureChoiceMenus(fragmentEl)
        loadedAssetId = null
        applyOpenState()
        dock.layOut()
        dock.bindDivider(fragmentEl.querySelector(`#${DIVIDER_ID}`))
    }

    /**
     * Asset detail shows, or starts loading, `assetId`; `isCurrent` tells whether that request is
     * still the latest for the active open. The panel empties, so the last asset's details are
     * never read as this one's, and loads if it is wanted.
     */
    function showAsset({ assetId, isCurrent }) {
        subject = { assetId, isCurrent }
        shownSize = null
        if (loadedAssetId !== assetId) {
            loadedAssetId = null
            contentEl()?.replaceChildren()
        }
        loadIfWanted()
    }

    /** The shown media element's size, which the Details' dimensions read */
    function setShownSize({ width, height }) {
        shownSize = { width, height }
        writeDimensions()
    }

    /** Opens or closes the panel, remembers it, and loads the shown asset's details when opening on them unread */
    function toggle() {
        state.panelOpen = !state.panelOpen
        remember()
        applyOpenState()

        if (state.panelOpen) {
            // The divider's bounds change with the panel shown
            dock.layOut()
            loadIfWanted()
        }
    }

    function isOpen() {
        return state.panelOpen
    }

    /** Whether the fragment is needed: while the panel is open. The Face highlights (`p`) will want it too, once they read the rows. */
    function wantsFragment() {
        return state.panelOpen
    }

    async function loadIfWanted() {
        const hostEl = contentEl()
        if (
            !subject ||
            !hostEl ||
            !wantsFragment() ||
            loadedAssetId === subject.assetId
        ) {
            return
        }

        const { assetId, isCurrent } = subject
        // Claimed before the request, so a second call while it is in flight loads nothing again
        loadedAssetId = assetId

        try {
            const response = await http.get(
                `/htmx/asset/r/${context.getRepoId()}/detail-panel/${assetId}`,
            )

            if (!isCurrent() || subject?.assetId !== assetId) {
                return
            }

            hostEl.innerHTML = response.data
            htmx.process(hostEl)
            openRememberedGroups(hostEl)
            writeDimensions()
        } catch (error) {
            if (!isCurrent() || subject?.assetId !== assetId) {
                return
            }

            loadedAssetId = null
            console.error(
                `Error loading the Detail panel: ${getHttpErrorMessage(error)}`,
            )
            showErrorSnackBar("Could not load the asset's details")
        }
    }

    /** The fragment's open state, which the stylesheet lays the grid out from, and the toggles' pressed state */
    function applyOpenState() {
        viewEl()?.toggleAttribute("data-app-detail-panel-open", state.panelOpen)
        syncToggleButtons()
    }

    function syncToggleButtons() {
        document
            .querySelectorAll(`[${Const.attributes.detailPanelToggle}]`)
            .forEach((buttonEl) =>
                buttonEl.setAttribute("aria-pressed", String(state.panelOpen)),
            )
    }

    /** The Details' dimensions row shows the shown element's size, the header's until the media is shown */
    function writeDimensions() {
        const dimensionsEl = contentEl()?.querySelector(
            `[${Const.attributes.detailDimensions}]`,
        )
        if (dimensionsEl && shownSize) {
            dimensionsEl.textContent = `${shownSize.width} × ${shownSize.height} px`
        }
    }

    /** A Metadata group opened or closed in the panel is remembered by its directory's name */
    function rememberGroup(event) {
        const groupEl = event.target
        if (
            !(groupEl instanceof HTMLDetailsElement) ||
            !groupEl.closest(`#${CONTENT_ID}`)
        ) {
            return
        }

        const name = groupEl.getAttribute(Const.attributes.metadataGroup)
        if (!name) {
            return
        }

        if (groupEl.open) {
            state.openGroups.add(name)
        } else {
            state.openGroups.delete(name)
        }
        remember()
    }

    function openRememberedGroups(hostEl) {
        hostEl
            .querySelectorAll(`[${Const.attributes.metadataGroup}]`)
            .forEach((groupEl) => {
                groupEl.open = state.openGroups.has(
                    groupEl.getAttribute(Const.attributes.metadataGroup),
                )
            })
    }

    /** The least the panel and the stage may be squeezed to along the axis, in px: the `core.css` tokens, registered as lengths */
    function panelMinimums(vertical) {
        const styles = getComputedStyle(viewEl() ?? document.documentElement)
        const token = (name) => parseFloat(styles.getPropertyValue(name))

        return {
            panel: token(
                vertical
                    ? "--detail-panel-min-height"
                    : "--detail-panel-min-width",
            ),
            content: token(
                vertical
                    ? "--detail-stage-min-height"
                    : "--detail-stage-min-width",
            ),
        }
    }

    /** The remembered settings, or the defaults where they are missing or unreadable: a closed panel, highlights off, no group open */
    function rememberedState() {
        let saved = null
        try {
            saved = JSON.parse(
                localStorage.getItem(Const.localStore.assetDetailView) ??
                    "null",
            )
        } catch {
            saved = null
        }
        if (typeof saved !== "object" || saved === null) {
            saved = {}
        }

        return {
            panelOpen: saved.panelOpen === true,
            highlightsOn: saved.highlightsOn === true,
            openGroups: new Set(
                Array.isArray(saved.openGroups)
                    ? saved.openGroups.filter(
                          (name) => typeof name === "string",
                      )
                    : [],
            ),
        }
    }

    /** Writes the settings under the key, leaving the dock's part, which the dock writes, as it is */
    function remember() {
        let stored = null
        try {
            stored = JSON.parse(
                localStorage.getItem(Const.localStore.assetDetailView) ??
                    "null",
            )
        } catch {
            stored = null
        }

        localStorage.setItem(
            Const.localStore.assetDetailView,
            JSON.stringify({
                ...(typeof stored === "object" && stored !== null
                    ? stored
                    : {}),
                panelOpen: state.panelOpen,
                highlightsOn: state.highlightsOn,
                openGroups: [...state.openGroups],
            }),
        )
    }

    return { start, attach, showAsset, setShownSize, toggle, isOpen }
}
