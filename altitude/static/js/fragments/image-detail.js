import { Const } from "../constants.js"
import { showAssetDetailModal } from "../common/modal.js"
import { setImgSrcAndWait } from "../search-results/detail-navigator.js"

function setSpinner(visible) {
    const spinner = document.getElementById("imageDetailSpinner")
    if (spinner) spinner.hidden = !visible
}

export function hydrateImageDetailFragment({ fragmentEl, Alpine, dispatch }) {
    if (fragmentEl.dataset.appImageDetailBound === "true") {
        return
    }

    fragmentEl.dataset.appImageDetailBound = "true"

    const imgEl = fragmentEl.querySelector("img")
    if (!imgEl) {
        return
    }

    setSpinner(true)
    ;(async () => {
        try {
            await setImgSrcAndWait({
                Alpine,
                img: imgEl,
                url: fragmentEl.dataset.appImageDetailUrl,
            })

            showAssetDetailModal({
                title: fragmentEl.dataset.appImageDetailTitle,
                width: Number(fragmentEl.dataset.appImageDetailWidth),
                height: Number(fragmentEl.dataset.appImageDetailHeight),
            })

            dispatch(Const.events.detailShown, {
                assetId: fragmentEl.dataset.appImageDetailAssetId,
            })
        } catch (error) {
            console.error("Error loading asset image", error)
        } finally {
            setSpinner(false)
        }
    })()
}
