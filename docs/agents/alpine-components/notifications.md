# Notifications

- **Source:** https://alpinejs.dev/component/notifications (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Lesson (hand-built with core Alpine)
- **Dependencies:** alpinejs v3.x

## What it does

A toast stack. Any code dispatches a `notify` event with a message and type; a listener component
appends it to a list, animates it in, and removes it after a timeout or when dismissed.

## How it is built

- One component listens with `@notify.window` and keeps `notifications: []`; `add(notification)` pushes an object `{ id, type, content }`, `remove(id)` filters it out.
- The list is rendered with `x-for`; each item uses `x-show` with `x-transition.duration.500ms`, and `$nextTick` is used to flip the item visible after insertion so the enter transition plays.
- A `setTimeout` removes each notification after a few seconds; a close button calls `remove(id)`.
- Producers call `$dispatch('notify', { type: 'success', content: '...' })` from anywhere; the demo uses a small form with `x-on:submit.prevent` and `x-model` to send one.
- The container is an `aria-live="polite"` region with `role="status"` so screen readers announce new messages.

## Keyboard and accessibility

- Dismiss buttons are real buttons (Enter/Space).
- `aria-live="polite"` / `role="status"` on the region; icons are `aria-hidden`.

## State

`notifications: Array<{ id, type, content }>`. Methods: `add()`, `remove(id)`.

## Minimal example (original)

```html
<div x-data="{
        items: [],
        add(n) { const id = Date.now(); this.items.push({ id, ...n }); setTimeout(() => this.remove(id), 3000) },
        remove(id) { this.items = this.items.filter(i => i.id !== id) },
     }"
     x-on:notify.window="add($event.detail)"
     role="status" aria-live="polite">
    <template x-for="item in items" :key="item.id">
        <div x-transition.duration.300ms :class="item.type">
            <span x-text="item.content"></span>
            <button type="button" x-on:click="remove(item.id)" aria-label="Dismiss">&times;</button>
        </div>
    </template>
</div>

<!-- anywhere else -->
<button type="button" x-data x-on:click="$dispatch('notify', { type: 'success', content: 'Saved' })">Save</button>
```

## Notes for Altitude

Altitude's snackbar (`js/common/snackbar.js`) does the same job imperatively with one message at a time. This is the shape to move to if stacked or multiple simultaneous messages are wanted.
