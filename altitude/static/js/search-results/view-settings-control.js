import { buildDialogTriggerCtrl } from "../common/context-menu-markup.js"

/**
 * Builds the ⚙ View control into its host, the same dialog-trigger context menu as the Add album and
 * Add folder buttons: it toggles a panel holding only the view settings dialog, which it requests
 * into that panel. Built here rather than in the template because it is a context menu component,
 * like the folder and album menus; the template only supplies the host.
 *
 * The whole results fragment is re-swapped on every search, so the host arrives empty and the
 * control is built again; the guard keeps a re-hydration of the same DOM from building a second one.
 */
export function ensureViewSettingsControl(fragmentEl) {
    const hostEl = fragmentEl.querySelector("#viewSettingsActions")

    if (!hostEl || hostEl.childElementCount > 0) {
        return
    }

    hostEl.appendChild(
        buildDialogTriggerCtrl({
            triggerId: "viewSettingsBtn",
            panelId: "viewSettingsMenu",
            dialogId: "viewSettingsDialog",
            label: "View",
            iconClass: "fas fa-cog",
            url: `/htmx/view-settings/r/${fragmentEl.dataset.resultsRepoId}/dialogs/view-settings`,
            buttonClass: "action-button small",
        }),
    )

    htmx.process(hostEl)
}
