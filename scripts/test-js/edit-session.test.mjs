/**
 * The Edit panel's server sync (`altitude/static/js/edit/edit-session.js`) over a fake server. Run by `make test-js`.
 */
import { test } from "node:test"
import assert from "node:assert/strict"

import {
    createEditSession,
    isNewerEditState,
    SaveStatus,
} from "../../altitude/static/js/edit/edit-session.js"
import { defaults, normalize } from "../../altitude/static/js/edit/recipe.js"

/**
 * A server holding an asset's Edit as `EditController` does: the Edit and its version, a save built on another version a
 * conflict; every request resolves when the test lets it (`release`), so the test sees what is in flight
 */
function fakeServer() {
    let version = 0
    let recipe = defaults()
    const sent = []
    const pending = []

    function state() {
        return { editVersion: version, recipe, edited: recipe.exposure !== 0 }
    }

    function answer(request, apply) {
        sent.push(request)
        return new Promise((resolve, reject) => {
            pending.push(() => {
                if (request.base !== undefined && request.base !== version) {
                    const error = new Error("conflict")
                    error.conflictState = state()
                    reject(error)
                    return
                }
                apply()
                resolve(state())
            })
        })
    }

    return {
        sent,
        get editVersion() {
            return version
        },
        get recipe() {
            return recipe
        },
        /** Another tab or account saves `edit` */
        savedElsewhere(edit) {
            version += 1
            recipe = edit
        },
        /** Answers the oldest request in flight, and lets the session's continuation run */
        async release() {
            pending.shift()()
            await new Promise((resolve) => setImmediate(resolve))
        },
        get inFlight() {
            return pending.length
        },
        api: {
            load: () => answer({ kind: "load" }, () => {}),
            save: ({ base, recipe: edit }) =>
                answer({ kind: "save", base, recipe: edit }, () => {
                    version += 1
                    recipe = edit
                }),
        },
    }
}

const exposure = (value) => normalize({ ...defaults(), exposure: value })

const sentSaves = (server) =>
    server.sent
        .filter((r) => r.kind === "save")
        .map((r) => [r.base, r.recipe.exposure])

/** A change made and saved at once: the change, then Done */
function commitAndDone(session, edit) {
    session.commit(edit)
    session.done()
}

test("A change is held, shown at once and saved by nothing until Done, which saves it once", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.load()
    await server.release()

    assert.equal(session.commit(exposure(0.5)), true)
    assert.equal(session.commit(exposure(0.5)), false)
    assert.equal(session.commit(exposure(1)), true)
    assert.equal(session.snapshot().recipe.exposure, 1)
    assert.equal(session.snapshot().pending, true)
    assert.equal(session.snapshot().unsaved, false)
    assert.equal(server.inFlight, 0)
    assert.deepEqual(sentSaves(server), [])

    assert.equal(session.done(), true)
    assert.equal(session.snapshot().pending, false)
    assert.equal(session.snapshot().unsaved, true)
    await server.release()
    assert.deepEqual(sentSaves(server), [[0, 1]])
    assert.equal(session.snapshot().status, SaveStatus.saved)
    // Nothing held: Done saves nothing
    assert.equal(session.done(), false)
    assert.equal(server.inFlight, 0)
})

test("The changes held are counted as steps of Undo since the last Done", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    assert.equal(session.snapshot().heldCount, 0)
    session.commit(exposure(0.5))
    session.commit(exposure(1))
    session.commit(exposure(1.5))
    assert.equal(session.snapshot().heldCount, 3)
    session.undo()
    assert.equal(session.snapshot().heldCount, 2)
    session.redo()
    assert.equal(session.snapshot().heldCount, 3)

    session.done()
    await server.release()
    assert.equal(session.snapshot().heldCount, 0)
    // Undo past the last Done is one change held, taking the Done back
    session.undo()
    assert.equal(session.snapshot().heldCount, 1)
    session.cancel()
    assert.equal(session.snapshot().heldCount, 0)

    // Changes that come back to the Edit saved are nothing held
    session.commit(exposure(2))
    session.commit(exposure(1.5))
    assert.equal(session.snapshot().pending, false)
    assert.equal(session.snapshot().heldCount, 0)
})

