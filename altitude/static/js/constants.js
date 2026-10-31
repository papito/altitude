export const Const = {
    views: {
        repository: "repository",
        triage: "triage",
        trashbin: "trashbin",
    },

    // Where the explorer sits against the results, as `data-dock-side` on <html> names it
    dockSides: {
        left: "left",
        right: "right",
        top: "top",
        bottom: "bottom",
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
        // A person was merged, hidden, shown or discarded; the People tab reloads its panes
        peopleChanged: "PEOPLE_CHANGED_EVENT",
        escapeKeyPressed: "ESCAPE_KEY_PRESSED_EVENT",
        showNext: "SHOW_NEXT_EVENT",
        showPrevious: "SHOW_PREVIOUS_EVENT",
        togglePlayback: "TOGGLE_PLAYBACK_EVENT",
        settingsSaved: "SETTINGS_SAVED_EVENT",
        // The Dock side changed, by a pick or by the viewport's orientation; `detail.side` is the new one
        dockSideChanged: "DOCK_SIDE_CHANGED_EVENT",
        styleGuideSampleSubmitted: "STYLE_GUIDE_SAMPLE_SUBMITTED_EVENT",
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
        // A Setting field's default, which its Reset fills in
        settingDefault: "data-setting-default",
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
        snackbar: "snackbar",
    },

    localStore: {
        gridMetadataFields: "gridMetadataFields",
        // The explorer's Dock side, once picked, and its share of the width and of the height
        // (js/common/explorer-dock.js; static/js/explorer-dock-boot.js names the same key in its `data-key`)
        explorerDock: "explorerDock",
        // The results layout (`grid` / `map`) last chosen, seeded into the search parameters on load
        resultsLayout: "resultsLayout",
        // The theme (`light` / `dark`) the style guide was last shown in (js/style-guide.js)
        styleGuideTheme: "styleGuideTheme",
        // The design tweaks panel's state (the tokentweak package, configured by js/design-tweaks.js): the
        // active tweak set, the snapshots, the theme, the panel's dock and open state and the adopted
        // sheet's text. The boot script tag of includes/header_common names the same key in its `data-key`
        designTweaks: "designTweaks",
        // The People tab's remembered state (js/alpine/components/people-tab.js): each pane's
        // People buckets, whether Split view is on and the panes' sizes on each axis
        peopleView: "peopleView",
    },

    sessionStore: {
        // The map view last looked at per search scope (js/map/map-state.js)
        mapViews: "mapViews",
    },

    search: {
        layout: {
            grid: "grid",
            map: "map",
        },
        // The sort by match quality (`Const.Search.SORT_RELEVANCE`), which the server refuses without Search text
        sortRelevance: "relevance",
        // The largest page size the server serves (`Const.Search.MAX_RPP`); it refuses a larger one
        maxRpp: 500,
    },

    http: {
        // JSON the server adds to a dialog operation's success detail (`Api.Field.SUCCESS_DETAIL_HEADER`)
        successDetailHeader: "App-Success-Detail",
    },

    people: {
        // The People bucket names are the server's (`Const.PeopleBucket`), sent as the `buckets` parameter
        buckets: {
            matched: "matched",
            hidden: "hidden",
            unmatched: "unmatched",
        },
        // The two panes of the People tab, as `data-app-people-pane` names them: the Main pane,
        // always shown, and the Duplicate pane of Split view
        panes: {
            main: "main",
            duplicate: "duplicate",
        },
    },
}

window.Const = Const
