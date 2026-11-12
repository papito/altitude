import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { getHttpErrorMessage, http } from "../http/client.js"
import {
    applySearchFilters,
    seedSearchParams,
    serializeSearchParams,
} from "../stores/search-params.js"
import { runSearch } from "./search.js"

/**
 * The advanced search panel of the nav (`#searchPanel` in `includes/nav`, views/AGENTS.md
 * **Advanced search panel**): a native `popover="auto"` panel under the quick search form, which the
 * form's filter button opens (`popovertarget`). The browser owns its visibility; this module loads
 * its form, counts the filters on the button, and runs the search its form asks for. The form's own
 * behaviour is its components' (`alpine/components/search-panel.js`, `filter-combobox.js`,
 * `date-range.js`).
 *
 * The panel and the quick search form are `hx-preserve`, so they are bound once and outlive a nav
 * reload. The nav is on every page: on the main page the panel shows the search in force and
 * searches in place; on a page with no search of its own (Import, Settings, Users, the style
 * guide), and in Triage and the Trash, whose pages are laid out for their view, it opens Browse
 * with the search as the query string, as the quick search does.
 *
 * Opening loads the form (`GET /htmx/search/r/:repoId/panel`, the host's `data-panel-url`) filled
 * in from the search parameters in force, so the server names the chips, with the search's triage
 * filter, which the form's People options count by. The text is the quick
 * search input's, typed or not yet searched, so the two fields hold one value: the input's is
 * copied to the panel as it opens, and the panel's to the input as it searches. The form is read
 * through the axios client and put in the panel here, not by htmx, so that only the answer of the
 * latest opening is shown; the panel is emptied as it closes, and stays invisible until its form
 * arrives (`htmx/search_panel.css`).
 */

const PANEL_ID = "searchPanel"

// The form's text field (htmx/search_panel), which takes focus once the form is in
const TEXT_ID = "searchPanelText"

// The latest load of the form; an earlier one's answer is dropped
let loadToken = 0

// The filters the next opening shows over the ones in force (`openSearchPanel`)
let openingFilters = null

/** Binds the panel and the filter button of the page's nav, once; a page without the nav binds nothing */
export function bindSearchPanel() {
    const panelEl = document.getElementById(PANEL_ID)

    if (!panelEl) {
        return
    }

    // `beforetoggle` fires for every opening, where the `toggle` of a fast close and reopen is one event
    panelEl.addEventListener("beforetoggle", (event) => {
        if (event.newState === "open") {
            loadForm(panelEl)
        }
    })

    panelEl.addEventListener("toggle", () => {
        if (!panelEl.matches(":popover-open")) {
            emptyPanel(panelEl)
        }
    })

    // A page with no search of its own has no store, and nothing to count
    const searchParams = Alpine.store(Const.state.searchParams)
    if (searchParams) {
        Alpine.effect(() => showFilterCount(searchParams.filterCount()))
    }
}

/**
 * Opens the panel on the search in force with `filters` laid over it (`{ folderId }` for a folder's
 * Search here), each under its search parameter's name; an open panel is loaded again with them.
 * Nothing is searched until its Search.
 *
 * A closing panel hands focus back to what had it as the panel opened: a filtered title's Edit keeps
 * it, but an entry of a menu is gone by then, since the panel's opening closes the menu, so from
 * inside another popover the focus goes to the filter button first.
 */
export function openSearchPanel(filters = {}) {
    const panelEl = document.getElementById(PANEL_ID)
    openingFilters = filters

    if (panelEl.matches(":popover-open")) {
        loadForm(panelEl)
        return
    }

    const buttonEl = filterButtonEl()
    if (document.activeElement?.closest(":popover-open")) {
        buttonEl.focus()
    }
    // The button as the source, so Tab goes on from it
    panelEl.showPopover({ source: buttonEl })
}

/** Closes the panel, if it is open, and returns whether it was: Escape's job in `global.js` */
export function closeOpenSearchPanel() {
    const panelEl = document.getElementById(PANEL_ID)

    if (!panelEl?.matches(":popover-open")) {
        return false
    }

    panelEl.hidePopover()
    return true
}

/**
 * Runs the search of the panel's `filters`, each under its search parameter's name, which replace
 * the ones in force (`applySearchFilters`). In Browse the store takes them and the results are read
 * again in place, the panel closed and the quick search input showing the text searched; from
 * anywhere else the window goes to Browse with the search as its query string, which seeds the
 * store there.
 */
export function searchByFilters(filters) {
    const searchParams = Alpine.store(Const.state.searchParams)

    if (Alpine.store(Const.state.currentView)?.isDefaultViewView()) {
        searchParams.replaceFilters(filters)
        // The store writes its text into the input only when the text changes, and the input may
        // hold text that was typed and never searched
        quickSearchFormEl().elements.q.value = searchParams.q ?? ""
        closeOpenSearchPanel()
        runSearch()
        return
    }

    const params = applySearchFilters(
        searchParams ? searchParams.snapshot() : seedSearchParams(""),
        filters,
    )

    window.location.assign(
        `${quickSearchFormEl().getAttribute("action")}?${serializeSearchParams(params)}`,
    )
}

function quickSearchFormEl() {
    return document.getElementById("searchForm")
}

function filterButtonEl() {
    return document.querySelector(`[popovertarget="${PANEL_ID}"]`)
}

/**
 * Loads the panel's form for the search in force, with the quick search input's text as its text
 * and the filters of `openSearchPanel` over both, and with the search's triage filter, which a
 * search from the panel keeps. The panel shows nothing meanwhile; a failure closes it.
 */
async function loadForm(panelEl) {
    const token = ++loadToken
    const searchParams = Alpine.store(Const.state.searchParams)
    const filters = {
        ...searchParams?.filters(),
        q: quickSearchFormEl().elements.q.value.trim(),
        ...openingFilters,
        triaged: searchParams?.triaged ?? null,
    }
    openingFilters = null
    panelEl.replaceChildren()

    try {
        const response = await http.get(
            panelEl.getAttribute(Const.attributes.panelUrl),
            { params: withoutEmpty(filters) },
        )
        if (token !== loadToken) {
            return
        }

        // Alpine's mutation observer initializes the form's components
        panelEl.innerHTML = response.data
        document.getElementById(TEXT_ID)?.focus()
    } catch (error) {
        if (token !== loadToken) {
            return
        }

        console.error(
            `Error loading the search panel: ${getHttpErrorMessage(error)}`,
        )
        showErrorSnackBar("Could not load the search panel")
        closeOpenSearchPanel()
    }
}

/** A closed panel holds nothing: its components go with their form, and a load still out is dropped */
function emptyPanel(panelEl) {
    loadToken++
    panelEl.replaceChildren()
}

function withoutEmpty(filters) {
    return Object.fromEntries(
        Object.entries(filters).filter(
            ([, value]) => value !== null && value !== "",
        ),
    )
}

/**
 * The number of filters in force on the filter button: its badge, shown from one up, and its
 * accessible name
 */
function showFilterCount(count) {
    const buttonEl = filterButtonEl()
    const name = buttonEl.title

    buttonEl.querySelector(`[${Const.attributes.filterCount}]`).textContent =
        count > 0 ? count : ""
    buttonEl.setAttribute(
        "aria-label",
        count > 0
            ? `${name}: ${count} ${count === 1 ? "filter" : "filters"} in force`
            : name,
    )
}
