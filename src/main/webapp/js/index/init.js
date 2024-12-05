/**
 * @see: https://github.com/nathancahill/split/
 */
import Split from "../lib/split.es.js"

const savedHorizontalSplitSizes = localStorage.getItem("horizontalSplitSizes")
let horizontalSplitSizes = [25, 75]

if (savedHorizontalSplitSizes) {
  horizontalSplitSizes = JSON.parse(savedHorizontalSplitSizes)
}

Split(["#explorer", "#content"], {
  sizes: horizontalSplitSizes,
  minSize: [100, 300],
  onDragEnd: function (horizontalSplitSizes) {
    localStorage.setItem(
      "horizontalSplitSizes",
      JSON.stringify(horizontalSplitSizes),
    )
  },
})

const savedVerticalSplitSizes = localStorage.getItem("verticalSplitSizes")
let verticalSplitSizes = [65, 35]

if (savedVerticalSplitSizes) {
  verticalSplitSizes = JSON.parse(savedVerticalSplitSizes)
}

Split(["#explorerViews", "#infoPanel"], {
  sizes: verticalSplitSizes,
  direction: "vertical",
  onDragEnd: function (verticalSplitSizes) {
    localStorage.setItem(
      "verticalSplitSizes",
      JSON.stringify(verticalSplitSizes),
    )
  },
})
