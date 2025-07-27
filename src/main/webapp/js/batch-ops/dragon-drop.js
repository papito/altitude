import interact from "https://cdn.interactjs.io/v1.9.20/interactjs/index.js"
import { dragged, dragMoveListener } from "../common/dragon-drop.js"

interact("#batchOps button.drag-drop").draggable({
    inertia: true,
    autoScroll: true,

    listeners: {
        move: dragMoveListener,
        end: dragged,
    },
})
