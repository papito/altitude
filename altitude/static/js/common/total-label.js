/**
 * A results total as the toolbar and the map panel read it: the count, followed by "+" when the
 * server counted the matches only up to its cap and there are more
 */
export function totalLabel(count, isCapped) {
    return isCapped ? `${count}+` : `${count}`
}
