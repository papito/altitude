# Choices.js

- **Source:** https://alpinejs.dev/component/choices (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, choices.js v10.1.x (CSS + script)

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`. A dependency-free select enhancer for single and multiple selection.

## How it is built

- `x-data` holds `multiple`, `value`, and `options: [{ label, value }]`; `init()` creates `new Choices($refs.select, { removeItemButton: true })`.
- A refresh routine calls `choices.clearStore()` then `choices.setChoices(options.map(o => ({ value, label, selected: value.includes(o.value) })))`.
- The `<select>`'s `change` event copies `choices.getValue(true)` into `value`; `$watch('value', ...)` re-runs the refresh inside `$nextTick`.

## Keyboard and accessibility

- Choices supports keyboard navigation and search in its dropdown.

## State

`multiple: boolean`, `value: string | string[]`, `options: Array<{ label, value }>`.

## Minimal example (original)

```html
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/choices.js/public/assets/styles/choices.min.css">
<script src="https://cdn.jsdelivr.net/npm/choices.js/public/assets/scripts/choices.min.js"></script>

<div x-data="{
        value: ['a'], options: [{ label: 'Alice', value: 'a' }, { label: 'Bob', value: 'b' }],
        init() {
            const choices = new Choices(this.$refs.select, { removeItemButton: true })
            const refresh = () => { choices.clearStore(); choices.setChoices(this.options.map(o => ({ ...o, selected: this.value.includes(o.value) }))) }
            refresh()
            this.$refs.select.addEventListener('change', () => { this.value = choices.getValue(true) })
            this.$watch('value', () => this.$nextTick(refresh))
        },
     }">
    <select x-ref="select" multiple></select>
</div>
```
