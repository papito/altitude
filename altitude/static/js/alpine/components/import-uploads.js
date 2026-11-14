import { Const } from "../../constants.js"
import { showErrorSnackBar } from "../../common/snackbar.js"
import { getHttpErrorMessage } from "../../http/client.js"
import {
    entriesOfDrop,
    filesOfEntries,
    filesOfInput,
    uploadOf,
} from "../../import/chosen-files.js"
import { runUpload } from "../../import/upload-run.js"

// The units a number of bytes is written in, each 1024 of the one before
const BYTE_UNITS = ["B", "KB", "MB", "GB", "TB"]

/** `sent` and `total` bytes in the unit that suits the total: "12.3 of 61.4 GB" */
function bytesOf(sent, total) {
    const unit = Math.min(
        BYTE_UNITS.length - 1,
        Math.floor(Math.log(Math.max(total, 1)) / Math.log(1024)),
    )
    const inUnit = (bytes) =>
        (bytes / 1024 ** unit).toLocaleString(undefined, {
            maximumFractionDigits: unit < 2 ? 0 : 1,
        })

    return `${inUnit(sent)} of ${inUnit(total)} ${BYTE_UNITS[unit]}`
}

/** A number of files, as a line says it: "1 file", "4,812 files" */
function filesOf(count) {
    return count === 1 ? "1 file" : `${count.toLocaleString()} files`
}

/** A section as it starts: To Triage, no destination, nothing dragged over it and nothing to say */
function newSection() {
    return {
        // The option's second choice is on: Skip Triage, or Keep folder structure
        sorting: false,
        // The destination folder chosen, `{ id, label }`, its label the folder's path
        destination: null,
        // An upload was asked for with the option on and no destination chosen
        lacksDestination: false,
        // Files are dragged over the zone
        over: false,
        // What its last upload came to, or why none started; `warns` when files failed or were not sent
        notice: "",
        warns: false,
    }
}

/**
 * The Import page's upload sections (`includes/upload_sections`), File upload and Folder upload, on
 * their common root. A section's `kind` is `file` or `folder`. Each takes what is chosen in its
 * picker or dropped on its zone, with its own option (`sorting`: Skip Triage, or Keep folder
 * structure, into the destination chosen in its Folder field, a `filterCombobox` whose `change` it
 * reads), and uploads it (`import/chosen-files.js` for what is sent and under which paths,
 * `import/upload-run.js` for the sending). An account without Organize is rendered no option, and
 * every upload goes to Triage.
 *
 * One upload runs at a time (`running`): its section shows the progress and Cancel, and both
 * sections take nothing more until it ends, when the section says what it came to. A failure to
 * start, and a refusal that ends an upload early, go to the snackbar in the server's words.
 */
