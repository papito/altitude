import { Const } from "../constants.js"
import { context } from "../context.js"
import { showSuccessSnackBar } from "../common/snackbar.js"

document.body.addEventListener(Const.events.confirmPersonMerge, (event) => {
    const mergeSourceId = event.detail["mergeSourceId"]
    const mergeDestId = event.detail["mergeDestId"]

    console.debug(
        "Person " + mergeSourceId + " dragon dropped into " + mergeDestId,
    )

    if (mergeSourceId === mergeDestId) {
        return
    }

    htmx.ajax("GET", `/htmx/people/r/${context.getRepoId()}/modals/merge`, {
        swap: "innerHTML",
        target: "#modalContent",
        values: { ...event.detail },
    })
})

document.body.addEventListener(Const.events.personMerged, (event) => {
    const mergeSourceId = event.detail["mergeSourceId"]
    const mergeDestId = event.detail["mergeDestId"]

    console.debug("Person " + mergeSourceId + " MERGED into " + mergeDestId)

    // remove the source person from the DOM
    htmx.find("#person-" + mergeSourceId).remove()

    showSuccessSnackBar("Person merged successfully")
})

document.body.addEventListener(Const.events.personNameEdited, (event) => {
    const personId = event.detail["personId"]
    const newPersonName = event.detail["newPersonName"]
    console.debug(`Person ${personId} name changed to ${newPersonName}`)
    htmx.find("#person-" + personId + " .name a").textContent = newPersonName
})

document.body.addEventListener(Const.events.personCoverFaceSet, (event) => {
    const personId = event.detail["personId"]
    const faceId = event.detail["faceId"]
    console.debug(`Person ${personId} updated with face ${faceId}`)
    htmx.find("#person-" + personId + " .image img").src = `/content/r/${context.getRepoId()}/face/${faceId}`
})
