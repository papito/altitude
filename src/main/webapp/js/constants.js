export const Const = {
    events: {
        folderMoved: "FOLDER_MOVED_EVENT",
        folderTrashed: "FOLDER_TRASHED_EVENT",
        assetMoved: "ASSET_MOVED_EVENT",
        assetTrashed: "ASSET_TRASHED_EVENT",
        folderDeleted: "FOLDER_DELETED_EVENT",
        folderAdded: "FOLDER_ADDED_EVENT",
        folderCollapsed: "FOLDER_COLLAPSED_EVENT",
        personMerged: "PERSON_MERGED_EVENT",
        confirmPersonMerge: "CONFIRM_PERSON_MERGE_EVENT",
        personNameEdited: "PERSON_NAME_EDITED_EVENT",
        personMarkedAsBadMatch: "PERSON_MARKED_AS_BAD_MATCH_EVENT",
        personCoverFaceSet: "PERSON_COVER_FACE_SET_EVENT",
        escapeKeyPressed: "ESCAPE_KEY_PRESSED_EVENT",
        viewSettingChanged: "VIEW_SETTING_CHANGED_EVENT",
    },

    /**
     * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
     * CAUTION: These are hard-coded in the HTML templates and are here to avoid magic strings in JS code.
     *
     * One way to fix this it to have these constants in SSP server-side and reference them in JS code.
     * It's going to be ugly eiter way.
     */
    attributes: {
        expanded: "alt-expanded",
        isRoot: "alt-is-root",
        numOfChildren: "alt-num-of-children",
        parentFolderId: "alt-parent-folder-id",
        folderId: "alt-folder-id",
        assetId: "alt-asset-id",
        originalWidth: "alt-og-width",
        dataSrc: "alt-data-src",
        personId: "alt-person-id",
        faceId: "alt-face-id",
    },

    context: {
        repoId: "REPO_ID",
        gridMetadataFields: "GRID_METADATA_FIELDS",
    },

    localStore: {
        gridMetadataFields: "gridMetadataFields",
        verticalSplitSizes: "verticalSplitSizes",
        horizontalSplitSizes: "horizontalSplitSizes",
    },
}
