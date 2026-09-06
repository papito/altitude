# Radio, headless

- **Source:** https://alpinejs.dev/component/headless-radio (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Headless (`@alpinejs/ui`)
- **Dependencies:** alpinejs v3.x, @alpinejs/ui v3.x, @alpinejs/focus v3.x

## What it does

Headless component from the `@alpinejs/ui` plugin: behavior, keyboard handling, and ARIA come from the plugin's directives; all styling is yours. Full documentation is linked from each page (https://alpinejs.dev/components#headless).

A radio group rendered as cards or segments, bound with `x-model`.

## Variants on the page

- Cards
- Horizontal cards
- With indicator
- With icons
- Segmented
- Segmented with icons

## How it is built

- `x-radio` on the group with `x-model="value"`; `x-radio:option` on each option with a `:value`; `x-radio:label` and `x-radio:description` inside options.
- `$radioOption.isChecked` styles the selected option.
- Variants are purely presentational: vertical cards, horizontal cards, an explicit check indicator, icons, and segmented controls.

## Keyboard and accessibility

- Arrow keys move and select; only the checked option is in the Tab order.
- `role="radiogroup"`, `role="radio"`, `aria-checked`, `aria-labelledby`, `aria-describedby` set by the directives.

## State

`value` bound with `x-model`.

## Minimal example (original)

```html
<div x-data="{ speed: 'standard' }">
    <div x-radio x-model="speed" aria-label="Shipping">
        <div x-radio:option value="standard" :class="{ 'selected': $radioOption.isChecked }">
            <span x-radio:label>Standard</span>
            <span x-radio:description>4 to 10 days</span>
        </div>
        <div x-radio:option value="express" :class="{ 'selected': $radioOption.isChecked }">
            <span x-radio:label>Express</span>
            <span x-radio:description>1 day</span>
        </div>
    </div>
</div>
```
