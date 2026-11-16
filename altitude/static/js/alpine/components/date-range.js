/**
 * A date range field of the advanced search panel: the Date Taken or Date Imported range, picked on one
 * Flatpickr calendar in range mode, loaded with its stylesheet when the first field appears.
 *
 * The range is two ISO days, both ends inclusive, held in the field's two hidden inputs (the search
 * parameters); the visible input is read-only and shows `rangeLabel`. A single pick is that one day, sent
 * as both ends.
 *
 * The calendar is appended to the field, not `body`: there it would sit beneath the panel, which is in
 * the top layer, and a click on it would light-dismiss the panel. It is a manual popover of its own,
 * placed under the field by CSS anchor positioning, and a dialog named after the field.
 */
import { showErrorSnackBar } from "../../common/snackbar.js"
import { loadFlatpickr } from "../../common/vendor-loader.js"

// The months the calendar shows side by side
const MONTHS_SHOWN = 2

// Flatpickr's format for an ISO day, as the server reads days
const ISO_DAY = "Y-m-d"

// The first three letters in English, as the server's `DateRange.label` writes a month, so the field and a filtered
// search's chip agree in every browser
const MONTHS = [
    "Jan",
    "Feb",
    "Mar",
    "Apr",
    "May",
    "Jun",
    "Jul",
    "Aug",
    "Sep",
    "Oct",
    "Nov",
    "Dec",
]

/** "8 Jun" for the ISO day, with its year when asked */
function dayLabel(isoDay, withYear) {
    const [year, month, day] = isoDay.split("-")
    const label = `${Number(day)} ${MONTHS[Number(month) - 1]}`
    return withYear ? `${label} ${year}` : label
}

/**
 * `8 Jun – 21 Jun 2024`, each end with its year when the two differ; must agree with the server's
 * `DateRange.label`. An open end, which a search's URL can hold though the calendar picks none, reads
 * `from 8 Jun 2024` or `until 21 Jun 2024`.
 */
function rangeLabel(from, to) {
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
    // The Flatpickr instance. Closure state rather than component data: the library compares the
    // instance by identity, which a reactive proxy would break
    let picker = null

    return {
        // ISO days; both empty for no range
        from: "",
        to: "",
        // The input's `aria-expanded`
        open: false,

        get hasRange() {
            return Boolean(this.from || this.to)
        },

        /** Also the input's tooltip, for a range wider than the field */
        get label() {
            return rangeLabel(this.from, this.to)
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

            // Given no days, Flatpickr parses the input's text and would read a day out of the label, so the input is
            // empty while the calendar is made
            const inputEl = this.$refs.input
            inputEl.value = ""
            picker = flatpickr(inputEl, {
                mode: "range",
                dateFormat: ISO_DAY,
                showMonths: MONTHS_SHOWN,
                // The calendar marks only closed ranges; an open-ended one from a URL shows in the field alone
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

            // A popover so it sits in the top layer over the panel's edge; x-ignore, since nothing in it is Alpine's
            const calendarEl = picker.calendarContainer
            calendarEl.popover = "manual"
            calendarEl.setAttribute("role", "dialog")
            calendarEl.setAttribute(
                "aria-label",
                `${inputEl.labels[0].textContent} calendar`,
            )
            calendarEl.setAttribute("x-ignore", "")
            this.showLabel()
        },

        destroy() {
            picker?.destroy()
            picker = null
        },

        /** The `change` on the first hidden input tells the form the field's value changed */
        setRange(from, to) {
            this.from = from
            this.to = to
            this.$refs.from.value = from
            this.$refs.to.value = to
            this.$refs.from.dispatchEvent(
                new Event("change", { bubbles: true }),
            )
            this.showLabel()
        },

        /** Flatpickr writes its own text into the input on every pick; this puts the label back */
        showLabel() {
            this.$refs.input.value = this.label
        },

        /** One day picked is the range of that day, two are its ends */
        onPicked(days) {
            const isoDays = days.map((day) => picker.formatDate(day, ISO_DAY))
            this.setRange(isoDays[0] ?? "", isoDays.at(-1) ?? "")
        },

        showCalendar() {
            const calendarEl = picker.calendarContainer
            if (!calendarEl.matches(":popover-open")) {
                calendarEl.showPopover()
            }
            this.open = true
        },

        /** A lone pick is completed with the same day; Flatpickr would otherwise clear it on close. */
        onCalendarClosed(days) {
            if (days.length === 1) {
                picker.setDate([days[0], days[0]], false)
                this.showLabel()
            }

            const calendarEl = picker.calendarContainer
            if (calendarEl.matches(":popover-open")) {
                calendarEl.hidePopover()
            }
            this.open = false
        },

        /**
         * Escape closes an open calendar alone, focusing the field. It is consumed in the capture phase,
         * before Flatpickr or the document sees it, so the panel stays open.
         */
        onEscape(event) {
            if (!picker?.isOpen) {
                return
            }

            event.preventDefault()
            event.stopPropagation()
            this.focusField()
        },

        /** Focusing the field opens the calendar, so it is closed again at once */
        focusField() {
            this.$refs.input.focus()
            picker?.close()
        },

        clear() {
            picker?.clear(false)
            picker?.close()
            this.setRange("", "")
        },

        /** Focus moves to the field, since the × hides with the range it cleared */
        clearFromButton() {
            this.clear()
            this.focusField()
        },
    }
}
