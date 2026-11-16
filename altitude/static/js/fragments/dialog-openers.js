/**
 * Buttons that open a dialog the page already holds, with no request: `data-app-open-dialog` names
 * a `<template>` of a modal dialog. A click copies it into the modal host and hydrates it as a
 * fetched dialog would be, so it behaves the same.
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
