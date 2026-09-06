# Dropdown

- **Source:** https://alpinejs.dev/component/dropdown (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Lesson (hand-built with core Alpine)
- **Dependencies:** alpinejs v3.x

## What it does

A button that opens a floating panel of actions. The panel closes on Escape, on a click outside,
and when keyboard focus leaves it, and focus returns to the button when Escape closes it.

## How it is built

- One `x-data` component holding `open` plus `toggle()` and `close(focusAfter)` helpers.
- `x-id` generates a shared id so the button's `:aria-controls` points at the panel and `:aria-expanded` mirrors `open`.
- The panel uses `x-show`, `x-transition.origin.top.left`, and `x-cloak` to avoid a flash before Alpine starts.
- `x-on:click.outside` on the panel closes it; `x-on:focusin.window` closes it when the newly focused element is not inside the panel (`$refs.panel.contains($event.target)`).
- `x-on:keydown.escape.prevent.stop` on the root closes and hands focus back to the button.

## Keyboard and accessibility

- Escape closes the dropdown and refocuses the trigger.
- Tabbing out of the panel closes it, so focus never lands on an invisible menu.
- `aria-expanded` and `aria-controls` on the trigger; no roving focus inside the menu (use the headless Menu for that).

## State

`open: boolean`. Helpers: `toggle()`, `close(focusAfter?)`.

## Minimal example (original)

```html
<div x-data="{ open: false, close(focusAfter) { this.open = false; focusAfter?.focus() } }"
     x-id="['panel']"
     x-on:keydown.escape.prevent.stop="close($refs.button)"
     x-on:focusin.window="if (!$refs.panel.contains($event.target)) open = false">
    <button x-ref="button" type="button"
            :aria-expanded="open" :aria-controls="$id('panel')"
            x-on:click="open = !open">Options</button>

    <div x-ref="panel" :id="$id('panel')" x-show="open" x-cloak
         x-transition.origin.top.left
         x-on:click.outside="close($refs.button)">
        <button type="button" x-on:click="close($refs.button)">Rename</button>
        <button type="button" x-on:click="close($refs.button)">Delete</button>
    </div>
</div>
```

## Notes for Altitude

The folder context menu (`static/js/common/folder-tree.js` builds it; `static/js/alpine/components/folder-menu.js` coordinates it) is a native `popover="auto"` panel rather than this `x-show` dropdown: the browser owns visibility, outside-click dismissal, and the trigger relationship (`popovertarget`), while the close-on-focus-out idea from this lesson is kept in the component. Escape is handled once for the whole document in `global.js` instead of on the component root, so it works wherever focus is. Unlike a plain dropdown, the panel has a second state: choosing an action loads that action's dialog (add, rename, delete folder) into the panel in place of the actions, and the component switches back to the actions when the panel closes.
