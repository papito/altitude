export function highlightNav(view) {
    const el = document.querySelector(`nav [data-view="${view}"]`)
    if (el) {
        el.classList.add("active")
    }
}

export function selectTab(parentId, tabId) {
    // ot-tabs manages its own selected state; this is intentionally a no-op
}
