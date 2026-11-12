# Flatpickr (date picker)

- **Source:** https://alpinejs.dev/component/flatpickr (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, flatpickr v4.6.x (CSS + script)

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`. The demo is a date range picker.

## How it is built

- `x-data` holds `value` as a string; `init()` calls `flatpickr($refs.picker, { mode: 'range', dateFormat, defaultDate: value.split(' to '), onChange: (dates, str) => value = str })`.
- `$watch('value', ...)` calls `picker.setDate(...)` so external changes update the widget.

## Keyboard and accessibility

- Flatpickr renders an accessible calendar popup with arrow-key navigation.

## State

`value: string` (flatpickr's formatted string, for example `2026-09-01 to 2026-09-05`).

## Minimal example (original)

```html
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/flatpickr/dist/flatpickr.min.css">
<script src="https://cdn.jsdelivr.net/npm/flatpickr"></script>

<div x-data="{
        value: '',
        init() {
            const picker = flatpickr(this.$refs.picker, {
                mode: 'range', dateFormat: 'Y-m-d',
                onChange: (dates, text) => { this.value = text },
            })
            this.$watch('value', (value) => picker.setDate(value.split(' to ')))
        },
     }">
    <input x-ref="picker" type="text" placeholder="Date range">
</div>
```

## Notes for Altitude

The advanced search panel's Date Taken and Date Imported ranges are this recipe (`static/js/alpine/components/date-range.js`,
views/AGENTS.md **Advanced search panel**), with flatpickr 4.6.13 vendored (`altitude/static/js/lib/README.md`) rather than
loaded from a CDN, and four differences: the component holds the range as two ISO days in hidden inputs and writes its own
label into the field (`8 Jun – 21 Jun 2024`) after every pick, in place of flatpickr's text; one day picked alone is kept as a
range of that day when the calendar closes; the calendar is appended to the field (`appendTo`) and shown as a manual popover
placed by CSS anchor positioning, with flatpickr's placement switched off; and the stylesheet is imported into a cascade
layer, so the application's token overrides outrank it.
