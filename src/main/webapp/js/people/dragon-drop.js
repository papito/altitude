import interact from "https://cdn.interactjs.io/v1.9.20/interactjs/index.js"

import { Const } from "../constants.js"
import {
    dragged,
    dragMoveListener,
    setFixedPositionWhileDragging,
} from "../common/dragon-drop.js"

interact("#people .drag-drop").draggable({
    inertia: true,
    autoScroll: true,

    listeners: {
        move: dragMoveListener,
        end: dragged,
        start: setFixedPositionWhileDragging,
    },
})

interact("#people .dropzone, #person.dropzone").dropzone({
    accept: "#person .drag-drop, #people .drag-drop",
    overlap: 0.75,

    ondropactivate: function (event) {
        event.target.classList.add("drop-active")
    },

    ondragenter: function (event) {
        const draggableElement = event.relatedTarget
        const dropzoneElement = event.target

        dropzoneElement.classList.add("drop-target")
        draggableElement.classList.add("can-drop")
    },

    ondragleave: function (event) {
        event.target.classList.remove("drop-target")
        event.relatedTarget.classList.remove("can-drop")
    },

    ondrop: function (event) {
        const draggableElement = event.relatedTarget
        const dropzoneElement = event.target

        dropzoneElement.classList.remove("drop-active")
        dropzoneElement.classList.remove("drop-target")

        draggableElement.classList.remove("can-drop")

        const mergeSrcId = draggableElement.getAttribute(
            Const.attributes.personId,
        )
        const mergeDestId = dropzoneElement.getAttribute(
            Const.attributes.personId,
        )

        console.debug("Merging " + mergeSrcId + " into " + mergeDestId)
        const confirmPersonMergeEvent = new CustomEvent(
            Const.events.confirmPersonMerge,
            {
                detail: {
                    mergeSourceId: mergeSrcId,
                    mergeDestId: mergeDestId,
                },
            },
        )

        document.body.dispatchEvent(confirmPersonMergeEvent)
    },

    ondropdeactivate: function (event) {
        event.target.classList.remove("drop-active")
        event.target.classList.remove("drop-target")
    },
})
