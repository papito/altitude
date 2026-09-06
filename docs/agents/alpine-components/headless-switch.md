# Switch (Toggle), headless

- **Source:** https://alpinejs.dev/component/headless-switch (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Headless (`@alpinejs/ui`)
- **Dependencies:** alpinejs v3.x, @alpinejs/ui v3.x

## What it does

Headless component from the `@alpinejs/ui` plugin: behavior, keyboard handling, and ARIA come from the plugin's directives; all styling is yours. Full documentation is linked from each page (https://alpinejs.dev/components#headless).

An on/off switch bound with `x-model`, with an associated label and optional description.

## Variants on the page

- Single switch
- Fieldset of switches
- With descriptions

## How it is built

- `x-switch:group` wraps the label, switch, and description so the plugin can associate them.
- `x-switch:label` and `x-switch:description` mark the text; `x-switch` with `x-model="value"` is the control.
- `$switch.isChecked` is available inside the switch for styling the knob and track.
- The "Fieldset" variant stacks several switches inside a `<fieldset>` with a legend.

## Keyboard and accessibility

- Enter/Space toggle.
- `role="switch"`, `aria-checked`, `aria-labelledby`, `aria-describedby` set by the directives.

## State

`value: boolean` bound with `x-model`.

## Minimal example (original)

```html
<div x-data="{ enabled: false }" x-switch:group>
    <span x-switch:label>Send notifications</span>
    <span x-switch:description>Email me when an import finishes.</span>
    <button x-switch x-model="enabled" type="button" :class="$switch.isChecked ? 'on' : 'off'">
        <span aria-hidden="true" class="knob"></span>
    </button>
</div>
```
