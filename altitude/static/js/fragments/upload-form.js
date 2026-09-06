import { findFragmentRoots } from "./helpers.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { getHttpErrorMessage, http } from "../http/client.js"

/**
 * The upload is posted through the shared axios client instead of htmx.
 *
 * htmx 4 issues requests with fetch(), which exposes no upload progress, so the
 * progress bar has to be driven by axios' onUploadProgress. The server contract
 * is unchanged: the endpoint answers with a fresh upload form fragment (new upload
 * ID), which replaces the current one, exactly as the htmx swap used to.
 */
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
    const elStartUploadButton = fragmentEl.querySelector(
        "#startUploadButtonControl",
    )
    const elAbortButton = fragmentEl.querySelector("#abortUploadButtonControl")
    const elStayOnPage = fragmentEl.querySelector("#stayOnPage")
    const elProgressBar = fragmentEl.querySelector("#progressBar")
    const elFiles = fragmentEl.querySelector("#files")
    const elProgressTextCtrl = fragmentEl.querySelector("#progressTextControl")
    const abortButton = fragmentEl.querySelector("[data-app-upload-abort]")
    const uploadUrl = fragmentEl.dataset.appUploadUrl
    const cancelUrl = fragmentEl.dataset.appUploadCancelUrl

    // Non-null only while an upload request is in flight
    let uploadAbortController = null

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

    function updateProgress(progressEvent) {
        if (!progressEvent.total) {
            return
        }

        const percentLoaded = Math.min(
            100,
            Math.floor((progressEvent.loaded / progressEvent.total) * 100),
        )

        setBusy(percentLoaded)
    }

    async function upload() {
        // Collect the files before the input is disabled - disabled controls
        // are left out of FormData
        const formData = new FormData(fragmentEl)

        uploadAbortController = new AbortController()
        setBusy(0)

        try {
            const response = await http.post(uploadUrl, formData, {
                // Override the client's JSON default so axios lets the browser
                // set the multipart boundary
                headers: { "Content-Type": "multipart/form-data" },
                responseType: "text",
                signal: uploadAbortController.signal,
                onUploadProgress: updateProgress,
            })

            replaceFragment(fragmentEl, response.data)
        } catch (error) {
            if (!window.axios.isCancel(error)) {
                showErrorSnackBar(
                    `Error uploading files: ${getHttpErrorMessage(error)}`,
                )
            }

            setIdle()
        } finally {
            uploadAbortController = null
        }
    }

    async function cancelUpload() {
        uploadAbortController?.abort()

        if (!cancelUrl) {
            return
        }

        try {
            await http.post(cancelUrl)

            setIdle()
        } catch (error) {
            const status = error?.response?.status

            showErrorSnackBar(
                status
                    ? `Error cancelling upload: POST ${cancelUrl} failed with HTTP ${status}`
                    : `Error cancelling upload: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    fragmentEl.addEventListener("submit", (event) => {
        event.preventDefault()

        if (!uploadAbortController) {
            upload()
        }
    })

    abortButton?.addEventListener("click", (event) => {
        event.preventDefault()
        cancelUpload()
    })
}

/**
 * Swap the served upload form fragment in place of the current one and hydrate it.
 */
function replaceFragment(fragmentEl, html) {
    const parentEl = fragmentEl.parentNode
    const template = document.createElement("template")
    template.innerHTML = html

    fragmentEl.replaceWith(template.content)
    hydrateUploadFormFragments(parentEl)
}
