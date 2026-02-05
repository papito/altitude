export function highlightNav(elClass) {
    const fullClassName = `nav .menu.${elClass}`
    document.querySelector(fullClassName).classList.add("active")
}

export function selectTab(parentId, tabId) {
    htmx.findAll(`#${parentId} button[role='tab'`).forEach((tab) => {
        tab.removeAttribute("aria-selected")
    })

    htmx.find(`#${tabId}`).setAttribute("aria-selected", "True")
}
