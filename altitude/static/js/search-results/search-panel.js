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
 * The nav's advanced search panel: a native popover holding a server-rendered form, plus the
 * filter count on its button. The panel and the quick search form are `hx-preserve`, so they are
 * bound once and survive a nav reload. The form is fetched with axios rather than htmx so only the
 * latest opening's answer is shown; the panel is emptied as it closes.
 */

const PANEL_ID = "searchPanel"

// Focused once the form loads
const DESCRIBE_ID = "searchPanelDescribe"

// The latest load of the form; an earlier one's answer is dropped
let loadToken = 0

// Filters the next opening shows over the ones in force
let openingFilters = null

/** Binds once; a page without the nav binds nothing */
export function bindSearchPanel() {
    const panelEl = document.getElementById(PANEL_ID)

    if (!panelEl) {
        return
    }

    // `beforetoggle` fires on every opening, while a fast close and reopen make a single `toggle`
    panelEl.addEventListener("beforetoggle", (event) => {
        if (event.newState === "open") {
            loadForm(panelEl)
        }
    })

    // Closing drops unsearched changes, so the button counts the search in force again
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

/** 0 on a page with no search of its own */
function filterCountInForce() {
    return Alpine.store(Const.state.searchParams)?.filterCount() ?? 0
}

/**
 * A folder holds no triaged asset, so a Triage search drops the folder the hidden field may have
 * kept from another pick.
 */
export function searchOfForm(formEl) {
    const search = Object.fromEntries(new FormData(formEl))

    if (search.view === triage) {
        delete search.folderId
    }

    return search
}

/** Counts the filled fields as the store counts a search's; a form outside the panel counts nothing */
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
 * Opens the panel on the search in force with `filters` over it; an open panel reloads. A closing
 * panel returns focus to what had it on opening, and a menu entry is gone by then, so from inside
 * another popover focus moves to the filter button first.
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

/** Returns whether the panel was open */
export function closeOpenSearchPanel() {
    const panelEl = document.getElementById(PANEL_ID)

    if (!panelEl?.matches(":popover-open")) {
        return false
    }

    panelEl.hidePopover()
    return true
}

/**
 * Runs a search with `filters` replacing the ones in force: in place on the page's own view,
 * otherwise by navigating there. Filters without `view` (a removed chip) keep the view.
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

/** The view the page is laid out for; null on a page with no search of its own */
function pageView() {
    return Alpine.store(Const.state.currentView)?.view ?? null
}

/**
 * The view and triage filter the panel opens on. A folder's search goes to Browse even from Triage,
 * since a folder holds no triaged asset. Off Browse, the triage filter is the one remembered in this
 * browser, as Browse will be seeded with it.
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

/** The server fills the form and names its chips; the panel is empty meanwhile, and a failure closes it */
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

/** A prefilled field otherwise gets the caret first, so typing would go ahead of its text */
function focusAtEnd(fieldEl) {
    if (!fieldEl) {
        return
    }

    fieldEl.focus()
    fieldEl.setSelectionRange(fieldEl.value.length, fieldEl.value.length)
}

/** Its components go with the form, and a load still out is dropped */
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

/** The badge shows from one up; the accessible name carries the count too */
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
