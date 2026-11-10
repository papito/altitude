import { Const } from "../constants.js"
import { suppressClickAfterRelease } from "../search-results/click-suppression.js"
import {
    dragged,
    dragMoveListener,
    dropzoneListeners,
    setFixedPositionWhileDragging,
} from "./helpers.js"

export function bindPeopleDragDrop({ dispatch }) {
    interact("#people .drag-drop, #person .drag-drop").draggable({
        inertia: true,
        autoScroll: {
            container: document.querySelector("#explorerPanelContent"),
        },

        listeners: {
            move: dragMoveListener,
            start: (event) => {
                // Scroll the pane the cell was lifted from (interact.js reads the container on every
                // move); the `#person` cover in the results title has none, so the panel scrolls.
                // `draggable()` extends the autoScroll options in place and touches no other
                // option, so the listeners and `inertia` stay
                event.interactable.draggable({
                    autoScroll: {
                        container:
                            event.target.closest(".pane .list") ??
                            document.querySelector("#explorerPanelContent"),
                    },
                })
                setFixedPositionWhileDragging(event)

                // The release's click lands on the Person's link, moved under the pointer, and
                // would search (or warn in Triage) as if the tile had been clicked. Armed at
                // start rather than in `end`: with inertia, a flick's `end` fires after the
                // browser's click.
                suppressClickAfterRelease()
            },
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
