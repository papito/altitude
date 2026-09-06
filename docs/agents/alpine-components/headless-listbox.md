# Listbox (Select), headless

- **Source:** https://alpinejs.dev/component/headless-listbox (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Headless (`@alpinejs/ui`)
- **Dependencies:** alpinejs v3.x, @alpinejs/ui v3.x, @alpinejs/focus v3.x

## What it does

Headless component from the `@alpinejs/ui` plugin: behavior, keyboard handling, and ARIA come from the plugin's directives; all styling is yours. Full documentation is linked from each page (https://alpinejs.dev/components#headless).

A custom select: a button showing the current value opens a list of options; supports disabled
options and multiple selection.

## Variants on the page

- Single select
- With icons
- Multiple

## How it is built

- `x-listbox` with `x-model="value"`, `x-listbox:label`, `x-listbox:button` (its text is bound with `x-text` to the selected item's name via `$listbox.value`), `x-listbox:options` (with `x-cloak`), and `x-listbox:option` rendered inside `x-for` with `:value` and `:disabled`.
- `$listboxOption.isActive`, `isSelected`, and `isDisabled` style each option; `x-show` reveals a check mark on the selected one.
- The "Multiple" variant binds an array model and shows the selected names joined; "With Icons" adds icons to options.

## Keyboard and accessibility

- Up/Down move the active option, Enter/Space select, Escape closes, typing jumps to a match.
- `role="listbox"`, `role="option"`, `aria-selected`, `aria-activedescendant` set by the directives.

## State

`value` (or an array) bound with `x-model`; an `options` array in `x-data` drives `x-for`.

## Minimal example (original)

```html
<div x-data="{ value: null, options: [{ id: 'jpg', name: 'JPEG' }, { id: 'png', name: 'PNG', disabled: true }] }">
    <div x-listbox x-model="value">
        <label x-listbox:label>Format</label>
        <button x-listbox:button type="button" x-text="$listbox.value ? $listbox.value.name : 'Choose...'"></button>
        <ul x-listbox:options x-cloak>
            <template x-for="option in options" :key="option.id">
                <li x-listbox:option :value="option" :disabled="option.disabled"
                    :class="{ 'active': $listboxOption.isActive, 'muted': $listboxOption.isDisabled }">
                    <span x-text="option.name"></span>
                    <span x-show="$listboxOption.isSelected" aria-hidden="true">&#10003;</span>
                </li>
            </template>
        </ul>
    </div>
</div>
```

## Notes for Altitude

The sort dropdown in `search_results.scala.html` is a native `<select>` driven by HTMX; keep it native unless custom option rendering is needed.
