# Toggle

- **Source:** https://alpinejs.dev/component/toggle (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Lesson (hand-built with core Alpine)
- **Dependencies:** alpinejs v3.x

## What it does

A switch-style on/off control with a label, exposed to assistive technology as a native switch.

## How it is built

- `x-data="{ value: false }"`; the control is a `<button type="button" role="switch">` with `:aria-checked="value"` and `@click="value = !value"`.
- The label gets an id from `x-id` / `$id()` and the button points at it with `:aria-labelledby`; clicking the label forwards to the button via `$refs.toggle.click()`.
- The knob position and track color are `:class` bindings on `value`; the inner elements are `aria-hidden`.

## Keyboard and accessibility

- Enter and Space toggle (native button).
- `role="switch"` with `aria-checked`; label association via `aria-labelledby`.

## State

`value: boolean`.

## Minimal example (original)

```html
<div x-data="{ value: false }" x-id="['label']">
    <span :id="$id('label')" x-on:click="$refs.toggle.click()">Send notifications</span>
    <button x-ref="toggle" type="button" role="switch"
            :aria-checked="value" :aria-labelledby="$id('label')"
            :class="value ? 'on' : 'off'"
            x-on:click="value = !value">
        <span aria-hidden="true" class="knob"></span>
    </button>
</div>
```
