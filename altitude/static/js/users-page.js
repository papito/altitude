import { Alpine } from "./lib/alpine.esm.min.js"
import { accountForm } from "./alpine/components/account-form.js"
import { passwordField } from "./alpine/components/password-field.js"
import { usersPage } from "./alpine/components/users-page.js"
import { registerUsersListeners } from "./listeners/account.js"
import { startNavPage } from "./nav-page.js"

/**
 * The Users page. It starts as every page with the nav outside the main app does (`startNavPage`:
 * Alpine for what `html_common` renders and the nav's menus, the htmx request rule and the dialog
 * wiring), with its own components registered first: the page's (`usersPage`, the tab and the
 * filter), the account form's (`accountForm`, the type and the Permission matrix) and the password
 * field's (`passwordField`, Generate and Show). Every change it makes reloads the cards.
 */
Alpine.data("usersPage", usersPage)
Alpine.data("accountForm", accountForm)
Alpine.data("passwordField", passwordField)

startNavPage()
registerUsersListeners()
