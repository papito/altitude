# Alpine UI Components, reference notes

Notes on the components published at https://alpinejs.dev/components (Alpine UI Components,
a licensed product). Each note describes what a component does and how it is built: the Alpine
directives, plugins, ARIA roles, keyboard behavior, and state involved. The site's own text and
source are not reproduced; every example here is an original minimal snippet written for this
reference. Consult the site for the full, styled implementations.

Reviewed on 2026-09-05. All pages declare `alpinejs v3.x`; extra dependencies are listed per note.
Altitude vendors Alpine 3.13.10 and the focus plugin (`altitude/static/js/lib/README.md`); the
`@alpinejs/ui`, `@alpinejs/collapse`, and `@alpinejs/intersect` plugins and the third-party
libraries below are not vendored.

## Lessons (hand-built with core Alpine)

| Note | Extra dependencies |
|---|---|
| [Dropdown](dropdown.md) | none |
| [Modal](modal.md) | `@alpinejs/focus` |
| [Accordion](accordion.md) | `@alpinejs/collapse` |
| [Carousel](carousel.md) | `@alpinejs/intersect`, smoothscroll-polyfill |
| [Tabs](tabs.md) | `@alpinejs/focus` |
| [Notifications](notifications.md) | none |
| [Radio Group](radio-group.md) | none |
| [Toggle](toggle.md) | none |
| [Tooltip](tooltip.md) | @popperjs/core, tippy.js |

## Headless components (`@alpinejs/ui`)

| Note | Extra dependencies |
|---|---|
| [Dialog (Modal)](headless-dialog.md) | `@alpinejs/focus` |
| [Popover](headless-popover.md) | `@alpinejs/focus` |
| [Tabs](headless-tabs.md) | `@alpinejs/focus` |
| [Switch (Toggle)](headless-switch.md) | none |
| [Disclosure (Accordion)](headless-disclosure.md) | `@alpinejs/collapse` |
| [Menu (Dropdown)](headless-menu.md) | `@alpinejs/focus` |
| [Radio](headless-radio.md) | `@alpinejs/focus` |
| [Listbox (Select)](headless-listbox.md) | `@alpinejs/focus` |
| [Combobox (Autocomplete)](headless-combobox.md) | `@alpinejs/focus` |

## Integrations (third-party libraries)

| Note | Library |
|---|---|
| [Trix](trix.md) | trix 2.x |
| [Quill](quill.md) | quill.js 1.3.x |
| [SimpleMDE](simple-mde.md) | simplemde 1.11.x |
| [Chart.js](chart-js.md) | chart.js 3.5.x |
| [ApexCharts](apexcharts.md) | apexcharts 3.35.x |
| [Flatpickr](flatpickr.md) | flatpickr 4.6.x |
| [Date Range Picker](date-range-picker.md) | daterangepicker 3.1.x, jquery 3.5.x, moment 2.29.x |
| [FullCalendar](fullcalendar.md) | fullcalendar 5.11 |
| [Select2](select2.md) | select2 4.x, jquery 3.5.x |
| [Choices.js](choices.md) | choices.js 10.1.x |
| [Glide](glide.md) | glide 3.5.x |
| [Splide](splide.md) | splide 4.0.x |

## Relevance to Altitude

- The modal hosts in `views/includes/html_common.scala.html` follow the [Modal](modal.md) lesson;
  see `../../../plans/done/alpine-modal-migration.md` for the deliberate differences.
- The folder and album context menus are native HTML popovers coordinated by core Alpine
  (`static/js/alpine/components/context-menu.js`); see the Altitude notes in [Dropdown](dropdown.md)
  and [Popover](headless-popover.md). [Menu](headless-menu.md) remains the reference if it ever
  needs roving arrow-key focus; [Notifications](notifications.md) for a stacked snackbar;
  [Flatpickr](flatpickr.md) for a date filter in search.