export function importUploads() {
    // The upload's AbortController while one runs: Cancel aborts it
    let canceller = null

    return {
        sections: { file: newSection(), folder: newSection() },
        // The upload that runs, or the reading of what was picked before it: its section's kind, the files and
        // bytes it is to send and those done so far, and whether it was cancelled; null while none runs
        running: null,

        get importsUrl() {
            return this.$root.dataset.appImportsUrl
        },

        /** The names imports ignore, the server's list */
        get ignored() {
            return JSON.parse(this.$root.dataset.appIgnoredFiles)
        },

        /** An upload runs, in either section: nothing can be chosen, dropped or switched */
        get isBusy() {
            return this.running !== null
        },

        get isCancelling() {
            return this.running?.cancelled ?? false
        },

        isRunning(kind) {
            return this.running?.kind === kind
        },

        isSorting(kind) {
            return this.sections[kind].sorting
        },

        setSorting(kind, sorting) {
            this.sections[kind].sorting = sorting
            this.sections[kind].lacksDestination = false
        },

        hasDestination(kind) {
            return this.sections[kind].destination !== null
        },

        destinationLabel(kind) {
            return this.sections[kind].destination?.label ?? ""
        },

        lacksDestination(kind) {
            return this.sections[kind].lacksDestination
        },

        /** The Folder field's value changed: its chip, if it has one, is the destination */
        onDestination(event, kind) {
            const chipEl = event.currentTarget.querySelector(
                `[${Const.attributes.optionId}]`,
            )
            const section = this.sections[kind]

            section.destination = chipEl
                ? {
                      id: chipEl.getAttribute(Const.attributes.optionId),
                      label: chipEl.getAttribute(Const.attributes.optionLabel),
                  }
                : null
            if (section.destination) {
                section.lacksDestination = false
            }
        },

        /**
         * Whether the section can take an upload now: none is running, and its option, when on, has
         * a destination; otherwise the field says that one is needed
         */
        isReady(kind) {
            const section = this.sections[kind]
            if (this.isBusy) {
                return false
            }

            section.lacksDestination = section.sorting && !section.destination
            return !section.lacksDestination
        },

        zoneClasses(kind) {
            return { over: this.sections[kind].over, waiting: this.isBusy }
        },

        /** Files dragged over a zone that can take them: the zone says so, and a drop is allowed */
        onDragOver(event, kind) {
            if (!this.isBusy && event.dataTransfer.types.includes("Files")) {
                event.preventDefault()
                event.dataTransfer.dropEffect = "copy"
                this.sections[kind].over = true
            }
        },

        /** The drag left the zone, not merely moved onto something inside it */
        onDragLeave(event, kind) {
            if (!event.currentTarget.contains(event.relatedTarget)) {
                this.sections[kind].over = false
            }
        },

        /** A drop on a zone uploads what was dropped, the dropped folders walked to their last file */
        onDrop(event, kind) {
            event.preventDefault()
            this.sections[kind].over = false

            // The entries are read off the event now: they are gone once this handler returns
            const entries = entriesOfDrop(event.dataTransfer)
            if (entries.length > 0 && this.isReady(kind)) {
                this.upload(kind, () => filesOfEntries(entries, this.ignored))
            }
        },

        /** The section's button opens its picker */
        choose(kind) {
            if (this.isReady(kind)) {
                this.$refs[`${kind}Input`].click()
            }
        },

        /** What was chosen in a picker is uploaded; the picker is emptied, so the same choice can be made again */
        onChosen(event, kind) {
            const inputEl = event.currentTarget
            const picked = filesOfInput(inputEl, this.ignored)
            inputEl.value = ""

            if (this.isReady(kind)) {
                this.upload(kind, () => picked)
            }
        },

        /**
         * Uploads what `pick` gathers, as the section's option says, and says what it came to when
         * it ends. The section is busy from the gathering on, which for a dropped tree takes a
         * while.
         */
        async upload(kind, pick) {
            const section = this.sections[kind]
            const destination = section.sorting ? section.destination : null

            section.notice = ""
            canceller = new AbortController()
            this.running = {
                kind,
                files: 0,
                bytes: 0,
                done: 0,
                failed: 0,
                sent: 0,
                cancelled: false,
            }

            try {
                const { name, files } = uploadOf(kind, await pick())
                if (files.length === 0) {
                    this.say(kind, "Nothing to import there", true)
                    return
                }
                // Cancelled while the tree was read: no Import is opened
                if (canceller.signal.aborted) {
                    this.say(kind, "The upload was cancelled.", true)
                    return
                }

                this.running.files = files.length
                this.running.bytes = files.reduce(
                    (sum, { file }) => sum + file.size,
                    0,
                )

                const result = await runUpload({
                    importsUrl: this.importsUrl,
                    name,
                    destFolderId: destination?.id,
                    files,
                    signal: canceller.signal,
                    onProgress: ({ done, failed, bytes }) => {
                        this.running.done = done
                        this.running.failed = failed
                        this.running.sent = bytes
                    },
                })
                this.sayOutcome(kind, files.length, result)
            } catch (error) {
                showErrorSnackBar(
                    `Could not start the upload: ${getHttpErrorMessage(error)}`,
                )
            } finally {
                canceller = null
                this.running = null
            }
        },

        /** Cancel stops the sending; the upload ends once the Import is closed */
        cancel() {
            if (this.running) {
                this.running.cancelled = true
                canceller?.abort()
            }
        },

        /** The share of the upload's bytes sent, in percent */
        get percent() {
            const { sent = 0, bytes = 0 } = this.running ?? {}
            return bytes > 0 ? Math.min(100, (sent / bytes) * 100) : 0
        },

        /** "1,204 of 4,812 files · 12.3 of 61.4 GB", with the failed ones when there are any */
        get progressText() {
            const upload = this.running
            if (!upload) {
                return ""
            }
            if (upload.files === 0) {
                return "Reading…"
            }
            if (upload.cancelled) {
                return "Cancelling…"
            }

            const failed =
                upload.failed > 0
                    ? ` · ${upload.failed.toLocaleString()} failed`
                    : ""
            return `${upload.done.toLocaleString()} of ${filesOf(upload.files)} · ${bytesOf(upload.sent, upload.bytes)}${failed}`
        },

        say(kind, notice, warns = false) {
            this.sections[kind].notice = notice
            this.sections[kind].warns = warns
        },

        /** What an upload of `total` files came to; a refusal that ended it early is reported in the server's words */
        sayOutcome(kind, total, { done, failed, cancelled, stopped, closed }) {
            const sent = done - failed
            const parts = [
                sent === total
                    ? `Sent ${filesOf(total)}.`
                    : `Sent ${sent.toLocaleString()} of ${filesOf(total)}.`,
            ]

            if (failed > 0) {
                parts.push(`${failed.toLocaleString()} failed.`)
            }
            if (cancelled) {
                parts.push("The upload was cancelled.")
            }
            if (stopped) {
                parts.push("The upload was stopped.")
                showErrorSnackBar(`The upload was stopped: ${stopped}`)
            }
            if (!closed && !stopped) {
                showErrorSnackBar(
                    "The Import could not be closed: it stays in progress until the server restarts",
                )
            }

            this.say(kind, parts.join(" "), sent < total)
        },

        hasNotice(kind) {
            return this.sections[kind].notice !== "" && !this.isRunning(kind)
        },

        noticeOf(kind) {
            return this.sections[kind].notice
        },

        noticeClasses(kind) {
            return { warning: this.sections[kind].warns }
        },
    }
}
