
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
