import { fillSelectionField } from "./selection-field.js"

/**
 * The Add to album dialog. The select's empty-valued option creates a new album: while it is chosen
 * the name field shows (the server renders its initial state). The success detail names the chosen
 * album so the snackbar can name it; for a new album the server's detail, merged over this one,
 * carries the created album.
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
