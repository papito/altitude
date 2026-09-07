# Select2

- **Source:** https://alpinejs.dev/component/select2 (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, select2 v4.x, jquery v3.5.x

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`. Single and multiple select backed by a plain `<select>`.

## How it is built

- `x-data` holds `multiple`, `value` (string or array), and `options: [{ label, value }]`.
- `init()` calls `$($refs.select).select2({ data: options.map(o => ({ id: o.value, text: o.label, selected: value.includes(o.value) })) })`.
- `$($refs.select).on('change', ...)` copies `$(el).val()` back; `$watch('value', ...)` sets `.val(value).trigger('change')` for outside updates.
- `multiple` is a plain attribute on the `<select>`; values are compared as strings.

## Keyboard and accessibility

- Select2 supplies search-as-you-type and arrow-key navigation inside its dropdown.

## State

`multiple: boolean`, `value: string | string[]`, `options: Array<{ label, value }>`.

## Minimal example (original)

```html
<script src="https://cdnjs.cloudflare.com/ajax/libs/jquery/3.5.1/jquery.min.js"></script>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/select2@4.1.0-rc.0/dist/css/select2.min.css">
<script src="https://cdn.jsdelivr.net/npm/select2@4.1.0-rc.0/dist/js/select2.min.js"></script>

<div x-data="{
        value: ['a'], options: [{ label: 'Alice', value: 'a' }, { label: 'Bob', value: 'b' }],
        init() {
            $(this.$refs.select).select2({ data: this.options.map(o => ({ id: o.value, text: o.label, selected: this.value.includes(o.value) })) })
            $(this.$refs.select).on('change', () => { this.value = $(this.$refs.select).val() })
            this.$watch('value', (value) => $(this.$refs.select).val(value).trigger('change'))
        },
     }">
    <select x-ref="select" multiple></select>
</div>
```

## Notes for Altitude

Requires jQuery; the Choices.js recipe covers the same ground without it.
