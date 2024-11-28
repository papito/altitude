import {Const} from "/js/constants.js"
import interact from "https://cdn.interactjs.io/v1.9.20/interactjs/index.js"
import {dragged, dragMoveListener} from "/js/common/dragon-drop.js";

interact('#rootFolderList .drag-drop').draggable({
    // enable inertial throwing
    inertia: true,
    // keep the element within the area of it's parent
    modifiers: [
        interact.modifiers.restrict({
            restriction: '#rootFolderList > div.children',
            endOnly: true,
        })
    ],
    autoScroll: true,

    listeners: {
        move: dragMoveListener,
        end: dragged,
    }
})

// enable a draggable to be dropped into this
interact('#rootFolderList .dropzone').dropzone({
    // only accept elements matching this CSS selector
    accept: '.drag-drop',
    // going above seems to break the dropzone
    overlap: 0.50,

    // listen for drop related events:

    ondropactivate: function (event) {
        // add active dropzone feedback
        event.target.classList.add('drop-active')
    },
    ondragenter: function (event) {
        const draggableElement = event.relatedTarget
        const dropzoneElement = event.target

        // feedback the possibility of a drop
        dropzoneElement.classList.add('drop-target')
        draggableElement.classList.add('can-drop')
    },
    ondragleave: function (event) {
        // remove the drop feedback style
        event.target.classList.remove('drop-target')
        event.relatedTarget.classList.remove('can-drop')
    },
    ondrop: function (event) {
        const draggableElement = event.relatedTarget
        const dropzoneElement = event.target

        dropzoneElement.classList.remove('drop-active')
        dropzoneElement.classList.remove('drop-target')

        draggableElement.classList.remove('can-drop')

        const movedFolderId = draggableElement.getAttribute("folder-id")
        const newParentFolderId = dropzoneElement.getAttribute("folder-id")

        // console.log("DROPPED INTO FOLDER")
        const movedFolderEvent = new CustomEvent(
            Const.events.folderMoved, {
                detail: {
                    movedFolderId: movedFolderId,
                    newParentId: newParentFolderId,
        }});

        document.body.dispatchEvent(movedFolderEvent)
    },

    ondropdeactivate: function (event) {
        // remove active dropzone feedback
        event.target.classList.remove('drop-active')
        event.target.classList.remove('drop-target')
    }
})
