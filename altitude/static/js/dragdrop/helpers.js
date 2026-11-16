export function dragged(event) {
    let target = event.target
    target.style.position = "relative"
    target.style.top = "auto"
    target.style.left = "auto"
    event.target.style.transform = "translate(0px, 0px)"

    // Without this reset, the next drag would resume from where this one ended
    event.target.removeAttribute("data-x")
    event.target.removeAttribute("data-y")

    const placeholder = document.querySelector(".drag-placeholder")
    if (placeholder) {
        placeholder.remove()
    }
}

export function dragMoveListener(event) {
    const target = event.target

    const x = (parseFloat(target.getAttribute("data-x")) || 0) + event.dx
    const y = (parseFloat(target.getAttribute("data-y")) || 0) + event.dy

    target.style.transform = "translate(" + x + "px, " + y + "px)"

    target.setAttribute("data-x", x)
    target.setAttribute("data-y", y)
}

/** The batch ops' Move button: it carries no asset ID, so its drop acts on the selection */
export function isBatchMoverElement(element) {
    return element.classList.contains("batch-mover")
}

// Fixed, the element can be dragged out of its container (between panels); a placeholder holds its place
export function setFixedPositionWhileDragging(event) {
    let target = event.target
    let position = target.getBoundingClientRect()

    target.style.position = "fixed"
    target.style.top = position.top + "px"
    target.style.left = position.left + "px"

    const placeholder = document.createElement("div")
    placeholder.classList.add("drag-placeholder")
    placeholder.style.width = position.width + "px"
    placeholder.style.height = position.height + "px"
    placeholder.style.margin = window.getComputedStyle(target).margin

    target.parentNode.insertBefore(placeholder, target)
}

/**
 * The drop-target highlighting every dropzone shares: `drop-active` on the zone during a compatible
 * drag, `drop-target` on it and `can-drop` on the dragged element while it hovers. `ondrop` runs after
 * the classes are cleared.
 */
export function dropzoneListeners(ondrop) {
    return {
        ondropactivate: (event) => {
            event.target.classList.add("drop-active")
        },

        ondragenter: (event) => {
            event.target.classList.add("drop-target")
            event.relatedTarget.classList.add("can-drop")
        },

        ondragleave: (event) => {
            event.target.classList.remove("drop-target")
            event.relatedTarget.classList.remove("can-drop")
        },

        ondrop: (event) => {
            event.target.classList.remove("drop-active", "drop-target")
            event.relatedTarget.classList.remove("can-drop")
            ondrop(event)
        },

        ondropdeactivate: (event) => {
            event.target.classList.remove("drop-active", "drop-target")
        },
    }
}
