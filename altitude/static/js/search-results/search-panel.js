import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { getHttpErrorMessage, http } from "../http/client.js"
import { context } from "../context.js"
import {
    applySearchFilters,
    countSearchFilters,
    rememberedTriaged,
    seedSearchParams,
    serializeSearchParams,
} from "../stores/search-params.js"

const { repository, triage } = Const.views
import { runSearch } from "./search.js"

/**
 * The advanced search panel of the nav (`#searchPanel` in `includes/nav`, views/AGENTS.md
 * **Advanced search panel**): a native `popover="auto"` panel under the quick search form, which the
 * form's filter button opens (`popovertarget`). The browser owns its visibility; this module loads
 * its form, counts the filters on the button, and runs the search its form asks for. The button
 * counts the filters of the search in force while the panel is closed and the fields its form fills
 * while it is open (`showFormFilterCount`), so a field emptied in the panel, or its Clear, shows on
 * the button before anything is searched. The form's own
 * behaviour is its components' (`alpine/components/search-panel.js`, `filter-combobox.js`,
 * `date-range.js`).
 *
 * The panel and the quick search form are `hx-preserve`, so they are bound once and outlive a nav
 * reload. The nav is on every page: in Browse and in Triage the panel shows the search in force and
 * searches in place when its search is that view's, and opens the other of the two, whose page is
 * laid out for its view, when its "Search in" row picks it; on a page with no search of its own
 * (Import, Settings, Users, the style guide), and in the Trash, it opens the view its search is of
 * with the search as the query string.
 *
 * Opening loads the form (`GET /htmx/search/r/:repoId/panel`, the host's `data-panel-url`) filled
 * in from the search parameters in force, its description included, so the server names the chips,
 * with the view and the triage filter of the search it will run (`pickOfPanelSearch`), which the
 * form's "Search in" row opens on and its People options count by. The text is the quick
 * search input's, typed or not yet searched, so the form's field for it and the input hold one
 * value: the input's is copied to the panel as it opens, and the panel's to the input as it
 * searches. The form is read through the axios client and put in the panel here, not by htmx, so
 * that only the answer of the latest opening is shown; the panel is emptied as it closes, and stays
 * invisible until its form arrives (`htmx/search_panel.css`).
 */

const PANEL_ID = "searchPanel"

// The form's first field, Describe (htmx/search_panel), which takes focus once the form is in, the caret after its text
const DESCRIBE_ID = "searchPanelDescribe"

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

    // A closed panel's form is gone, and the button counts the search in force again: what was
    // changed in the panel and not searched is dropped
    panelEl.addEventListener("toggle", () => {
        if (!panelEl.matches(":popover-open")) {
            emptyPanel(panelEl)
            showFilterCount(filterCountInForce())
        }
    })

    const searchParams = Alpine.store(Const.state.searchParams)
    if (searchParams) {
        Alpine.effect(() => showFilterCount(searchParams.filterCount()))
    }
}

/** How many filters the search in force has; none on a page with no search of its own, which has no store */
function filterCountInForce() {
    return Alpine.store(Const.state.searchParams)?.filterCount() ?? 0
}

/**
 * The search `formEl` holds, each entry under its search parameter's name: its filters and, in a
 * member's form, its pick of the assets to search, the view and the triage filter. A folder holds
 * no triaged asset, so a form that searches Triage hides its Folder field and holds no folder,
 * whatever the field kept from another pick.
 */
export function searchOfForm(formEl) {
    const search = Object.fromEntries(new FormData(formEl))

    if (search.view === triage) {
        delete search.folderId
    }

    return search
}

/**
 * Counts on the filter button the fields `formEl` fills, as the store counts those of a search
 * (`countSearchFilters`): the panel's form calls it as it loads and whenever a field's value
 * changes. A form outside the nav's panel (the style guide's specimen) counts nothing.
 */
