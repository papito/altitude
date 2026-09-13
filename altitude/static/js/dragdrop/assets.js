import { Alpine } from "../lib/alpine.esm.min.js"
import { Const } from "../constants.js"
import { suppressNextClick } from "../search-results/click-suppression.js"
import {
    dragged,
    dragMoveListener,
    dropzoneListeners,
    setFixedPositionWhileDragging,
} from "./helpers.js"

/**
 * Thumbnails in the results grid drag into folders, albums, and the trash; a thumbnail that is
 * among the selected ones drags the whole selection with it (its listeners escalate to a batch).
 * The `#trash` nav item is a drop zone for a thumbnail or the batch mover.
 */
export function bindAssetDragDrop({ dispatch }) {
    const assetDraggable = interact("#assets .drag-drop")

    // A Shift-drag over a thumbnail is an additive box selection (see box-selection.js), so no asset
    // drag may start while Shift is held
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

    interact("#trash").dropzone({
        accept: "#assets .drag-drop, #batchOps .drag-drop",
        overlap: 0.2,

        ...dropzoneListeners((event) => {
            const draggableElement = event.relatedTarget
            const trashedAssetId = draggableElement.getAttribute(
                Const.attributes.assetId,
            )

            // The batch mover carries no asset ID; the selection store has the assets
            const isBatchMover =
                draggableElement.parentNode.classList.contains("batch-mover")

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
    // The stand-in lives on <body>, outside `#assets`, so the
    // cell badges lose their pill styling and would render as
    // stray text
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

    // remember the original width so we can restore it later
    const originalWidth = imgElement.clientWidth
    imgElement.setAttribute(Const.attributes.originalWidth, originalWidth)

    // Calculate the offset needed to center the resized image at cursor
    const newWidth = 45
    const widthDifference = originalWidth - newWidth
    const offsetX = widthDifference / 2

    imgElement.style.width = newWidth + "px"

    // Adjust the target position to center the resized image at cursor
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

        // Create or update the count badge
        const countBadge = document.createElement("div")
        target.appendChild(countBadge)

        // Position the badge to match the image dimensions and position
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

        // Mark affected assets as being dragged
        selectedAssetsStore.setDragging(true)

        target.style.opacity = "1"
        imgElement.style.opacity = "40%"
    }
}

function onDragEnd(event) {
    // Clean up multiple selection styling before calling the common dragged function
    const target = event.target

    const assetId = event.target.getAttribute(Const.attributes.assetId)
    const imgElement = target.querySelector("img")

    imgElement.style.opacity = "1"

    // Remove the grid placeholder if it exists
    const placeholder = target.parentNode.querySelector(
        ".drag-grid-placeholder",
    )
    if (placeholder) {
        placeholder.remove()
    }

    const countBadge = target.querySelector(".drag-count-badge")
    if (countBadge) {
        countBadge.remove()
    }

    const clone = document.getElementById("dragCloneStandIn")
    if (clone) {
        clone.parentNode.removeChild(clone)
    }

    const originalWidth = imgElement.getAttribute(
        Const.attributes.originalWidth,
    )

    imgElement.style.width = originalWidth + "px"
    imgElement.style.height = "auto"
    imgElement.removeAttribute(Const.attributes.originalWidth)

    const checkmark = target.querySelector(".checkmark")
    if (checkmark) {
        checkmark.style.display = "block"
    }

    const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)

    if (selectedAssetsStore.contains(assetId)) {
        // Unmark affected assets as being dragged
        selectedAssetsStore.setDragging(false)
    }

    target.classList.remove("dragging")

    // The browser fires a click after pointerup; it must not reach the thumbnail
    suppressNextClick()

    // Call the common dragged function for final cleanup
    dragged(event)
}
