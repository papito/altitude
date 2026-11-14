import { getModalOpenSource, ModalHost, openModal } from "../common/modal.js"
import { parseEdit } from "../edit/edit-stage.js"

/**
 * Asset detail fragment (`data-app-fragment="image-detail"`): opens the asset-detail host right
 * away, with its loading indicator, attaches the Detail panel to the fragment (its dock, its
 * divider and whether it is open, `search-results/detail-panel.js`), fullscreen to its player
 * (`search-results/detail-fullscreen.js`), the Edit's stage to its canvas (`edit/edit-stage.js`) and
 * the Edit panel to its panel, its divider and its tools (`edit/edit-panel.js`),
 * and hands the media load to the detail coordinator, which shows the fragment's `<img>` (or, for an
 * edited image, the Display image or the Edit's canvas, by the Edit the fragment carries in
 * `data-app-image-detail-edit`) or its video player by the asset's media type and has the panel
 * load the asset's details while it is open. The coordinator only
 * applies the result if this open is still active and the load was not superseded by
 * previous/next navigation.
 *
 * Navigation starts from the cell whose thumbnail requested the detail, when it was one: a grid can
 * hold an asset in several cells (a Location grouping), so the cell itself is remembered, not the
 * asset. A detail opened from elsewhere (a map pin, an asset's URL) has no cell, and no previous
 * or next.
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
