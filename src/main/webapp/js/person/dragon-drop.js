import interact from "../lib/@interactjs/interactjs/index.js"

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
