# Tooltip

- **Source:** https://alpinejs.dev/component/tooltip (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Lesson (custom directive and magic)
- **Dependencies:** alpinejs v3.x, @popperjs/core v2.x, tippy.js v6.x

## What it does

Two ways to show a tooltip: a directive for hover/focus tooltips and a magic function for a
transient message after an action (for example "Copied!").

## How it is built

- `Alpine.directive('tooltip', (el, { expression }) => tippy(el, { content: expression }))` turns `x-tooltip="'Message'"` into a hover tooltip; tippy handles positioning through Popper.
- `Alpine.magic('tooltip', el => message => { ... })` creates a tippy instance with `trigger: 'manual'`, shows it, and after about two seconds hides and destroys it. Used as `@click="$tooltip('Copied!')"`.
- Both must be registered before `Alpine.start()`, the same way plugins are.

## Keyboard and accessibility

- tippy adds `aria-describedby` for hover tooltips and shows them on focus as well as hover.
- The transient variant is purely visual; pair it with an `aria-live` region if the message matters.

## State

None in the component; tippy instances are created per element.

## Minimal example (original)

```html
<script type="module">
    import { Alpine } from "/static/js/lib/alpine.esm.min.js"
    // assumes tippy is loaded globally
    Alpine.directive("tooltip", (el, { expression }) => { tippy(el, { content: expression }) })
    Alpine.magic("tooltip", (el) => (message) => {
        const instance = tippy(el, { content: message, trigger: "manual" })
        instance.show()
        setTimeout(() => { instance.hide(); setTimeout(() => instance.destroy(), 150) }, 2000)
    })
</script>

<button type="button" x-data x-tooltip="'Copies the link'" x-on:click="$tooltip('Copied!')">Copy link</button>
```

## Notes for Altitude

Altitude has the magic and not the directive, with neither tippy nor Popper: `$tooltip('Message')`
(`altitude/static/js/common/tooltip.js`, registered with everything else `html_common` binds) writes the
message into `#tooltip`, a `popover="manual"` host of `includes/html_common`, and CSS anchor positioning
(`#tooltip` in `core.css`) centers it above the element, named by an inline `anchor-name`. It hides after
two seconds or at the next press or key, and the message is announced through the snackbar's polite live
region. The batch bar's Move button, a drag handle, shows its hint this way on a click. Hover tooltips stay
native `title` attributes (for example on grid metadata). The detail is under **Tooltip** in
`altitude/views/AGENTS.md`.
