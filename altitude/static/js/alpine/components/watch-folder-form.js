import { showErrorSnackBar } from "../../common/snackbar.js"
import { getHttpErrorMessage, http } from "../../http/client.js"

// What the submit reads, with the deletion of imported files off and on
const SAVE = "Save"
const SAVE_AND_DELETE = "Save and delete imported files"

/**
 * The Watch folder dialog of the Import page (`htmx/watch_folder_form`, views/AGENTS.md **Import
 * page**). The component root is the form. It holds the form's two switches, starting from what
 * the server rendered (`data-app-keeps-structure`, `data-app-deletes-imported`): "Keep folder
 * structure", which shows the destination folder's field, and "Delete imported files", which
 * shows the notice of what is deleted and makes the submit say so.
 *
 * The notice names the number of files in the folder now, which only the server can count: it is
 * asked for (`data-app-notice-url`, with the path typed and, for an edit, the Watch folder's ID)
 * when the deletion is switched on, and again when the path changes while it is on. Nothing is
 * saved by a switch: the form's submit is the save.
 */
export function watchFolderForm() {
    return {
        keepsStructure: false,
        deletesImported: false,
        // How many times the notice was asked for: an answer to an earlier asking is dropped
        noticesAsked: 0,

        init() {
            this.keepsStructure = this.$el.dataset.appKeepsStructure === "true"
            this.deletesImported =
                this.$el.dataset.appDeletesImported === "true"
        },

        get saveLabel() {
            return this.deletesImported ? SAVE_AND_DELETE : SAVE
        },

        toggleKeepsStructure() {
            this.keepsStructure = !this.keepsStructure
        },

        toggleDeletesImported() {
            this.deletesImported = !this.deletesImported
            if (this.deletesImported) {
                this.loadNotice()
            }
        },

        onPathChange() {
            if (this.deletesImported) {
                this.loadNotice()
            }
        },

        /**
         * Asks the server what the notice says for the path typed, and shows the answer of the
         * latest asking alone. The axios client, not an htmx request: a request htmx issues from
         * inside a dialog is one of the dialog's operations, whose success closes it.
         */
        async loadNotice() {
            const params = { path: this.$refs.path.value }
            if (this.$refs.watchFolderId) {
                params.watchFolderId = this.$refs.watchFolderId.value
            }
            const asked = ++this.noticesAsked

            try {
                const response = await http.get(
                    this.$root.dataset.appNoticeUrl,
                    { params },
                )
                if (asked === this.noticesAsked) {
                    this.$refs.notice.innerHTML = response.data
                }
            } catch (error) {
                showErrorSnackBar(
                    `Could not count the files of the folder: ${getHttpErrorMessage(error)}`,
                )
            }
        },
    }
}
