import { findFragmentRoots } from "./helpers.js"
import { showErrorSnackBar } from "../common/snackbar.js"

export function hydrateUploadFormFragments(root = document) {
    findFragmentRoots(root, "upload-form").forEach((fragmentEl) => {
        hydrateUploadFormFragment({ fragmentEl })
    })
}

export function hydrateUploadFormFragment({ fragmentEl }) {
    if (fragmentEl.dataset.appUploadFormBound === "true") {
        return
    }

    fragmentEl.dataset.appUploadFormBound = "true"

    const elProgressBarCtrl = fragmentEl.querySelector("#progressBarControl")
    const elStartUploadButton = fragmentEl.querySelector("#startUploadButtonControl")
    const elAbortButton = fragmentEl.querySelector("#abortUploadButtonControl")
    const elStayOnPage = fragmentEl.querySelector("#stayOnPage")
    const elProgressBar = fragmentEl.querySelector("#progressBar")
    const elFiles = fragmentEl.querySelector("#files")
    const elProgressTextCtrl = fragmentEl.querySelector("#progressTextControl")
    const abortButton = fragmentEl.querySelector("[data-app-upload-abort]")
    const cancelUrl = fragmentEl.dataset.appUploadCancelUrl

    function setIdle() {
        elStartUploadButton?.removeAttribute("hidden")
        elProgressBarCtrl?.setAttribute("hidden", "true")
        elStayOnPage?.setAttribute("hidden", "true")
        elAbortButton?.setAttribute("hidden", "true")
        elFiles?.removeAttribute("disabled")
        elProgressTextCtrl?.setAttribute("hidden", "true")
    }

    function setBusy(percentLoaded) {
        elAbortButton?.removeAttribute("hidden")
        elProgressBarCtrl?.removeAttribute("hidden")
        elStayOnPage?.removeAttribute("hidden")
        elProgressBar?.setAttribute("value", percentLoaded)
        elStartUploadButton?.setAttribute("hidden", "true")
        elFiles?.setAttribute("disabled", "true")
        elProgressTextCtrl?.removeAttribute("hidden")

        if (elProgressTextCtrl) {
            elProgressTextCtrl.innerText = `${percentLoaded}%`
        }
    }

    fragmentEl.addEventListener("htmx:xhr:progress", (event) => {
        if (!event.detail.total) {
            return
        }

        const percentLoaded = Math.min(
            100,
            Math.floor((event.detail.loaded / event.detail.total) * 100),
        )

        setBusy(percentLoaded)

        if (percentLoaded === 100) {
            setIdle()
        }
    })

    fragmentEl.addEventListener("htmx:abort", async () => {
        if (!cancelUrl) {
            return
        }

        try {
            const response = await fetch(cancelUrl, {
                method: "POST",
            })

            if (!response.ok) {
                showErrorSnackBar(
                    `Error cancelling upload: POST ${cancelUrl} failed with HTTP ${response.status}`,
                )
                return
            }

            setIdle()
        } catch (error) {
            showErrorSnackBar("Error cancelling upload")
        }
    })

    abortButton?.addEventListener("click", (event) => {
        event.preventDefault()
        htmx.trigger(fragmentEl, "htmx:abort")
    })
}

