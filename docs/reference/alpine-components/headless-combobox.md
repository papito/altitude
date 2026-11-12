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

The nav search box posts to HTMX. There are two comboboxes. The advanced search panel's `filterCombobox`
(`static/js/alpine/components/filter-combobox.js`, views/AGENTS.md **Advanced search panel**) is the multiple (tags)
variant, and the single one for its folder: the chosen options are removable chips inside the field, the options are
matched and rendered by the server for the text typed (debounced, the latest answer alone shown) and put in the listbox
as they come, with the matched starts of words marked on the client, and Backspace in an empty input removes the last
chip. The other is the Change combobox of Asset detail's People rows
(`static/js/alpine/components/face-combobox.js`, views/AGENTS.md **Detail panel**), hand-built with core Alpine since
`@alpinejs/ui` is not vendored: the input carries `role="combobox"`, `aria-autocomplete="list"`, `aria-expanded`,
`aria-controls` and `aria-activedescendant`; the options (`role="option"`, the active one `aria-selected`) live in a
`role="listbox"` that is a manual popover anchored under the input by CSS, in the top layer so a scrolling panel never
clips it, with `mousedown` prevented so focus stays in the input; the options load once per opening from the server's
fragment and filter on the client; while nothing is typed the nearest people sit first as a labelled group, the APG
listbox pattern's `li[role=presentation] > span#heading + ul[role=group][aria-labelledby]`, which `@alpinejs/ui` has no
directive for; a query no option matches offers a Create row; Escape closes the listbox alone (`global.js`), as it does
here.
