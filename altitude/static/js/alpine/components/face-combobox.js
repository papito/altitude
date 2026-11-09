/**
 * The Change combobox of a People row in Asset detail's Detail panel (`htmx/detail_panel_people`,
 * views/AGENTS.md **Detail panel**): the people a Face can be given to, with autocomplete. Hand-built
 * with core Alpine on the ARIA combobox pattern (`docs/reference/alpine-components/headless-combobox.md`):
 * an input with `role="combobox"` (`aria-expanded`, `aria-controls`, `aria-activedescendant`) over a
 * `role="listbox"` popover of `role="option"` rows, the active one `aria-selected`, with focus staying in
 * the input throughout. The listbox is a manual popover in the top layer, so the panel's scrolling never
 * clips it, placed by CSS anchor positioning under the input (the anchor named per Face in `init`).
 *
 * The component root is a row's `.face-actions`, holding Change and Remove (`changing` false) or the
 * combobox in their place (`changing` true): Change calls `open()`, which loads the options once per
 * opening from the assignable route for the Face (`assignablePeopleUrl`, the one place the URL is
 * built), parses the fragment's options and filters them on the client as the user types, the first
 * `MAX_SHOWN_OPTIONS` matches rendered with lazy covers. The Face's own Person is left out of the
 * matches. The fragment opens with the Suggested group, the people whose Faces are nearest the Face's
 * vector (`docs/faces.md` **Corrections**), each marked and listed once: the component renders them as
 * a labelled group above the list while nothing is typed, so the listbox announces the heading with
 * them, and from the first character the group is left out and they are filtered with the rest, ahead
 * of them. The server is the one source of the options, the component their one renderer. A name that
 * no Person has, within the name length rules, is offered as the Create row; an existing name, in any
 * case or Unicode form, picks that Person instead, so a name is never created twice. Enter picks the
 * active option: before the arrows move it, the Person of the exact name, else the first match, else
 * the Create row. A pick is announced as `Const.events.facePersonPicked` with the Face and the Person
 * or the name; `detail-panel.js` sends the correction. The X (`cancel`) announces
 * `Const.events.faceChangeCancelled`, which clears the selection, and `reset` returns the row to its
 * buttons; the panel dispatches `face-combobox-close` on every row as the selection changes.
 *
 * Escape with the listbox open closes the listbox alone: `global.js` calls `closeOpenFaceCombobox`
 * before it would close Asset detail. The browser reports the popover's state through `toggle`, which
 * `expanded` follows. `m` and `p` are left to the input, as every text field's keys are.
 */
import { Const } from "../../constants.js"
import { context } from "../../context.js"
import { showErrorSnackBar } from "../../common/snackbar.js"
import { getHttpErrorMessage, http } from "../../http/client.js"

// The listbox popover of every combobox, by which the open one is found
export const LISTBOX_CLASS = "face-listbox"

// The most matches rendered at once: the list can hold thousands of people, and the query narrows it
export const MAX_SHOWN_OPTIONS = 50

// The key of the Create row among the options' Person IDs (no Person ID is this word)
export const CREATE_KEY = "create"

/**
 * The URL the options load from: the one place it is built. With the Face (`faceId`) the route opens
 * the options with the Suggested group of the people nearest it; without, it answers the list alone.
 */
export function assignablePeopleUrl({ repoId, faceId }) {
    const url = `/htmx/people/r/${repoId}/assignable`
    return faceId ? `${url}?faceId=${encodeURIComponent(faceId)}` : url
}

/**
 * Reads the options out of the assignable people fragment (`htmx/assignable_people`), wherever they
 * sit in it: each option's Person, their name, whether Hidden, their cover's URL, and whether the
 * option is in the Suggested group (`data-suggested`).
 */
export function parseAssignablePeople(html) {
    const doc = new DOMParser().parseFromString(html, "text/html")

    return [...doc.querySelectorAll('[role="option"][data-person-id]')].map(
        (optionEl) => ({
            personId: optionEl.dataset.personId,
            name: optionEl.dataset.personName ?? "",
            hidden: optionEl.dataset.personHidden === "true",
            suggested: optionEl.dataset.suggested !== undefined,
            coverUrl:
                optionEl.querySelector("img")?.getAttribute("src") ?? null,
        }),
    )
}

/**
 * Closes the open listbox, if any, and returns whether one was open. Escape's first job in Asset
 * detail (`global.js`), ahead of closing the dialog.
 */
