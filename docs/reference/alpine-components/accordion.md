# Accordion

- **Source:** https://alpinejs.dev/component/accordion (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Lesson (hand-built with core Alpine)
- **Dependencies:** alpinejs v3.x, @alpinejs/collapse v3.x

## What it does

A list of question/answer sections where opening one collapses the others, with an animated
height transition.

## How it is built

- The group is one `x-data` holding `active` (the id of the open section, or `null`).
- Each header is a `<button>` whose `@click` sets `active` to its own id, or to `null` when it is already open.
- `:aria-expanded` on the button; the panel has `role="region"` and is labelled by the button.
- The panel uses `x-show` with `x-collapse` (the collapse plugin animates height) and `x-cloak`.
- Decorative chevrons are `aria-hidden="true"` and rotate with a `:class` binding.

## Keyboard and accessibility

- Native button semantics: Enter and Space toggle a section.
- `aria-expanded` reflects the open state; panels are regions labelled by their headers.

## State

`active: string | null` on the group; sections compare their id against it.

## Minimal example (original)

```html
<div x-data="{ active: null }">
    <template x-for="item in [{ id: 'a', q: 'Question 1', a: 'Answer 1' }, { id: 'b', q: 'Question 2', a: 'Answer 2' }]" :key="item.id">
        <section>
            <button type="button" :id="'q-' + item.id"
                    :aria-expanded="active === item.id"
                    x-on:click="active = active === item.id ? null : item.id"
                    x-text="item.q"></button>
            <div x-show="active === item.id" x-collapse x-cloak
                 role="region" :aria-labelledby="'q-' + item.id">
                <p x-text="item.a"></p>
            </div>
        </section>
    </template>
</div>
```
