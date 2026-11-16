import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { runSearch } from "./search.js"

/**
 * The quick search input of the nav. It holds no state of its own: its value follows
 * `searchParams.q`, so a URL with `q` fills it and a place that clears `q` empties it. Text searches
 * the view it is typed in; the Trash has no text search, so typed there it opens Browse. A page
 * without this module submits the form natively, a GET of Browse with `q`.
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

        // Nothing typed and no text to clear: searching anyway would apply the scope rules of `q` and
        // leave the folder or view. It also makes the second of the two events one gesture can fire
        // (below) a no-op.
        if (q === "" && searchParams.q === null) {
            return
        }

        searchText(q)
    }

    formEl.addEventListener("submit", (event) => {
        event.preventDefault()
        submit()
    })

    formEl.querySelector(".clear").addEventListener("click", () => {
        inputEl.value = ""
        submit()
    })

    // The native clear (Escape) fires `search` and no submit. Enter fires it too, beside the submit,
    // where the event exists (it is not standard), so only an emptied input is handled here.
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
    if (!Alpine.store(Const.state.currentView).isTrashBinView()) {
        runSearch({ params: { q } })
        return
    }

    const searchParams = Alpine.store(Const.state.searchParams)
    searchParams.merge({ q })

    window.location.assign(
        `/r/${window.ctx.getRepoId()}?${searchParams.toQueryString()}`,
    )
}
