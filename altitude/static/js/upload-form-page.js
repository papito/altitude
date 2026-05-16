import { hydrateUploadFormFragments } from "./fragments/upload-form.js"

function hydrateUploadForms(root = document) {
    hydrateUploadFormFragments(root)
}

hydrateUploadForms(document)

document.body.addEventListener("htmx:load", (event) => {
    hydrateUploadForms(event.detail?.elt ?? event.target)
})
