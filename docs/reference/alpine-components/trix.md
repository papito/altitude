# Trix (rich text editor)

- **Source:** https://alpinejs.dev/component/trix (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, trix v2.x (CSS + UMD script)

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`. Trix is a web component, so there is no constructor call: a hidden input feeds the editor and the editor's own event reports changes.

## How it is built

- `x-data="{ value: '' }"` holds the HTML.
- A hidden `<input>` gets an id from `x-id` / `$id()`; the `<trix-editor>` element points at it with `:input`.
- `x-init` on the editor pushes the initial `value` into it (Trix loads the input's value on connect).
- `@trix-change` on the editor copies `$event.target.value` back into `value`.

## Keyboard and accessibility

- Trix provides its own toolbar with keyboard shortcuts; nothing extra is needed from Alpine.

## State

`value: string` (HTML).

## Minimal example (original)

```html
<link rel="stylesheet" href="https://unpkg.com/trix@2/dist/trix.css">
<script src="https://unpkg.com/trix@2/dist/trix.umd.js"></script>

<div x-data="{ value: '<p>Hello</p>' }" x-id="['editor']">
    <input :id="$id('editor')" type="hidden" :value="value">
    <trix-editor :input="$id('editor')" x-on:trix-change="value = $event.target.value"></trix-editor>
</div>
```
