/**
 * Before/after HTMX request listeners
 * ===================================
 *
 * This handles multiple "before request" scenarios, short-circuiting the request
 * if necessary.
 *
 * Some actions require a network call (show menu, expand folders)
 * but others do not (close menu, collapse folders).
 */

import { context } from "../context.js"
import { Const } from "../constants.js"
import { Folder } from "../models.js"
import { showErrorSnackBar } from "../common/snackbar.js"

htmx
  .find("#rootFolderList")
  .addEventListener("htmx:beforeRequest", function (evt) {
    console.debug(evt.detail.pathInfo.requestPath)
    /**
     * Context menu potential request
     */
    if (
      evt.detail.pathInfo.requestPath ===
      `/htmx/folder/r/${context.repoId}/context-menu`
    ) {
      const folderId = evt.detail.target.getAttribute(Const.attributes.folderId)
      const folder = new Folder(folderId)

      // Do not go to the server if the menu is already open, just kill it
      if (folder.isMenuExpanded()) {
        folder.closeContextMenu()
        evt.preventDefault()
      } else {
        /**
         * The request for the context menu will go out, so let's go full Marie Kondo
         * on the open menus because they do not bring us JOY.
         */
        const openMenuEls = document.querySelectorAll("#rootFolderList .menu")

        openMenuEls.forEach((menuEl) => {
          Folder.closeContextMenu(menuEl)
        })
      }
    }

    /**
     * Folder children potential request
     */
    if (
      evt.detail.pathInfo.requestPath ===
      `/htmx/folder/r/${context.repoId}/children`
    ) {
      // I don't JavaScript good - how do you get a param from a relative URL?
      const url = new URL(
        "https://dummy.com" + evt.detail.pathInfo.finalRequestPath,
      )
      const folderId = url.searchParams.get("parentId")
      const folder = new Folder(folderId)

      if (folder.isRoot) {
        return
      }

      if (folder.isExpanded()) {
        // IF the folder is currently expanded - abort the request and clear the children
        folder.collapse()

        evt.preventDefault() // do not trip to the server
      } else {
        // ELSE proceed with request and update state
        folder.expand()
      }
    }
  })

htmx
  .find("#rootFolderList")
  .addEventListener("htmx:afterRequest", function (evt) {
    console.debug("===========")
    const requestPath = evt.detail.pathInfo.requestPath
    const status = evt.detail.xhr.status

    if (evt.detail.successful === false) {
      console.debug(evt)
      showErrorSnackBar(
        "Error for request to " + requestPath + ". HTTP " + status,
      )
      return
    }

    console.debug("Request path is " + requestPath)

    // on successful context menu request, show the menu
    if (requestPath === `/htmx/folder/r/${context.repoId}/context-menu`) {
      console.debug("Context menu request successful")

      const folder = new Folder(
        evt.target.getAttribute(Const.attributes.folderId),
      )
      folder.showContextMenu()

      if (!folder.isExpanded() && !folder.isRoot) {
        // root folder is always expanded
        folder.htmxExpandChildrenAction()
      }
    }

    // on successful folder children request or child added request, expand the folder
    if (
      requestPath === `/htmx/folder/r/${context.repoId}/children` ||
      requestPath === `/htmx/folder/r/${context.repoId}/add`
    ) {
      const folder = new Folder(
        evt.target.getAttribute(Const.attributes.folderId),
      )
      folder.expand()
    }
  })
