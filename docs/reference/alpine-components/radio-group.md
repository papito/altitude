# Radio Group

- **Source:** https://alpinejs.dev/component/radio-group (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Lesson (hand-built with core Alpine)
- **Dependencies:** alpinejs v3.x

## What it does

A set of styled cards behaving like native radio buttons: one value selected, arrow keys move the
selection, and only one option sits in the Tab order.

## How it is built

- `x-data` holds `value` plus `select(v)`, `isSelected(v)`, `hasRovingTabindex(v)` (true for the selected option, or the first when nothing is selected), `selectNext()` and `selectPrevious()` which wrap around and also move focus.
- Group element: `role="radiogroup"` with `:aria-labelledby`; options: `role="radio"`, `:aria-checked`, `:tabindex`, `:aria-labelledby`, `:aria-describedby`, ids from `x-id` / `$id()`.
- `@click` selects; `@keydown.down` / `.right` select next and `@keydown.up` / `.left` select previous, all `.stop.prevent`; Enter and Space select the focused option.
- The demo mirrors the value into a hidden input for form submission.

## Keyboard and accessibility

- Arrow keys move and select (roving tabindex, wrapping).
- Enter/Space select the focused option.
- `role="radiogroup"`, `role="radio"`, `aria-checked`, labels and descriptions via `aria-labelledby` / `aria-describedby`.

## State

`value: string`. Methods: `select()`, `isSelected()`, `hasRovingTabindex()`, `selectNext()`, `selectPrevious()`.

## Minimal example (original)

```html
<div x-data="{
        value: 'sqlite', options: ['sqlite', 'postgres'],
        index() { return this.options.indexOf(this.value) },
        move(step) { this.value = this.options[(this.index() + step + this.options.length) % this.options.length]; this.$nextTick(() => this.$el.querySelector('[aria-checked=true]').focus()) },
     }"
     role="radiogroup" aria-label="Database engine">
    <template x-for="option in options" :key="option">
        <div role="radio" :aria-checked="value === option"
             :tabindex="value === option ? 0 : -1"
             x-on:click="value = option"
             x-on:keydown.enter.prevent="value = option" x-on:keydown.space.prevent="value = option"
             x-on:keydown.down.prevent="move(1)" x-on:keydown.right.prevent="move(1)"
             x-on:keydown.up.prevent="move(-1)" x-on:keydown.left.prevent="move(-1)"
             x-text="option"></div>
    </template>
    <input type="hidden" name="engine" :value="value">
</div>
```
