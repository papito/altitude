/**
 * The search parameters' rules for the advanced search panel's "Search in" row
 * (`altitude/static/js/stores/search-params.js`): a pick of Library, Triage or All is the search's
 * view and triage filter, lasts for the search it was made for, and is never remembered. The
 * module loads behind the same stand-ins as in `search-params.test.mjs`. Run by `make test-js`.
 */
import { beforeEach, test } from "node:test"
import assert from "node:assert/strict"

const remembered = new Map()

globalThis.window = globalThis
Object.defineProperty(globalThis, "localStorage", {
    configurable: true,
    value: {
        getItem: (key) => remembered.get(key) ?? null,
        setItem: (key, value) => remembered.set(key, String(value)),
    },
})

const {
    applySearchFilters,
    applySearchParamChanges,
    createSearchParamsStore,
    seedSearchParams,
    serializeSearchParams,
} = await import("../../altitude/static/js/stores/search-params.js")

const KEY = "showTriaged"

/** The three picks of the row, as the panel's form holds them in its `view` and `triaged` inputs */
const LIBRARY = { view: "repository", triaged: "false" }
const ALL = { view: "repository", triaged: "true" }
const TRIAGE = { view: "triage", triaged: "" }

/** The search of an account that sees triaged items, a member, as a page load seeds it */
const seedMember = (search) => seedSearchParams(search, { seesTriaged: true })

/** A store seeded for an account that sees triaged items */
const memberStore = (search = "") => {
    const store = createSearchParamsStore()
    store.seedFromLocation(search, { seesTriaged: true })
    return store
}

/** The parameters a query string holds, by name */
const sent = (query) => Object.fromEntries(new URLSearchParams(query))

beforeEach(() => remembered.clear())

test("a pick is the search's view and triage filter: Library, All, or Triage, which holds no filter", () => {
    const browsing = seedMember("")

    assert.deepEqual(
        sent(serializeSearchParams(applySearchFilters(browsing, { q: "beach", ...LIBRARY }))),
        { view: "repository", q: "beach", triaged: "false" },
    )
    assert.deepEqual(
        sent(serializeSearchParams(applySearchFilters(browsing, { q: "beach", ...ALL }))),
        { view: "repository", q: "beach", triaged: "true" },
    )
    assert.deepEqual(
        sent(serializeSearchParams(applySearchFilters(browsing, { q: "beach", ...TRIAGE }))),
        { view: "triage", q: "beach" },
    )
})

test("a pick made in Triage for Library or All is Browse's search, with its filter in the URL", () => {
    remembered.set(KEY, "false")
    const inTriage = seedMember("?view=triage&subset=noLocation")

    const all = applySearchFilters(inTriage, { describe: "a cactus", ...ALL })

    assert.equal(
        serializeSearchParams(all),
        "view=repository&describe=a+cactus&triaged=true&sort=similarity",
    )
    // Browse opens on the URL's filter, which wins over the remembered choice
    assert.equal(seedMember(`?${serializeSearchParams(all)}`).triaged, "true")
})

test("a description searches Triage most alike first, under the grouping Triage keeps", () => {
    const store = memberStore("?view=triage&groupBy=sequence")

    store.replaceFilters({ describe: "a cactus", personId: "ann", ...TRIAGE })

    assert.equal(
        store.toQueryString(),
        "view=triage&personId=ann&describe=a+cactus&sort=similarity&groupBy=sequence",
    )
})

test("a pick is not remembered, and lasts until its search is replaced", () => {
    const store = memberStore()
    assert.equal(store.triaged, "true")

    store.replaceFilters({ q: "beach", takenFrom: "2024-06-01", ...LIBRARY })
    assert.equal(store.triaged, "false")
    assert.equal(remembered.has(KEY), false)

    // What reorders or lays out the search keeps it
    store.merge({ sort: "filename0", layout: "map", groupBy: "dateTaken" })
    store.merge({ rpp: 100 })
    assert.equal(store.triaged, "false")

    for (const elsewhere of [
        { folderId: "trips" },
        { personId: "ann" },
        { albumId: "best" },
        { locationId: "lisbon" },
        { personId: "" },
        { q: "sunset" },
        { q: "" },
    ]) {
        const next = applySearchParamChanges(store.snapshot(), elsewhere)

        assert.equal(next.triaged, "true", JSON.stringify(elsewhere))
    }

    store.merge({ folderId: "trips" })
    assert.equal(store.triaged, "true")
    assert.equal(remembered.has(KEY), false)
})

