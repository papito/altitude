/**
 * The Edit panel's server sync for one asset (`CONTEXT.md`: Edit, History): the head step and the Edit at it, the
 * History's steps, and a serial queue of writes, so each write carries as its `base` the head the one before it
 * answered (`EditController`: `POST …/steps {base, recipe}`, `PUT …/head {base, seq}`).
 *
 * - **Optimistic:** a change (`commit`) is the Edit shown at once, and saved behind the writes already queued. Undo,
 *   Redo and a jump from the History (`undo`, `redo`, `jump`) move the head on the server and show the Edit it answers
 *   with; Undo and Redo pick their step when their turn comes, from the head the writes before them left, so a change
 *   still on its way is the one undone.
 * - **The answer of the last write** is adopted whole: the head, the Edit at it (the server's normalization, which is
 *   the client's) and the steps, newest first, each labeled by the server.
 * - **A conflict** (a 409: the head moved elsewhere, another tab or another account) drops the writes still queued,
 *   which were built on the stale head, adopts the state the 409 carries and is reported (`onConflict`).
 * - **A failure** drops the writes still queued too, reads the state again so what is shown is what the server holds,
 *   and is reported (`onFailure`).
 * - **Idle again** after a write was saved, the session hands the state over (`onSaved`), so the caller can follow the
 *   render the server queues (`render-status.js`) and show the Edit everywhere else.
 *
 * DOM-free: the requests are the caller's (`api`), so the tests run it under Node (`scripts/test-js/edit-session.test.mjs`).
 */
import { normalize, sameRecipe } from "./recipe.js"

/** What the session's last writes came to, which the panel's footer says */
export const SaveStatus = Object.freeze({
    // Nothing written yet in this session
    idle: "idle",
    saving: "saving",
    saved: "saved",
    failed: "failed",
    // A write found the head moved elsewhere, and the session shows the Edit it moved to
    conflict: "conflict",
})

// The writes that move the head, after which the Edit shown is the one the server answers
const MOVES = ["undo", "redo", "jump"]

/**
 * A session over `assetId`'s Edit, starting from what the page knows of it (`initial`: the head and the Edit at it, as
 * Asset detail's `edit` carries them, or nothing for an asset never edited) until `load` reads its History.
 *
 * `api`, each a promise of the Edit's state (`EditController.stateJson`):
 * - `load()`: the state;
 * - `addStep({ base, recipe })`: a step after `base`;
 * - `moveHead({ base, seq })`: the head moved to `seq`.
 * A write refused because its base is stale rejects with an error carrying the current state as `conflictState`.
 *
 * `onConflict()` and `onFailure(error, kind)` report, `kind` the failed write's (`load` for the History's read);
 * `onSaved(state)` runs once the queue is empty after a write was saved. While a head move is queued or on its way
 * (`isMoving`), a change is refused (`commit` answers false): it would be built on the Edit before the move and saved on the
 * head after it, reversing the move.
 */
