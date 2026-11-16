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
                // move), or the panel for the `#person` cover, which has none. `draggable()` merges the
                // options, so the listeners and `inertia` stay
                event.interactable.draggable({
                    autoScroll: {
                        container:
                            event.target.closest(".pane .list") ??
                            document.querySelector("#explorerPanelContent"),
                    },
                })
                setFixedPositionWhileDragging(event)

                // The release's click lands on the Person's link, now under the pointer, and would act
                // as a click on the tile. Armed at start: with inertia, a flick's `end` fires after the
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
