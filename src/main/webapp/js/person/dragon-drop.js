import interact from "https://cdn.interactjs.io/v1.9.20/interactjs/index.js"
import {
    dragged,
    dragMoveListener,
    setFixedPositionWhileDragging,
} from "../common/dragon-drop.js"

interact("#person .drag-drop").draggable({
    listeners: {
        move: dragMoveListener,
        start: setFixedPositionWhileDragging,
        end: dragged,
    },
})
