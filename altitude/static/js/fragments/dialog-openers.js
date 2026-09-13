/**
 * Buttons that open a dialog the page already holds, with no request: `data-app-open-dialog`
 * names a `<template>` whose content is a `data-app-fragment="modal"` dialog (the purge
 * confirmation in `htmx/trashbin_header.scala.html`). A click copies the template into the modal
 * host, wires it for htmx, and hydrates it exactly as a dialog fetched from the server would be, so
 * it opens, submits, closes, and announces its success event through the same code.
 */
export function bindDialogOpeners({ root, hydrate }) {
    openerElements(root).forEach((openerEl) => {
        if (openerEl.dataset.appOpenDialogBound === "true") {
            return
        }

        openerEl.dataset.appOpenDialogBound = "true"

        openerEl.addEventListener("click", () => {
            const templateEl = document.querySelector(
                openerEl.dataset.appOpenDialog,
            )
            const hostEl = document.getElementById("modalContent")

            if (!(templateEl instanceof HTMLTemplateElement) || !hostEl) {
                console.warn("Dialog template or modal host not found")
                return
            }

            hostEl.replaceChildren(templateEl.content.cloneNode(true))
            htmx.process(hostEl)
            hydrate(hostEl)
        })
    })
}

function openerElements(root) {
    if (!(root instanceof Element || root instanceof Document)) {
        return []
    }

    const selector = "[data-app-open-dialog]"
    const elements = []

    if (root instanceof Element && root.matches(selector)) {
        elements.push(root)
    }

    elements.push(...root.querySelectorAll(selector))

    return elements
}
