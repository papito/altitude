/**
 * One upload of the Import page, from opening its Import to closing it, through the shared axios
 * client (not htmx). Each file is its own request, `CONCURRENCY` at a time. A file that gets no answer
 * or a 5xx is retried `RETRIES` times, then recorded failed on the server, and the rest go on; a 400
 * refuses that file alone. Any other refusal ends the upload, since no later file would fare better:
 * the Import is gone or closed (404, 409), or the account may no longer import (401, 403). Cancelling,
 * or leaving the page (by a beacon), closes the Import, which the server sees as one with fewer files.
 */
import { getHttpErrorMessage, http } from "../http/client.js"

const CONCURRENCY = 3
const RETRIES = 2
const RETRY_DELAYS_MS = [1000, 3000]

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

/** No answer at all, or a 5xx */
function isTransient(error) {
    const status = error?.response?.status
    return !status || status >= 500
}

/**
 * `onProgress` gets the files done (sent, failed or refused), how many of them failed, and the bytes
 * sent. Resolves after closing with the counts, `stopped` (the server's words for what ended it early)
 * and whether the Import closed. Rejects, with nothing sent, when the Import cannot be opened.
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
        // Ignored files are already left out; the history counts these as remaining
        expectedFiles: files.length,
    })
    const importUrl = `${importsUrl}/${opened.data.id}`

    let next = 0
    let done = 0
    let failed = 0
    let doneBytes = 0
    let stopped = null
    // Bytes sent of each request in flight, by file index
    const inFlight = new Map()

    const report = () => {
        const sending = [...inFlight.values()].reduce((sum, n) => sum + n, 0)
        onProgress({ done, failed, bytes: doneBytes + sending })
    }

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

    /** Retries like a file's request; resolves whether the Import closed */
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
