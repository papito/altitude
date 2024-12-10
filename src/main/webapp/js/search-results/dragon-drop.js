import interact from "https://cdn.interactjs.io/v1.9.20/interactjs/index.js"
import { dragged, dragMoveListener } from "../common/dragon-drop.js"
import { Const } from "../constants.js"

interact("#assets .drag-drop").draggable({
    inertia: true,
    autoScroll: true,

    listeners: {
        move: dragMoveListener,
        end: dragged,
        start: function (event) {
            let target = event.target
            let position = target.getBoundingClientRect()

            target.style.position = "fixed"
            target.style.top = position.top + "px"

            let imgElement = target.querySelector("img")
            if (imgElement) {
                imgElement.setAttribute(
                    Const.attributes.originalWidth,
                    imgElement.clientWidth,
                )
                imgElement.style.width = "40px"
                const yOffset = event.clientY - position.top
                target.style.top = position.top + yOffset + "px"
                // adjusting the X here causes the image to jump around
            }
        },
    },
})
