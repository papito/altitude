import { ModalHost, openModal } from "../common/modal.js"

/** Opens the Curation host with focus on the shell itself, so the keys it binds press no control */
export function hydrateCurationFragment() {
    openModal({
        host: ModalHost.curation,
        title: "Curation",
        focusSelector: "#curation",
    })
}
