export function highlightNav(elClass) {
    const fullClassName = `nav .menu.${elClass}`
    document.querySelector(fullClassName).classList.add("active")
}

/** Marks `tabEl` as the selected tab of its tab list, and its siblings as not selected */
export function selectTab(tabEl) {
    tabEl
        .closest('[role="tablist"]')
        ?.querySelectorAll('[role="tab"]')
        .forEach((tab) => tab.setAttribute("aria-selected", "false"))

    tabEl.setAttribute("aria-selected", "true")
}
