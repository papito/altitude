# Tabs

- **Source:** https://alpinejs.dev/component/tabs (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Lesson (hand-built with core Alpine)
- **Dependencies:** alpinejs v3.x, @alpinejs/focus v3.x

## What it does

A tab list with panels, following the WAI-ARIA tabs pattern with automatic activation: moving
focus between tabs with the arrow keys selects them, and only the selected tab is in the Tab order.

## How it is built

- `x-data` holds `selectedId` and helpers `select(id)`, `isSelected(id)`, and `whichChild(el, parent)`; `init()` selects the first tab after `$nextTick` so ids exist.
- `x-id` generates paired ids for each tab and its panel; tabs set `:aria-selected` and `:tabindex="isSelected(id) ? 0 : -1"` (roving tabindex).
- `@click` and `@focus` on a tab both select it (automatic activation).
- Arrow keys, Home, End, PageUp, PageDown are handled on the tablist with `.prevent.stop` and move focus using the focus plugin's `$focus.within($el).wrap().next()` / `.previous()` / `.first()` / `.last()`.
- Panels have `role="tabpanel"`, `:aria-labelledby` the tab id, and `x-show`.

## Keyboard and accessibility

- Left/Right (and Up/Down) move between tabs and wrap; Home/End jump to the first/last tab.
- Tab key leaves the tablist and enters the visible panel's content.
- `role="tablist"`, `role="tab"`, `aria-selected`, `role="tabpanel"`, `aria-labelledby`.

## State

`selectedId: string`. Methods: `select(id)`, `isSelected(id)`.

## Minimal example (original)

```html
<div x-data="{ selected: 'one', select(id) { this.selected = id } }">
    <div role="tablist"
         x-on:keydown.right.prevent.stop="$focus.wrap().next()"
         x-on:keydown.left.prevent.stop="$focus.wrap().previous()"
         x-on:keydown.home.prevent.stop="$focus.first()"
         x-on:keydown.end.prevent.stop="$focus.last()">
        <button type="button" role="tab" id="tab-one" aria-controls="panel-one"
                :aria-selected="selected === 'one'" :tabindex="selected === 'one' ? 0 : -1"
                x-on:click="select('one')" x-on:focus="select('one')">One</button>
        <button type="button" role="tab" id="tab-two" aria-controls="panel-two"
                :aria-selected="selected === 'two'" :tabindex="selected === 'two' ? 0 : -1"
                x-on:click="select('two')" x-on:focus="select('two')">Two</button>
    </div>

    <div role="tabpanel" id="panel-one" aria-labelledby="tab-one" x-show="selected === 'one'">First panel</div>
    <div role="tabpanel" id="panel-two" aria-labelledby="tab-two" x-show="selected === 'two'">Second panel</div>
</div>
```

## Notes for Altitude

The explorer tabs (Folders / Albums / People) are plain links styled by `tabs.css` and swapped by HTMX; this pattern is the reference if they need arrow-key navigation.