test("the remembered choice comes back as the one last made with the switch", () => {
    remembered.set(KEY, "false")
    const store = memberStore()

    store.replaceFilters({ describe: "a cactus", ...ALL })
    assert.equal(store.triaged, "true")

    store.merge({ q: "beach" })
    assert.equal(store.triaged, "false")
    assert.equal(remembered.get(KEY), "false")
})

test("the switch turned during a picked search changes the search and is remembered", () => {
    const store = memberStore()

    store.replaceFilters({ q: "beach", takenFrom: "2024-06-01", ...LIBRARY })
    store.merge({ triaged: "true" })
    assert.equal(store.triaged, "true")
    assert.equal(remembered.get(KEY), "true")
    assert.equal(store.q, "beach")

    store.merge({ triaged: "false" })
    assert.equal(remembered.get(KEY), "false")

    store.merge({ folderId: "trips" })
    assert.equal(store.triaged, "false")
})

test("a chip removed from a filtered search keeps its view and its pick, and the last one ends it", () => {
    const store = memberStore()
    store.replaceFilters({ q: "beach", takenFrom: "2024-06-01", ...LIBRARY })

    // A chip's × searches by the other chips' filters, with no pick
    store.replaceFilters({ q: "beach" })
    assert.equal(store.triaged, "false")
    assert.equal(store.q, "beach")

    store.replaceFilters({})
    assert.equal(store.triaged, "true")
    assert.equal(store.toQueryString(), "view=repository&triaged=true")

    const inTriage = memberStore("?view=triage&describe=a+cactus&q=beach")
    inTriage.replaceFilters({ q: "beach" })
    assert.equal(inTriage.toQueryString(), "view=triage&q=beach")
    inTriage.replaceFilters({})
    assert.equal(inTriage.toQueryString(), "view=triage")
})

test("a URL's triage filter is its search's: a place puts the remembered choice back", () => {
    remembered.set(KEY, "true")
    const store = memberStore("?q=beach&triaged=false")
    assert.equal(store.triaged, "false")

    store.merge({ folderId: "trips" })

    assert.equal(store.triaged, "true")
})

test("quick search text searches the view it is typed in, and leaves the Trash for Browse", () => {
    assert.equal(
        applySearchParamChanges(seedMember("?view=triage&personId=ann"), { q: "beach" }).view,
        "triage",
    )
    assert.equal(
        applySearchParamChanges(seedMember("?view=trashbin"), { q: "beach" }).view,
        "repository",
    )
    assert.equal(
        applySearchParamChanges(seedMember("?folderId=trips"), { q: "beach" }).view,
        "repository",
    )

    // Emptied, the text leaves the whole of its view
    const cleared = applySearchParamChanges(seedMember("?view=triage&q=beach"), { q: "" })
    assert.equal(serializeSearchParams(cleared), "view=triage")
})

test("a search from the panel never searches the Trash, with a pick or without one", () => {
    const inTrash = seedMember("?view=trashbin")

    assert.equal(applySearchFilters(inTrash, { folderId: "trips" }).view, "repository")
    assert.equal(applySearchFilters(inTrash, { folderId: "trips", ...TRIAGE }).view, "triage")
})

test("a Guest's panel carries no pick: its search is the repository view's, with no filter", () => {
    const guest = seedSearchParams("?q=beach")

    const searched = applySearchFilters(guest, { q: "beach", takenFrom: "2024-06-01" })
    assert.equal(searched.triaged, null)
    assert.equal(
        serializeSearchParams(searched),
        "view=repository&takenFrom=2024-06-01&q=beach",
    )

    remembered.set(KEY, "true")
    assert.equal(applySearchParamChanges(searched, { folderId: "trips" }).triaged, null)
    assert.equal(applySearchFilters(searched, {}).triaged, null)
})
