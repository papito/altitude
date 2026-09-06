import { Const } from "../constants.js"

export function dragged(event) {
    let target = event.target
    target.style.position = "relative"
    target.style.top = "auto"
    target.style.left = "auto"
    event.target.style.transform = "translate(0px, 0px)"

    // reset the position attributes for draggables (on failed drag, it will resume from the last position)
    event.target.removeAttribute("data-x")
    event.target.removeAttribute("data-y")

    // Remove the placeholder element
    const placeholder = document.querySelector(".drag-placeholder")
    if (placeholder) {
        placeholder.remove()
    }

    let imgElement = target.querySelector("img")
    if (imgElement) {
        imgElement.style.width =
            imgElement.getAttribute(Const.attributes.originalWidth) + "px"
        imgElement.removeAttribute(Const.attributes.originalWidth)
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

// Setting element as "fixed" will let us drag it outside the parent container boundaries (between panels)
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
 * The drop-target highlighting every dropzone shares: `drop-active` on the zone while a
 * compatible drag is in progress, `drop-target` on the zone and `can-drop` on the dragged element
 * while it hovers. `ondrop` runs after the classes are cleared.
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
