/**
 * The Add User and Edit form of the Users page (`htmx/account_form`), on the form: the account type
 * chosen, the Libraries given and the Permissions in each, and the Admin's Create Libraries and
 * new-Library grant switches, starting from the state the server writes in `data-app-account-form`
 * (the values shown, the creator's ceiling: per Library whether it may give a User or a Guest
 * access there, and which Permissions, and the preset a User given a Library starts with,
 * `AccountForm.PRESET`). The matrix's toggles run here, with no request:
 *
 * - checking a Library's access, for a User, checks Import and Curate own there where the ceiling
 *   holds them; unchecking it clears its Permissions;
 * - checking a Permission checks the Library's access too;
 * - a row's All checks every Permission of the row, or clears them when all are checked;
 * - a column's header checks its Permission in every Library chosen, or clears it there when all
 *   of them hold it.
 *
 * The inputs follow the state through bindings, and what may not be sent is disabled, so the
 * browser leaves it out of the form: a Guest's Permissions, an Admin's whole matrix and switches,
 * a Library the type may not be given there, and an unchecked Library once as many are checked as
 * an account may hold (`maxLibraries`). The server holds every rule again.
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

        /** Whether the type chosen holds grants: a User or a Guest */
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

        /** Whether the Library's row is offered to the type chosen: the creator may give it a User, or a Guest */
        rowShown(libraryId) {
            const row = this.ceiling[libraryId]
            return (
                (this.isUser() && row.users) || (this.isGuest() && row.guests)
            )
        },

        hasAccess(libraryId) {
            return this.access.includes(libraryId)
        },

        /** Whether as many Libraries of the rows offered are checked as an account may hold */
        atBound() {
            return (
                this.access.filter(
                    (id) => this.ceiling[id] && this.rowShown(id),
                ).length >= this.maxLibraries
            )
        },

        /** Whether the Library's access cannot change: its row is not offered, or it is unchecked at the bound */
        accessBlocked(libraryId) {
            return (
                !this.rowShown(libraryId) ||
                (!this.hasAccess(libraryId) && this.atBound())
            )
        },

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

        /** Whether the Library's Permissions cannot change: the type holds none, or its access cannot be given */
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

        /** A row's All: every Permission of the ceiling there, or none once all are held */
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

        /** A column's header: its Permission in every Library chosen that offers it, or in none once all hold it */
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

        // The list with `value` in it, once, or without it
        _with(list, value, on) {
            const without = list.filter((item) => item !== value)
            return on ? [...without, value] : without
        },
    }
}
