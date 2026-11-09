/**
 * The Face corrections of Asset detail's People section (views/AGENTS.md **Detail panel**,
 * `docs/faces.md` **Corrections**): Remove and Change, offered under the selected row
 * (`htmx/detail_panel_people`). This module owns the two clicks of Remove, the request each
 * correction sends and what its answer means; `detail-panel.js`, which creates it, owns the
 * selection and what the swapped-in section changes on screen (`afterEdit`, `reloadSection`,
 * `cancelChange`).
 *
 * Remove cannot be undone, so the two clicks are its only guard: the first click arms the button,
 * whose label changes to the one it carries ("Remove (last click)"), and the second click, on the
 * same Face, sends `DELETE /htmx/asset/r/:repoId/face/:faceId`; a change of the selection disarms it
 * and puts the first label back. Change is the combobox's (`alpine/components/face-combobox.js`),
 * which announces a pick as `Const.events.facePersonPicked`; this module answers it with
 * `PUT /htmx/asset/r/:repoId/face/:faceId/person`, the body naming the Person or the new name.
 *
 * Either answers the asset's People section (200), which replaces the one shown, with the result
 * message and any deleted Person read from its root's data attributes; a 400 (the body, a reserved
 * name) is a message to show and nothing changes; a 404 (the Face gone or moved meanwhile) or a 409
 * (the asset recycled meanwhile) means the section is stale: the panel reloads and the server's
 * message is shown. One request at a time per Face, so a second pick during one is dropped.
 */
import { Const } from "../constants.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { allowHttpStatuses, getHttpErrorMessage, http } from "../http/client.js"

// The statuses the routes answer a refused correction with, each a plain-text message
const REFUSED = 400
const STALE = [404, 409]

export function createFaceCorrections({
    context,
    sectionEl,
    afterEdit,
    reloadSection,
    cancelChange,
}) {
    // The Face whose Remove was clicked once, with its button and the label to put back
    let armed = null
    // The Faces with a correction in flight
    const inFlight = new Set()

    /** Binds the page-wide listeners once: Remove's clicks, the combobox's pick and its X */
    function bind() {
        document.addEventListener("click", (event) => {
            const buttonEl = event.target.closest?.(
                `[${Const.attributes.faceRemove}]`,
            )
            if (buttonEl) {
                handleRemoveClick(buttonEl)
            }
        })

        document.body.addEventListener(
            Const.events.facePersonPicked,
            (event) => {
                const { faceId, personId, name } = event.detail
                move(faceId, personId ? { personId } : { name })
            },
        )

        document.body.addEventListener(
            Const.events.faceChangeCancelled,
            (event) => cancelChange(event.detail.faceId),
        )
    }

    /** The selection changed: Remove is disarmed, and every row's combobox returns to its buttons */
    function selectionChanged() {
        disarm()
        sectionEl()
            ?.querySelectorAll(".face-actions")
            .forEach((actionsEl) =>
                actionsEl.dispatchEvent(new CustomEvent("face-combobox-close")),
            )
    }

    function faceIdOf(el) {
        return el
            .closest(`[${Const.attributes.faceId}]`)
            ?.getAttribute(Const.attributes.faceId)
    }

    /** The first click arms Remove for its Face and shows the last label; the second click on the same Face removes */
    function handleRemoveClick(buttonEl) {
        const faceId = faceIdOf(buttonEl)
        if (!faceId || inFlight.has(faceId)) {
            return
        }

        if (armed?.faceId !== faceId) {
            disarm()
            armed = { faceId, buttonEl, firstLabel: buttonEl.textContent }
            buttonEl.textContent = buttonEl.getAttribute(
                Const.attributes.faceRemoveLastLabel,
            )
            return
        }

        disarm()
        remove(faceId)
    }

    function disarm() {
        if (armed?.buttonEl.isConnected) {
            armed.buttonEl.textContent = armed.firstLabel
        }
        armed = null
    }

    /** Remove: deletes the Face and its files; not undoable */
    function remove(faceId) {
        return send(faceId, () =>
            http.delete(`/htmx/asset/r/${context.getRepoId()}/face/${faceId}`, {
                validateStatus: allowHttpStatuses(REFUSED, ...STALE),
            }),
        )
    }

    /** Change: gives the Face to `target`, `{ personId }` or `{ name }` */
    function move(faceId, target) {
        return send(faceId, () =>
            http.put(
                `/htmx/asset/r/${context.getRepoId()}/face/${faceId}/person`,
                target,
                { validateStatus: allowHttpStatuses(REFUSED, ...STALE) },
            ),
        )
    }

    /**
     * Sends one correction of `faceId` and settles its answer: the People section for the panel to
     * swap in, a refusal's message, or a stale section to reload. The row's controls are disabled
     * while it runs.
     */
    async function send(faceId, request) {
        if (inFlight.has(faceId)) {
            return
        }
        inFlight.add(faceId)
        setBusy(faceId, true)

        try {
            const response = await request()

            if (response.status === REFUSED) {
                showErrorSnackBar(response.data)
                return
            }

            if (STALE.includes(response.status)) {
                reloadSection(response.data)
                return
            }

            afterEdit(parsePeopleSection(response.data))
        } catch (error) {
            console.error(
                `Error correcting a Face: ${getHttpErrorMessage(error)}`,
            )
            showErrorSnackBar("Could not change the face")
        } finally {
            inFlight.delete(faceId)
            setBusy(faceId, false)
        }
    }

    function setBusy(faceId, busy) {
        sectionEl()
            ?.querySelector(
                `[${Const.attributes.faceId}="${CSS.escape(faceId)}"]`,
            )
            ?.querySelectorAll(
                `[${Const.attributes.faceChange}], [${Const.attributes.faceRemove}]`,
            )
            .forEach((buttonEl) => {
                buttonEl.disabled = busy
            })
    }

    return { bind, selectionChanged }
}

/**
 * The People section a correction answers with (`htmx/detail_panel_people`): the section's element,
 * adopted into this document, with the result message and the deleted Person its root carries.
 */
export function parsePeopleSection(html) {
    const doc = new DOMParser().parseFromString(html, "text/html")
    const sectionEl = document.adoptNode(
        doc.getElementById("detailPanelPeople"),
    )

    return {
        sectionEl,
        message: sectionEl.getAttribute(Const.attributes.resultMessage) ?? "",
        deletedPersonId:
            sectionEl.getAttribute(Const.attributes.deletedPersonId) ?? null,
        deletedPersonMessage:
            sectionEl.getAttribute(Const.attributes.deletedPersonMessage) ??
            null,
    }
}
