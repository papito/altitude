
export function clearInnerNodes(node) {
    while (node.hasChildNodes()) {
        _clearNode(node.firstChild);
    }
}

function _clearNode(node) {
    while (node.hasChildNodes()) {
        _clearNode(node.firstChild);
    }
    node.parentNode.removeChild(node);
}

export function selectTab(parentId, tabId) {
    htmx.findAll(`#${parentId} button[role='tab'`).forEach(tab => {
        tab.removeAttribute("aria-selected")
    })

    htmx.find(`#${tabId}`).setAttribute("aria-selected", "True")
}
