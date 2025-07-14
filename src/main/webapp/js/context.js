import { Const } from "./constants.js"

/**
 * This module is used to store the context of the current user/request.
 *
 * If a module requires the context (for URL generation, etc.), the parent SPP template can set the context
 * as shown below:
 *
 * <script type="module">
 *     import {context} from "../context.js"
 *
 *      context.setRepoId("<%= RequestContext.getRepository.persistedId %>")
 * </script>
 */
export const context = {
    setRepoId: function (repoId) {
        Alpine.store(Const.context.repoId, repoId)
    },

    getRepoId: function () {
        return Alpine.store(Const.context.repoId)
    },

    loadMetadataFieldViewSettingsFromStore: function () {
        // localStorage stores the grid-visible metadata fields as a comma-separated list
        const savedGridMetadataFields = localStorage.getItem(
            Const.localStore.gridMetadataFields,
        )
        if (!savedGridMetadataFields || savedGridMetadataFields.length === 0) {
            return
        }

        const fieldNames = savedGridMetadataFields?.split(",")
        const cache = new Set(fieldNames)
        Alpine.store(Const.context.gridMetadataFields, cache)
    },

    getGridMetadataFields: function () {
        return Alpine.store(Const.context.gridMetadataFields) || new Set()
    },

    addGridMetadataField: function (fieldName) {
        const cache = this.getGridMetadataFields()
        cache.add(fieldName)
        Alpine.store(Const.context.gridMetadataFields, cache)
        this.persistGridMetadataFields()
    },

    removeGridMetadataField: function (fieldName) {
        const cache = this.getGridMetadataFields()
        cache.delete(fieldName)
        Alpine.store(Const.context.gridMetadataFields, cache)
        this.persistGridMetadataFields()
    },

    persistGridMetadataFields: function () {
        const cache = this.getGridMetadataFields()
        const serialized = Array.from(cache).join(",")
        localStorage.setItem(Const.localStore.gridMetadataFields, serialized)
    },
}
