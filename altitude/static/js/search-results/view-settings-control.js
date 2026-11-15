import { buildDialogTriggerCtrl } from "../common/context-menu-markup.js"

/**
 * Builds the ⚙ View control into its host: it toggles a panel holding only the view settings
 * dialog, which it requests
 * into that panel. Built here rather than in the template because it is a context menu component,
 * like the folder and album menus; the template only supplies the host.
 *
 * The host is `hx-preserve` (`includes/search_results`): each results swap carries it, the built
 * control and its open panel included, into the new fragment, so the control is built once, into the
 * empty host of the first fragment; the guard keeps every later hydration from building a second one.
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
