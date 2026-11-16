const FORMATS = {
    date: { dateStyle: "medium" },
    datetime: { dateStyle: "medium", timeStyle: "short" },
}

/**
 * Rewrites a server-rendered `<time datetime>` (UTC text) in the viewer's zone and locale, per
 * `data-app-time-style`. A `datetime` the browser cannot parse keeps the UTC text.
 */
export function localTime() {
    return {
        init() {
            const at = new Date(this.$el.dateTime)
            if (Number.isNaN(at.getTime())) return
            const format =
                FORMATS[this.$el.dataset.appTimeStyle] ?? FORMATS.datetime
            this.$el.textContent = new Intl.DateTimeFormat(
                undefined,
                format,
            ).format(at)
        },
    }
}
