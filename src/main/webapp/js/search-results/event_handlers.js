import { Const } from "../constants.js"
import { Folder } from "../models.js"
import {
    showErrorSnackBar,
    showSuccessSnackBar,
    showWarningSnackBar,
} from "../common/snackbar.js"
import { context } from "../context.js"

document.body.addEventListener(Const.events.assetMoved, (event) => {
    const assetId = event.detail["assetId"]
    const newParentFolderId = event.detail["folderId"]
    const newParentFolder = new Folder(newParentFolderId)

    function handler(response) {
        const status = response["htmx-internal-data"].xhr.status

        if (status === 200) {
            const message = `Asset moved to ${newParentFolder.name()}`
            showSuccessSnackBar(message)
        } else if (status === 409) {
            const message = response["htmx-internal-data"].xhr.responseText
            showWarningSnackBar(message)
        } else {
            showErrorSnackBar(
                `Error moving asset ${assetId} into ${newParentFolder.name()}: ${status}`,
            )
        }
    }

    htmx.ajax("put", `/htmx/asset/r/${context.getRepoId()}/move`, {
        swap: "none",
        values: { ...event.detail },
        handler: handler,
    })
})

document.body.addEventListener(Const.events.viewSettingChanged, (event) => {
    const fieldName = event.detail["fieldName"]
    const checked = event.detail["checked"]

    document.querySelectorAll('.metadata > div.' + fieldName).forEach(div =>
        div.style.display = checked ? "inline-block": "none"
    );

    if (checked) {
        context.addGridMetadataField(fieldName)
    } else {
        context.removeGridMetadataField(fieldName)
    }
})
