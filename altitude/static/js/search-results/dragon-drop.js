import {
    dragged,
    dragMoveListener,
    setFixedPositionWhileDragging,
} from "../common/dragon-drop.js"
import { Const } from "../constants.js"
import { Alpine } from "../lib/alpine.esm.min.js"

interact("#assets .drag-drop").draggable({
    inertia: true,
    autoScroll: true,

    listeners: {
        move: dragMoveListener,
        start: function (event) {
            const assetId = event.target.getAttribute(Const.attributes.assetId)

            const selectedAssetsStore = Alpine.store(Const.state.selectedAssets)

            setFixedPositionWhileDragging(event)

            let target = event.target
            let position = target.getBoundingClientRect()
            const imgElement = target.querySelector("img")

            const clone = target.cloneNode(true)
            clone.id = "dragCloneStandIn"
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
            imgElement.setAttribute(
                Const.attributes.originalWidth,
                originalWidth,
            )

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

            if (
                !selectedAssetsStore.isEmpty &&
                selectedAssetsStore.contains(assetId)
            ) {
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
                selectedAssetsStore.items.forEach((asset) => {
                    asset.drag()
                })

                target.style.opacity = "1"
                imgElement.style.opacity = "40%"
            }
        },
        end: function (event) {
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

            if (
                !selectedAssetsStore.isEmpty &&
                selectedAssetsStore.contains(assetId)
            ) {
                // Unmark affected assets as being dragged
                selectedAssetsStore.items.forEach((asset) => {
                    asset.drop()
                })
            }

            target.classList.remove("dragging")

            // Call the common dragged function for final cleanup
            dragged(event)
        },
    },
})

interact("#trash").dropzone({
    accept: "#assets .drag-drop, #batchOps .drag-drop",
    overlap: 0.2,

    ondropactivate: function (event) {
        event.target.classList.add("drop-active")
    },
    ondragenter: function (event) {
        const draggableElement = event.relatedTarget
        const dropzoneElement = event.target

        dropzoneElement.classList.add("drop-target")
        draggableElement.classList.add("can-drop")
    },
    ondragleave: function (event) {
        event.target.classList.remove("drop-target")
        event.relatedTarget.classList.remove("can-drop")
    },
    ondrop: function (event) {
        const draggableElement = event.relatedTarget
        const dropzoneElement = event.target

        dropzoneElement.classList.remove("drop-active")
        dropzoneElement.classList.remove("drop-target")
        draggableElement.classList.remove("can-drop")

        const trashedFolderId = draggableElement.getAttribute(
            Const.attributes.folderId,
        )
        const trashedAssetId = draggableElement.getAttribute(
            Const.attributes.assetId,
        )

        // Check if this is a batch mover (i.e. multiple selected assets)
        // If so, this operation uses state store to get the list of selected assets
        const isBatchMover =
            draggableElement.parentNode.classList.contains("batch-mover")

        if (trashedFolderId) {
            console.debug(`Trashed folder ${trashedFolderId}`)
            const trashedFolderEvent = new CustomEvent(
                Const.events.folderTrashed,
                {
                    detail: {
                        trashedFolderId: trashedFolderId,
                    },
                },
            )

            document.body.dispatchEvent(trashedFolderEvent)
        }

        if (trashedAssetId) {
            console.debug(`Trashed asset ${trashedAssetId}`)
            const trashedAssetEvent = new CustomEvent(
                Const.events.assetTrashed,
                {
                    detail: {
                        assetId: trashedAssetId,
                    },
                },
            )

            document.body.dispatchEvent(trashedAssetEvent)
        }

        if (isBatchMover) {
            console.debug(`Batch recycling assets`)
            const batchTrashedEvent = new CustomEvent(
                Const.events.batchAssetsRecycled,
            )
            document.body.dispatchEvent(batchTrashedEvent)
        }
    },

    ondropdeactivate: function (event) {
        event.target.classList.remove("drop-active")
        event.target.classList.remove("drop-target")
    },
})
