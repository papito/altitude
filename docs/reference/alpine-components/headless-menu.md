# Menu (Dropdown), headless

- **Source:** https://alpinejs.dev/component/headless-menu (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Headless (`@alpinejs/ui`)
- **Dependencies:** alpinejs v3.x, @alpinejs/ui v3.x, @alpinejs/focus v3.x

## What it does

Headless component from the `@alpinejs/ui` plugin: behavior, keyboard handling, and ARIA come from the plugin's directives; all styling is yours. Full documentation is linked from each page (https://alpinejs.dev/components#headless).

An action menu with full keyboard support: arrow keys move an active item, typing jumps to items,
Enter activates, Escape closes.

## Variants on the page

- Basic
- With icons
- Icon trigger
- Groups with headings
- Keyboard hints

## How it is built

- `x-menu` on the wrapper, `x-menu:button` on the trigger, `x-menu:items` on the list (the demo adds `x-transition.origin.top.left` and `x-cloak`), `x-menu:item` on each entry (`<a>` or `<button>`).
- `$menuItem.isActive` and `$menuItem.isDisabled` style the highlighted and disabled entries.
- Variants add icons, use an icon-only trigger, group items under headings, and show keyboard shortcut hints; the directives are unchanged.

## Keyboard and accessibility

- Up/Down move the active item, Home/End jump, Enter/Space activate, Escape closes and refocuses the button; typing letters jumps to matching items.
- `role="menu"`, `role="menuitem"`, `aria-haspopup`, `aria-expanded` set by the directives.

## State

Internal to the directive.

## Minimal example (original)

```html
<div x-menu>
    <button x-menu:button type="button">Options</button>
    <div x-menu:items x-transition.origin.top.left x-cloak>
        <button x-menu:item type="button" :class="{ 'highlight': $menuItem.isActive }">Rename</button>
        <button x-menu:item type="button" :class="{ 'highlight': $menuItem.isActive }">Move</button>
        <button x-menu:item type="button" disabled :class="{ 'muted': $menuItem.isDisabled }">Delete</button>
    </div>
</div>
```

## Notes for Altitude

The closest match for the folder context menu if it moves from server-rendered spans to a keyboard-navigable menu.
