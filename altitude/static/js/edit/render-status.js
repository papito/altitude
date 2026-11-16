/**
 * After a save the server renders the Edit in the background (debounced 1.5 s after the last save)
 * and stores the edited Preview and Display image under a new revision. This module waits for that
 * render and then points the asset's grid cells at the new Preview. The status route needs the Edit
 * images Permission.
 */
import { cellsOf } from "../search-results/cells.js"
import { previewUrl, repointImage } from "../common/content-urls.js"
import { getHttpErrorMessage, http } from "../http/client.js"

export const POLL_INTERVAL_MS = 1000
export const POLL_TIMEOUT_MS = 60000
// Status reads in a row that may fail (a network blip, a restart) before the wait gives up
export const MAX_FAILED_READS = 3

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * Resolves to the render status once `editVersion` is rendered, or to null on the timeout, on
 * `isCancelled()`, when the Edit was saved again, or after `MAX_FAILED_READS` failed reads in a row.
 * The first poll is immediate.
 */
export async function waitForRender({
    repoId,
    assetId,
    editVersion,
    isCancelled = () => false,
    intervalMs = POLL_INTERVAL_MS,
    timeoutMs = POLL_TIMEOUT_MS,
}) {
    const deadline = Date.now() + timeoutMs
    let failedReads = 0
    while (!isCancelled()) {
        let status = null
        try {
            const response = await http.get(
                `/api/edit/r/${repoId}/asset/${assetId}/status`,
            )
            status = response.data
            failedReads = 0
        } catch (error) {
            failedReads++
            console.warn(
                `Could not read the render status of ${assetId} (${failedReads} of ${MAX_FAILED_READS}): ${getHttpErrorMessage(error)}`,
            )
            if (failedReads >= MAX_FAILED_READS) {
                return null
            }
        }

        if (isCancelled()) {
            return null
        }
        if (status) {
            // A later edit version is another save's to wait for
            if (status.editVersion !== editVersion) {
                return null
            }
            if (status.renderedVersion === editVersion) {
                return status
            }
        }
        if (Date.now() + intervalMs > deadline) {
            console.debug(
                `The render of ${assetId} at edit version ${editVersion} took longer than ${timeoutMs} ms`,
            )
            return null
        }
        await pause(intervalMs)
    }
    return null
}

/** Points every grid cell of `assetId` at its Preview of revision `rev`; answers how many cells changed */
export function swapGridPreviews({ repoId, assetId, rev }) {
    const url = previewUrl(repoId, assetId, rev)
    return cellsOf(assetId)
        .map((cellEl) => cellEl.querySelector("img[data-asset-id]"))
        .filter((imgEl) => imgEl && repointImage(imgEl, url)).length
}

/** Waits for the render of `editVersion` and swaps the grid's Previews to it; resolves as `waitForRender` does */
export async function refreshAfterRender({
    repoId,
    assetId,
    editVersion,
    isCancelled,
}) {
    const status = await waitForRender({
        repoId,
        assetId,
        editVersion,
        isCancelled,
    })
    if (status) {
        swapGridPreviews({ repoId, assetId, rev: status.rev })
    }
    return status
}
