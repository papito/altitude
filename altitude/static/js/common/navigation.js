export function selectTab(tabEl) {
    tabEl
        .closest('[role="tablist"]')
        ?.querySelectorAll('[role="tab"]')
        .forEach((tab) => tab.setAttribute("aria-selected", "false"))

    tabEl.setAttribute("aria-selected", "true")
}
