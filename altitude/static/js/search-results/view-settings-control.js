import { buildDialogTriggerCtrl } from "../common/context-menu-markup.js"

/**
 * Builds the ⚙ View control into its host: a toggle for a panel holding only the view settings
 * dialog. It is built here because it is a context menu component, like the folder and album menus.
 * The host is `hx-preserve`, so every results swap carries the built control into the new fragment,
 * and only the first fragment's empty host is built into.
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
