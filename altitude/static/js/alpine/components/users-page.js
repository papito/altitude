import { partialMatchKey } from "../../common/name-key.js"

/**
 * The Users page's account tab and card filter, kept above the cards so a reload of the cards keeps
 * them. The filter matches a card's name, username and email in any case or Unicode form.
 */
export function usersPage() {
    return {
        tab: "User",
        filter: "",

        /** The server's pick: the first tab holding a card */
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
