export function closeModal() {
    htmx.find("#modalContainer").style.display = "none"
    htmx.find("#imageDetailModalContainer").style.display = "none"
}

export function showModal({minWidthPx, title}) {
    htmx.find("#modalContainer .modal-title").innerText = title

    if (minWidthPx) {
        htmx.find("#modalContent").style.width = `${minWidthPx}px`
    }

    htmx.find("#modalContainer").style.display = "block"
}

export function showAssetDetailModal({title}) {
    htmx.find("#imageDetailModalContainer .modal-title").innerText = title
    htmx.find("#imageDetailModalContainer").style.display = "grid"
}
