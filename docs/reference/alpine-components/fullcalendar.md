# FullCalendar

- **Source:** https://alpinejs.dev/component/fullcalendar (Alpine UI Components, licensed; this note is a summary in our own words and the example below is original)
- **Type:** Integration (third-party library wired through `x-init` / `x-ref`)
- **Dependencies:** alpinejs v3.x, fullcalendar v5.11 (CSS + script)

## What it does

Integration recipe: the library is loaded from a CDN, initialized in the component's `init()` against an `x-ref` element, and kept in sync with Alpine state through the library's change event and `$watch`. Events live in Alpine state and the page also lists them and adds new ones from a form.

## How it is built

- `x-data` holds `calendar` (the instance), `events: [{ id, title, start, end }]`, and form fields `newEventTitle`, `newEventStart`, `newEventEnd`.
- `init()` creates `new FullCalendar.Calendar($refs.calendar, { initialView: 'dayGridMonth', events })` and calls `render()`.
- A form with `x-on:submit.prevent` and `x-model` inputs pushes to `events` and calls `calendar.addEvent(...)`; the list of events is rendered with `x-for` and `x-text`.

## Keyboard and accessibility

- FullCalendar's own toolbar buttons are focusable; the month grid itself is largely mouse-driven.

## State

`calendar`, `events: Array<{ id, title, start, end }>`, new-event form fields.

## Minimal example (original)

```html
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/fullcalendar@5.11.0/main.min.css">
<script src="https://cdn.jsdelivr.net/npm/fullcalendar@5.11.0/main.min.js"></script>

<div x-data="{
        calendar: null, title: '', start: '',
        events: [{ id: 1, title: 'Backup', start: '2026-09-10' }],
        init() {
            this.calendar = new FullCalendar.Calendar(this.$refs.calendar, { initialView: 'dayGridMonth', events: this.events })
            this.calendar.render()
        },
        add() {
            const event = { id: Date.now(), title: this.title, start: this.start }
            this.events.push(event); this.calendar.addEvent(event); this.title = ''; this.start = ''
        },
     }">
    <div x-ref="calendar"></div>
    <form x-on:submit.prevent="add()">
        <input x-model="title" placeholder="Title" required>
        <input x-model="start" type="date" required>
        <button type="submit">Add</button>
    </form>
    <ul><template x-for="e in events" :key="e.id"><li x-text="`${e.start}: ${e.title}`"></li></template></ul>
</div>
```
