# Date Range Picker (jQuery daterangepicker)

- **Source:** https://alpinejs.dev/component/date-range-picker (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, daterangepicker v3.1.x, jquery v3.5.x, moment v2.29.x

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`. Uses the jQuery plugin with preset ranges built from moment.

## How it is built

- `x-data` holds `value`; `init()` calls `$($refs.picker).daterangepicker({ startDate, endDate, ranges: { 'Today': [moment(), moment()], 'Last 7 days': [moment().subtract(6, 'days'), moment()], 'This month': [moment().startOf('month'), moment().endOf('month')] } }, (start, end) => value = start.format(...) + ' - ' + end.format(...))`.
- `$watch('value', ...)` reads the plugin instance with `$($refs.picker).data('daterangepicker')` and calls `setStartDate` / `setEndDate`.

## Keyboard and accessibility

- The plugin's calendar is mouse-oriented; keyboard support is limited compared with flatpickr.

## State

`value: string`.

## Minimal example (original)

```html
<script src="https://cdn.jsdelivr.net/jquery/latest/jquery.min.js"></script>
<script src="https://cdn.jsdelivr.net/momentjs/latest/moment.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/daterangepicker/daterangepicker.min.js"></script>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/daterangepicker/daterangepicker.css">

<div x-data="{
        value: '',
        init() {
            $(this.$refs.picker).daterangepicker(
                { ranges: { 'Today': [moment(), moment()], 'Last 7 days': [moment().subtract(6, 'days'), moment()] } },
                (start, end) => { this.value = `${start.format('YYYY-MM-DD')} - ${end.format('YYYY-MM-DD')}` },
            )
        },
     }">
    <input x-ref="picker" type="text">
</div>
```

## Notes for Altitude

Requires jQuery and moment; prefer the Flatpickr recipe for the same feature.
