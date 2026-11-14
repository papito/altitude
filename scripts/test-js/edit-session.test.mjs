/**
 * The Edit panel's server sync (`altitude/static/js/edit/edit-session.js`) over a fake server: the writes go one at a
 * time, each on the head the one before answered; Undo picks its step when its turn comes; a 409 adopts the state it
 * carries and drops the stale writes; a failure reads the state again; the caller hears once the queue is empty. Run
 * by `make test-js`.
 */
import { test } from "node:test"
import assert from "node:assert/strict"

import {
    createEditSession,
    headOf,
    SaveStatus,
} from "../../altitude/static/js/edit/edit-session.js"
import { defaults, normalize } from "../../altitude/static/js/edit/recipe.js"

/**
 * A server holding an Edit's History as `EditController` does: steps newest first, the Original last; every write
 * resolves when the test lets it (`release`), so the test sees what is in flight
 */
function fakeServer() {
    const steps = [{ seq: 0, label: "Original", recipe: defaults() }]
    let head = 0
    const sent = []
    const pending = []

    function state() {
        return {
            head,
            recipe: steps.find((s) => s.seq === head).recipe,
            steps: [...steps]
                .sort((a, b) => b.seq - a.seq)
                .map(({ seq, label }) => ({ seq, label, mine: true })),
        }
    }

    function answer(request, apply) {
        sent.push(request)
        return new Promise((resolve, reject) => {
            pending.push(() => {
                if (request.base !== undefined && request.base !== head) {
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
        steps,
        get head() {
            return head
        },
        set head(value) {
            head = value
        },
        state,
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
            addStep: ({ base, recipe }) =>
                answer({ kind: "step", base, recipe }, () => {
                    // A step after an undo drops the steps after the head
                    for (let i = steps.length - 1; i >= 0; i--) {
                        if (steps[i].seq > head) {
                            steps.splice(i, 1)
                        }
                    }
                    head += 1
                    steps.push({ seq: head, label: `Step ${head}`, recipe })
                }),
            moveHead: ({ base, seq }) =>
                answer({ kind: "head", base, seq }, () => {
                    head = seq
                }),
        },
    }
}

const exposure = (value) => normalize({ ...defaults(), exposure: value })

test("Changes are shown at once and saved one at a time, each on the head the last answered", async () => {
    const server = fakeServer()
    const saved = []
    const session = createEditSession({
        assetId: "a",
        api: server.api,
        onSaved: (state) => saved.push(state.head),
    })
    session.load()
    await server.release()
    assert.equal(session.snapshot().loaded, true)

    session.commit(exposure(0.5))
    session.commit(exposure(1))
    assert.equal(session.snapshot().recipe.exposure, 1)
    assert.equal(session.snapshot().status, SaveStatus.saving)
    // One request at a time
    assert.equal(server.inFlight, 1)
    await server.release()
    // The first answer does not take the later change off the screen
    assert.equal(session.snapshot().recipe.exposure, 1)
    await server.release()

    assert.deepEqual(
        server.sent.filter((r) => r.kind === "step").map((r) => r.base),
        [0, 1],
    )
    assert.equal(session.snapshot().head, 2)
    assert.equal(session.snapshot().status, SaveStatus.saved)
    assert.deepEqual(saved, [2])
})

test("The same Edit twice is one step", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    assert.equal(session.commit(exposure(0.5)), true)
    assert.equal(session.commit(exposure(0.5)), false)
    await server.release()
    assert.equal(server.sent.length, 1)
})

test("Undo picks its step when its turn comes, so a change still on its way is the one undone", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.load()
    await server.release()
    session.commit(exposure(0.5))
    await server.release()

    session.commit(exposure(1))
    assert.equal(session.snapshot().canUndo, true)
    session.undo()
    await server.release()
    await server.release()

    const move = server.sent.find((r) => r.kind === "head")
    assert.deepEqual(move, { kind: "head", base: 2, seq: 1 })
    assert.equal(session.snapshot().recipe.exposure, 0.5)
    assert.equal(session.snapshot().canRedo, true)

    session.redo()
    await server.release()
    assert.equal(session.snapshot().head, 2)
    assert.equal(session.snapshot().recipe.exposure, 1)

    session.jump(0)
    await server.release()
    assert.equal(session.snapshot().head, 0)
    assert.equal(session.snapshot().recipe.exposure, 0)
    assert.equal(session.snapshot().canUndo, false)
})

test("A conflict adopts the state it carries, drops the stale writes and is reported", async () => {
    const server = fakeServer()
    let conflicts = 0
    const session = createEditSession({
        assetId: "a",
        api: server.api,
        onConflict: () => conflicts++,
    })
    session.load()
    await server.release()

    // Another tab saves a step meanwhile
    server.steps.push({ seq: 1, label: "Elsewhere", recipe: exposure(2) })
    server.head = 1

    session.commit(exposure(0.5))
    session.commit(exposure(1))
    await server.release()

    assert.equal(conflicts, 1)
    assert.equal(server.inFlight, 0)
    assert.equal(session.snapshot().head, 1)
    assert.equal(session.snapshot().recipe.exposure, 2)
    // Nothing of this tab's was saved: the footer says the Edit shown is the one changed elsewhere
    assert.equal(session.snapshot().status, SaveStatus.conflict)
})

test("A failure reads the state again and is reported", async () => {
    const server = fakeServer()
    let failures = 0
    let fail = true
    const api = {
        ...server.api,
        addStep: (request) =>
            fail
                ? Promise.reject(new Error("offline"))
                : server.api.addStep(request),
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

    fail = false
    session.commit(exposure(0.5))
    await server.release()
    assert.equal(session.snapshot().status, SaveStatus.saved)
})

test("A change while an Undo is on its way is refused, so the Undo is not reversed by a step built before it", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.load()
    await server.release()
    session.commit(exposure(0.5))
    await server.release()

    session.undo()
    assert.equal(session.snapshot().moving, true)
    // Built on the Edit before the Undo answered
    assert.equal(session.commit(exposure(0.8)), false)
    await server.release()

    assert.equal(server.inFlight, 0)
    assert.deepEqual(
        server.sent.filter((r) => r.kind !== "load").map((r) => r.kind),
        ["step", "head"],
    )
    assert.equal(session.snapshot().head, 0)
    assert.equal(session.snapshot().recipe.exposure, 0)
    assert.equal(session.snapshot().moving, false)
    // Once the Undo answered, a change is a step after it again
    assert.equal(session.commit(exposure(0.8)), true)
    await server.release()
    assert.equal(session.snapshot().head, 1)
    assert.equal(session.snapshot().recipe.exposure, 0.8)
})

test("Redo right behind an Undo still on its way goes back to the step undone", async () => {
    const server = fakeServer()
    const session = createEditSession({ assetId: "a", api: server.api })
    session.load()
    await server.release()
    session.commit(exposure(0.5))
    await server.release()

    session.undo()
    assert.equal(session.snapshot().canRedo, true)
    session.redo()
    await server.release()
    await server.release()

    assert.deepEqual(
        server.sent.filter((r) => r.kind === "head").map((r) => r.seq),
        [0, 1],
    )
    assert.equal(session.snapshot().head, 1)
    assert.equal(session.snapshot().recipe.exposure, 0.5)
})

test("A failed read of the History is reported as a read, and says nothing failed to save", async () => {
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

test("The head's state leaves the History out", () => {
    assert.deepEqual(headOf({ head: 1, rev: 2, steps: [] }), {
        head: 1,
        rev: 2,
    })
})