test("A change held back to the Edit saved is nothing pending", () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.commit(exposure(0.5))
    session.commit(exposure(0))
    assert.equal(session.snapshot().pending, false)
    assert.equal(session.done(), false)
    assert.equal(server.inFlight, 0)
})

test("Saves go one at a time, each on the version the last answered, and Dones waiting are one save", async () => {
    const server = fakeServer()
    const adopted = []
    const session = createEditSession({
        assetId: "a",
        api: server.api,
        onAdopted: (state) => adopted.push(state.editVersion),
    })
    session.load()
    await server.release()
    assert.equal(session.snapshot().loaded, true)

    commitAndDone(session, exposure(0.5))
    commitAndDone(session, exposure(1))
    commitAndDone(session, exposure(1.5))
    assert.equal(session.snapshot().recipe.exposure, 1.5)
    assert.equal(session.snapshot().status, SaveStatus.saving)
    assert.equal(session.snapshot().unsaved, true)
    // One request at a time
    assert.equal(server.inFlight, 1)
    await server.release()
    // The first answer does not take the later change off the screen
    assert.equal(session.snapshot().recipe.exposure, 1.5)
    await server.release()

    // The Dones waiting behind the first save are one save, of the latest
    assert.deepEqual(sentSaves(server), [
        [0, 0.5],
        [1, 1.5],
    ])
    assert.equal(session.snapshot().editVersion, 2)
    assert.equal(session.snapshot().status, SaveStatus.saved)
    assert.equal(session.snapshot().unsaved, false)
    // Each answer is handed over as it is adopted: the read's, then each save's
    assert.deepEqual(adopted, [0, 1, 2])
})

test("Undo and Redo step through the changes held, and Done folds them into one step", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    assert.equal(session.snapshot().canUndo, false)
    session.commit(exposure(0.5))
    session.commit(exposure(1))

    assert.equal(session.undo(), true)
    assert.equal(session.snapshot().recipe.exposure, 0.5)
    assert.equal(session.snapshot().canRedo, true)
    assert.equal(session.redo(), true)
    assert.equal(session.snapshot().recipe.exposure, 1)
    assert.deepEqual(sentSaves(server), [])

    session.done()
    await server.release()
    assert.deepEqual(sentSaves(server), [[0, 1]])
    assert.equal(session.snapshot().canRedo, false)
    // One Undo takes the whole Done back, held until the next Done
    assert.equal(session.undo(), true)
    assert.equal(session.snapshot().recipe.exposure, 0)
    assert.equal(session.snapshot().pending, true)
    assert.equal(session.snapshot().canUndo, false)
    assert.equal(server.inFlight, 0)

    // A change after an Undo drops what Redo had
    session.commit(exposure(2))
    assert.equal(session.snapshot().canRedo, false)
    assert.equal(session.snapshot().canUndo, true)
})

test("Undo past the last Done is held, and its Done saves it as a change Undo takes back", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    commitAndDone(session, exposure(0.5))
    await server.release()

    session.undo()
    assert.equal(session.snapshot().recipe.exposure, 0)
    session.done()
    await server.release()
    assert.deepEqual(sentSaves(server), [
        [0, 0.5],
        [1, 0],
    ])
    session.undo()
    assert.equal(session.snapshot().recipe.exposure, 0.5)
    assert.equal(session.snapshot().pending, true)
})

test("Cancel shows the Edit saved again, with Undo as it was at the last Done", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    commitAndDone(session, exposure(0.5))
    await server.release()

    session.commit(exposure(1))
    session.commit(exposure(2))
    session.undo()
    assert.equal(session.cancel(), true)
    assert.equal(session.snapshot().recipe.exposure, 0.5)
    assert.equal(session.snapshot().pending, false)
    assert.equal(session.snapshot().canRedo, false)
    assert.equal(session.snapshot().canUndo, true)
    assert.equal(server.inFlight, 0)
    // Undo is the Done's, back to the Edit before it
    session.undo()
    assert.equal(session.snapshot().recipe.exposure, 0)
    // Nothing held after a Cancel of the Undo: nothing to cancel
    session.cancel()
    assert.equal(session.cancel(), false)
})

