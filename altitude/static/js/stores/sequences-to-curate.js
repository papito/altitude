import { Const } from "../constants.js"
import { context } from "../context.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { getHttpErrorMessage, http } from "../http/client.js"

function sequencesText(count) {
    return count === 1 ? "1 sequence" : `${count} sequences`
}

/**
 * The number of Sequences in Curation's queue. Only the count endpoint sets it (`refresh`), never client arithmetic. It is
 * null until the first answer, which keeps Triage's Curate row hidden until then.
 */
export function createSequencesToCurateStore() {
    // An older answer never replaces a newer one
    let latestRead = 0

    return {
        count: null,

        get hasAny() {
            return this.count > 0
        },

        get label() {
            return this.count === 1
                ? "Curate 1 sequence"
                : `Curate all ${this.count} sequences`
        },

        get remaining() {
            if (this.count === null) {
                return ""
            }
            return this.count === 0
                ? "No sequences left"
                : `${sequencesText(this.count)} left`
        },

        async refresh() {
            // Curation needs a Curate Permission: without one there is nothing to ask, and the Curate
            // row stays hidden
            if (
                !context.holds(
                    Const.permission.curateOwn,
                    Const.permission.curateAll,
                )
            ) {
                return
            }

            const read = ++latestRead

            try {
                const response = await http.get(
                    `/api/curation/r/${context.getRepoId()}/count`,
                )
                if (read === latestRead) {
                    this.count = response.data.count
                }
            } catch (error) {
                showErrorSnackBar(
                    `Error reading the sequences to curate: ${getHttpErrorMessage(error)}`,
                )
            }
        },
    }
}
