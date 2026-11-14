/**
 * The Edit panel's server sync for one asset (`CONTEXT.md`: Edit): the Edit the server keeps and its version, the
 * Undo and Redo of the panel's session, and a serial queue of writes, so each save carries as its `base` the version
 * the write before it answered (`EditController`: `PUT …/asset/:id {base, recipe}`).
 *
 * - **Optimistic:** a change (`commit`) is the Edit shown at once, and saved behind the write on its way. Saves waiting
 *   behind it are one: the server keeps only the latest Edit, so only the latest change waiting is sent.
 * - **Undo and Redo** are the browser's: the Edits before and after the one shown, kept while the session lasts (the
 *   caller clears them, `clearUndo`, as the panel closes or Asset detail steps away). An Undo or a Redo shows that Edit
 *   and saves it as any change.
 * - **The answer of the last write** is adopted whole: the version and the Edit (the server's normalization, which is
 *   the client's).
 * - **A conflict** (a 409: the Edit was saved elsewhere, another tab or another account) drops the save waiting, adopts
 *   the state the 409 carries, clears Undo and Redo, whose Edits were this session's, and is reported (`onConflict`).
 * - **A failure** drops the save waiting too, clears Undo and Redo, reads the state again so what is shown is what the
 *   server holds, and is reported (`onFailure`).
 * - **Every state adopted** (a save's answer, a read's, a conflict's, the read after a failure) is handed over
 *   (`onAdopted`), so the caller shows the Edit the server holds everywhere else and follows the render the server queues
 *   (`render-status.js`).
 *
 * DOM-free: the requests are the caller's (`api`), so the tests run it under Node (`scripts/test-js/edit-session.test.mjs`).
 */
import { normalize, sameRecipe } from "./recipe.js"

/**
 * Whether the Edit's state `a` is newer than `b` (either as `EditController.stateJson` gives it; `b` may be null, an asset
 * never edited): a later edit version, or the same one rendered where `b` is not
 */
export function isNewerEditState(a, b) {
    const version = b?.editVersion ?? 0
    return (
        a.editVersion > version ||
        (a.editVersion === version &&
            a.renderedVersion === a.editVersion &&
            b?.renderedVersion !== version)
    )
}

/** What the session's last writes came to, which the panel's footer says */
export const SaveStatus = Object.freeze({
    // Nothing written yet in this session
    idle: "idle",
    saving: "saving",
    saved: "saved",
    failed: "failed",
    // A save found the Edit saved elsewhere, and the session shows the Edit it was saved as
    conflict: "conflict",
})

/**
 * A session over `assetId`'s Edit, starting from what the page knows of it (`initial`: the version and the Edit, as Asset
 * detail's `edit` carries them, or nothing for an asset never edited) until `load` reads it again.
 *
 * `api`, each a promise of the Edit's state (`EditController.stateJson`):
 * - `load()`: the state;
 * - `save({ base, recipe })`: the Edit saved, built on the version `base`;
 * - `saveOnHide({ base, recipe })`: the same save, sent so that it outlives the page (`fetch`'s `keepalive`); its answer is
 *   not waited for, so a session the page shows again reads the Edit again (`load`).
 * A save refused because its base is stale rejects with an error carrying the current state as `conflictState`.
 *
 * `onConflict()` and `onFailure(error, kind)` report, `kind` the failed write's (`load` or `save`); `onAdopted(state)` runs
 * with each state of the server's the session adopts, as it adopts it.
 */
