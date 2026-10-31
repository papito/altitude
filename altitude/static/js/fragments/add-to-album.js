import { fillSelectionField } from "./selection-field.js"

/**
 * The Add to album modal dialog (`htmx/add_to_album_dialog.scala.html`,
 * `data-app-dialog-kind="add-to-album"`): the form's hidden `assetIds` field carries the selection
 * (`fillSelectionField`). The select's last option, "New album…", has an empty value: while it is
 * chosen the new album's name field shows, and the server creates that album for the selection.
 * The server renders the field's initial state (shown when there are no albums, or when the
 * re-rendered submission chose "New album…"); a change of the select shows or hides it here, and
 * focuses it when it shows.
 *
 * The success event's detail names the chosen album - its ID and its name from the option's
 * `data-name` - as the Add to location dialog's does, so the listener can name it in the snackbar
 * whichever explorer tab is displayed. For a new album the server's detail, merged over this one,
 * carries the created album's ID and name.
 */
export function hydrateAddToAlbumFragment({ fragmentEl }) {
    fillSelectionField(fragmentEl)

    const selectEl = fragmentEl.querySelector("select")
    const newAlbumEl = fragmentEl.querySelector("#newAlbum")
    if (!selectEl || !newAlbumEl) {
        return
    }

    const nameEl = newAlbumEl.querySelector("input")

    const writeDetail = () => {
        fragmentEl.dataset.appSuccessDetail = JSON.stringify({
            albumId: selectEl.value || null,
            name: selectEl.selectedOptions[0]?.dataset.name ?? null,
        })
    }

    writeDetail()
    selectEl.addEventListener("change", () => {
        const creating = selectEl.value === ""
        newAlbumEl.hidden = !creating
        writeDetail()

        if (creating) {
            nameEl?.focus()
        }
    })
}
