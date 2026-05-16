export function closeModal() {
    const generalModal = htmx.find("#modalContainer")
    const imageModal = htmx.find("#imageDetailModalContainer")
    if (generalModal?.open) generalModal.close()
    if (imageModal?.open) imageModal.close()
}

export function showModal({ minWidthPx, title }) {
    htmx.find("#modalContainer .modal-title").innerText = title

    if (minWidthPx) {
        htmx.find("#modalContent").style.width = `${minWidthPx}px`
    }

    htmx.find("#modalContainer").showModal()
}

export function showAssetDetailModal({ title, width, height }) {
    htmx.find("#imageDetailModalContainer .modal-title").innerText = title

    const box = htmx.find("#imageDetailModalContainer .modal-box")
    if (width && height) {
        const maxW = window.innerWidth - 10
        const maxH = window.innerHeight - 40
        const scale = Math.min(1, maxW / width, maxH / height)
        box.style.width = `${Math.round(width * scale)}px`
        box.style.height = `${Math.round(height * scale) + 40}px` // +40 for toolbar
    } else {
        box.style.width = ""
        box.style.height = ""
    }

    htmx.find("#imageDetailModalContainer").showModal()
}

document.querySelectorAll(".close-modal").forEach((element) => {
    element.addEventListener("click", () => {
        closeModal()
    })
})
