/**
 * The search parameters' rules for a description (`altitude/static/js/stores/search-params.js`): a
 * Describe search's `describe` beside the other parameters, what clears it, what a search that
 * holds one is sent without, and what comes back when it goes. The store's module is the browser's
 * as it is: `constants.js` publishes itself on `window`, and the store reads the remembered layout
 * from `localStorage`, so a stand-in for each is in place before the module is loaded. Run by
 * `make test-js`.
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
    countSearchFilters,
    createSearchParamsStore,
    searchFiltersOf,
    seedSearchParams,
    serializeSearchParams,
} = await import("../../altitude/static/js/stores/search-params.js")

beforeEach(() => remembered.clear())

/** The parameters a query string holds, by name */
const sent = (query) => Object.fromEntries(new URLSearchParams(query))

test("a description is a parameter of the search, serialized after the Search text", () => {
    const params = seedSearchParams("?describe=woman+in+a+park&q=beach")

    assert.equal(params.describe, "woman in a park")
    assert.equal(
        serializeSearchParams(params),
        "view=repository&q=beach&describe=woman+in+a+park",
    )
})

test("a reload keeps the description with the filters beside it", () => {
    const url =
        "view=repository&folderId=trips&personId=ann%2Cbob&locationId=lisbon&peopleMatch=any" +
        "&takenFrom=2024-06-01&takenTo=2024-06-30&importedFrom=2025-01-08&q=beach" +
        "&describe=woman+in+a+park&triaged=true&rpp=100"

    assert.equal(serializeSearchParams(seedSearchParams(`?${url}`)), url)
})

test("a description is kept without the white space around it, its line breaks as written", () => {
    assert.equal(
        seedSearchParams("?describe=%20%20a+cactus%0Ain+a+pot%20%0A").describe,
        "a cactus\nin a pot",
    )
})

test("a description the server would refuse is no description", () => {
    assert.equal(seedSearchParams("?describe=").describe, null)
    assert.equal(seedSearchParams("?describe=+%0A+").describe, null)
    assert.equal(
        seedSearchParams(`?describe=${"a".repeat(301)}`).describe,
        null,
    )
    assert.equal(
        seedSearchParams(`?describe=${"a".repeat(300)}`).describe,
        "a".repeat(300),
    )
})

test("a described search is sent without a sort, a grouping or the map layout, which the store keeps", () => {
    const params = seedSearchParams(
        "?describe=a+cactus&q=beach&sort=relevance&groupBy=dateTaken&groupDirection=asc&layout=map",
    )

    assert.deepEqual(sent(serializeSearchParams(params)), {
        view: "repository",
        q: "beach",
        describe: "a cactus",
    })
    assert.equal(params.sort, "relevance")
    assert.equal(params.groupBy, "dateTaken")
    assert.equal(params.groupDirection, "asc")
    assert.equal(params.layout, "map")
})

test("the sort, the grouping and the layout come back when the description goes", () => {
    const described = seedSearchParams(
        "?describe=a+cactus&folderId=trips&sort=filename0&groupBy=location&layout=map",
    )
    const { describe: _, ...rest } = searchFiltersOf(described)

    assert.deepEqual(
        sent(serializeSearchParams(applySearchFilters(described, rest))),
        {
            view: "repository",
            folderId: "trips",
            layout: "map",
            sort: "filename0",
            groupBy: "location",
        },
    )
})

test("a description searches the repository view by its filters alone: no trash, no Triage, no album, subset or map area", () => {
    for (const view of ["trashbin", "triage"]) {
        assert.equal(
            seedSearchParams(`?view=${view}&describe=a+cactus`).view,
            "repository",
        )
    }

    const params = seedSearchParams(
        "?describe=a+cactus&albumId=best&subset=noLocation&bbox=1,2,3,4&folderId=trips",
    )

    assert.deepEqual(sent(serializeSearchParams(params)), {
        view: "repository",
        folderId: "trips",
        describe: "a cactus",
    })
})

test("the triage filter and the page size stay beside a description", () => {
    const params = applySearchParamChanges(
        seedSearchParams("?describe=a+cactus"),
        { triaged: "true", rpp: 100 },
    )

    assert.deepEqual(sent(serializeSearchParams(params)), {
        view: "repository",
        describe: "a cactus",
        triaged: "true",
        rpp: "100",
    })
})

