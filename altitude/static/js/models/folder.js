/**
 * DOM wrapper for one folder-tree node (`#folder-<id>`) and the single owner of its expansion
 * state. The tree renderer (`common/folder-tree.js`) builds the nodes; everything that opens or
 * closes them goes through this class.
 *
 * A **branch** is a non-root folder with child folders. Only a branch ever carries the
 * `data-expanded` state: root is always expanded and has no expansion gestures, and a leaf has
 * nothing to expand, even when it holds assets.
 *
 * Reset invariant: collapsing a branch also collapses every descendant branch. A closed branch can
 * therefore never silently retain expansion that would reappear on its next single-click:
 * reopening it with `expand()` reveals its direct children only, and `expandAll()` opens every
 * level. Every branch operation walks the selected subtree once.
 *
 * Expansion never changes which folders are highlighted as the viewed scope
 * (`common/viewed-folder-scope.js`): that follows the displayed search results.
 */
import { closeOpenContextMenu } from "../alpine/components/context-menu.js"
import { Const } from "../constants.js"

export class Folder {
    /**
     * The folder rendered as `#folder-<id>`, or null when the tree holds no such node: the node was
     * removed by a rebuild mid-interaction, or the ID names a folder not in this tree at all.
     */
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

    /** The parent folder; null for root, which carries no parent attribute */
    parent() {
        return Folder.find(this.element.dataset.parentFolderId)
    }

    /**
     * Reveals this branch's direct children. Descendant branches keep the collapsed state the
     * reset invariant left them in. Nothing happens for root or a leaf.
     */
    expand() {
        if (!this.isBranch()) {
            return
        }

        this._setExpanded(true)
        console.debug(`Expanded folder ${this.name()}`)
    }

    /**
     * Reveals every level of this branch: this folder and each descendant branch. Nothing happens
     * for root or a leaf.
     */
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

    /**
     * Closes this branch and resets every descendant branch (the reset invariant). Nothing happens
     * for root or a leaf.
     */
    collapse() {
        if (!this.isBranch()) {
            return
        }

        // Hiding the descendants hides their menu triggers too; the open menu (at most one) must
        // not outlive its trigger. One search of the subtree finds it.
        closeOpenContextMenu({
            reason: `ancestor ${this.name()} collapsed`,
            within: this.childrenEl,
        })

        // Focus must not be left in hidden content (the closed menu returns it to its trigger,
        // which is about to be hidden as well); this branch's control takes it instead
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

    /**
     * Whether this folder is the folder identified by `ancestorId` or one of its descendants,
     * walking up the parent attributes until root, which has none.
     */
    isDescendantOrSelf(ancestorId) {
        for (let folder = this; folder; folder = folder.parent()) {
            if (folder.id === ancestorId) {
                return true
            }
        }

        return false
    }

    /**
     * Every branch below this folder, found in one walk of its subtree. Folder nodes are the
     * `.folder` elements carrying a folder ID (the controls and children containers carry the ID
     * too, but are not nodes); leaves are dropped since they hold no expansion state.
     */
    _descendantBranches() {
        return Array.from(
            this.childrenEl.querySelectorAll(
                `.folder[${Const.attributes.folderId}]`,
            ),
        )
            .map((el) => new Folder(el))
            .filter((folder) => folder.isBranch())
    }

    /**
     * The one place a branch's state changes: the `data-expanded` flag, the visibility of its
     * children, its plus/minus glyph, and the `aria-expanded` of its control move together.
     */
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