export function closeOpenFaceCombobox() {
    const listboxEl = document.querySelector(`.${LISTBOX_CLASS}:popover-open`)
    if (!listboxEl) {
        return false
    }

    listboxEl.hidePopover()
    return true
}

/** A name as the server compares names: trimmed, in NFC, lowercased (`PersonService.nameKey`) */
function nameKey(text) {
    return text.trim().normalize("NFC").toLowerCase()
}

export function faceCombobox() {
    // The latest load of the options; an earlier one's answer is dropped
    let loadToken = 0

    return {
        CREATE_KEY,

        // The combobox shows in place of Change and Remove
        changing: false,
        // The listbox popover is open (the browser's state, followed through `toggle`)
        expanded: false,
        // What was typed
        query: "",
        // Every assignable Person, as loaded on this opening
        options: [],
        // The option Enter picks: a Person's ID or the Create row's key, or null
        activeKey: null,

        /** The listbox is anchored to this row's input: an anchor name per Face, since a panel holds a row per Face */
        init() {
            const anchorName = `--face-combobox-${this.faceId}`
            this.$refs.input.style.setProperty("anchor-name", anchorName)
            this.$refs.listbox.style.setProperty("position-anchor", anchorName)
        },

        /** The Face of the row, read from the row (`htmx/detail_panel_people`) */
        get faceId() {
            return this.rowEl()?.getAttribute(Const.attributes.faceId) ?? ""
        },

        /** The Face's own Person, who is no option */
        get personId() {
            return this.rowEl()?.getAttribute(Const.attributes.personId) ?? ""
        },

        get listboxId() {
            return `faceListbox-${this.faceId}`
        },

        /** The Suggested group's heading, which labels the group for the listbox */
        get suggestedHeadingId() {
            return `faceSuggested-${this.faceId}`
        },

        /** The Suggested group: the people nearest the Face, closest first, shown while nothing is typed */
        get suggested() {
            return nameKey(this.query) === ""
                ? this.options.filter((option) => option.suggested)
                : []
        },

        /**
         * The options the query leaves, in the server's order (named first, then Unknowns), the first
         * few dozen: with nothing typed, the list under the Suggested group, which holds the rest;
         * typed, every option, the suggested people ahead of the others
         */
        get matches() {
            const key = nameKey(this.query)

            return this.options
                .filter(
                    (option) =>
                        option.personId !== this.personId &&
                        (key === ""
                            ? !option.suggested
                            : nameKey(option.name).includes(key)),
                )
                .slice(0, MAX_SHOWN_OPTIONS)
        },

        /**
         * The name the Create row offers: what was typed, trimmed, when it is a name no Person has (in
         * any case, the Face's own Person included) and the server would accept its length; else null.
         */
        get createName() {
            const name = this.query.trim()
            const { min, max } = Const.people.nameLength

            if (name.length < min || name.length > max) {
                return null
            }

            const key = nameKey(name)
            return this.options.some((option) => nameKey(option.name) === key)
                ? null
                : name
        },

        get createLabel() {
            return `Create '${this.createName}'`
        },

        /** The keys of the options shown, in order: the Suggested group, the matches, then the Create row */
        get keys() {
            return [
                ...this.suggested.map((option) => option.personId),
                ...this.matches.map((option) => option.personId),
                ...(this.createName ? [CREATE_KEY] : []),
            ]
        },

        /** The active option's element ID, for `aria-activedescendant`; none when the active option left the list */
        get activeOptionId() {
            return this.activeKey && this.keys.includes(this.activeKey)
                ? this.optionId(this.activeKey)
                : null
        },

        optionId(key) {
            return `faceOption-${this.faceId}-${key}`
        },

        isActive(key) {
            return this.activeKey === key
        },

        rowEl() {
            return this.$root.closest(`[${Const.attributes.faceId}]`)
        },

        /** Change: the combobox takes the buttons' place, the options load, and the listbox opens on all of them */
        async open() {
            this.changing = true
            this.query = ""
            this.activeKey = null
            this.$refs.input.value = ""
            const token = ++loadToken

            try {
                const response = await http.get(
                    assignablePeopleUrl({
                        repoId: context.getRepoId(),
                        faceId: this.faceId,
                    }),
                )
                if (token !== loadToken || !this.changing) {
                    return
                }

                this.options = parseAssignablePeople(response.data)
            } catch (error) {
                if (token !== loadToken) {
                    return
                }

                console.error(
                    `Error loading the assignable people: ${getHttpErrorMessage(error)}`,
                )
                showErrorSnackBar("Could not load the people")
                this.reset()
                return
            }

            await this.$nextTick()
            this.$refs.input.focus()
            this.showListbox()
        },

        showListbox() {
            const listboxEl = this.$refs.listbox
            if (listboxEl.isConnected && !listboxEl.matches(":popover-open")) {
                listboxEl.showPopover()
            }
        },

        hideListbox() {
            const listboxEl = this.$refs.listbox
            if (listboxEl.matches(":popover-open")) {
                listboxEl.hidePopover()
            }
        },

        /** The browser owns the popover's visibility; `expanded` follows it */
        onToggle(event) {
            this.expanded = event.newState === "open"
        },

        /** Typing filters the options and puts the active option back at its default; the listbox opens if Escape had closed it */
        onInput(event) {
            this.query = event.target.value
            this.activeKey = this.defaultActiveKey()
            this.showListbox()
        },

        /**
         * The option Enter picks before the arrows move it: the Person of the exact name, else the
         * first match, else the Create row; nothing while the input is empty.
         */
        defaultActiveKey() {
            const key = nameKey(this.query)
            if (key === "") {
                return null
            }

            const exact = this.matches.find(
                (option) => nameKey(option.name) === key,
            )

            return (
                exact?.personId ??
                this.matches[0]?.personId ??
                (this.createName ? CREATE_KEY : null)
            )
        },

        /**
         * Down and Up move the active option, wrapping, and open the listbox when it is closed; Enter
         * picks the active option, or opens the listbox when it is closed. Escape is `global.js`'s,
         * which closes the listbox alone. Every other key is the input's own.
         */
        onKeydown(event) {
            if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                event.preventDefault()
                this.showListbox()
                this.moveActive(event.key === "ArrowDown" ? 1 : -1)
            } else if (event.key === "Enter") {
                event.preventDefault()
                if (!this.expanded) {
                    this.showListbox()
                } else if (this.activeKey) {
                    this.pick(this.activeKey)
                }
            }
        },

        moveActive(delta) {
            const keys = this.keys
            if (keys.length === 0) {
                return
            }

            const current = keys.indexOf(this.activeKey)
            const next =
                current < 0
                    ? delta > 0
                        ? 0
                        : keys.length - 1
                    : (current + delta + keys.length) % keys.length
            this.activeKey = keys[next]

            this.$nextTick(() =>
                document
                    .getElementById(this.optionId(this.activeKey))
                    ?.scrollIntoView({ block: "nearest" }),
            )
        },

        /** A click on an option picks it; the listbox's `mousedown` is prevented, so focus stays in the input */
        onOptionClick(event) {
            const key = this.optionKeyOf(event.target)
            if (key) {
                this.pick(key)
            }
        },

        /** The pointer over an option makes it the active one, as the arrows would */
        onOptionHover(event) {
            const key = this.optionKeyOf(event.target)
            if (key) {
                this.activeKey = key
            }
        },

        optionKeyOf(target) {
            return (
                target.closest?.("[data-option-key]")?.dataset.optionKey ?? null
            )
        },

        /** Focus leaving the combobox (Tab, a click elsewhere) closes the listbox; the input stays as it was */
        onFocusOut(event) {
            if (!this.$root.contains(event.relatedTarget)) {
                this.hideListbox()
            }
        },

        /**
         * Picks the option of `key`: the Create row's name or a match's Person, announced for
         * `detail-panel.js` to send the correction. An option no longer shown picks nothing.
         */
        pick(key) {
            const detail = { faceId: this.faceId }

            if (key === CREATE_KEY) {
                if (!this.createName) {
                    return
                }
                detail.name = this.createName
            } else {
                if (!this.keys.includes(key)) {
                    return
                }
                detail.personId = key
            }

            this.hideListbox()
            document.body.dispatchEvent(
                new CustomEvent(Const.events.facePersonPicked, { detail }),
            )
        },

        /** The X: back to the buttons, with nothing selected (the panel clears the selection) */
        cancel() {
            const faceId = this.faceId
            this.reset()
            document.body.dispatchEvent(
                new CustomEvent(Const.events.faceChangeCancelled, {
                    detail: { faceId },
                }),
            )
        },

        /** The row's original state: Change and Remove, no listbox, nothing typed; a load in flight is dropped */
        reset() {
            loadToken++
            this.hideListbox()
            this.changing = false
            this.query = ""
            this.activeKey = null
            this.options = []
        },
    }
}
