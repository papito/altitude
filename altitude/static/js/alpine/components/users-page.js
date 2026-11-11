import { partialMatchKey } from "../../common/name-key.js"

/**
 * The Users page (`users.scala.html`), on its `main`: the tab shown of the account cards (Admins,
 * Users or Guests; `htmx/user_accounts`) and the filter box's text, which hides every card whose
 * name, username and email address do not hold it, in any case or Unicode form, with no request.
 * Both live here, above the cards, so a reload of the cards after a change keeps them; the tab
 * starts as the server says.
 */
export function usersPage() {
    return {
        tab: "User",
        filter: "",

        /** Opens on the tab the server chose (`data-app-start-tab` on the cards: the first holding a card) */
        init() {
            this.tab =
                this.$el.querySelector("#accounts")?.dataset.appStartTab ??
                this.tab
        },

        isTab(accountType) {
            return this.tab === accountType
        },

        showTab(accountType) {
            this.tab = accountType
        },

        /** Whether the card `cardEl` matches the filter, by the text it carries */
        shows(cardEl) {
            const key = partialMatchKey(this.filter)
            return (
                key === "" ||
                partialMatchKey(cardEl.dataset.appFilterText ?? "").includes(
                    key,
                )
            )
        },
    }
}
