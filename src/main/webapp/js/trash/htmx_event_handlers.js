import { showErrorSnackBar } from "../common/snackbar.js"
import { context } from "../context.js"

htmx.find("#purgeRecycleBin").addEventListener(
    "htmx:afterRequest",
    function (evt) {
        const requestPath = evt.detail.pathInfo.requestPath
        const status = evt.detail.xhr.status

        if (evt.detail.successful === false) {
            console.debug(evt)
            showErrorSnackBar(
                "Error for request to " + requestPath + ". HTTP " + status,
            )
            return
        }

        htmx.ajax("GET", `/htmx/nav/r/${context.getRepoId()}`, {
            swap: "innerHTML",
            target: "nav",
        })
    },
)
