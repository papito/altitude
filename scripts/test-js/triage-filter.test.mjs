/**
 * The search parameters' rules for the triage filter (`altitude/static/js/stores/search-params.js`):
 * what Browse shows of the triaged items on a first visit, the choice the View settings dialog's
 * switch leaves in `localStorage`, and what a URL, a Guest and another view make of it. The store's
 * module is the browser's as it is, behind the stand-ins `search-params.test.mjs` describes. Run by
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
    createSearchParamsStore,
    rememberedTriaged,
    seedSearchParams,
    serializeSearchParams,
} = await import("../../altitude/static/js/stores/search-params.js")

const KEY = "showTriaged"

/** The search of an account that sees triaged items, a member, as a page load seeds it */
const seedMember = (search) => seedSearchParams(search, { seesTriaged: true })

/** A store seeded for an account that sees triaged items */
const memberStore = (search = "") => {
    const store = createSearchParamsStore()
    store.seedFromLocation(search, { seesTriaged: true })
    return store
}

beforeEach(() => remembered.clear())

test("Browse shows triaged items to a member with no remembered choice, and says so in its requests", () => {
    const params = seedMember("")

    assert.equal(params.triaged, "true")
    assert.equal(serializeSearchParams(params), "view=repository&triaged=true")
    assert.equal(memberStore().showsTriaged(), true)
})

test("the remembered choice is the one a URL that says nothing of the filter gets", () => {
    remembered.set(KEY, "false")

    const params = seedMember("?folderId=trips")

    assert.equal(params.triaged, "false")
    assert.equal(
        serializeSearchParams(params),
        "view=repository&folderId=trips&triaged=false",
    )
    assert.equal(memberStore().showsTriaged(), false)
})

test("a URL that names the filter wins for its page and leaves the remembered choice alone", () => {
    remembered.set(KEY, "false")
    assert.equal(memberStore("?personId=ann&triaged=true").triaged, "true")
    assert.equal(remembered.get(KEY), "false")

    remembered.clear()
    assert.equal(memberStore("?triaged=false").triaged, "false")
    assert.equal(remembered.has(KEY), false)
})

test("a URL whose filter the server would refuse gets the remembered choice", () => {
    for (const search of ["?triaged=", "?triaged=banana", "?triaged=TRUE"]) {
        assert.equal(seedMember(search).triaged, "true")
    }

    remembered.set(KEY, "false")
    assert.equal(seedMember("?triaged=banana").triaged, "false")
})

test("a remembered value that is neither choice is no choice: triaged items are shown", () => {
    remembered.set(KEY, "banana")

    assert.equal(rememberedTriaged(), "true")
    assert.equal(seedMember("").triaged, "true")
})

test("a Guest, who sees no triaged item, is seeded no filter whatever is remembered", () => {
    assert.equal(seedSearchParams("").triaged, null)
    assert.equal(serializeSearchParams(seedSearchParams("")), "view=repository")

    remembered.set(KEY, "true")
    assert.equal(seedSearchParams("?folderId=trips").triaged, null)
    assert.equal(seedSearchParams("?triaged=banana").triaged, null)

    const store = createSearchParamsStore()
    store.seedFromLocation("")
    assert.equal(store.showsTriaged(), false)
})

test("the filter is the repository view's: Triage and the Trash are seeded none and sent none", () => {
    remembered.set(KEY, "true")

    for (const view of ["triage", "trashbin"]) {
        const params = seedMember(`?view=${view}&triaged=true`)

        assert.equal(params.triaged, null)
        assert.equal(serializeSearchParams(params), `view=${view}`)
    }
})

test("a search that leaves the Trash for Browse is sent without the filter, which Browse then seeds", () => {
    remembered.set(KEY, "false")

    const leaving = applySearchParamChanges(seedMember("?view=trashbin"), {
        q: "beach",
    })
    assert.equal(serializeSearchParams(leaving), "view=repository&q=beach")

    assert.equal(
        seedMember(`?${serializeSearchParams(leaving)}`).triaged,
        "false",
    )
})

test("a search typed or described in Triage stays there, and holds no filter", () => {
    remembered.set(KEY, "false")

    const typed = applySearchParamChanges(seedMember("?view=triage"), {
        q: "beach",
    })
    assert.equal(serializeSearchParams(typed), "view=triage&q=beach")

    const described = applySearchFilters(seedMember("?view=triage"), {
        describe: "a cactus",
    })
    assert.equal(
        serializeSearchParams(described),
        "view=triage&describe=a+cactus",
    )
    assert.equal(seedMember("?view=triage&describe=a+cactus").triaged, null)
})

test("flipping the switch is remembered, and the next page load opens with it", () => {
    const store = memberStore()

    store.merge({ triaged: "false" })
    assert.equal(store.showsTriaged(), false)
    assert.equal(remembered.get(KEY), "false")
    assert.equal(store.toQueryString(), "view=repository&triaged=false")
    assert.equal(memberStore().triaged, "false")

    store.merge({ triaged: "true" })
    assert.equal(store.showsTriaged(), true)
    assert.equal(remembered.get(KEY), "true")
    assert.equal(memberStore("?albumId=best").triaged, "true")
})

test("a search that does not flip the switch remembers nothing", () => {
    const store = memberStore("?triaged=false")

    store.merge({ sort: "filename0", layout: "map" })
    store.replaceFilters({ personId: "ann,bob", takenFrom: "2024-06-01" })
    store.replaceId("personId", "ann", "cid")
    assert.equal(store.triaged, "false")

    store.merge({ folderId: "trips" })
    store.merge({ q: "beach" })
    store.replaceFilters({ q: "beach", view: "repository", triaged: "false" })

    assert.equal(remembered.has(KEY), false)
})

test("a value the server would refuse flips nothing and is not remembered", () => {
    const store = memberStore()

    store.merge({ triaged: "banana" })

    assert.equal(store.triaged, null)
    assert.equal(remembered.has(KEY), false)
})
