# Dialog (Modal), headless

- **Source:** https://alpinejs.dev/component/headless-dialog (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Headless (`@alpinejs/ui`)
- **Dependencies:** alpinejs v3.x, @alpinejs/ui v3.x, @alpinejs/focus v3.x

## What it does

Headless component from the `@alpinejs/ui` plugin: behavior, keyboard handling, and ARIA come from the plugin's directives; all styling is yours. Full documentation is linked from each page (https://alpinejs.dev/components#headless).

A dialog whose open state is either internal or bound with `x-model`. The plugin provides the
overlay, panel, and title roles, focus trapping, and Escape/overlay dismissal.

## Variants on the page

- Centered dialog
- Flyout (slides in from the right)
- Flyout (left)

## How it is built

- `x-dialog` on the root, optionally with `x-model="open"` to control it from outside; open it with `@click="open = true"` or call the plugin's `$dialog.close()` from inside.
- `x-dialog:overlay` marks the backdrop (the demo adds `x-transition.opacity`).
- `x-dialog:panel` marks the content box (the demo adds `x-transition`, and the flyout variants use explicit `x-transition:enter` / `:enter-start` / `:enter-end` / `:leave` / `:leave-start` / `:leave-end` classes to slide from the side).
- `x-dialog:title` labels the dialog.
- `x-cloak` on the root avoids a flash before Alpine initializes.

## Keyboard and accessibility

- Escape and overlay click close (plugin behavior).
- Focus is trapped inside the panel and restored on close (the plugin uses `@alpinejs/focus`).
- `role="dialog"`, `aria-modal`, and `aria-labelledby` are set by the directives.

## State

`open: boolean` when bound with `x-model`; otherwise internal to the directive.

## Minimal example (original)

```html
<div x-data="{ open: false }">
    <button type="button" x-on:click="open = true">Open dialog</button>

    <div x-dialog x-model="open" x-cloak class="overlay-root">
        <div x-dialog:overlay x-transition.opacity class="backdrop"></div>
        <div x-dialog:panel x-transition class="panel">
            <h2 x-dialog:title>Ready to go live?</h2>
            <p>Once published, the content is visible to everyone.</p>
            <button type="button" x-on:click="$dialog.close()">Cancel</button>
            <button type="button" x-on:click="open = false">Publish</button>
        </div>
    </div>
</div>
```

## Notes for Altitude

This is the plugin equivalent of the hand-built Modal lesson. Adopting it would replace `x-show` + `x-trap` on the hosts with `x-dialog` directives, but the plugin owns the open state per element, which does not fit the shared `modal` store and the one-modal rule without extra glue.
