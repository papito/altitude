import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { suppressNextClick } from "../search-results/click-suppression.js"
import {
    dragged,
    dragMoveListener,
    dropzoneListeners,
    isBatchMoverElement,
    setFixedPositionWhileDragging,
} from "./helpers.js"

/**
 * Thumbnails drag into folders, albums, Locations and the trash; one among the selected drags the
 * whole selection (its event's listener escalates to a batch). `#trash` takes drops only with
 * `toTrash`: the account may recycle what the view shows.
 */

// The width of the dragged thumbnail's image, shrunk under the pointer
const GHOST_IMAGE_WIDTH_PX = 45

// The inline size each dragged image had before its drag, restored when the drag ends
const ghostImageInlineSizes = new WeakMap()

export function bindAssetDragDrop({ dispatch, toTrash }) {
    const assetDraggable = interact("#assets .drag-drop")

    // A Shift-drag is an additive box selection (box-selection.js), so no asset drag starts with Shift
    assetDraggable.actionChecker((pointer, event, action) =>
        pointer.shiftKey ? null : action,
    )

    assetDraggable.draggable({
        inertia: true,
        autoScroll: true,

        listeners: {
            move: dragMoveListener,
            start: onDragStart,
            end: onDragEnd,
        },
    })

    if (!toTrash) {
        return
    }

    interact("#trash").dropzone({
        accept: "#assets .drag-drop, .batch-ops .drag-drop",
        overlap: 0.2,

        ...dropzoneListeners((event) => {
            const draggableElement = event.relatedTarget
            const trashedAssetId = draggableElement.getAttribute(
                Const.attributes.assetId,
            )

            const isBatchMover = isBatchMoverElement(draggableElement)

            if (trashedAssetId) {
                console.debug(`Trashed asset ${trashedAssetId}`)
                dispatch(Const.events.assetTrashed, { assetId: trashedAssetId })
            }

            if (isBatchMover) {
                console.debug("Batch recycling assets")
                dispatch(Const.events.batchAssetsRecycled)
            }
        }),
    })
}

function onDragStart(event) {
    const assetId = event.target.getAttribute(Const.attributes.assetId)

    const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)

    setFixedPositionWhileDragging(event)

    let target = event.target
    let position = target.getBoundingClientRect()
    const imgElement = target.querySelector("img")

    const clone = target.cloneNode(true)
    clone.id = "dragCloneStandIn"
    // The stand-in lives on <body>, outside `#assets`, where the cell badges lose their pill styling
    // and would render as stray text
    clone.querySelector(".triage-marker")?.remove()
    clone.querySelector(".no-date-marker")?.remove()
    clone.style.position = "fixed"
    clone.style.pointerEvents = "none"
    clone.style.left = `${position.left}px`
    clone.style.top = `${position.top}px`
    clone.style.width = `${position.width}px`
    clone.style.height = `${position.height}px`
    clone.style.opacity = "30%"
    document.body.appendChild(clone)

    // Both dimensions are set: lazy-images.js may have fixed the rendered size inline, and with only
    // the width set the ghost would be a sliver as tall as the thumbnail, too tall for a folder row
    // to ever cover a drop zone's share of it. The inline size is restored on drag end.
    const originalWidth = imgElement.clientWidth
    ghostImageInlineSizes.set(imgElement, {
        width: imgElement.style.width,
        height: imgElement.style.height,
    })
    imgElement.style.width = `${GHOST_IMAGE_WIDTH_PX}px`
    imgElement.style.height = "auto"

    // The shrunk image stays centered in the thumbnail's width, its top at the pointer
    const offsetX = (originalWidth - GHOST_IMAGE_WIDTH_PX) / 2

    const yOffset = event.clientY - position.top
    target.style.top = position.top + yOffset + "px"
    target.style.left = position.left + offsetX + "px"
    target.classList.add("dragging")

    if (!selectedAssetsStore.isEmpty && selectedAssetsStore.contains(assetId)) {
        const checkmark = target.querySelector(".checkmark")

        if (checkmark) {
            checkmark.style.display = "none"
            const cloneCheckmark = clone.querySelector(".checkmark")
            cloneCheckmark.style.display = "flex"
        }

        const countBadge = document.createElement("div")
        target.appendChild(countBadge)

        // The badge covers the image
        const imgRect = imgElement.getBoundingClientRect()
        const targetRect = target.getBoundingClientRect()
        const offsetLeft = imgRect.left - targetRect.left
        const offsetTop = imgRect.top - targetRect.top

        countBadge.style.top = `${offsetTop}px`
        countBadge.style.left = `${offsetLeft}px`
        countBadge.style.width = `${imgRect.width}px`
        countBadge.style.height = `${imgRect.height}px`
        countBadge.className = "drag-count-badge"
        countBadge.textContent = selectedAssetsStore.size

        selectedAssetsStore.setDragging(true)

        target.style.opacity = "1"
        imgElement.style.opacity = "40%"
    }
}

function onDragEnd(event) {
    const target = event.target

    const assetId = event.target.getAttribute(Const.attributes.assetId)
    const imgElement = target.querySelector("img")

    imgElement.style.opacity = "1"

    const countBadge = target.querySelector(".drag-count-badge")
    if (countBadge) {
        countBadge.remove()
    }

    const clone = document.getElementById("dragCloneStandIn")
    if (clone) {
        clone.parentNode.removeChild(clone)
    }

    const inlineSize = ghostImageInlineSizes.get(imgElement)
    imgElement.style.width = inlineSize?.width ?? ""
    imgElement.style.height = inlineSize?.height ?? ""
    ghostImageInlineSizes.delete(imgElement)

    const checkmark = target.querySelector(".checkmark")
    if (checkmark) {
        checkmark.style.display = "block"
    }

    const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)

    if (selectedAssetsStore.contains(assetId)) {
        selectedAssetsStore.setDragging(false)
    }

    target.classList.remove("dragging")

    // The browser fires a click after pointerup; it must not reach the thumbnail
    suppressNextClick()

    dragged(event)
}
