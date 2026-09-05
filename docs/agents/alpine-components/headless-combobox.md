# Combobox (Autocomplete), headless

- **Source:** https://alpinejs.dev/component/headless-combobox (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Headless (`@alpinejs/ui`)
- **Dependencies:** alpinejs v3.x, @alpinejs/ui v3.x, @alpinejs/focus v3.x

## What it does

Headless component from the `@alpinejs/ui` plugin: behavior, keyboard handling, and ARIA come from the plugin's directives; all styling is yours. Full documentation is linked from each page (https://alpinejs.dev/components#headless).

A text input that filters a list of options as you type, with single or multiple (tag) selection.

## Variants on the page

- Single select
- Multiple select (tags)

## How it is built

- `x-combobox` with `x-model="selected"`; `x-combobox:input` whose `@change` updates a `query`; `x-combobox:button` toggles the list; `x-combobox:options` and `x-combobox:option` inside `x-for` over a filtered array computed from `query`.
- `$comboboxOption.isActive`, `isSelected`, and `isDisabled` style options; an empty-state row shows when nothing matches.
- The multiple variant keeps an array model and renders chosen items as removable tags (`x-on:click.prevent` on the remove control).

## Keyboard and accessibility

- Typing filters; Up/Down move the active option; Enter selects; Escape closes.
- `role="combobox"`, `aria-expanded`, `aria-controls`, `aria-activedescendant`, `role="option"` set by the directives.

## State

`selected` (value or array), `query: string`, `options` array, and a filtered getter.

## Minimal example (original)

```html
<div x-data="{
        query: '', selected: null,
        options: [{ id: 1, name: 'Photography' }, { id: 2, name: 'Design' }],
        get filtered() { return this.options.filter(o => o.name.toLowerCase().includes(this.query.toLowerCase())) },
     }">
    <div x-combobox x-model="selected">
        <input x-combobox:input :display-value="option => option?.name" x-on:change="query = $event.target.value" placeholder="Search...">
        <button x-combobox:button type="button" aria-label="Show options">&#9662;</button>
        <ul x-combobox:options x-cloak>
            <li x-show="filtered.length === 0">No results.</li>
            <template x-for="option in filtered" :key="option.id">
                <li x-combobox:option :value="option" :class="{ 'active': $comboboxOption.isActive }">
                    <span x-text="option.name"></span>
                </li>
            </template>
        </ul>
    </div>
</div>
```

## Notes for Altitude

The nav search box posts to HTMX; a combobox would only apply if the app grows client-side suggestions (people names, folders).
