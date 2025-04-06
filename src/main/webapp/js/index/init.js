/**
 * @see: https://github.com/nathancahill/split/
 */
import Split from "../lib/split.es.js"

import { highlightNav } from "../common/navigation.js"
import { Const } from "../constants.js"
import { context } from "../context.js"

export function init() {
    // select the right nav tab
    const urlParams = new URLSearchParams(window.location.search)
    const view = urlParams.get("view") || "repository"
    highlightNav(view)
    // this switches the background color of the content
    document.getElementById("content").classList.add(view);


    /**
     * Load which fields to show in the grid
     */
    context.loadMetadataFieldViewSettingsFromStore()

    /**
     * Split the screen according to last preferences
     */
    const savedHorizontalSplitSizes = localStorage.getItem(
        Const.localStore.horizontalSplitSizes,
    )
    let horizontalSplitSizes = [25, 75]

    if (savedHorizontalSplitSizes) {
        horizontalSplitSizes = JSON.parse(savedHorizontalSplitSizes)
    }

    Split(["#explorer", "#content"], {
        sizes: horizontalSplitSizes,
        minSize: [100, 300],
        onDragEnd: function (horizontalSplitSizes) {
            localStorage.setItem(
                Const.localStore.horizontalSplitSizes,
                JSON.stringify(horizontalSplitSizes),
            )
        },
    })

    const savedVerticalSplitSizes = localStorage.getItem(
        Const.localStore.verticalSplitSizes,
    )
    let verticalSplitSizes = [65, 35]

    if (savedVerticalSplitSizes) {
        verticalSplitSizes = JSON.parse(savedVerticalSplitSizes)
    }

    Split(["#explorerViews", "#infoPanel"], {
        sizes: verticalSplitSizes,
        direction: "vertical",
        onDragEnd: function (verticalSplitSizes) {
            localStorage.setItem(
                Const.localStore.verticalSplitSizes,
                JSON.stringify(verticalSplitSizes),
            )
        },
    })
}
