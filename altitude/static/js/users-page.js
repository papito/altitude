import { Alpine } from "./lib/alpine.esm.min.js"
import { accountForm } from "./alpine/components/account-form.js"
import { passwordField } from "./alpine/components/password-field.js"
import { usersPage } from "./alpine/components/users-page.js"
import { registerUsersListeners } from "./listeners/account.js"
import { startNavPage } from "./nav-page.js"

/** The Users page: its components registered before `startNavPage()`. */
Alpine.data("usersPage", usersPage)
Alpine.data("accountForm", accountForm)
Alpine.data("passwordField", passwordField)

startNavPage()
registerUsersListeners()
