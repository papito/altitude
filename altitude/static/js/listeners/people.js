import { Const } from "../constants.js"
import { showSuccessSnackBar } from "../common/snackbar.js"

export function registerPeopleListeners(app) {
    document.body.addEventListener(Const.events.confirmPersonMerge, (event) => {
        const mergeSourceId = event.detail.mergeSourceId
        const mergeDestId = event.detail.mergeDestId

        if (mergeSourceId === mergeDestId) {
            return
        }

        htmx.ajax(
            "GET",
            `/htmx/people/r/${app.context.getRepoId()}/modals/merge`,
            {
                swap: "innerHTML",
                target: "#modalContent",
                values: { ...event.detail },
            },
        )
    })

    document.body.addEventListener(Const.events.personMerged, (event) => {
        const mergeSourceId = event.detail.mergeSourceId
        const sourcePersonEl = htmx.find(`#person-${mergeSourceId}`)

        if (sourcePersonEl) {
            sourcePersonEl.remove()
        }

        showSuccessSnackBar("Person merged successfully")
    })

    document.body.addEventListener(Const.events.personNameEdited, (event) => {
        const personId = event.detail.personId
        const newPersonName = event.detail.newPersonName
        const personNameEl = htmx.find(`#person-${personId} .name a`)

        if (!personNameEl) {
            return
        }

        personNameEl.textContent = newPersonName
        personNameEl.classList.remove("unknown")
    })

    document.body.addEventListener(Const.events.personCoverFaceSet, (event) => {
        const personId = event.detail.personId
        const faceId = event.detail.faceId
        const imageEl = htmx.find(`#person-${personId} .image img`)

        if (!imageEl) {
            return
        }

        imageEl.src = `/content/r/${app.context.getRepoId()}/face/${faceId}`
    })

    document.body.addEventListener(
        Const.events.personMarkedAsBadMatch,
        (event) => {
            const personId = event.detail.personId
            const personEl = htmx.find(`#person-${personId}`)

            if (personEl) {
                personEl.remove()
            }
        },
    )
}
