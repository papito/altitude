// The two ways a moment reads: its date alone, or its date and time
const FORMATS = {
    date: { dateStyle: "medium" },
    datetime: { dateStyle: "medium", timeStyle: "short" },
}

/**
 * A moment the server renders as `<time datetime="<ISO instant>">` holding its UTC text
 * (`Util.utcText`), shown in the viewer's zone and locale instead: the date alone, or the date and
 * time (`data-app-time-style`, "date" or "datetime"). The account info dialog's times use it
 * (`htmx/account_info_dialog`). A `datetime` the browser cannot read leaves the UTC text.
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
