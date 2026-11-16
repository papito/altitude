/**
 * The Face corrections under the selected People row: Remove, and the Change picks of the combobox.
 * This module owns Remove's two clicks and the requests; `detail-panel.js` owns the selection and
 * what an answer changes on screen.
 *
 * Remove cannot be undone, so two clicks on the same Face are its only guard; a selection change
 * disarms it. A name or merge carries the Person the panel showed with the Face, which the server
 * checks, since the Face may have moved since. A 404 or 409 means the section is stale (the Face
 * moved, the asset recycled, the Person named meanwhile). One request at a time per Face.
 */
import { Const } from "../constants.js"
import { showErrorSnackBar } from "../common/snackbar.js"
import { allowHttpStatuses, getHttpErrorMessage, http } from "../http/client.js"

// A refused correction's statuses, each answered with a plain-text message
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

    /** Binds the page-wide listeners once */
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
                const {
                    faceId,
                    action,
                    personId,
                    sourcePersonId,
                    destPersonId,
                    name,
                } = event.detail

                if (action === "name") {
                    namePerson(faceId, sourcePersonId, name)
                } else if (action === "merge") {
                    mergePerson(faceId, sourcePersonId, destPersonId)
                } else {
                    move(faceId, personId ? { personId } : { name })
                }
            },
        )

        document.body.addEventListener(
            Const.events.faceChangeCancelled,
            (event) => cancelChange(event.detail.faceId),
        )
    }

    /** Disarms Remove and returns every row's combobox to its buttons */
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

    /** Deletes the Face and its files */
    function remove(faceId) {
        return send(faceId, () =>
            http.delete(`/htmx/asset/r/${context.getRepoId()}/face/${faceId}`, {
                validateStatus: allowHttpStatuses(REFUSED, ...STALE),
            }),
        )
    }

    /** Gives the Face to `target`, `{ personId }` or `{ name }` */
    function move(faceId, target) {
        return send(faceId, () => putPerson(faceId, "", target))
    }

    /** Names the Face's nameless Person, `personId`, as a whole */
    function namePerson(faceId, personId, name) {
        return send(
            faceId,
            () => putPerson(faceId, "/name", { personId, name }),
            { namedPersonId: personId },
        )
    }

    /** Merges the Face's nameless Person, `personId`, into the named `destPersonId` */
    function mergePerson(faceId, personId, destPersonId) {
        return send(faceId, () =>
            putPerson(faceId, "/merge", { personId, destPersonId }),
        )
    }

    function putPerson(faceId, subPath, body) {
        return http.put(
            `/htmx/asset/r/${context.getRepoId()}/face/${faceId}/person${subPath}`,
            body,
            { validateStatus: allowHttpStatuses(REFUSED, ...STALE) },
        )
    }

    /**
     * Sends one correction and settles its answer. `outcome` is what only the request knows (who was
     * named), merged with what the section says. The row's controls are disabled while it runs.
     */
    async function send(faceId, request, outcome = {}) {
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

            afterEdit({ ...parsePeopleSection(response.data), ...outcome })
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

/** The People section a correction answers with, adopted into this document, and what its root carries */
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
        mergedPersonId:
            sectionEl.getAttribute(Const.attributes.mergedPersonId) ?? null,
        mergedIntoId:
            sectionEl.getAttribute(Const.attributes.mergedIntoId) ?? null,
    }
}
