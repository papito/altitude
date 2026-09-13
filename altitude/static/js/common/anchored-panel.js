/**
 * Places a viewport-positioned panel below its anchor, left-aligned unless `center` is set.
 * Flip above when needed; if neither side fits, use the roomier side with internal scrolling.
 * Callers provide the positioning/overflow CSS and ensure the panel's ancestors are displayed.
 */
export function placeAnchoredPanel(panel, anchor, { gap, center = false }) {
    const trigger = anchor.getBoundingClientRect()
    const viewportWidth = document.documentElement.clientWidth
    const viewportHeight = document.documentElement.clientHeight

    // Native popovers are hidden before opening. Measure with their open display and without
    // an earlier height constraint, then restore display so the browser still owns visibility.
    const display = panel.style.display
    panel.style.display = "grid"
    panel.style.maxHeight = ""
    panel.style.maxWidth = `${Math.max(0, viewportWidth - 2 * gap)}px`

    const below = Math.min(viewportHeight - gap, Math.max(gap, trigger.bottom))
    const above = Math.min(viewportHeight - gap, Math.max(gap, trigger.top))
    const spaceBelow = Math.max(0, viewportHeight - below - gap)
    const spaceAbove = Math.max(0, above - gap)
    const { height } = panel.getBoundingClientRect()

    let top
    if (height <= spaceBelow) {
        top = below
    } else if (height <= spaceAbove) {
        top = above - height
    } else if (spaceBelow >= spaceAbove) {
        panel.style.maxHeight = `${spaceBelow}px`
        top = below
    } else {
        panel.style.maxHeight = `${spaceAbove}px`
        top = gap
    }

    // A scrollbar introduced by the height cap can change the panel's width.
    const { width } = panel.getBoundingClientRect()
    const alignedLeft = center
        ? trigger.left + (trigger.width - width) / 2
        : trigger.left
    panel.style.top = `${top}px`
    panel.style.left = `${Math.max(gap, Math.min(alignedLeft, viewportWidth - gap - width))}px`
    panel.style.display = display
}
