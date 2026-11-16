/**
 * The Users page's Add User and Edit form. It starts from `data-app-account-form`, which carries the
 * creator's ceiling: per Library, whether it may give a User or a Guest access there, and which
 * Permissions. Inputs that may not be sent are disabled so the browser leaves them out of the form;
 * the server enforces every rule again.
 */
export function accountForm() {
    return {
        type: "",
        access: [],
        permissions: {},
        canCreateLibraries: false,
        grantsNewLibraries: false,
        newLibraryPermissions: [],
        ceiling: {},
        maxLibraries: 0,
        preset: [],

        init() {
            const state = JSON.parse(this.$el.dataset.appAccountForm)
            this.type = state.type
            this.access = state.access
            this.permissions = state.permissions
            this.canCreateLibraries = state.canCreateLibraries
            this.grantsNewLibraries = state.grantsNewLibraries
            this.newLibraryPermissions = state.newLibraryPermissions
            this.ceiling = state.ceiling
            this.maxLibraries = state.maxLibraries
            this.preset = state.preset
        },

        isAdmin() {
            return this.type === "Admin"
        },

        isUser() {
            return this.type === "User"
        },

        isGuest() {
            return this.type === "Guest"
        },

        takesGrants() {
            return this.isUser() || this.isGuest()
        },

        createLibrariesOff() {
            return !this.isUser()
        },

        newLibrariesOff() {
            return !this.takesGrants()
        },

        newLibraryChipsOff() {
            return !this.isUser() || !this.grantsNewLibraries
        },

        matrixClass() {
            return this.isGuest() ? "guest" : ""
        },

        toggleCreateLibraries() {
            this.canCreateLibraries = !this.canCreateLibraries
        },

        toggleNewLibraries() {
            this.grantsNewLibraries = !this.grantsNewLibraries
        },

        newLibraryHolds(permission) {
            return this.newLibraryPermissions.includes(permission)
        },

        setNewLibraryPermission(permission, on) {
            this.newLibraryPermissions = this._with(
                this.newLibraryPermissions,
                permission,
                on,
            )
        },

        /** Whether the creator may give the chosen type access to this Library */
        rowShown(libraryId) {
            const row = this.ceiling[libraryId]
            return (
                (this.isUser() && row.users) || (this.isGuest() && row.guests)
            )
        },

        hasAccess(libraryId) {
            return this.access.includes(libraryId)
        },

        /** Whether as many Libraries are checked as an account may hold */
        atBound() {
            return (
                this.access.filter(
                    (id) => this.ceiling[id] && this.rowShown(id),
                ).length >= this.maxLibraries
            )
        },

        accessBlocked(libraryId) {
            return (
                !this.rowShown(libraryId) ||
                (!this.hasAccess(libraryId) && this.atBound())
            )
        },

        /** Unchecking clears the Library's Permissions; checking it for a User with none starts at the preset */
        setAccess(libraryId, on) {
            this.access = this._with(this.access, libraryId, on)
            if (!on) {
                this.permissions[libraryId] = []
            } else if (
                this.isUser() &&
                (this.permissions[libraryId] ?? []).length === 0
            ) {
                this.permissions[libraryId] = this.preset.filter((permission) =>
                    this.ceiling[libraryId].permissions.includes(permission),
                )
            }
        },

        holds(libraryId, permission) {
            return (this.permissions[libraryId] ?? []).includes(permission)
        },

        permissionsBlocked(libraryId) {
            return !this.isUser() || this.accessBlocked(libraryId)
        },

        setPermission(libraryId, permission, on) {
            this.permissions[libraryId] = this._with(
                this.permissions[libraryId] ?? [],
                permission,
                on,
            )
            if (on && !this.hasAccess(libraryId)) {
                this.access = this._with(this.access, libraryId, true)
            }
        },

        /** A row's All: every Permission the ceiling offers there, or none once all are held */
        toggleRow(libraryId) {
            if (this.permissionsBlocked(libraryId)) {
                return
            }
            const offered = this.ceiling[libraryId].permissions
            const all = offered.every((permission) =>
                this.holds(libraryId, permission),
            )
            this.permissions[libraryId] = all ? [] : [...offered]
            if (!all) {
                this.access = this._with(this.access, libraryId, true)
            }
        },

        /** A column's header: its Permission in every chosen Library that offers it, or in none once all hold it */
        toggleColumn(permission) {
            if (!this.isUser()) {
                return
            }
            const chosen = this.access.filter(
                (id) =>
                    this.ceiling[id] &&
                    this.rowShown(id) &&
                    this.ceiling[id].permissions.includes(permission),
            )
            const all = chosen.every((id) => this.holds(id, permission))
            chosen.forEach((id) => this.setPermission(id, permission, !all))
        },

        // The list with `value` once, or without it
        _with(list, value, on) {
            const without = list.filter((item) => item !== value)
            return on ? [...without, value] : without
        },
    }
}
