import { Alpine } from "./lib/alpine.esm.min.js"
import { Const } from "./constants.js"

/**
 * The context of the current page: the repository being browsed and the user's grid settings. The
 * page template sets the repository once at load (`window.ctx.setRepoId`); it cannot change without a
 * full page load, so no partial sets it again.
 */
export const context = {
    setRepoId: function (repoId) {
        Alpine.store(Const.context.repoId, repoId)
    },

    getRepoId: function () {
        return Alpine.store(Const.context.repoId)
    },

    /**
     * Whether the account holds any of the Permissions (`Const.permission`) in this Library, as the
     * body's `data-app-permissions` lists them; a page without the list holds none
     */
    holds: function (...anyOf) {
        const held = (document.body.dataset.appPermissions ?? "").split(" ")
        return anyOf.some((permission) => held.includes(permission))
    },

    /**
     * Whether the account may recycle what the view shows, as the body's `data-app-recycles` says
     * (`RequestContext.recyclesIn`), so the rule stays the server's alone
     */
    recyclesHere: function () {
        return document.body.hasAttribute("data-app-recycles")
    },

    /**
     * Whether the account sees triaged items (a member), as the nav's search panel says. A Guest is
     * refused the triage filter, so nothing asks for it on their behalf.
     */
    seesTriaged: function () {
        return (
            document
                .getElementById("searchPanel")
                ?.hasAttribute(Const.attributes.seesTriaged) ?? false
        )
    },

    /**
     * The folder being browsed, or null. Read from the search parameters rather than the address bar,
     * which only catches up once the server has replied.
     */
    getCurrentFolderId: function () {
        return Alpine.store(Const.state.searchParams).folderId
    },

    getCurrentAlbumId: function () {
        return Alpine.store(Const.state.searchParams).albumId
    },

    /** The Location being browsed, or null, also when an advanced search names several */
    getCurrentLocationId: function () {
        return Alpine.store(Const.state.searchParams).viewedLocationId()
    },

    /**
     * Seeds the grid's metadata fields from localStorage, kept as a JSON array of field names; an
     * older comma-separated value still loads
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
