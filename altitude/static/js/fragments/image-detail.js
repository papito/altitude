import { getModalOpenSource, ModalHost, openModal } from "../common/modal.js"
import { parseEdit } from "../edit/edit-stage.js"

/**
 * Asset detail fragment: opens the asset-detail host at once, attaches the Detail panel, fullscreen,
 * the Edit stage and the Edit panel to the fragment, and hands the media load to the coordinator.
 *
 * Navigation starts from the cell whose thumbnail opened the detail: a grid can hold an asset in
 * several cells (a Location grouping), so the cell is remembered, not the asset. A detail opened from
 * elsewhere (a map pin, an asset's URL) has no previous or next.
 */
export function hydrateImageDetailFragment({
    fragmentEl,
    coordinator,
    detailPanel,
    detailFullscreen,
    editStage,
    editPanel,
}) {
    if (fragmentEl.dataset.appImageDetailBound === "true") {
        return
    }

    fragmentEl.dataset.appImageDetailBound = "true"

    const imgEl = fragmentEl.querySelector("img")
    const videoEl = fragmentEl.querySelector("video")
    if (!imgEl || !videoEl) {
        return
    }

    const openId = openModal({
        host: ModalHost.assetDetail,
        title: fragmentEl.dataset.appImageDetailTitle,
    })

    // Before the media: the panel loads the details alongside it
    detailPanel.attach(fragmentEl)
    detailFullscreen.attach(fragmentEl)
    editStage.attach(fragmentEl)
    // After the Detail panel, whose side the Edit panel's dock follows
    editPanel.attach(fragmentEl)

    coordinator.showMedia({
        openId,
        imgEl,
        videoEl,
        mediaType: fragmentEl.dataset.appImageDetailMediaType,
        url: fragmentEl.dataset.appImageDetailUrl,
        title: fragmentEl.dataset.appImageDetailTitle,
        width: Number(fragmentEl.dataset.appImageDetailWidth),
        height: Number(fragmentEl.dataset.appImageDetailHeight),
        assetId: fragmentEl.dataset.appImageDetailAssetId,
        edit: parseEdit(fragmentEl.dataset.appImageDetailEdit),
        editable: fragmentEl.dataset.appImageDetailEditable === "true",
        editRefusal: fragmentEl.dataset.appImageDetailEditRefusal ?? null,
        cellEl: getModalOpenSource()?.closest?.(".cell") ?? null,
    })
}