export function createEditSession({
    assetId,
    initial = null,
    api,
    onConflict = () => {},
    onFailure = () => {},
    onAdopted = () => {},
}) {
    // The server's state as last answered: { editVersion, recipe, ... }
    let server = {
        editVersion: initial?.editVersion ?? 0,
        ...(initial ?? {}),
        recipe: normalize(initial?.recipe ?? null),
    }
    // The Edit shown: the last change committed, or the server's
    let recipe = server.recipe
    // Undo and Redo: the Edits shown before the one shown, the latest last, and those undone, the latest undone last
    let past = []
    let future = []
    let status = SaveStatus.idle
    // The writes waiting: at most a read and a save, in the order they came; a later change replaces the save's Edit
    const queue = []
    // Whether the queue is being worked through (a write on its way, or the recovery from a failed one), and the write on
    // its way
    let running = false
    let current = null
    let loaded = false
    // Those waiting for the queue to be empty (`settled`)
    let idleWaiters = []
    const listeners = new Set()

    function busy() {
        return running || queue.length > 0
    }

    /** Whether a save is waiting or on its way: a change the server has not confirmed */
    function hasUnsaved() {
        return (
            current?.kind === "save" || queue.some((op) => op.kind === "save")
        )
    }

    function snapshot() {
        return {
            assetId,
            recipe,
            editVersion: server.editVersion,
            state: server,
            status,
            loaded,
            busy: busy(),
            unsaved: hasUnsaved(),
            canUndo: past.length > 0,
            canRedo: future.length > 0,
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

    /** Queues a save of `edit`, which replaces the Edit of a save already waiting */
    function enqueueSave(edit) {
        const waiting = queue.find((op) => op.kind === "save")
        if (waiting) {
            waiting.recipe = edit
        } else {
            queue.push({ kind: "save", recipe: edit })
        }
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
                adopt(answered, { whole: !hasUnsaved() })
            } catch (error) {
                current = null
                await recover(error, op.kind)
                running = false
                // A change made while the session recovered is saved on the state it recovered to
                if (queue.length > 0) {
                    drain()
                } else {
                    becameIdle()
                }
                return
            }
        }
        running = false
        if (status === SaveStatus.saving) {
            status = SaveStatus.saved
        }
        becameIdle()
    }

    function becameIdle() {
        notify()
        const waiters = idleWaiters
        idleWaiters = []
        waiters.forEach((resolve) => resolve())
    }

    /** Sends one write, a save's base the version the server last answered */
    function run(op) {
        return op.kind === "load"
            ? api.load()
            : api.save({ base: server.editVersion, recipe: op.recipe })
    }

    /**
     * Takes the server's state, and hands it over (`onAdopted`); `whole` (no save waiting) also makes its Edit the one shown,
     * while a later change still waiting keeps its own on screen
     */
    function adopt(state, { whole }) {
        server = { ...state, recipe: normalize(state.recipe) }
        loaded = true
        if (whole) {
            recipe = server.recipe
        }
        notify()
        onAdopted(server)
    }

    /**
     * A write of `kind` failed: the queue goes, so do Undo and Redo, and the server's state is shown, read again unless a
     * conflict carried it. A failed read leaves the save status as it was, since nothing failed to be saved
     */
    async function recover(error, kind) {
        queue.length = 0
        clearUndo()
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
            notify()
            return
        }
        try {
            adopt(await api.load(), { whole: !hasUnsaved() })
        } catch {
            // The server could not be read either: the last state it answered is shown
            recipe = server.recipe
            notify()
        }
    }

    /** Reads the Edit (as the panel opens on the asset), behind any write already queued */
    function load() {
        queue.push({ kind: "load" })
        drain()
    }

    /** Shows `edit` and saves it, after the Edit shown went onto `onto` (Undo's or Redo's stack) */
    function show(edit, onto) {
        onto.push(recipe)
        recipe = edit
        enqueueSave(edit)
    }

    /**
     * The Edit changed by a control: shown at once and saved; the Edit shown before it is Undo's, and Redo's Edits go.
     * The Edit shown again is nothing (false).
     */
    function commit(next) {
        const edit = normalize(next)
        if (sameRecipe(edit, recipe)) {
            return false
        }
        future = []
        show(edit, past)
        return true
    }

    /** The Edit before the one shown, shown and saved; false with nothing to undo */
    function undo() {
        if (past.length === 0) {
            return false
        }
        show(past.pop(), future)
        return true
    }

    /** The Edit last undone, shown and saved; false with nothing to redo */
    function redo() {
        if (future.length === 0) {
            return false
        }
        show(future.pop(), past)
        return true
    }

    /** Undo and Redo forget their Edits: the panel closed, or Asset detail stepped away */
    function clearUndo() {
        if (past.length > 0 || future.length > 0) {
            past = []
            future = []
            notify()
        }
    }

    /**
     * The changes not confirmed are dropped: the save waiting is not sent, and a save on its way is followed by one of
     * the Edit the server last confirmed, so the server ends where it was. The Edit shown is that one again.
     */
    function discard() {
        const confirmed = server.recipe
        const waiting = queue.findIndex((op) => op.kind === "save")
        if (waiting >= 0) {
            queue.splice(waiting, 1)
        }
        if (current?.kind === "save") {
            queue.push({ kind: "save", recipe: confirmed })
        }
        recipe = confirmed
        clearUndo()
        if (!busy() && status === SaveStatus.saving) {
            status = SaveStatus.idle
        }
        notify()
    }

    /** Resolves once no write is waiting or on its way */
    function settled() {
        return busy()
            ? new Promise((resolve) => idleWaiters.push(resolve))
            : Promise.resolve()
    }

    /**
     * The page is going away (`pagehide`): the Edit not saved yet, `next` (a change waiting out its delay) or else the save
     * waiting behind the one on its way, is sent so that it outlives the page. It is built on the edit version the server last
     * answered, or, behind a save on its way, on the one that save is about to answer: should that save fail, the server
     * refuses this one as a conflict, which changes nothing. Nothing is sent while the session recovers from a failure, or for
     * the Edit the server holds. Answers whether it was sent; a page shown again from the browser's cache reads the Edit again
     * (`load`), as the session's edit version is then behind the server's.
     */
    function saveOnHide(next = null) {
        const waiting = queue.find((op) => op.kind === "save")
        const edit = next ? normalize(next) : waiting?.recipe
        const recovering = running && current === null
        if (!edit || recovering) {
            return false
        }
        const behindSave = current?.kind === "save"
        if (!behindSave && sameRecipe(edit, server.recipe)) {
            return false
        }
        api.saveOnHide({
            base: server.editVersion + (behindSave ? 1 : 0),
            recipe: edit,
        })
        return true
    }

    return {
        assetId,
        subscribe,
        snapshot,
        load,
        commit,
        undo,
        redo,
        clearUndo,
        discard,
        settled,
        saveOnHide,
        /** Whether a write is waiting or on its way */
        isBusy: busy,
        hasUnsaved,
    }
}