test("An Undo behind a save still on its way is held and shown at once, and saved after it on Done", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    commitAndDone(session, exposure(0.5))
    session.undo()
    assert.equal(session.snapshot().recipe.exposure, 0)
    await server.release()
    // The answer to the Done does not put it back on screen: the Undo is held
    assert.equal(session.snapshot().recipe.exposure, 0)
    assert.equal(session.snapshot().pending, true)
    session.done()
    await server.release()

    assert.deepEqual(sentSaves(server), [
        [0, 0.5],
        [1, 0],
    ])
    assert.equal(server.editVersion, 2)
})

test("A conflict adopts the state it carries, drops the stale save and the changes held, forgets Undo and Redo and is reported", async () => {
    const server = fakeServer()
    let conflicts = 0
    const session = createEditSession({
        assetId: "a",
        api: server.api,
        onConflict: () => conflicts++,
    })
    session.load()
    await server.release()

    // Another tab saves meanwhile
    server.savedElsewhere(exposure(2))

    commitAndDone(session, exposure(0.5))
    commitAndDone(session, exposure(1))
    session.commit(exposure(1.5))
    await server.release()

    assert.equal(conflicts, 1)
    assert.equal(server.inFlight, 0)
    assert.equal(session.snapshot().editVersion, 1)
    assert.equal(session.snapshot().recipe.exposure, 2)
    assert.equal(session.snapshot().pending, false)
    assert.equal(session.snapshot().canUndo, false)
    // Nothing of this tab's was saved: the footer says the Edit shown is the one saved elsewhere
    assert.equal(session.snapshot().status, SaveStatus.conflict)
})

test("A failure reads the state again, forgets Undo and Redo, and is reported", async () => {
    const server = fakeServer()
    let failures = 0
    let fail = true
    const api = {
        ...server.api,
        save: (request) =>
            fail
                ? Promise.reject(new Error("offline"))
                : server.api.save(request),
    }
    const session = createEditSession({
        assetId: "a",
        api,
        onFailure: () => failures++,
    })
    commitAndDone(session, exposure(0.5))
    await new Promise((resolve) => setImmediate(resolve))
    // The state is read again
    assert.equal(server.sent.at(-1).kind, "load")
    await server.release()

    assert.equal(failures, 1)
    assert.equal(session.snapshot().status, SaveStatus.failed)
    assert.equal(session.snapshot().recipe.exposure, 0)
    assert.equal(session.snapshot().pending, false)
    assert.equal(session.snapshot().canUndo, false)

    fail = false
    commitAndDone(session, exposure(0.5))
    await server.release()
    assert.equal(session.snapshot().status, SaveStatus.saved)
})

test("A conflict's state and the state read again after a failure are handed over as adopted", async () => {
    const server = fakeServer()
    let fail = false
    const api = {
        ...server.api,
        save: (request) =>
            fail
                ? Promise.reject(new Error("offline"))
                : server.api.save(request),
    }
    const adopted = []
    const session = createEditSession({
        assetId: "a",
        api,
        onAdopted: (state) =>
            adopted.push([state.editVersion, state.recipe.exposure]),
    })

    // Another tab saves: the save built on version 0 is a conflict, whose state is handed over
    server.savedElsewhere(exposure(2))
    commitAndDone(session, exposure(0.5))
    await server.release()
    assert.deepEqual(adopted, [[1, 2]])

    // Another tab saves again, and this tab's next save fails: the state read again is handed over, the other tab's
    server.savedElsewhere(exposure(3))
    fail = true
    commitAndDone(session, exposure(1))
    await new Promise((resolve) => setImmediate(resolve))
    await server.release()
    assert.deepEqual(adopted, [
        [1, 2],
        [2, 3],
    ])
    assert.equal(session.snapshot().recipe.exposure, 3)

    // A read the caller asks for (the page shown again from the browser's cache) is handed over too
    server.savedElsewhere(exposure(4))
    session.load()
    await server.release()
    assert.deepEqual(adopted.at(-1), [3, 4])
})

