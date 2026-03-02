import { showErrorSnackBar } from "../common/snackbar.js"

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

        htmx.ajax("GET", `/htmx/nav/r/${window.ctx.getRepoId()}`, {
            swap: "innerHTML",
            target: "nav",
        })
    },
)
