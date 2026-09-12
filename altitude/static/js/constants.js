export let Const = {
    views: {
        repository: "repository",
        triage: "triage",
        trashbin: "trashbin",
    },
    events: {
        folderMoved: "FOLDER_MOVED_EVENT",
        folderRenamed: "FOLDER_RENAMED_EVENT",
        batchAssetsMoved: "BATCH_ASSETS_MOVED_EVENT",
        batchAssetsRecycled: "BATCH_ASSETS_RECYCLED_EVENT",
        batchAssetsPurged: "BATCH_ASSETS_PURGED_EVENT",
        batchAssetsRestored: "BATCH_ASSETS_RESTORED_EVENT",
        folderTrashed: "FOLDER_TRASHED_EVENT",
        assetMoved: "ASSET_MOVED_EVENT",
        assetTrashed: "ASSET_TRASHED_EVENT",
        folderDeleted: "FOLDER_DELETED_EVENT",
        folderAdded: "FOLDER_ADDED_EVENT",
        folderCollapsed: "FOLDER_COLLAPSED_EVENT",
        albumAdded: "ALBUM_ADDED_EVENT",
        albumRenamed: "ALBUM_RENAMED_EVENT",
        albumDeleted: "ALBUM_DELETED_EVENT",
        assetAddedToAlbum: "ASSET_ADDED_TO_ALBUM_EVENT",
        batchAssetsAddedToAlbum: "BATCH_ASSETS_ADDED_TO_ALBUM_EVENT",
        batchAssetsRemovedFromAlbum: "BATCH_ASSETS_REMOVED_FROM_ALBUM_EVENT",
        personMerged: "PERSON_MERGED_EVENT",
        confirmPersonMerge: "CONFIRM_PERSON_MERGE_EVENT",
        personNameEdited: "PERSON_NAME_EDITED_EVENT",
        personMarkedAsBadMatch: "PERSON_MARKED_AS_BAD_MATCH_EVENT",
        personCoverFaceSet: "PERSON_COVER_FACE_SET_EVENT",
        escapeKeyPressed: "ESCAPE_KEY_PRESSED_EVENT",
        viewSettingChanged: "VIEW_SETTING_CHANGED_EVENT",
        deselectAll: "DESELECT_ALL_EVENT",
        gridSelectionChanged: "GRID_SELECTION_CHANGED_EVENT",
        toggleAsset: "TOGGLE_ASSET_EVENT",
        showNext: "SHOW_NEXT_EVENT",
        showPrevious: "SHOW_PREVIOUS_EVENT",
        detailShown: "DETAIL_SHOWN_EVENT",
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
        viewedScope: "alt-viewed-scope",
        isRoot: "alt-is-root",
        numOfChildren: "alt-num-of-children",
        parentFolderId: "alt-parent-folder-id",
        folderId: "alt-folder-id",
        albumId: "alt-album-id",
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

    state: {
        selectedAssets: "selectedAssets",
        currentView: "currentView",
        resultsTotal: "resultsTotal",
        searchParams: "searchParams",
        imageDetailLoading: "imageDetailLoading",
        modal: "modal",
    },

    localStore: {
        gridMetadataFields: "gridMetadataFields",
        verticalSplitSizes: "verticalSplitSizes",
        horizontalSplitSizes: "horizontalSplitSizes",
    },
}

window.Const = Const
