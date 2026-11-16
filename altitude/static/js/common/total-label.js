/** "+" when the server counted matches only up to its cap and there are more */
export function totalLabel(count, isCapped) {
    return isCapped ? `${count}+` : `${count}`
}
