import { Const } from "../constants.js"
import {
    dragged,
    dragMoveListener,
    dropzoneListeners,
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

        ...dropzoneListeners((event) => {
            const mergeSourceId = event.relatedTarget.getAttribute(
                Const.attributes.personId,
            )
            const mergeDestId = event.target.getAttribute(
                Const.attributes.personId,
            )

            console.debug(`Merging ${mergeSourceId} into ${mergeDestId}`)
            dispatch(Const.events.confirmPersonMerge, {
                mergeSourceId,
                mergeDestId,
            })
        }),
    })
}
