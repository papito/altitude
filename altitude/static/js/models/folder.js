/**
 * A folder-tree node (`#folder-<id>`) and the single owner of its expansion state.
 *
 * Only a branch (a non-root folder with child folders) carries `data-expanded`: root is always
 * expanded, and a leaf has nothing to expand even when it holds assets. Collapsing a branch also
 * collapses every descendant branch (the reset invariant), so a reopened branch never shows a stale
 * deeper expansion: `expand()` reveals the direct children only, `expandAll()` every level.
 */
import { closeOpenContextMenu } from "../alpine/components/context-menu.js"
import { Const } from "../constants.js"

export class Folder {
    /** Null when the tree holds no such node, e.g. one a rebuild removed mid-interaction */
    static find(id) {
        const element = id ? document.getElementById(`folder-${id}`) : null

        return element ? new Folder(element) : null
    }

    constructor(element) {
        this.element = element
        this.id = element.dataset.folderId
        this.iconEl = htmx.find("#folder-icon-" + this.id)
        this.childrenEl = htmx.find("#children-" + this.id)
        // The branch control wrapping the icon; root has none
        this.expandCtrlEl = htmx.find("#expand-folder-children-" + this.id)
        this.isRoot = element.dataset.isRoot === "true"
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

    isBranch() {
        return !this.isRoot && this.numOfChildren() > 0
    }

    parent() {
        return Folder.find(this.element.dataset.parentFolderId)
    }

    /** Descendant branches stay collapsed, as the reset invariant left them */
    expand() {
        if (!this.isBranch()) {
            return
        }

        this._setExpanded(true)
        console.debug(`Expanded folder ${this.name()}`)
    }

    expandAll() {
        if (!this.isBranch()) {
            return
        }

        const descendants = this._descendantBranches()
        this._setExpanded(true)
        descendants.forEach((branch) => branch._setExpanded(true))
        console.debug(
            `Expanded folder ${this.name()} with ${descendants.length} descendant branch(es)`,
        )
    }

    /** Also collapses every descendant branch (the reset invariant) */
    collapse() {
        if (!this.isBranch()) {
            return
        }

        // Hiding the descendants hides their menu triggers, and the open menu must not outlive its
        // trigger
        closeOpenContextMenu({
            reason: `ancestor ${this.name()} collapsed`,
            within: this.childrenEl,
        })

        // Focus must not be left in hidden content (the closed menu returns it to its trigger, about to
        // be hidden too); this branch's control takes it
        const focusWasInside = this.childrenEl.contains(document.activeElement)

        const descendants = this._descendantBranches()
        descendants.forEach((branch) => branch._setExpanded(false))
        this._setExpanded(false)

        if (focusWasInside) {
            this.expandCtrlEl?.focus()
        }

        console.debug(
            `Collapsed folder ${this.name()}, resetting ${descendants.length} descendant branch(es)`,
        )
    }

    isDescendantOrSelf(ancestorId) {
        for (let folder = this; folder; folder = folder.parent()) {
            if (folder.id === ancestorId) {
                return true
            }
        }

        return false
    }

    /** Nodes are the `.folder` elements with a folder ID; controls and children containers carry the ID too */
    _descendantBranches() {
        return Array.from(
            this.childrenEl.querySelectorAll(
                `.folder[${Const.attributes.folderId}]`,
            ),
        )
            .map((el) => new Folder(el))
            .filter((folder) => folder.isBranch())
    }

    /** The one place a branch's state changes: flag, children, glyph and `aria-expanded` move together */
    _setExpanded(expanded) {
        if (expanded) {
            this.element.setAttribute(Const.attributes.expanded, "true")
        } else {
            this.element.removeAttribute(Const.attributes.expanded)
        }

        this.childrenEl.style.display = expanded ? "" : "none"
        this.iconEl.classList.toggle("fa-folder-minus", expanded)
        this.iconEl.classList.toggle("fa-folder-plus", !expanded)
        this.expandCtrlEl?.setAttribute("aria-expanded", String(expanded))
    }
}