export function showFormFilterCount(formEl) {
    if (!formEl.closest(`#${PANEL_ID}`)) {
        return
    }

    const filled = Object.fromEntries(
        Object.entries(searchOfForm(formEl)).map(([name, value]) => [
            name,
            value.trim() === "" ? null : value,
        ]),
    )
    showFilterCount(countSearchFilters(filled))
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
 * the ones in force (`applySearchFilters`), with the panel's pick of the assets to search, a `view`
 * and a `triaged`, when they carry one; a chip removed from a filtered search's title carries none,
 * and keeps the view. Where the page shows the view the search is of, Browse or Triage, the store
 * takes the filters and the results are read again in place, the panel closed and the quick search
 * input showing the text searched; from anywhere else the window goes to that view with the search
 * as its query string, which seeds the store there.
 */
export function searchByFilters(filters) {
    const searchParams = Alpine.store(Const.state.searchParams)
    const params = applySearchFilters(
        searchParams
            ? searchParams.snapshot()
            : seedSearchParams("", { seesTriaged: context.seesTriaged() }),
        filters,
    )

    if (params.view === pageView()) {
        searchParams.replaceFilters(filters)
        // The store writes its text into the input only when the text changes, and the input may
        // hold text that was typed and never searched
        quickSearchFormEl().elements.q.value = searchParams.q ?? ""
        closeOpenSearchPanel()
        runSearch()
        return
    }

    window.location.assign(
        `${quickSearchFormEl().getAttribute("action")}?${serializeSearchParams(params)}`,
    )
}

/** The view the page is laid out for: none on a page with no search of its own, which has no view */
function pageView() {
    return Alpine.store(Const.state.currentView)?.view ?? null
}

/**
 * The view and the triage filter of the search the panel will run, which its "Search in" row opens
 * on. In Triage it is the Triage view, which holds no filter. In Browse it is the repository view
 * with the triage filter in force: the "Show triaged items" choice, or the pick of the search in
 * force. Anywhere else the search opens Browse, which holds no filter yet (a page with no search
 * has no store, and the Trash's holds none) and will be seeded the choice remembered in this
 * browser, so that choice is the one: for an account that sees triaged items alone, since a Guest
 * is refused the filter. A search of a folder (a folder's Search here) is Browse's from Triage
 * too, since a folder holds no triaged asset.
 */
function pickOfPanelSearch(filters) {
    const view = pageView()

    if (view === triage && !filters.folderId) {
        return { view: triage, triaged: null }
    }

    const inForce =
        view === repository
            ? Alpine.store(Const.state.searchParams).triaged
            : null

    return {
        view: repository,
        triaged:
            inForce ?? (context.seesTriaged() ? rememberedTriaged() : null),
    }
}

function quickSearchFormEl() {
    return document.getElementById("searchForm")
}

function filterButtonEl() {
    return document.querySelector(`[popovertarget="${PANEL_ID}"]`)
}

/**
 * Loads the panel's form for the search in force, with the quick search input's text as its text
 * and the filters of `openSearchPanel` over both, and with the view and the triage filter of the
 * search the panel will run (`pickOfPanelSearch`). The panel shows nothing meanwhile; a failure
 * closes it.
 */
async function loadForm(panelEl) {
    const token = ++loadToken
    const searchParams = Alpine.store(Const.state.searchParams)
    const filters = {
        ...searchParams?.filters(),
        q: quickSearchFormEl().elements.q.value.trim(),
        ...openingFilters,
    }
    Object.assign(filters, pickOfPanelSearch(filters))
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
        focusAtEnd(document.getElementById(DESCRIBE_ID))
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

/**
 * Puts focus in the text field with the caret after its text. A field filled in by its markup
 * takes focus with the caret before its text, where typing would go ahead of a description in
 * force instead of adding to it.
 */
function focusAtEnd(fieldEl) {
    if (!fieldEl) {
        return
    }

    fieldEl.focus()
    fieldEl.setSelectionRange(fieldEl.value.length, fieldEl.value.length)
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
 * The number of filters on the filter button, those in force or those the open panel's form holds:
 * its badge, shown from one up, and its accessible name
 */
function showFilterCount(count) {
    const buttonEl = filterButtonEl()
    const name = buttonEl.title

    buttonEl.querySelector(`[${Const.attributes.filterCount}]`).textContent =
        count > 0 ? count : ""
    buttonEl.setAttribute(
        "aria-label",
        count > 0
            ? `${name}: ${count} ${count === 1 ? "filter" : "filters"}`
            : name,
    )
}
