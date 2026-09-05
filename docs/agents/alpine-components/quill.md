# Quill (rich text editor)

- **Source:** https://alpinejs.dev/component/quill (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, quill.js v1.3.x (snow theme CSS + script)

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`.

## How it is built

- `x-data` holds `value` (HTML) and an `init()` that creates `new Quill($refs.editor, { theme: 'snow' })`.
- `init()` seeds the editor with `value` by writing to the editor root's `innerHTML`.
- `quill.on('text-change', ...)` copies the root's `innerHTML` back into `value`.

## Keyboard and accessibility

- Quill supplies toolbar and shortcuts.

## State

`value: string` (HTML).

## Minimal example (original)

```html
<link rel="stylesheet" href="https://cdn.quilljs.com/1.3.6/quill.snow.css">
<script src="https://cdn.quilljs.com/1.3.6/quill.js"></script>

<div x-data="{
        value: '<p>Hello</p>',
        init() {
            const quill = new Quill(this.$refs.editor, { theme: 'snow' })
            quill.root.innerHTML = this.value
            quill.on('text-change', () => { this.value = quill.root.innerHTML })
        },
     }">
    <div x-ref="editor"></div>
</div>
```