test("a place, a view or new quick search text clears the description with the panel's other filters", () => {
    const described = () =>
        seedSearchParams(
            "?describe=a+cactus&q=beach&takenFrom=2024-06-01&personId=ann,bob&peopleMatch=any",
        )
    const elsewhere = [
        { folderId: "trips" },
        { personId: "ann" },
        { albumId: "best" },
        { locationId: "lisbon" },
        { subset: "noLocation" },
        { personId: "" },
        { view: "triage" },
        { q: "sunset" },
        { q: "" },
    ]

    for (const change of elsewhere) {
        const next = applySearchParamChanges(described(), change)

        assert.equal(next.describe, null, JSON.stringify(change))
        assert.equal(next.takenFrom, null, JSON.stringify(change))
        assert.equal(next.peopleMatch, null, JSON.stringify(change))
    }
})

test("a sort, a grouping or a layout chosen meanwhile leaves the description in force", () => {
    const next = applySearchParamChanges(
        seedSearchParams("?describe=a+cactus"),
        { sort: "filename0", groupBy: "dateTaken", layout: "map" },
    )

    assert.equal(next.describe, "a cactus")
    assert.deepEqual(sent(serializeSearchParams(next)), {
        view: "repository",
        describe: "a cactus",
    })
})

test("a search from the panel sets the description with the other filters, and one left out is none", () => {
    const browsing = seedSearchParams("?view=triage&albumId=best&sort=filename0")
    const described = applySearchFilters(browsing, {
        describe: "  woman in a park ",
        q: "beach",
        folderId: "trips",
    })

    assert.deepEqual(searchFiltersOf(described), {
        q: "beach",
        folderId: "trips",
        personId: null,
        locationId: null,
        describe: "woman in a park",
        peopleMatch: null,
        takenFrom: null,
        takenTo: null,
        importedFrom: null,
        importedTo: null,
    })
    assert.deepEqual(sent(serializeSearchParams(described)), {
        view: "repository",
        folderId: "trips",
        q: "beach",
        describe: "woman in a park",
    })

    assert.equal(
        applySearchFilters(described, { folderId: "trips" }).describe,
        null,
    )
})

test("the filter button counts a description as one filter", () => {
    assert.equal(countSearchFilters(seedSearchParams("?describe=a+cactus")), 1)
    assert.equal(
        countSearchFilters(
            seedSearchParams("?describe=a+cactus&q=beach&takenFrom=2024-06-01"),
        ),
        3,
    )
    assert.equal(countSearchFilters(seedSearchParams("")), 0)
})

test("a continuation of a described search carries the description, the filters and its cursor, and no sort", () => {
    const params = seedSearchParams(
        "?describe=a+cactus&folderId=trips&sort=filename0&groupBy=dateTaken&layout=map",
    )
    // What infinite scroll sends for a grid rendered flat (js/search-results/infinite-scroll.js)
    const continuation = serializeSearchParams(params, {
        layout: "grid",
        groupBy: null,
        groupDirection: null,
        after: "cursor",
        isContinuousScroll: true,
    })

    assert.deepEqual(sent(continuation), {
        view: "repository",
        folderId: "trips",
        describe: "a cactus",
        after: "cursor",
        isContinuousScroll: "true",
    })
})

test("the store seeds, replaces and clears a description, and says where the results are laid out", () => {
    const store = createSearchParamsStore()
    store.seedFromLocation("?describe=a+cactus&layout=map&sort=filename0")

    assert.equal(store.filters().describe, "a cactus")
    assert.equal(store.filterCount(), 1)
    assert.equal(store.toQueryString(), "view=repository&describe=a+cactus")
    assert.equal(
        store.toQueryString({ after: "cursor", isContinuousScroll: true }),
        "view=repository&describe=a+cactus&after=cursor&isContinuousScroll=true",
    )
    // The map is kept for the search after it: a description's results are on the grid
    assert.equal(store.layout, "map")
    assert.equal(store.showsMap(), false)

    store.replaceFilters({ describe: "a man in a suit", q: "beach" })
    assert.equal(
        store.toQueryString(),
        "view=repository&q=beach&describe=a+man+in+a+suit",
    )

    store.merge({ q: "sunset" })
    assert.equal(store.describe, null)
    assert.equal(store.filterCount(), 1)
    assert.equal(store.showsMap(), true)
    assert.equal(
        store.toQueryString(),
        "view=repository&q=sunset&layout=map&sort=filename0",
    )
})
