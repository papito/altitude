/**
 * A date range field of the advanced search panel (`htmx/search_panel`, views/AGENTS.md **Advanced
 * search panel**): the Date Taken or the Date Imported range, picked on one calendar. The calendar
 * is Flatpickr in range mode (`static/js/lib/README.md`), loaded with its stylesheet when the first
 * field appears (`loadFlatpickr`): both ends are picked on it with the span highlighted, the arrow
 * keys move through its days from the field (Down enters it) and Enter picks one.
 *
 * The component root is the field (`.field[data-date-range]`). It holds the range as two ISO days,
 * `from` and `to`, whole days with both ends inclusive, which are the values of the field's two
 * hidden inputs, the search parameters; the input the user sees is read-only and shows the range as
 * `8 Jun – 21 Jun 2024` (`rangeLabel`). One day picked alone is that single day, sent as both
 * ends: closing the calendar after one pick completes the range with the same day, where Flatpickr
 * alone would drop the pick. The × (`clear`) and `search-panel-clear`, which the panel's Clear
 * dispatches on every field, empty the range.
 *
 * The calendar is appended to the field (`appendTo`), so it is inside the panel that holds the
 * field: appended to `body` it would be drawn beneath the panel, which is in the top layer, and a
 * click on it would light-dismiss the panel. It is a manual popover of its own, shown and hidden
 * with Flatpickr's open state, so it is in the top layer too, over the panel's edge, and CSS anchor
 * positioning places it under the field's box (`htmx/search_panel.css`); Flatpickr's own placement
 * is switched off (`position`). Escape with the calendar open closes the calendar alone and is
 * consumed, so the panel stays; Enter in the field opens the calendar and never submits the panel.
 */
import { showErrorSnackBar } from "../../common/snackbar.js"
import { loadFlatpickr } from "../../common/vendor-loader.js"

// The months the calendar shows side by side
const MONTHS_SHOWN = 2

// The days as the server reads them, Flatpickr's format for an ISO day
const ISO_DAY = "Y-m-d"

const DAY_AND_MONTH = new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    timeZone: "UTC",
})

/** "8 Jun" for the ISO day, with its year when asked */
function dayLabel(isoDay, withYear) {
    const label = DAY_AND_MONTH.format(new Date(`${isoDay}T00:00:00Z`))
    return withYear ? `${label} ${isoDay.slice(0, 4)}` : label
}

/**
 * The range as the field shows it: `8 Jun – 21 Jun 2024`, each end with its year when the two
 * differ (`28 Dec 2023 – 3 Jan 2024`), a single day once (`8 Jun 2024`), and nothing for no range.
 * A range with one end open, which a search's URL can hold though the calendar picks none, reads
 * `from 8 Jun 2024` or `until 21 Jun 2024`.
 */
export function rangeLabel(from, to) {
    if (!from && !to) {
        return ""
    }

    if (!to) {
        return `from ${dayLabel(from, true)}`
    }

    if (!from) {
        return `until ${dayLabel(to, true)}`
    }

    if (from === to) {
        return dayLabel(from, true)
    }

    const sameYear = from.slice(0, 4) === to.slice(0, 4)
    return `${dayLabel(from, !sameYear)} – ${dayLabel(to, true)}`
}

/** The day a week starts on where the browser's language is spoken, as Flatpickr counts it: 0 for Sunday */
function firstDayOfWeek() {
    const locale = new Intl.Locale(navigator.language)
    const weekInfo = locale.getWeekInfo?.() ?? locale.weekInfo

    return weekInfo ? weekInfo.firstDay % 7 : 0
}

export function dateRange() {
    // The Flatpickr instance, once the library has loaded. Closure state rather than component
    // data: the library compares the instance by identity, which a reactive proxy would break
    let picker = null

    return {
        // The first and the last day of the range, ISO days; both empty for no range
        from: "",
        to: "",

        /** The field holds a range, which its × clears */
        get hasRange() {
            return Boolean(this.from || this.to)
        },

        async init() {
            this.setRange(this.$refs.from.value, this.$refs.to.value)

            try {
                await loadFlatpickr()
            } catch (error) {
                console.error(`Error loading the calendar: ${error.message}`)
                showErrorSnackBar("Could not load the calendar")
                return
            }

            // The field left the page while the library loaded
            if (!this.$root.isConnected) {
                return
            }

            picker = flatpickr(this.$refs.input, {
                mode: "range",
                dateFormat: ISO_DAY,
                showMonths: MONTHS_SHOWN,
                // A range with an open end, a URL's, is shown by the field alone: the calendar marks closed ranges
                defaultDate:
                    this.from && this.to ? [this.from, this.to] : undefined,
                locale: { firstDayOfWeek: firstDayOfWeek() },
                appendTo: this.$root,
                // CSS anchor positioning places the calendar
                position: () => {},
                onOpen: () => this.showCalendar(),
                onClose: (days) => this.onCalendarClosed(days),
                onValueUpdate: (days) => this.onPicked(days),
            })

            // In the top layer, over the panel's edge; and nothing in it is Alpine's to walk
            picker.calendarContainer.popover = "manual"
            picker.calendarContainer.setAttribute("x-ignore", "")
            this.showLabel()
        },

        destroy() {
            picker?.destroy()
            picker = null
        },

        /** Holds the range: the two hidden inputs take its days and the field shows it */
        setRange(from, to) {
            this.from = from
            this.to = to
            this.$refs.from.value = from
            this.$refs.to.value = to
            this.showLabel()
        },

        /** Flatpickr writes its own text into the field on every pick; the field shows the range's label instead */
        showLabel() {
            this.$refs.input.value = rangeLabel(this.from, this.to)
        },

        /** A pick on the calendar: one day is the range of that day, two are its ends */
        onPicked(days) {
            const isoDays = days.map((day) => picker.formatDate(day, ISO_DAY))
            this.setRange(isoDays[0] ?? "", isoDays.at(-1) ?? "")
        },

        showCalendar() {
            const calendarEl = picker.calendarContainer
            if (!calendarEl.matches(":popover-open")) {
                calendarEl.showPopover()
            }
        },

        /**
         * The calendar closed. With one day picked the range is completed with the same day, so the
         * calendar shows what the field holds; Flatpickr would otherwise clear the lone pick.
         */
        onCalendarClosed(days) {
            if (days.length === 1) {
                picker.setDate([days[0], days[0]], false)
                this.showLabel()
            }

            const calendarEl = picker.calendarContainer
            if (calendarEl.matches(":popover-open")) {
                calendarEl.hidePopover()
            }
        },

        /**
         * Escape, in the field or on the calendar, closes an open calendar alone, with focus back in
         * the field: the key is consumed here, on its way down, before Flatpickr or the document
         * sees it, so the panel stays open.
         */
        onEscape(event) {
            if (!picker?.isOpen) {
                return
            }

            event.preventDefault()
            event.stopPropagation()
            // Focus first, as Flatpickr does: the field's focus opens a closed calendar
            this.$refs.input.focus()
            picker.close()
        },

        /** The × and the panel's Clear: no range */
        clear() {
            picker?.clear(false)
            picker?.close()
            this.setRange("", "")
        },
    }
}
