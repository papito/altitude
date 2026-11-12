export const Const = {
    views: {
        repository: "repository",
        triage: "triage",
        trashbin: "trashbin",
    },

    /**
     * The Permissions a script's control may need, by the names `Permission` gives them on the
     * server and the main page's body lists in `data-app-permissions` (js/context.js `holds`): those
     * a script asks about; a template's controls follow the rest on the server
     */
    permission: {
        curateOwn: "CurateOwn",
        curateAll: "CurateAll",
        organize: "Organize",
        recycle: "Recycle",
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
        batchAddToAlbumRequested: "BATCH_ADD_TO_ALBUM_REQUESTED_EVENT",
        assetsAddedToAlbum: "ASSETS_ADDED_TO_ALBUM_EVENT",
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
        // A person was merged, hidden, shown, discarded, renamed or corrected on a Face; the People tab reloads its panes
        peopleChanged: "PEOPLE_CHANGED_EVENT",
        // The Change combobox of a People row picked a Person (`detail.personId`) or a new name (`detail.name`) for
        // `detail.faceId` (js/alpine/components/face-combobox.js); js/search-results/detail-panel.js sends the correction
        facePersonPicked: "FACE_PERSON_PICKED_EVENT",
        // The Change combobox's X: the row of `detail.faceId` goes back to its original state, with nothing selected
        faceChangeCancelled: "FACE_CHANGE_CANCELLED_EVENT",
        escapeKeyPressed: "ESCAPE_KEY_PRESSED_EVENT",
        showNext: "SHOW_NEXT_EVENT",
        showPrevious: "SHOW_PREVIOUS_EVENT",
        togglePlayback: "TOGGLE_PLAYBACK_EVENT",
        // `m` in Asset detail: opens or closes the Detail panel (js/search-results/detail-panel.js)
        toggleDetailPanel: "TOGGLE_DETAIL_PANEL_EVENT",
        // `p` in Asset detail: shows or hides the Face highlights (js/search-results/detail-panel.js)
        toggleFaceHighlights: "TOGGLE_FACE_HIGHLIGHTS_EVENT",
        // `f` in Asset detail: puts it in fullscreen or leaves it (js/search-results/detail-fullscreen.js)
        toggleFullscreen: "TOGGLE_FULLSCREEN_EVENT",
        settingsSaved: "SETTINGS_SAVED_EVENT",
        // An account was added, changed or deleted on the Users page; `detail.message` says which. The page reloads its cards
        accountsChanged: "ACCOUNTS_CHANGED_EVENT",
        // The account changed its own password (the account info dialog's Change password); the session carries on with the new token
        passwordChanged: "PASSWORD_CHANGED_EVENT",
        // The Dock side changed, by a pick or by the viewport's orientation; `detail.side` is the new one
        dockSideChanged: "DOCK_SIDE_CHANGED_EVENT",
        styleGuideSampleSubmitted: "STYLE_GUIDE_SAMPLE_SUBMITTED_EVENT",
        // The theme shown was changed, with `detail.theme` (js/common/theme.js)
        themeChanged: "THEME_CHANGED_EVENT",
        // The batch ops' Curate: opens Curation on the selection unless it is over the button's `data-max-selection`
        batchCurateRequested: "BATCH_CURATE_REQUESTED_EVENT",
        // Opens Curation: on the queue with no detail, on one Sequence with `detail.sequenceId`, on a selection
        // with `detail.assetIds`
        curateRequested: "CURATE_REQUESTED_EVENT",
        // A mutation in Curation was saved; the counts refresh
        curationChanged: "CURATION_CHANGED_EVENT",
        // Curation closed; `detail.recycledIds` and `detail.clearedSequenceIds` are what it leaves changed
        curationClosed: "CURATION_CLOSED_EVENT",
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
        // The Loupe's level on a stage pane's lens, "200%", which its stylesheet shows (js/common/loupe.js)
        loupeLevel: "data-level",
        isRoot: "data-is-root",
        numOfChildren: "data-num-of-children",
        parentFolderId: "data-parent-folder-id",
        folderId: "data-folder-id",
        albumId: "data-album-id",
        locationId: "data-location-id",
        categoryId: "data-category-id",
        kind: "data-kind",
        assetId: "data-asset-id",
        dataSrc: "data-src",
        personId: "data-person-id",
        faceId: "data-face-id",
        // An option of the Change combobox's fragment (htmx/assignable_people) in its Suggested group: a Person among the nearest
        suggested: "data-suggested",
        // A People row of the Detail panel (htmx/detail_panel_people): its Person's name, whether the Person is Hidden, and
        // the Frame time of a Face in a Video, which the Face highlights read with `data-face-box`; whether the Person is
        // named and their number of assets, which the Change combobox reads (also on an option of htmx/assignable_people,
        // whether named)
        personName: "data-person-name",
        personHidden: "data-person-hidden",
        personNamed: "data-person-named",
        personAssetCount: "data-person-asset-count",
        frameTimeMs: "data-frame-time-ms",
        // A frame of a Curation set in the rail (`data-asset-id` finds the cells of the grid beneath), its file's
        // name, what the stage's tag says is wrong with it, its Bundle, and whether it is in an album or Location
        frameId: "data-frame-id",
        fileName: "data-file-name",
        problems: "data-problems",
        bundleKind: "data-bundle-kind",
        inAlbumOrLocation: "data-in-album-or-location",
        // The Sequence a group header's Curate opens Curation on
        sequenceId: "data-app-sequence-id",
        // The Sequence the Detail panel's Curation link opens Curation on, over Asset detail
        curateSequence: "data-app-curate-sequence",
        // The Sequence a row of Curation's Sequence rail stands for (htmx/curation_queue); not `data-app-sequence-id`,
        // which finds a group header's Curate
        railSequenceId: "data-rail-sequence-id",
        // The fewest frames a group header's Curate stays for (`Const.Curation.MIN_FRAMES`)
        minFrames: "data-min-frames",
        // The most images the batch ops' Curate opens Curation on (`Const.Curation.MAX_SELECTION`)
        maxSelection: "data-max-selection",
        // A Curation set's opening frames
        openingPinnedId: "data-opening-pinned-id",
        openingFocusedId: "data-opening-focused-id",
        // The measurement's gray levels a pixel counts as blown or crushed at, and the box the image is fitted into
        // before it is measured, which the stage's Clipping blink reads from the set's root
        clippedLevel: "data-clipped-level",
        crushedLevel: "data-crushed-level",
        measurementBox: "data-measurement-box",
        // What the stage's Histogram strip shows of a frame, on its rail item, as the import measured it: the
        // Histogram, a byte per bin in Base64, and the fractions of its pixels crushed and blown; a frame not
        // measured carries none
        histogram: "data-histogram",
        crushedShadows: "data-crushed-shadows",
        clippedHighlights: "data-clipped-highlights",
        // The face strip's column of a frame, and a crop's box in upright pixels: x, y, width, height
        columnFrameId: "data-column-frame-id",
        faceBox: "data-face-box",
        // A Setting field's default, which its Reset fills in
        settingDefault: "data-setting-default",
        // A pick of the explorer's Dock side (the options of its menu, js/common/explorer-dock.js), and of the Detail
        // panel's, its own since both docks listen on the body (js/search-results/detail-panel.js)
        dockSide: "data-app-dock-side",
        detailDockSide: "data-app-detail-dock-side",
        // The Detail panel's toggle, the Face highlights' and fullscreen's in Asset detail's toolbar (includes/html_common)
        detailPanelToggle: "data-app-detail-panel-toggle",
        faceHighlightsToggle: "data-app-face-highlights-toggle",
        fullscreenToggle: "data-app-fullscreen-toggle",
        fullscreenKey: "data-app-fullscreen-key", // the key legend's fullscreen entry, gone where the toggle is
        // The button of a People row that selects its Face (htmx/detail_panel_people), and under a selected row its
        // corrections: Change, which opens the combobox, and Remove, whose second label (the one after the first click)
        // the button carries
        faceRow: "data-app-face-row",
        faceChange: "data-app-face-change",
        faceRemove: "data-app-face-remove",
        faceRemoveLastLabel: "data-app-face-remove-label-last",
        // The People section's root (htmx/detail_panel_people): read-only in the trash, and after a correction the result
        // message for the snackbar, when a Person was deleted their ID and the line that says so, and after a merge of the
        // Face's Person the merged Person's ID and the ID of the Person they were merged into
        readOnly: "data-app-read-only",
        resultMessage: "data-app-result-message",
        deletedPersonId: "data-app-deleted-person-id",
        deletedPersonMessage: "data-app-deleted-person-message",
        mergedPersonId: "data-app-merged-person-id",
        mergedIntoId: "data-app-merged-into-id",
        // The Detail panel's dimensions row, which reads the shown element's size (htmx/detail_panel)
        detailDimensions: "data-app-detail-dimensions",
        // The Asset detail fragment while the Detail panel is open (view_image_detail_modal.css lays the grid out from it),
        // and the asset whose sections the panel's fragment and its People section hold (htmx/detail_panel,
        // htmx/detail_panel_people); not `data-asset-id`, which finds the cells of the grid
        detailPanelOpen: "data-app-detail-panel-open",
        appAssetId: "data-app-asset-id",
        // The advanced search panel's host in the nav (includes/nav): the route its form is loaded from
        panelUrl: "data-panel-url",
        // A combobox field of the panel's form (htmx/search_panel): the route of its options, whether it takes several
        // names, the most it takes and the line that says so once it holds them
        optionsUrl: "data-options-url",
        multiple: "data-multiple",
        maxChosen: "data-max-chosen",
        maxChosenMessage: "data-max-chosen-message",
        // What an option of such a field and the chip of a chosen one carry (htmx/search_options_*, htmx/search_panel):
        // the ID a search takes and the label a chip shows; and their parts: the element that shows the name, and a
        // Person's cover Face, which a new chip copies (includes/search_chip, includes/search_person_cover)
        optionId: "data-option-id",
        optionLabel: "data-option-label",
        optionName: "data-option-name",
        optionCover: "data-option-cover",
        // The remove button of a chosen filter's chip, in the panel and in a filtered search's results title
        // (includes/search_chip)
        chipRemove: "data-chip-remove",
        // A chip of a filtered search's results title (includes/search_filter_chips), which carries the parameters it
        // stands for as `data-app-filter-<param>` literals; and the title's Edit, which opens the panel
        filter: "data-app-filter",
        editFilters: "data-app-edit-filters",
        // The badge of the nav's filter button (includes/nav): the number of filters in force, or of those in
        // the open panel's form
        filterCount: "data-app-filter-count",
        // The Import history (htmx/import_history, htmx/import_files): an Import's entry, the `<details>` of its
        // Import files, the route of the view a part of the history shows (the history itself, an Import's files),
        // by which the page reads it again, and on a control the route of the view it asks for instead
        importId: "data-import-id",
        importFiles: "data-app-import-files",
        viewUrl: "data-app-view-url",
        view: "data-app-view",
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
        sequencesToCurate: "sequencesToCurate",
    },

    localStore: {
        gridMetadataFields: "gridMetadataFields",
        // The explorer's Dock side, once picked, and its share of the width and of the height
        // (js/common/explorer-dock.js; static/js/explorer-dock-boot.js names the same key in its `data-key`)
        explorerDock: "explorerDock",
        // The results layout (`grid` / `map`) last chosen, seeded into the search parameters on load
        resultsLayout: "resultsLayout",
        // The theme (`light` / `dark`) last chosen, shown on every page with a nav (js/common/theme.js;
        // the boot script tag of includes/header_common names the same key in its `data-key`)
        theme: "theme",
        // The design tweaks panel's state (the tokentweak package, configured by js/design-tweaks.js): the
        // active tweak set, the snapshots, the theme, the panel's dock and open state and the adopted
        // sheet's text. The boot script tag of includes/header_common names the same key in its `data-key`
        designTweaks: "designTweaks",
        // The People tab's remembered state (js/alpine/components/people-tab.js): each pane's
        // People buckets, whether Split view is on and the panes' sizes on each axis
        peopleView: "peopleView",
        // The Loupe's magnification last chosen on Curation's stage, read and saved by js/common/loupe.js
        // through the `rememberedLoupeLevel` and `saveLoupeLevel` the Curation component hands it
        loupeLevel: "loupeLevel",
        // The Bundle kinds whose details Curation's rail shows, a JSON array (js/alpine/components/curation.js)
        curationDetails: "curationDetails",
        // Whether Curation's stage shows the Histogram strips, "true" or "false" (js/alpine/components/curation.js)
        curationHistogram: "curationHistogram",
        // Whether Curation shows the Sequence rail on the queue, "true" or "false" (js/alpine/components/curation.js)
        curationSequenceRail: "curationSequenceRail",
        // Asset detail's remembered view (js/search-results/detail-panel.js): whether the Detail panel is open, whether
        // the Face highlights are on and the panel's dock (its side once picked and its share of each axis)
        assetDetailView: "assetDetailView",
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
        // The Triage subsets, the assets the Triage summary's counts show: the server's (`TriageSubset.apiValue`), sent
        // as the `subset` parameter
        subsets: {
            unknownPeople: "unknownPeople",
            noLocation: "noLocation",
        },
        // How several people of one search combine (`PeopleMatch.apiValue`), sent as the `peopleMatch` parameter: the
        // assets with all of them, the server's rule when the parameter is left out, or with any of them
        peopleMatch: {
            all: "all",
            any: "any",
        },
        // The sort by match quality (`Const.Search.SORT_RELEVANCE`), which the server refuses without Search text
        sortRelevance: "relevance",
        // The largest page size the server serves (`Const.Search.MAX_RPP`); it refuses a larger one
        maxRpp: 500,
        // The most people, and the most Locations, one search names (`SearchRequestParser.MAX_IDS`); the server refuses
        // more in either parameter
        maxIds: 10,
    },

    http: {
        // JSON the server adds to a dialog operation's success detail (`Api.Field.SUCCESS_DETAIL_HEADER`)
        successDetailHeader: "App-Success-Detail",
    },

    people: {
        // A Person's name is held to these lengths by the server (`Api.Constraints.MIN_NAME_LENGTH`, `MAX_NAME_LENGTH`);
        // the Change combobox offers to create a name only inside them
        nameLength: {
            min: 2,
            max: 80,
        },
        // The People bucket names are the server's (`Const.PeopleBucket`), sent as the `buckets` parameter
        buckets: {
            matched: "matched",
            hidden: "hidden",
            unmatched: "unmatched",
        },
        // The two panes of the People tab, as `data-app-people-pane` names them: the Main pane,
        // always shown, and the Duplicate pane of Split view. The names are the server's too
        // (`Const.PeoplePane`), sent as the `pane` parameter of a pane's list
        panes: {
            main: "main",
            duplicate: "duplicate",
        },
    },
}

window.Const = Const
