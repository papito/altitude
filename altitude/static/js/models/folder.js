import { closeOpenFolderMenu } from "../common/folder-menu.js"
import { Const } from "../constants.js"

export class Folder {
    constructor(id) {
        if (!id) {
            throw new Error("Folder id is required")
        }
        this.element = htmx.find("#folder-" + id)

        if (!this.element) {
            throw new Error("Folder element not found for id " + id)
        }
        this.id = id
        this.iconEl = htmx.find("#folder-icon-" + this.id)
        this.childrenEl = htmx.find("#children-" + this.id)
        this.isRoot =
            this.element.getAttribute(Const.attributes.isRoot) === "true"
    }

    folderNameEl() {
        return htmx.find("#folderName-" + this.id)
    }

    isExpanded() {
        return this.element.getAttribute(Const.attributes.expanded) === "true"
    }

    name() {
        return this.folderNameEl().innerText
    }

    numOfChildren() {
        return parseInt(
            this.element.getAttribute(Const.attributes.numOfChildren),
        )
    }

    incrementNumOfChildren() {
        console.debug(
            "Incrementing # of children for  " +
                this.name() +
                ". New value: " +
                this.numOfChildren(),
        )
        console.debug("\tOld value: " + this.numOfChildren())
        this.element.setAttribute(
            Const.attributes.numOfChildren,
            this.numOfChildren() + 1,
        )
        console.debug("\tNew value: " + this.numOfChildren())
    }

    decrementNumOfChildren() {
        const newValue = this.numOfChildren() - 1

        console.debug(
            "Decrementing # of children for  " +
                this.name() +
                ". New value: " +
                newValue,
        )
        console.debug("\tOld value: " + this.numOfChildren())
        this.element.setAttribute(
            Const.attributes.numOfChildren,
            newValue.toString(),
        )
        console.debug("\tNew value: " + newValue)
    }

    updateVisualState() {
        console.debug("Updating visual state for folder " + this.name())
        console.debug("\tNumber of children: " + this.numOfChildren())

        if (this.isRoot) {
            return
        }

        if (!this.isExpanded()) {
            // Collapsing hides the descendants' triggers; an open descendant menu must not outlive its trigger
            closeOpenFolderMenu({
                reason: `ancestor ${this.name()} collapsed`,
                within: this.childrenEl,
            })

            if (this.numOfChildren()) {
                console.debug("\tFolder is collapsed and has children")
                this.iconEl.classList.remove("fa-folder-minus")
                this.iconEl.classList.add("fa-folder-plus")
            } else {
                console.debug("\tFolder is collapsed and has no children")
                this.iconEl.classList.remove("fa-folder-plus")
                this.iconEl.classList.remove("fa-folder-minus")
                this.iconEl.classList.add("fa-folder")
            }
        }

        if (this.isExpanded()) {
            if (this.numOfChildren()) {
                console.debug("\tFolder is expanded and has children")
                this.iconEl.classList.remove("fa-folder-plus")
                this.iconEl.classList.add("fa-folder-minus")
            } else {
                console.debug("\tFolder is expanded and has no children")
                this.iconEl.classList.remove("fa-folder-plus")
                this.iconEl.classList.remove("fa-folder-minus")
                this.iconEl.classList.add("fa-folder")
            }
        }
    }

    parent() {
        const parentId = this.element.getAttribute(
            Const.attributes.parentFolderId,
        )
        return new Folder(parentId)
    }

    collapse() {
        console.debug("Setting folder " + this.name() + " to collapsed")
        this.element.removeAttribute(Const.attributes.expanded)
        if (this.childrenEl && !this.isRoot) {
            this.childrenEl.style.display = "none"
        }
        this.updateVisualState()
    }

    expand() {
        console.debug("Setting folder " + this.name() + " to expanded")
        this.element.setAttribute(Const.attributes.expanded, "true")
        if (this.childrenEl && !this.isRoot) {
            this.childrenEl.style.display = ""
        }
        this.updateVisualState()
    }

    remove() {
        this.element.remove()
    }

    addChild(folder) {
        // NOTE: increment/decrement of children must be done by the caller, NOT here
        this.childrenEl.appendChild(folder.element)
    }

    /**
     * Returns true if this folder is the same as, or a descendant of, the folder
     * identified by ancestorId. Walks up the DOM via alt-parent-folder-id attributes.
     * Returns false if ancestorId is not found in the ancestor chain (or the chain
     * is broken by a missing DOM element).
     */
    isDescendantOrSelf(ancestorId) {
        let currentId = this.id
        while (currentId) {
            if (currentId === ancestorId) return true
            const el = document.getElementById("folder-" + currentId)
            if (!el) return false
            const parentId = el.getAttribute(Const.attributes.parentFolderId)
            // root folder is its own parent — stop to avoid infinite loop
            if (parentId === currentId) return false
            currentId = parentId
        }
        return false
    }
}
