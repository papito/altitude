import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { runSearch } from "./search.js"

/**
 * The Search input of the nav.
 *
 * Enter and the Search button submit the form, which searches for the text; the input's native
 * clear (×) and a submit of an empty input clear the text of the current search. The input never
 * holds state of its own: its value follows `searchParams.q`, so a URL with `q` fills it on load
 * and a folder, person, album or Location, which clear `q` (see `stores/search-params.js`), empty
 * it.
 */
export function bindSearchInput() {
    const formEl = document.getElementById("searchForm")

    if (!formEl) {
        return
    }

    const inputEl = formEl.elements.q
    const searchParams = Alpine.store(Const.state.searchParams)

    const submit = () => {
        const q = inputEl.value.trim()

        // Nothing typed and no text to clear. Running the search anyway would still apply the scope
        // rules of `q` and leave the folder or the view being looked at. It also makes the second
        // of the two events one gesture can fire (below) a no-op.
        if (q === "" && searchParams.q === null) {
            return
        }

        searchText(q)
    }

    formEl.addEventListener("submit", (event) => {
        event.preventDefault()
        submit()
    })

    // The native clear fires `search` and no submit. Enter fires it as well where the event exists
    // (it is not standard), beside the submit, which is why only an emptied input is handled here.
    inputEl.addEventListener("search", () => {
        if (inputEl.value === "") {
            submit()
        }
    })

    Alpine.effect(() => {
        inputEl.value = searchParams.q ?? ""
    })
}

function searchText(q) {
    if (Alpine.store(Const.state.currentView).isDefaultViewView()) {
        runSearch({ params: { q } })
        return
    }

    const searchParams = Alpine.store(Const.state.searchParams)
    searchParams.merge({ q })

    window.location.assign(
        `/r/${window.ctx.getRepoId()}?${searchParams.toQueryString()}`,
    )
}
