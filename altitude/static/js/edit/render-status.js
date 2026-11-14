/**
 * After an Edit is saved, the server renders it in the background (the `edit-render` queue, 1.5 s
 * after the last save) and replaces the edited Preview and the Display image under a new revision.
 * This module waits for that render (`waitForRender`: `GET /api/edit/r/:repo/asset/:id/status`
 * every second, for up to a minute, until the edit version rendered is the one saved) and then points
 * the asset's cells in the grid at the new Preview (`swapGridPreviews`), which the Edit panel runs
 * once its session (`edit-session.js`) is idle after a save. The status route is the holder of Edit
 * images'.
 */
import { cellsOf } from "../search-results/cells.js"
import { previewUrl, repointImage } from "../common/content-urls.js"
import { getHttpErrorMessage, http } from "../http/client.js"

export const POLL_INTERVAL_MS = 1000
export const POLL_TIMEOUT_MS = 60000
// How many reads of the status in a row may fail (a network blip, a restart) before the wait gives up
export const MAX_FAILED_READS = 3

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

/**
 * Resolves to the render status (`{ editVersion, renderedVersion, rev }`) once the edit version
 * rendered is `editVersion`, or to null when a minute passes first, `isCancelled()` turns true (a later save, which
 * waits for its own render), the Edit has been saved again, or `MAX_FAILED_READS` reads in a row
 * fail (each logged). Polls every second, the first time at once.
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
            // A later edit version than the one waited for is another save's to wait for
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

/**
 * Points every cell of `assetId` in the grid at its Preview of revision `rev` (`repointImage`: its
 * `data-src`, and its `src` while it shows its image). Answers how many cells changed.
 */
export function swapGridPreviews({ repoId, assetId, rev }) {
    const url = previewUrl(repoId, assetId, rev)
    return cellsOf(assetId)
        .map((cellEl) => cellEl.querySelector("img[data-asset-id]"))
        .filter((imgEl) => imgEl && repointImage(imgEl, url)).length
}

/**
 * Waits for the render of `editVersion` and swaps the grid's Previews to it: the panel's call once its
 * session is idle after a save. Resolves to the status, or null when the render was not seen (see
 * `waitForRender`).
 */
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
