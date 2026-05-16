import { Const } from "../constants.js"
import {
    dragged,
    dragMoveListener,
    setFixedPositionWhileDragging,
} from "../common/dragon-drop.js"

export function bindPeopleDragDrop({ dispatch }) {
    interact("#people .drag-drop, #person .drag-drop").draggable({
        inertia: true,
        autoScroll: { container: document.querySelector("#explorer") },

        listeners: {
            move: dragMoveListener,
            start: setFixedPositionWhileDragging,
            end: dragged,
        },
    })

    interact("#people .dropzone, #person.dropzone").dropzone({
        accept: "#person .drag-drop, #people .drag-drop",
        overlap: 0.75,

        ondropactivate: (event) => {
            event.target.classList.add("drop-active")
        },

        ondragenter: (event) => {
            const draggableElement = event.relatedTarget
            const dropzoneElement = event.target

            dropzoneElement.classList.add("drop-target")
            draggableElement.classList.add("can-drop")
        },

        ondragleave: (event) => {
            event.target.classList.remove("drop-target")
            event.relatedTarget.classList.remove("can-drop")
        },

        ondrop: (event) => {
            const draggableElement = event.relatedTarget
            const dropzoneElement = event.target

            dropzoneElement.classList.remove("drop-active")
            dropzoneElement.classList.remove("drop-target")
            draggableElement.classList.remove("can-drop")

            const mergeSourceId = draggableElement.getAttribute(
                Const.attributes.personId,
            )
            const mergeDestId = dropzoneElement.getAttribute(
                Const.attributes.personId,
            )

            console.debug(`Merging ${mergeSourceId} into ${mergeDestId}`)
            dispatch(Const.events.confirmPersonMerge, {
                mergeSourceId,
                mergeDestId,
            })
        },

        ondropdeactivate: (event) => {
            event.target.classList.remove("drop-active")
            event.target.classList.remove("drop-target")
        },
    })
}