test("A read keeps the changes held on screen, over the Edit it makes the one saved", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.commit(exposure(0.5))
    server.savedElsewhere(exposure(2))
    session.load()
    await server.release()
    assert.equal(session.snapshot().recipe.exposure, 0.5)
    assert.equal(session.snapshot().pending, true)
    session.cancel()
    assert.equal(session.snapshot().recipe.exposure, 2)
})

test("One Edit state is newer than another by its edit version, or by its render at the same one", () => {
    const state = (editVersion, renderedVersion = null) => ({
        editVersion,
        renderedVersion,
    })
    assert.equal(isNewerEditState(state(2), state(1, 1)), true)
    assert.equal(isNewerEditState(state(1, 1), state(2)), false)
    assert.equal(isNewerEditState(state(2, 2), state(2)), true)
    assert.equal(isNewerEditState(state(2), state(2, 2)), false)
    assert.equal(isNewerEditState(state(2, 2), state(2, 2)), false)
    assert.equal(isNewerEditState(state(2), state(2)), false)
    // An asset never edited: no state, edit version 0
    assert.equal(isNewerEditState(state(1), null), true)
    assert.equal(isNewerEditState(state(0), null), false)
})

test("Clearing Undo forgets the Edits of Undo and Redo and keeps the Edit shown", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.commit(exposure(0.5))
    session.commit(exposure(1))
    session.undo()
    session.clearUndo()

    assert.equal(session.snapshot().canUndo, false)
    assert.equal(session.snapshot().canRedo, false)
    assert.equal(session.snapshot().recipe.exposure, 0.5)
})

test("A discard drops the changes held and the save waiting, and saves the confirmed Edit again behind a save on its way", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    commitAndDone(session, exposure(0.5))
    await server.release()

    commitAndDone(session, exposure(1))
    commitAndDone(session, exposure(1.5))
    session.commit(exposure(2))
    session.discard()
    assert.equal(session.snapshot().recipe.exposure, 0.5)
    assert.equal(session.snapshot().pending, false)
    assert.equal(session.snapshot().canUndo, false)
    await server.release()
    await server.release()

    // 1 was on its way and landed; 1.5 was never sent; 0.5 is saved again after it
    assert.deepEqual(sentSaves(server), [
        [0, 0.5],
        [1, 1],
        [2, 0.5],
    ])
    assert.equal(server.recipe.exposure, 0.5)
    assert.equal(session.snapshot().unsaved, false)
})

test("Settled resolves once no write is waiting or on its way", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    let settled = false
    commitAndDone(session, exposure(0.5))
    session.settled().then(() => (settled = true))
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(settled, false)
    await server.release()
    assert.equal(settled, true)
})

test("A failed read is reported as a read, and says nothing failed to save", async () => {
    const server = fakeServer()
    const failures = []
    const session = createEditSession({
        assetId: "a",
        api: {
            ...server.api,
            load: () => Promise.reject(new Error("offline")),
        },
        onFailure: (error, kind) => failures.push(kind),
    })
    session.load()
    await new Promise((resolve) => setImmediate(resolve))
    assert.deepEqual(failures, ["load"])
    assert.equal(session.snapshot().status, SaveStatus.idle)
    assert.equal(session.snapshot().busy, false)
})

test("A session starts from the Edit the page carries", () => {
    const session = createEditSession({
        assetId: "a",
        initial: { editVersion: 3, recipe: exposure(0.5), rev: 2 },
        api: fakeServer().api,
    })
    assert.equal(session.snapshot().editVersion, 3)
    assert.equal(session.snapshot().recipe.exposure, 0.5)
    assert.equal(session.snapshot().state.rev, 2)
})
