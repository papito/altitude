export const Const = {
    events: {
        folderMoved: "FOLDER_MOVED_EVENT",
        folderDeleted: "FOLDER_DELETED_EVENT",
        folderAdded: "FOLDER_ADDED_EVENT",
        folderCollapsed: "FOLDER_COLLAPSED_EVENT",
        personMerged: "PERSON_MERGED_EVENT",
        confirmPersonMerge: "CONFIRM_PERSON_MERGE_EVENT",
    },

    /**
     * CAUTION: These are hard-coded in the HTML templates.
     * One way to fix this it to have these constants in SSP server-side and reference them in JS code.
     * It's going to be ugly eiter way.
     */
    attributes: {
        expanded: "expanded",
        // CAREFUL! This is still hard-coded, once, in the root folder element
        isRoot: "is-root",
        numOfChildren: "num-of-children",
        parentFolderId: "parent-folder-id",
        folderId: "folder-id",
    }
}