export function createEditSession({
    assetId,
    initial = null,
    api,
    onConflict = () => {},
    onFailure = () => {},
    onSaved = () => {},
}) {
    // The server's state as last answered: { head, recipe, steps, ... }; the steps are null until `load` answers
    let server = {
        head: initial?.head ?? 0,
        recipe: normalize(initial?.recipe ?? null),
        steps: null,
        ...(initial ?? {}),
    }
    // The Edit shown: the last change committed, or the server's
    let recipe = normalize(server.recipe)
    // The last Edit a step was queued for, so the same change twice is one step
    let lastQueued = recipe
    let status = SaveStatus.idle
    const queue = []
    let running = false
    // The write on its way
    let current = null
    let loaded = false
    // Whether a write was saved since the queue was last empty
    let savedSinceIdle = false
    const listeners = new Set()

    function snapshot() {
        return {
            assetId,
            recipe,
            head: server.head,
            steps: server.steps,
            state: server,
            status,
            loaded,
            busy: running || queue.length > 0,
            moving: isMoving(),
            canUndo: canUndo(),
            canRedo: canRedo(),
        }
    }

    function notify() {
        const shown = snapshot()
        listeners.forEach((listener) => listener(shown))
    }

    /** Calls `listener` with the session's snapshot on every change, and now; answers the unsubscription */
    function subscribe(listener) {
        listeners.add(listener)
        listener(snapshot())
        return () => listeners.delete(listener)
    }

    /** The index of the head among the steps, newest first, or -1 before they are read */
    function headIndex() {
        return server.steps?.findIndex((step) => step.seq === server.head) ?? -1
    }

    /** The writes queued or on their way */
    function pending() {
        return current ? [current, ...queue] : [...queue]
    }

    /** Whether a head move is queued or on its way */
    function isMoving() {
        return pending().some((op) => MOVES.includes(op.kind))
    }

    /**
     * Where the head will stand among the steps once the Undos and Redos pending are answered, each picking its step
     * when its turn comes; -1 before the steps are read
     */
    function projectedIndex() {
        const index = headIndex()
        if (index < 0) {
            return index
        }
        const moves = pending().reduce(
            (sum, op) =>
                sum + (op.kind === "undo" ? 1 : op.kind === "redo" ? -1 : 0),
            0,
        )
        return Math.min(server.steps.length - 1, Math.max(0, index + moves))
    }

    function canUndo() {
        if (pending().some((op) => op.kind === "step")) {
            return true
        }
        const index = projectedIndex()
        return index >= 0 && index < server.steps.length - 1
    }

    /** Redo, also behind an Undo still on its way; never after a change, which drops the steps after the head */
    function canRedo() {
        return (
            !pending().some((op) => op.kind === "step" || op.kind === "jump") &&
            projectedIndex() > 0
        )
    }

    function enqueue(op) {
        queue.push(op)
        status = SaveStatus.saving
        notify()
        drain()
    }

    async function drain() {
        if (running) {
            return
        }
        running = true
        while (queue.length > 0) {
            const op = queue.shift()
            current = op
            try {
                const answered = await run(op)
                current = null
                if (answered) {
                    adopt(answered, { whole: queue.length === 0 })
                    savedSinceIdle = savedSinceIdle || op.kind !== "load"
                }
            } catch (error) {
                current = null
                await recover(error, op.kind)
                running = false
                // A change made while the session recovered is saved on the state it recovered to
                if (queue.length > 0) {
                    drain()
                } else {
                    notify()
                }
                return
            }
        }
        running = false
        if (status === SaveStatus.saving) {
            status = SaveStatus.saved
        }
        notify()
        if (savedSinceIdle) {
            savedSinceIdle = false
            onSaved(server)
        }
    }

    /** Sends one write, its base the head the server last answered; null for a move with nowhere to go */
    function run(op) {
        switch (op.kind) {
            case "load":
                return api.load()
            case "step":
                return api.addStep({ base: server.head, recipe: op.recipe })
            case "undo":
            case "redo": {
                const steps = server.steps ?? []
                const index = headIndex()
                const target =
                    op.kind === "undo" ? steps[index + 1] : steps[index - 1]
                return index >= 0 && target
                    ? api.moveHead({ base: server.head, seq: target.seq })
                    : Promise.resolve(null)
            }
            case "jump":
                return op.seq === server.head
                    ? Promise.resolve(null)
                    : api.moveHead({ base: server.head, seq: op.seq })
        }
        return Promise.resolve(null)
    }

    /**
     * Takes the server's state; `whole` (the last write's answer) also makes its Edit the one shown, while a later
     * change still queued keeps its own on screen
     */
    function adopt(state, { whole }) {
        server = { ...state, recipe: normalize(state.recipe) }
        loaded = loaded || Array.isArray(state.steps)
        if (whole) {
            recipe = server.recipe
            lastQueued = recipe
        }
        notify()
    }

    /**
     * A write of `kind` failed: the queue goes, and the server's state is shown, read again unless a conflict carried it.
     * A failed read of the History leaves the save status as it was, since nothing failed to be saved
     */
    async function recover(error, kind) {
        queue.length = 0
        savedSinceIdle = false
        if (error?.conflictState) {
            status = SaveStatus.conflict
            adopt(error.conflictState, { whole: true })
            onConflict()
            return
        }

        if (kind !== "load") {
            status = SaveStatus.failed
        } else if (status === SaveStatus.saving) {
            status = SaveStatus.idle
        }
        onFailure(error, kind)
        if (kind === "load") {
            recipe = server.recipe
            lastQueued = recipe
            notify()
            return
        }
        try {
            const state = await api.load()
            adopt(state, { whole: queue.length === 0 })
        } catch {
            // The server could not be read either: the last state it answered is shown
            recipe = server.recipe
            lastQueued = recipe
            notify()
        }
    }

    /** Reads the History (once, as the panel opens on the asset), behind any write already queued */
    function load() {
        queue.push({ kind: "load" })
        drain()
    }

    /**
     * The Edit changed by a control: shown at once, saved as one step; the same Edit as the last queued is nothing, and a
     * change while a head move is pending is refused
     */
    function commit(next) {
        if (isMoving()) {
            return false
        }
        const edit = normalize(next)
        if (sameRecipe(edit, lastQueued)) {
            recipe = edit
            return false
        }
        recipe = edit
        lastQueued = edit
        enqueue({ kind: "step", recipe: edit })
        return true
    }

    function undo() {
        if (canUndo()) {
            enqueue({ kind: "undo" })
        }
    }

    function redo() {
        if (canRedo()) {
            enqueue({ kind: "redo" })
        }
    }

    /** A jump from the History to the step `seq` (0, the Original) */
    function jump(seq) {
        enqueue({ kind: "jump", seq })
    }

    return {
        assetId,
        subscribe,
        snapshot,
        load,
        commit,
        undo,
        redo,
        jump,
        /** Whether a write is queued or on its way */
        isBusy: () => running || queue.length > 0,
        isMoving,
    }
}

/** The Edit at the head as Asset detail's `edit` carries it (`EditController.headJson`), from a state */
export function headOf(state) {
    const head = { ...state }
    delete head.steps
    return head
}
