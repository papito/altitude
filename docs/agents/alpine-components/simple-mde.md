# SimpleMDE (Markdown editor)

- **Source:** https://alpinejs.dev/component/simple-mde (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, simplemde v1.11.x (CSS + script)

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`.

## How it is built

- `x-data` holds `value` (Markdown) and `init()` creates `new SimpleMDE({ element: $refs.editor })`.
- `editor.value(value)` seeds the content; `editor.codemirror.on('change', ...)` copies `editor.value()` back into `value`.

## Keyboard and accessibility

- SimpleMDE's toolbar and CodeMirror shortcuts apply.

## State

`value: string` (Markdown).

## Minimal example (original)

```html
<link rel="stylesheet" href="https://cdn.jsdelivr.net/simplemde/1.11/simplemde.min.css">
<script src="https://cdn.jsdelivr.net/simplemde/1.11/simplemde.min.js"></script>

<div x-data="{
        value: '# Title',
        init() {
            const editor = new SimpleMDE({ element: this.$refs.editor })
            editor.value(this.value)
            editor.codemirror.on('change', () => { this.value = editor.value() })
        },
     }">
    <textarea x-ref="editor"></textarea>
</div>
```
