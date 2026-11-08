import { context } from "../context.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { getHttpErrorMessage, http } from "../http/client.js"

/** "1 sequence", "3 sequences" */
function sequencesText(count) {
    return count === 1 ? "1 sequence" : `${count} sequences`
}

/**
 * The number of Sequences in Curation's queue (`Const.state.sequencesToCurate`). Only the count endpoint sets it, through
 * `refresh`, so nothing adds or subtracts on the client. It is null until the first answer, which keeps Triage's
 * Curate row (`includes/search_results`) hidden until then. Called when the Triage results render, from `refreshCounts`
 * (`assets/asset-actions.js`), and by Curation itself when it opens and when its queue runs out.
 */
export function createSequencesToCurateStore() {
    // An older answer never replaces a newer one
    let latestRead = 0

    return {
        count: null,

        get hasAny() {
            return this.count > 0
        },

        // The text of Triage's Curate button
        get label() {
            return this.count === 1
                ? "Curate 1 sequence"
                : `Curate all ${this.count} sequences`
        },

        // Curation's header
        get remaining() {
            if (this.count === null) {
                return ""
            }
            return this.count === 0
                ? "No sequences left"
                : `${sequencesText(this.count)} left`
        },

        async refresh() {
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
