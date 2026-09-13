import { Alpine } from "./lib/alpine.esm.min.js"
import { Const } from "./constants.js"

/**
 * The context of the current page: the repository being browsed and the user's grid settings.
 *
 * The page template sets the repository once, at load, from the request:
 *
 *     window.ctx.setRepoId("@{ RequestContext.getRepository.persistedId }")
 *
 * It cannot change without a full page load, so no partial needs to set it again.
 */
export const context = {
    setRepoId: function (repoId) {
        Alpine.store(Const.context.repoId, repoId)
    },

    getRepoId: function () {
        return Alpine.store(Const.context.repoId)
    },

    /**
     * The folder currently being browsed, or null when no folder filter is active. Read from the
     * search parameters rather than the address bar, which only catches up once the server has
     * replied.
     */
    getCurrentFolderId: function () {
        return Alpine.store(Const.state.searchParams).folderId
    },

    /** The album whose assets are being browsed, or null when no album filter is active */
    getCurrentAlbumId: function () {
        return Alpine.store(Const.state.searchParams).albumId
    },

    /** The Location whose assets are being browsed, or null when no Location filter is active */
    getCurrentLocationId: function () {
        return Alpine.store(Const.state.searchParams).locationId
    },

    /**
     * Seeds the grid-visible metadata fields from localStorage, where they are kept as a JSON array
     * of field names. A value saved by an earlier version as a comma-separated list still loads.
     */
    loadMetadataFieldViewSettingsFromStore: function () {
        const saved = localStorage.getItem(Const.localStore.gridMetadataFields)
        if (!saved) {
            return
        }

        let fieldNames
        try {
            fieldNames = JSON.parse(saved)
        } catch {
            fieldNames = saved.split(",")
        }

        if (!Array.isArray(fieldNames)) {
            return
        }

        const fields = this.getGridMetadataFields()
        fields.clear()
        fieldNames.filter(Boolean).forEach((name) => fields.add(name))
    },

    /** The reactive set of field names shown under every grid cell */
    getGridMetadataFields: function () {
        return Alpine.store(Const.context.gridMetadataFields)
    },

    addGridMetadataField: function (fieldName) {
        this.getGridMetadataFields().add(fieldName)
        this.persistGridMetadataFields()
    },

    removeGridMetadataField: function (fieldName) {
        this.getGridMetadataFields().delete(fieldName)
        this.persistGridMetadataFields()
    },

    persistGridMetadataFields: function () {
        localStorage.setItem(
            Const.localStore.gridMetadataFields,
            JSON.stringify(Array.from(this.getGridMetadataFields())),
        )
    },
}
