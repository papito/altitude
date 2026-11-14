/**
 * After an Edit's head moves, the server renders it in the background (the `edit-render` queue,
 * 1.5 s after the last change) and stores the edited Preview and the Display image under a new
 * revision. This module waits for that render (`waitForRender`: `GET /api/edit/r/:repo/asset/:id/status`
 * every second, for up to a minute, until the step rendered is the head) and then points the
 * asset's cells in the grid at the new Preview (`swapGridPreviews`), which the Edit panel's session
 * (`edit-session.js`) runs after its last change. The status route is the holder of Edit images'.
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
 * Resolves to the render status (`{ head, rendered, rev }`) once the step rendered is `head`, or
 * to null when a minute passes first, `isCancelled()` turns true (a later change, which waits for
 * its own render), the head has moved on, or `MAX_FAILED_READS` reads in a row fail (each logged).
 * Polls every second, the first time at once.
 */
export async function waitForRender({
    repoId,
    assetId,
    head,
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
            // A later head than the one waited for is another change's to wait for
            if (status.head !== head) {
                return null
            }
            if (status.rendered === head) {
                return status
            }
        }
        if (Date.now() + intervalMs > deadline) {
            console.debug(
                `The render of ${assetId} at step ${head} took longer than ${timeoutMs} ms`,
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
 * Waits for the render of `head` and swaps the grid's Previews to it: the session's call after its
 * last change. Resolves to the status, or null when the render was not seen (see `waitForRender`).
 */
export async function refreshAfterRender({
    repoId,
    assetId,
    head,
    isCancelled,
}) {
    const status = await waitForRender({ repoId, assetId, head, isCancelled })
    if (status) {
        swapGridPreviews({ repoId, assetId, rev: status.rev })
    }
    return status
}
