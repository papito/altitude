import { ModalHost, openModal } from "../common/modal.js"

/**
 * Curation's shell (`data-app-fragment="curation"`, `htmx/curation.scala.html`), which `listeners/curation.js`
 * requests into the Curation host: it opens that host with focus on the shell itself, so the keys it binds
 * press no control. The `curation` component on the shell takes it from there.
 */
export function hydrateCurationFragment() {
    openModal({
        host: ModalHost.curation,
        title: "Curation",
        focusSelector: "#curation",
    })
}
