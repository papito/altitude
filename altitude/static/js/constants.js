export const Const = {
    views: {
        repository: "repository",
        triage: "triage",
        trashbin: "trashbin",
    },

    // The Permissions scripts ask about (`context.holds`), by their server `Permission` names
    permission: {
        curateOwn: "CurateOwn",
        curateAll: "CurateAll",
        organize: "Organize",
        recycle: "Recycle",
    },

    // The explorer's side of the results, as `data-dock-side` on <html> names it
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
        // Any change to a Person or to a Face's Person; the People tab reloads its panes
        peopleChanged: "PEOPLE_CHANGED_EVENT",
        // The Change combobox picked a Person (`detail.personId`) or a new name (`detail.name`) for `detail.faceId`
        facePersonPicked: "FACE_PERSON_PICKED_EVENT",
        // The Change combobox's X: the row of `detail.faceId` reverts, with nothing selected
        faceChangeCancelled: "FACE_CHANGE_CANCELLED_EVENT",
        escapeKeyPressed: "ESCAPE_KEY_PRESSED_EVENT",
        showNext: "SHOW_NEXT_EVENT",
        showPrevious: "SHOW_PREVIOUS_EVENT",
        togglePlayback: "TOGGLE_PLAYBACK_EVENT",
        // `m` in Asset detail
        toggleDetailPanel: "TOGGLE_DETAIL_PANEL_EVENT",
        // `p` in Asset detail
        toggleFaceHighlights: "TOGGLE_FACE_HIGHLIGHTS_EVENT",
        // `f` in Asset detail
        toggleFullscreen: "TOGGLE_FULLSCREEN_EVENT",
        // `\` pressed (`detail.held` true) or released in Asset detail: Show original while held, on an edited asset
        showOriginalHeld: "SHOW_ORIGINAL_HELD_EVENT",
        // `e` in Asset detail
        toggleEditPanel: "TOGGLE_EDIT_PANEL_EVENT",
        // `c` with the Edit panel open
        toggleCropTool: "TOGGLE_CROP_TOOL_EVENT",
        // In the Crop tool: `o` cycles the guide, `x` swaps the aspect's orientation
        cycleCropGuide: "CYCLE_CROP_GUIDE_EVENT",
        swapCropAspect: "SWAP_CROP_ASPECT_EVENT",
        // Enter with the Edit panel's changes held
        editDone: "EDIT_DONE_EVENT",
        // Ctrl/Cmd+Z, and Ctrl/Cmd+Shift+Z or Ctrl+Y, with the Edit panel open
        undoEdit: "UNDO_EDIT_EVENT",
        redoEdit: "REDO_EDIT_EVENT",
        settingsSaved: "SETTINGS_SAVED_EVENT",
        // An account was added, changed or deleted; `detail.message` says which
        accountsChanged: "ACCOUNTS_CHANGED_EVENT",
        // A Watch folder was added, changed or removed, here or as the status feed reports; `detail.message`, if any,
        // says what
        watchFoldersChanged: "WATCH_FOLDERS_CHANGED_EVENT",
        // The account changed its own password; the session carries on with the new token
        passwordChanged: "PASSWORD_CHANGED_EVENT",
        // The Dock side changed, by a pick or the viewport's orientation; `detail.side` is the new one
        dockSideChanged: "DOCK_SIDE_CHANGED_EVENT",
        styleGuideSampleSubmitted: "STYLE_GUIDE_SAMPLE_SUBMITTED_EVENT",
        // `detail.theme` is the theme now shown
        themeChanged: "THEME_CHANGED_EVENT",
        // The batch ops' Curate: opens Curation on the selection unless it exceeds the button's `data-max-selection`
        batchCurateRequested: "BATCH_CURATE_REQUESTED_EVENT",
        // Opens Curation: the queue with no detail, one Sequence with `detail.sequenceId`, a selection with
        // `detail.assetIds`
        curateRequested: "CURATE_REQUESTED_EVENT",
        // A change in Curation was saved; the counts refresh
        curationChanged: "CURATION_CHANGED_EVENT",
        // Curation closed; `detail.recycledIds` and `detail.clearedSequenceIds` are what it leaves changed
        curationClosed: "CURATION_CLOSED_EVENT",
    },

    /**
     * The `data-*` attributes JS reads identity and state from. Templates and the client-side tree and
     * list renderers write the same names; `dataset` reads them camel-cased.
     */
    attributes: {
        expanded: "data-expanded",
        viewedScope: "data-viewed-scope",
        // On a stage pane's lens: the Loupe's level ("200%"), which its stylesheet shows
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
        // On a Change combobox option in its Suggested group: a Person among the nearest
        suggested: "data-suggested",
        // On a Detail panel People row: the Person's name, Hidden state, whether named and asset count (the last two
        // read by the Change combobox, also on its options), and a Video Face's Frame time, for the Face highlights
        personName: "data-person-name",
        personHidden: "data-person-hidden",
        personNamed: "data-person-named",
        personAssetCount: "data-person-asset-count",
        frameTimeMs: "data-frame-time-ms",
        // On a Curation rail frame (`data-asset-id` finds the grid cells beneath): its file name, the stage tag's
        // problems, its Bundle, and whether it is in an album or Location
        frameId: "data-frame-id",
        fileName: "data-file-name",
        problems: "data-problems",
        bundleKind: "data-bundle-kind",
        inAlbumOrLocation: "data-in-album-or-location",
        // On a group header's Curate: the Sequence it opens
        sequenceId: "data-app-sequence-id",
        // On the Detail panel's Curation link: the Sequence it opens over Asset detail
        curateSequence: "data-app-curate-sequence",
        // On a row of Curation's Sequence rail; not `data-app-sequence-id`, which finds a group header's Curate
        railSequenceId: "data-rail-sequence-id",
        // On a group header's Curate: the fewest frames it stays for (`Const.Curation.MIN_FRAMES`)
        minFrames: "data-min-frames",
        // On the batch ops' Curate: the most images it opens Curation on (`Const.Curation.MAX_SELECTION`)
        maxSelection: "data-max-selection",
        // A Curation set's opening frames
        openingPinnedId: "data-opening-pinned-id",
        openingFocusedId: "data-opening-focused-id",
        // On a Curation set's root and the Asset detail fragment, for the Clipping blink: the gray levels a pixel
        // counts as blown or crushed at, and the box the image is fitted into before it is measured
        clippedLevel: "data-clipped-level",
        crushedLevel: "data-crushed-level",
        measurementBox: "data-measurement-box",
        // On a rail item, for the stage's Histogram strip, as the import measured the frame: the Histogram (a byte
        // per bin, Base64) and the fractions crushed and blown; absent on a frame not measured
        histogram: "data-histogram",
        crushedShadows: "data-crushed-shadows",
        clippedHighlights: "data-clipped-highlights",
        // The face strip's column of a frame, and a crop's box in upright pixels: x, y, width, height
        columnFrameId: "data-column-frame-id",
        faceBox: "data-face-box",
        // A Setting field's default, which its Reset fills in
        settingDefault: "data-setting-default",
        // A pick of the explorer's Dock side, and of the Detail panel's (its own, since both docks listen on the body)
        dockSide: "data-app-dock-side",
        detailDockSide: "data-app-detail-dock-side",
        // Asset detail toolbar's toggles: the Detail panel, the Face highlights and fullscreen
        detailPanelToggle: "data-app-detail-panel-toggle",
        faceHighlightsToggle: "data-app-face-highlights-toggle",
        fullscreenToggle: "data-app-fullscreen-toggle",
        fullscreenKey: "data-app-fullscreen-key", // the key legend's fullscreen entry, gone where the toggle is
        // Shown on an edited asset alone: Show original's toolbar toggle, the title's badge saying whether the stage
        // shows the Edit or the original, and the key legend's `\` entry
        showOriginalToggle: "data-app-show-original-toggle",
        editBadge: "data-app-edit-badge",
        showOriginalKey: "data-app-show-original-key",
        // The Edit panel's toggle in Asset detail's toolbar, for a holder of Edit images
        editPanelToggle: "data-app-edit-panel-toggle",
        // On the stage's canvas while it draws the identity behind the original's color-managed <img>, for the Edit
        // panel's reads; the stylesheet hides it
        editCanvasBehind: "data-app-edit-canvas-behind",
        // On the Asset detail fragment while the Edit panel, and its Crop tool, are open; the grid and stage lay out
        // by them
        editPanelOpen: "data-app-edit-panel-open",
        cropToolOpen: "data-app-crop-tool-open",
        // The stage's bar of held changes: the Crop tool's guide button, the guide's name and the crop's size, Cancel,
        // Done and the count of changes held
        cropGuide: "data-app-crop-guide",
        cropGuideName: "data-app-crop-guide-name",
        cropSize: "data-app-crop-size",
        editCancel: "data-app-edit-cancel",
        editDone: "data-app-edit-done",
        editHeldCount: "data-app-edit-held-count",
        // The warning of changes not saved: a choice's button, valued `save`, `discard` or `stay`, and the message
        editLeave: "data-app-edit-leave",
        editLeaveMessage: "data-app-edit-leave-message",
        // A People row's button that selects its Face, and under a selected row Change (opens the combobox) and Remove,
        // which carries the label its first click switches to
        faceRow: "data-app-face-row",
        faceChange: "data-app-face-change",
        faceRemove: "data-app-face-remove",
        faceRemoveLastLabel: "data-app-face-remove-label-last",
        // On the People section's root: read-only in the trash; after a correction the snackbar message, a deleted
        // Person's ID and the line that says so, and after a merge the merged Person's ID and the one they merged into
        readOnly: "data-app-read-only",
        resultMessage: "data-app-result-message",
        deletedPersonId: "data-app-deleted-person-id",
        deletedPersonMessage: "data-app-deleted-person-message",
        mergedPersonId: "data-app-merged-person-id",
        mergedIntoId: "data-app-merged-into-id",
        // The Detail panel's dimensions row, filled from the shown element's size
        detailDimensions: "data-app-detail-dimensions",
        // On the Asset detail fragment while the Detail panel is open (the grid lays out by it), and the asset the
        // panel and its People section show; not `data-asset-id`, which finds grid cells
        detailPanelOpen: "data-app-detail-panel-open",
        appAssetId: "data-app-asset-id",
        // On the advanced search panel's host in the nav: the route its form loads from
        panelUrl: "data-panel-url",
        // On the same host for a member, absent for a Guest: the account sees triaged items (`context.seesTriaged`)
        seesTriaged: "data-app-sees-triaged",
        // On a combobox field of the panel: its options' route and the form inputs sent with it, whether it takes
        // several names, the most it takes and the line shown at that limit
        optionsUrl: "data-options-url",
        optionsParams: "data-options-params",
        multiple: "data-multiple",
        maxChosen: "data-max-chosen",
        maxChosenMessage: "data-max-chosen-message",
        // On an option of such a field and a chosen one's chip: the ID a search takes, the chip's label, the element
        // showing the name, and a Person's cover Face, which a new chip copies
        optionId: "data-option-id",
        optionLabel: "data-option-label",
        optionName: "data-option-name",
        optionCover: "data-option-cover",
        // A chosen filter's chip remove button, in the panel and in a filtered search's results title
        chipRemove: "data-chip-remove",
        // A chip of a filtered search's results title, carrying its parameters as `data-app-filter-<param>`; and the
        // title's Edit, which opens the panel
        filter: "data-app-filter",
        editFilters: "data-app-edit-filters",
        // The nav filter button's badge: the number of filters in force, or in the open panel's form
        filterCount: "data-app-filter-count",
        // The Import history: an Import's entry, the `<details>` of its files, the route of the view a part shows (by
        // which the page reads it again), and on a control the route of the view it asks for instead
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
        // The explorer's Dock side, once picked, and its share of each axis; `explorer-dock-boot.js` names the same key
        // in its `data-key`
        explorerDock: "explorerDock",
        // The results layout last chosen (`grid` / `map`), seeded into the search parameters on load
        resultsLayout: "resultsLayout",
        // Whether Browse shows triaged items, "true" or "false", as last flipped; seeded into the search parameters on
        // load for an account that sees them, and shown when nothing is kept
        showTriaged: "showTriaged",
        // The theme last chosen (`light` / `dark`); the boot script of `includes/header_common` names the same key in
        // its `data-key`
        theme: "theme",
        // The design tweaks panel's state; the boot script of `includes/header_common` names the same key in its
        // `data-key`
        designTweaks: "designTweaks",
        // The People tab's state: each pane's People buckets, Split view, and the panes' sizes on each axis
        peopleView: "peopleView",
        // The Loupe's magnification last chosen on Curation's stage
        loupeLevel: "loupeLevel",
        // The Bundle kinds whose details Curation's rail shows, a JSON array
        curationDetails: "curationDetails",
        // Whether Curation's stage shows the Histogram strips, "true" or "false"
        curationHistogram: "curationHistogram",
        // Whether Curation shows the Sequence rail on the queue, "true" or "false"
        curationSequenceRail: "curationSequenceRail",
        // Asset detail's view: the Detail panel's open state, Face highlights and dock, and the Edit panel's open state
        // (`editOpen`) and dock (`editDock`)
        assetDetailView: "assetDetailView",
    },

    sessionStore: {
        // The map view last looked at per search scope
        mapViews: "mapViews",
    },

    search: {
        layout: {
            grid: "grid",
            map: "map",
        },
        // The Triage summary's subsets (`TriageSubset.apiValue`), sent as the `subset` parameter
        subsets: {
            unknownPeople: "unknownPeople",
            noLocation: "noLocation",
        },
        // How several people of one search combine (`PeopleMatch.apiValue`), sent as `peopleMatch`; the server defaults
        // to all
        peopleMatch: {
            all: "all",
            any: "any",
        },
        // Sort by match quality (`Const.Search.SORT_RELEVANCE`), which the server refuses without Search text
        sortRelevance: "relevance",
        // Sort by likeness to a description (`Const.Search.SORT_SIMILARITY`), which the server refuses without one
        sortSimilarity: "similarity",
        // The largest page size the server serves (`Const.Search.MAX_RPP`)
        maxRpp: 500,
        // The most people, and the most Locations, one search names (`SearchRequestParser.MAX_IDS`)
        maxIds: 10,
        // The longest Describe search description (`Const.Search.MAX_DESCRIPTION_LENGTH`), counted trimmed
        maxDescriptionLength: 300,
    },

    http: {
        // JSON the server adds to a dialog operation's success detail (`Api.Field.SUCCESS_DETAIL_HEADER`)
        successDetailHeader: "App-Success-Detail",
    },

    people: {
        // A Person's name length limits (`Api.Constraints.MIN_NAME_LENGTH`, `MAX_NAME_LENGTH`); the Change combobox
        // offers to create a name only within them
        nameLength: {
            min: 2,
            max: 80,
        },
        // The server's `Const.PeopleBucket` names, sent as the `buckets` parameter
        buckets: {
            matched: "matched",
            hidden: "hidden",
            unmatched: "unmatched",
        },
        // The People tab's Main pane and Split view's Duplicate pane, as `data-app-people-pane` and the server's
        // `Const.PeoplePane` name them (a pane list's `pane` parameter)
        panes: {
            main: "main",
            duplicate: "duplicate",
        },
    },
}

window.Const = Const
