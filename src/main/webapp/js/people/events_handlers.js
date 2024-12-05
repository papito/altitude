import { Const } from "../constants.js"
import { context } from "../context.js"
import { showSuccessSnackBar } from "../common/snackbar.js"

/**
 * A folder is dragon dropped in UI BUT not yet removed on the server-side
 */
document.body.addEventListener(Const.events.confirmPersonMerge, (event) => {
  const mergeSourceId = event.detail["mergeSourceId"]
  const mergeDestId = event.detail["mergeDestId"]

  console.debug(
    "Person " + mergeSourceId + " dragon dropped into " + mergeDestId,
  )

  if (mergeSourceId === mergeDestId) {
    return
  }

  htmx.ajax("GET", `/htmx/people/r/${context.repoId}/modals/merge`, {
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
