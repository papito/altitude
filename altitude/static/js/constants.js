export const Const = {
    views: {
        repository: "repository",
        triage: "triage",
        trashbin: "trashbin",
    },

    /**
     * Custom event names, dispatched on `document.body`. Templates name the same strings verbatim
     * in `data-app-success-event` attributes and `$dispatch(...)` calls, so a rename touches both.
     */
    events: {
        folderMoved: "FOLDER_MOVED_EVENT",
        folderRenamed: "FOLDER_RENAMED_EVENT",
        batchAssetsMoved: "BATCH_ASSETS_MOVED_EVENT",
        batchAssetsRecycled: "BATCH_ASSETS_RECYCLED_EVENT",
        batchAssetsPurged: "BATCH_ASSETS_PURGED_EVENT",
        batchAssetsRestored: "BATCH_ASSETS_RESTORED_EVENT",
        assetMoved: "ASSET_MOVED_EVENT",
        assetTrashed: "ASSET_TRASHED_EVENT",
        trashPurged: "TRASH_PURGED_EVENT",
        folderDeleted: "FOLDER_DELETED_EVENT",
        folderAdded: "FOLDER_ADDED_EVENT",
        albumAdded: "ALBUM_ADDED_EVENT",
        albumRenamed: "ALBUM_RENAMED_EVENT",
        albumDeleted: "ALBUM_DELETED_EVENT",
        assetAddedToAlbum: "ASSET_ADDED_TO_ALBUM_EVENT",
        batchAssetsAddedToAlbum: "BATCH_ASSETS_ADDED_TO_ALBUM_EVENT",
        batchAssetsRemovedFromAlbum: "BATCH_ASSETS_REMOVED_FROM_ALBUM_EVENT",
        locationAdded: "LOCATION_ADDED_EVENT",
        categoryAdded: "CATEGORY_ADDED_EVENT",
        locationRenamed: "LOCATION_RENAMED_EVENT",
        locationMoved: "LOCATION_MOVED_EVENT",
        locationDeleted: "LOCATION_DELETED_EVENT",
        assetAddedToLocation: "ASSET_ADDED_TO_LOCATION_EVENT",
        batchAssetsAddedToLocation: "BATCH_ASSETS_ADDED_TO_LOCATION_EVENT",
        batchAssetsRemovedFromLocation:
            "BATCH_ASSETS_REMOVED_FROM_LOCATION_EVENT",
        batchAddToLocationRequested: "BATCH_ADD_TO_LOCATION_REQUESTED_EVENT",
        assetsAddedToLocation: "ASSETS_ADDED_TO_LOCATION_EVENT",
        personMerged: "PERSON_MERGED_EVENT",
        confirmPersonMerge: "CONFIRM_PERSON_MERGE_EVENT",
        personNameEdited: "PERSON_NAME_EDITED_EVENT",
        personMarkedAsBadMatch: "PERSON_MARKED_AS_BAD_MATCH_EVENT",
        personCoverFaceSet: "PERSON_COVER_FACE_SET_EVENT",
        escapeKeyPressed: "ESCAPE_KEY_PRESSED_EVENT",
        showNext: "SHOW_NEXT_EVENT",
        showPrevious: "SHOW_PREVIOUS_EVENT",
        mapPanelRequested: "MAP_PANEL_REQUESTED_EVENT",
    },

    /**
     * The `data-*` attributes JS reads element identity and state from. The Twirl templates and the
     * client-side renderers (`common/folder-tree.js`, `common/album-list.js`,
     * `common/location-list.js`) write the same names;
     * `element.dataset` reads them by their camel-cased key (`data-folder-id` is `dataset.folderId`).
     */
    attributes: {
        expanded: "data-expanded",
        viewedScope: "data-viewed-scope",
        isRoot: "data-is-root",
        numOfChildren: "data-num-of-children",
        parentFolderId: "data-parent-folder-id",
        folderId: "data-folder-id",
        albumId: "data-album-id",
        locationId: "data-location-id",
        categoryId: "data-category-id",
        kind: "data-kind",
        assetId: "data-asset-id",
        originalWidth: "data-og-width",
        dataSrc: "data-src",
        personId: "data-person-id",
        faceId: "data-face-id",
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
        // The results layout (`grid` / `map`) last chosen, seeded into the search parameters on load
        resultsLayout: "resultsLayout",
    },

    search: {
        layout: {
            grid: "grid",
            map: "map",
        },
    },
}

window.Const = Const
