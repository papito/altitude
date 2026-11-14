/**
 * The Edit panel's server sync (`altitude/static/js/edit/edit-session.js`) over a fake server: the saves go one at a
 * time, each on the version the one before answered, and a save waiting behind another is replaced by a later change;
 * Undo and Redo are the browser's, each an ordinary save; a 409 adopts the state it carries, drops the stale save and
 * forgets Undo and Redo; a failure reads the state again; a discard puts the server back where it was confirmed; the
 * caller hears once the queue is empty. Run by `make test-js`.
 */
import { test } from "node:test"
import assert from "node:assert/strict"

import {
    createEditSession,
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
    const kept = []

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
        kept,
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
            saveOnHide: (request) => kept.push(request),
        },
    }
}

const exposure = (value) => normalize({ ...defaults(), exposure: value })

const sentSaves = (server) =>
    server.sent
        .filter((r) => r.kind === "save")
        .map((r) => [r.base, r.recipe.exposure])

test("Changes are shown at once and saved one at a time, each on the version the last answered", async () => {
    const server = fakeServer()
    const saved = []
    const session = createEditSession({
        assetId: "a",
        api: server.api,
        onSaved: (state) => saved.push(state.editVersion),
    })
    session.load()
    await server.release()
    assert.equal(session.snapshot().loaded, true)

    session.commit(exposure(0.5))
    session.commit(exposure(1))
    session.commit(exposure(1.5))
    assert.equal(session.snapshot().recipe.exposure, 1.5)
    assert.equal(session.snapshot().status, SaveStatus.saving)
    assert.equal(session.snapshot().unsaved, true)
    // One request at a time
    assert.equal(server.inFlight, 1)
    await server.release()
    // The first answer does not take the later change off the screen
    assert.equal(session.snapshot().recipe.exposure, 1.5)
    await server.release()

    // The changes waiting behind the first save are one save, of the latest
    assert.deepEqual(sentSaves(server), [
        [0, 0.5],
        [1, 1.5],
    ])
    assert.equal(session.snapshot().editVersion, 2)
    assert.equal(session.snapshot().status, SaveStatus.saved)
    assert.equal(session.snapshot().unsaved, false)
    assert.deepEqual(saved, [2])
})

test("The same Edit twice is one save", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    assert.equal(session.commit(exposure(0.5)), true)
    assert.equal(session.commit(exposure(0.5)), false)
    await server.release()
    assert.equal(server.sent.length, 1)
})

test("Undo and Redo show the Edits before and after the one shown, each saved as a change", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    assert.equal(session.snapshot().canUndo, false)
    session.commit(exposure(0.5))
    await server.release()
    session.commit(exposure(1))
    await server.release()

    assert.equal(session.undo(), true)
    assert.equal(session.snapshot().recipe.exposure, 0.5)
    assert.equal(session.snapshot().canRedo, true)
    await server.release()
    assert.equal(session.undo(), true)
    assert.equal(session.snapshot().recipe.exposure, 0)
    assert.equal(session.snapshot().canUndo, false)
    assert.equal(session.undo(), false)
    await server.release()

    assert.equal(session.redo(), true)
    await server.release()
    assert.equal(session.snapshot().recipe.exposure, 0.5)
    assert.equal(server.recipe.exposure, 0.5)
    assert.deepEqual(sentSaves(server), [
        [0, 0.5],
        [1, 1],
        [2, 0.5],
        [3, 0],
        [4, 0.5],
    ])

    // A change after an Undo drops what Redo had
    session.commit(exposure(2))
    assert.equal(session.snapshot().canRedo, false)
    assert.equal(session.snapshot().canUndo, true)
})

test("An Undo behind a save still on its way is saved after it, and shown at once", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.commit(exposure(0.5))
    session.undo()
    assert.equal(session.snapshot().recipe.exposure, 0)
    await server.release()
    // The answer to the change does not put it back on screen: the Undo is waiting
    assert.equal(session.snapshot().recipe.exposure, 0)
    await server.release()

    assert.deepEqual(sentSaves(server), [
        [0, 0.5],
        [1, 0],
    ])
    assert.equal(server.editVersion, 2)
    assert.equal(session.snapshot().canRedo, true)
})

test("A conflict adopts the state it carries, drops the stale save, forgets Undo and Redo and is reported", async () => {
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

    session.commit(exposure(0.5))
    session.commit(exposure(1))
    await server.release()

    assert.equal(conflicts, 1)
    assert.equal(server.inFlight, 0)
    assert.equal(session.snapshot().editVersion, 1)
    assert.equal(session.snapshot().recipe.exposure, 2)
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
    session.commit(exposure(0.5))
    await new Promise((resolve) => setImmediate(resolve))
    // The state is read again
    assert.equal(server.sent.at(-1).kind, "load")
    await server.release()

    assert.equal(failures, 1)
    assert.equal(session.snapshot().status, SaveStatus.failed)
    assert.equal(session.snapshot().recipe.exposure, 0)
    assert.equal(session.snapshot().canUndo, false)

    fail = false
    session.commit(exposure(0.5))
    await server.release()
    assert.equal(session.snapshot().status, SaveStatus.saved)
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

test("A discard drops the save waiting, and saves the confirmed Edit again behind a save on its way", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.commit(exposure(0.5))
    await server.release()

    session.commit(exposure(1))
    session.commit(exposure(1.5))
    session.discard()
    assert.equal(session.snapshot().recipe.exposure, 0.5)
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
    session.commit(exposure(0.5))
    session.settled().then(() => (settled = true))
    await new Promise((resolve) => setImmediate(resolve))
    assert.equal(settled, false)
    await server.release()
    assert.equal(settled, true)
})

test("A change left as the page goes is sent to outlive it, behind a save on its way on the version that save will answer", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.commit(exposure(0.5))
    // Behind the save on its way, which will answer edit version 1
    assert.equal(session.saveOnHide(exposure(1)), true)
    assert.deepEqual(
        [server.kept[0].base, server.kept[0].recipe.exposure],
        [1, 1],
    )
    await server.release()

    // Nothing on its way: built on the edit version answered, and the Edit the server holds is not sent again
    assert.equal(session.saveOnHide(exposure(0.5)), false)
    assert.equal(session.saveOnHide(exposure(1.5)), true)
    assert.deepEqual(
        [server.kept[1].base, server.kept[1].recipe.exposure],
        [1, 1.5],
    )
    // With no change waiting, nothing is left to send
    assert.equal(session.saveOnHide(), false)
})

test("The save waiting behind the one on its way is what the page sends as it goes", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.commit(exposure(0.5))
    session.commit(exposure(1))
    assert.equal(session.saveOnHide(), true)
    assert.deepEqual(
        [server.kept[0].base, server.kept[0].recipe.exposure],
        [1, 1],
    )
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
