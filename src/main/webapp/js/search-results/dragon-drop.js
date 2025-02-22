import interact from "https://cdn.interactjs.io/v1.9.20/interactjs/index.js"
import {
    dragged,
    dragMoveListener,
    setFixedPositionWhileDragging,
} from "../common/dragon-drop.js"
import { Const } from "../constants.js"

interact("#assets .drag-drop").draggable({
    inertia: true,
    autoScroll: true,

    listeners: {
        move: dragMoveListener,
        /**
         * This a custom function that, in addition to setting the display as "fixed",
         * makes the image smaller while dragging, for better UX.
         * Normally, we would just use the common setFixedPositionWhileDragging() function.
         */
        start: function (event) {
            setFixedPositionWhileDragging(event)

            let target = event.target
            let position = target.getBoundingClientRect()

            let imgElement = target.querySelector("img")
            if (imgElement) {
                imgElement.setAttribute(
                    Const.attributes.originalWidth,
                    imgElement.clientWidth,
                )
                imgElement.style.width = "40px"
                const yOffset = event.clientY - position.top
                target.style.top = position.top + yOffset + "px"
            }
        },
        end: dragged,
    },
})
