import { getModalOpenSource, ModalHost, openModal } from "../common/modal.js"

/**
 * Asset detail fragment (`data-app-fragment="image-detail"`): opens the asset-detail host right
 * away, with its loading indicator, and hands the image load to the detail coordinator. The
 * coordinator only applies the result if this open is still active and the load was not
 * superseded by previous/next navigation.
 *
 * Navigation starts from the cell whose thumbnail requested the detail, when it was one: a grid can
 * hold an asset in several cells (a Location grouping), so the cell itself is remembered, not the
 * asset. A detail opened from elsewhere (a map pin) has no cell, and no previous or next.
 */
export function hydrateImageDetailFragment({ fragmentEl, coordinator }) {
    if (fragmentEl.dataset.appImageDetailBound === "true") {
        return
    }

    fragmentEl.dataset.appImageDetailBound = "true"

    const imgEl = fragmentEl.querySelector("img")
    if (!imgEl) {
        return
    }

    const openId = openModal({
        host: ModalHost.assetDetail,
        title: fragmentEl.dataset.appImageDetailTitle,
    })

    coordinator.showImage({
        openId,
        imgEl,
        url: fragmentEl.dataset.appImageDetailUrl,
        title: fragmentEl.dataset.appImageDetailTitle,
        width: Number(fragmentEl.dataset.appImageDetailWidth),
        height: Number(fragmentEl.dataset.appImageDetailHeight),
        cellEl: getModalOpenSource()?.closest?.(".cell") ?? null,
    })
}
