import { showErrorSnackBar } from "../../common/snackbar.js"
import { getHttpErrorMessage, http } from "../../http/client.js"

const SAVE = "Save"
const SAVE_AND_DELETE = "Save and delete imported files"

/**
 * The Import page's Watch folder form switches. The deletion notice names the number of files in
 * the folder now, which only the server can count, so it is fetched when the deletion is switched on
 * and again when the path changes while it is on. A switch saves nothing; the submit does.
 */
export function watchFolderForm() {
    return {
        keepsStructure: false,
        deletesImported: false,
        // An answer to an earlier request is dropped
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

        /** Not an htmx request: one issued from inside a dialog is a dialog operation, whose success closes it */
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
