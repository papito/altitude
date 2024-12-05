export const Const = {
  events: {
    folderMoved: "FOLDER_MOVED_EVENT",
    assetMoved: "ASSET_MOVED_EVENT",
    folderDeleted: "FOLDER_DELETED_EVENT",
    folderAdded: "FOLDER_ADDED_EVENT",
    folderCollapsed: "FOLDER_COLLAPSED_EVENT",
    personMerged: "PERSON_MERGED_EVENT",
    confirmPersonMerge: "CONFIRM_PERSON_MERGE_EVENT",
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
  },
}
