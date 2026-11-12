/**
 * One upload of the Import page, from its Import's opening to its closing (the upload routes of
 * `ImportController`), through the shared axios client, whose `onUploadProgress` drives the
 * section's progress; nothing of it is an htmx request.
 *
 * The Import is opened with its name and destination folder, each file is sent in a request of its
 * own, `CONCURRENCY` at a time, under its relative path, and the Import is closed once the last is
 * answered. A file whose request fails with no answer, or with a 5xx, is sent again, `RETRIES`
 * times after a pause; when the last try fails too, the page has the server record the file as
 * failed and goes on with the rest. A file the server answers as failed was recorded so by the
 * server, and one it refuses (a 400) was not recorded; neither is sent again. Any other refusal
 * ends the upload, since no file after it would fare better: the Import is gone or takes no more
 * files (404, 409), or the account may no longer import (401, 403).
 *
 * Cancelling (`signal`) stops the sending, the requests in flight included, and closes the Import:
 * to the server a cancelled upload is an Import with fewer files. Leaving the page is cancelling
 * too: the Import is closed by a beacon as the page goes, so it does not stay in progress.
 */
import { getHttpErrorMessage, http } from "../http/client.js"

const CONCURRENCY = 3
const RETRIES = 2
// The pause before each retry, in ms
const RETRY_DELAYS_MS = [1000, 3000]

/** Waits `ms`, or until the signal aborts */
function pause(ms, signal) {
    return new Promise((resolve) => {
        const timer = setTimeout(resolve, ms)
        signal.addEventListener(
            "abort",
            () => {
                clearTimeout(timer)
                resolve()
            },
            { once: true },
        )
    })
}

/** Whether a failed request is worth another try: it got no answer, or the server failed */
function isTransient(error) {
    const status = error?.response?.status
    return !status || status >= 500
}

/**
 * Runs the upload of `files` (`{ file, relPath }`) to the route that opens an Import
 * (`importsUrl`), under `name` and into `destFolderId` when given. `onProgress` is told, as it
 * changes, how many files are done (sent, failed or refused), how many of those failed, and the
 * bytes sent so far. Resolves, once the Import is closed, with those counts, whether the upload
 * was cancelled, what ended it early (`stopped`, the server's words) and whether the Import could
 * be closed. An Import that cannot be opened rejects, with nothing sent.
 */
export async function runUpload({
    importsUrl,
    name,
    destFolderId,
    files,
    signal,
    onProgress,
}) {
    const opened = await http.post(importsUrl, {
        ...(name ? { name } : {}),
        ...(destFolderId ? { destFolderId } : {}),
    })
    const importUrl = `${importsUrl}/${opened.data.id}`

    let next = 0
    let done = 0
    let failed = 0
    let doneBytes = 0
    let stopped = null
    // The bytes sent of each request in flight, by its file's index
    const inFlight = new Map()

    const report = () => {
        const sending = [...inFlight.values()].reduce((sum, n) => sum + n, 0)
        onProgress({ done, failed, bytes: doneBytes + sending })
    }

    /** Has the server record a file the page could not send; a record that fails too is only logged */
    async function recordFailed({ file, relPath }) {
        try {
            await http.post(`${importUrl}/failures`, {
                relPath,
                sizeBytes: file.size,
            })
        } catch (error) {
            console.warn(
                `Could not record ${relPath} as failed: ${getHttpErrorMessage(error)}`,
            )
        }
    }

    /** Sends one file, with its retries: whether it was sent (recorded and handed on), or failed */
    async function send(upload, index) {
        for (let attempt = 0; ; attempt++) {
            try {
                const response = await http.post(
                    `${importUrl}/files`,
                    upload.file,
                    {
                        params: { relPath: upload.relPath },
                        headers: { "Content-Type": "application/octet-stream" },
                        signal,
                        onUploadProgress: (event) => {
                            inFlight.set(index, event.loaded)
                            report()
                        },
                    },
                )
                return response.data.outcome !== "failed"
            } catch (error) {
                if (signal.aborted) {
                    return false
                }

                const status = error?.response?.status
                if (status === 400) {
                    console.warn(
                        `${upload.relPath} was refused: ${getHttpErrorMessage(error)}`,
                    )
                    return false
                }
                if (!isTransient(error)) {
                    stopped = getHttpErrorMessage(error)
                    return false
                }
                if (attempt === RETRIES) {
                    await recordFailed(upload)
                    return false
                }

                await pause(RETRY_DELAYS_MS[attempt], signal)
                if (signal.aborted) {
                    return false
                }
            } finally {
                inFlight.delete(index)
            }
        }
    }

    /** Takes the next file until none is left, the upload is cancelled or the server ends it */
    async function sendNext() {
        while (next < files.length && !signal.aborted && !stopped) {
            const index = next++
            const sent = await send(files[index], index)

            // A file cut off by Cancel, or by the refusal that ended the upload, is not counted
            if (!signal.aborted && !stopped) {
                done++
                doneBytes += files[index].file.size
                if (!sent) {
                    failed++
                }
                report()
            }
        }
    }

    /** Closes the Import, trying again as a file's request does; whether it closed */
    async function close() {
        for (let attempt = 0; ; attempt++) {
            try {
                await http.post(`${importUrl}/close`)
                return true
            } catch (error) {
                if (!isTransient(error) || attempt === RETRIES) {
                    console.warn(
                        `Could not close the Import: ${getHttpErrorMessage(error)}`,
                    )
                    return false
                }
                await new Promise((resolve) =>
                    setTimeout(resolve, RETRY_DELAYS_MS[attempt]),
                )
            }
        }
    }

    const closeOnLeave = () => navigator.sendBeacon(`${importUrl}/close`)
    window.addEventListener("pagehide", closeOnLeave)

    try {
        await Promise.all(
            Array.from(
                { length: Math.min(CONCURRENCY, files.length) },
                sendNext,
            ),
        )
        const closed = await close()

        return { done, failed, cancelled: signal.aborted, stopped, closed }
    } finally {
        window.removeEventListener("pagehide", closeOnLeave)
    }
}
