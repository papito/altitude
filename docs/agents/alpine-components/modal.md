# Modal

- **Source:** https://alpinejs.dev/component/modal (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Lesson (hand-built with core Alpine)
- **Dependencies:** alpinejs v3.x, @alpinejs/focus v3.x

## What it does

A centered dialog over a dimmed page. Focus is trapped inside, the rest of the page is hidden from
assistive technology and cannot scroll, Escape and a backdrop click close it, and focus returns to
the trigger on close.

## How it is built

- Root element: `x-show="open"` with `style="display: none"`, `role="dialog"`, `aria-modal="true"`, and `:aria-labelledby` pointing at the title id generated with `x-id` / `$id()`.
- `x-on:keydown.escape.prevent.stop` on the root sets `open = false`.
- A separate overlay element with `x-show` and `x-transition.opacity`.
- A full-screen wrapper with `x-show`, `x-transition`, and `x-on:click` that closes; the panel inside has `x-on:click.stop` so clicks on it do not reach the wrapper.
- The panel carries `x-trap.noscroll.inert="open"` from the focus plugin: trap focus, lock body scroll, and set `aria-hidden` on everything outside.

## Keyboard and accessibility

- Escape closes.
- Tab and Shift+Tab cycle inside the panel while open.
- Focus moves into the panel on open and back to the previously focused element on close (the plugin's default `x-trap` behavior).
- `role="dialog"`, `aria-modal`, `aria-labelledby`.

## State

`open: boolean` in a local `x-data`.

## Minimal example (original)

```html
<div x-data="{ open: false }" x-id="['title']">
    <button type="button" x-on:click="open = true">Open</button>

    <div x-show="open" style="display: none"
         x-on:keydown.escape.prevent.stop="open = false"
         role="dialog" aria-modal="true" :aria-labelledby="$id('title')"
         class="overlay-root">
        <div x-show="open" x-transition.opacity class="backdrop"></div>

        <div x-show="open" x-transition x-on:click="open = false" class="centering-wrapper">
            <div x-on:click.stop x-trap.noscroll.inert="open" class="panel">
                <h2 :id="$id('title')">Confirm</h2>
                <p>Are you sure?</p>
                <button type="button" x-on:click="open = false">Cancel</button>
                <button type="button" x-on:click="open = false">Confirm</button>
            </div>
        </div>
    </div>
</div>
```

## Notes for Altitude

This is the component the modal hosts in `views/includes/html_common.scala.html` follow. Differences
are documented in `../../../plans/done/alpine-modal-migration.md`: shared `modal` store instead of a local `open`,
Escape handled once in `global.js`, no overlay element or transitions, backdrop click only for asset
detail, and `.noreturn.noautofocus` so `js/common/modal.js` places and restores focus itself.
