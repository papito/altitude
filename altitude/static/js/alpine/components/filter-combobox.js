/**
 * A combobox of the advanced search panel (`htmx/search_panel`, views/AGENTS.md **Advanced search
 * panel**): the folder, the people or the Locations a search is narrowed to, chosen from the names
 * that match what is typed. Hand-built with core Alpine on the ARIA combobox pattern
 * (`docs/reference/alpine-components/headless-combobox.md`), as the Change combobox is
 * (`face-combobox.js`): an input with `role="combobox"` (`aria-expanded`, `aria-controls`,
 * `aria-activedescendant`) over a `role="listbox"` manual popover of `role="option"` rows, the
 * active one `aria-selected`, with focus staying in the input throughout. The listbox is in the top
 * layer and anchored under the field's box by CSS (`htmx/search_panel.css`).
 *
 * The component root is the field (`.field[data-filter]`). The three fields differ only in what
 * their markup says: the options route (`data-options-url`), whether several can be chosen
 * (`data-multiple`), the most that can (`data-max-chosen`, with the line that says so,
 * `data-max-chosen-message`) and the chip's markup (the field's `<template>`).
 *
 * The server matches and renders the options, and the component only places them: typing asks the
 * route for the options of the text (once typing pauses, and only the latest answer is shown), the
 * fragment's `role="option"` elements go into the listbox as they come, less the ones already
 * chosen, and the start of each word a typed word matches is marked (`markMatches`, the server's
 * rule: a typed word matches the start of a word of the name). Down and Up open the listbox and move
 * the active option, wrapping; Enter picks it, and so does a click; Escape closes the listbox alone.
 *
 * A chosen option becomes a chip (`li.chip`, cloned from the field's template) carrying what the
 * option carried: `data-option-id`, `data-option-label` and a Person's `data-option-cover-url`, with
 * the option's label in its `.name` and in the name of its ×, led by a copy of the option's `.cover`
 * when it has one (a Person's cover Face, or the placeholder of a Person with none). The chips are
 * the state: the IDs of the chips, in order and separated by commas, are the value of the
 * field's hidden input, the search parameter. A chip's × removes it, Backspace in an empty input
 * removes the last one, and a field that takes one (the folder) replaces its chip. A field at its
 * most says so in place of its options. `search-panel-clear`, which the panel's Clear dispatches on
 * every field, removes every chip.
 */
import { Const } from "../../constants.js"
import { showErrorSnackBar } from "../../common/snackbar.js"
import { getHttpErrorMessage, http } from "../../http/client.js"

// How long typing pauses before the options are asked for
const LOAD_DELAY_MS = 150

// The input's placeholder in a field of several once it holds a chip; a field of one then has none
const MORE_PLACEHOLDER = "add…"

// The line shown in place of the options when there are none to show
const NO_MATCH = "No matches"
const NOTHING_TO_CHOOSE = "Nothing to choose"

// A word of a name, as the server splits one (`SearchWords`): a run of letters, or a run of digits
const WORD = /\p{L}[\p{L}\p{M}]*|\p{Nd}+/gu

// Inside a word, a camelCase hump, where the server starts a word too
const HUMP = /(?<=\p{Ll}\p{M}*)(?=\p{Lu})/gu

/** A word as the server compares it: no diacritics, lower case */
function fold(text) {
    return text
        .normalize("NFD")
        .replace(/\p{M}+/gu, "")
        .toLowerCase()
}

/** The words of what was typed, as the server compares them */
export function typedWords(text) {
    return fold(text).match(WORD) ?? []
}

/**
 * How many characters at the start of `word` read as `typed`, or 0 when the word does not start
 * with it. The count is in the word's own characters: an accented letter and its marks count with
 * the letter they fold to.
 */
function matchedLength(word, typed) {
    for (let length = 1; length <= word.length; length++) {
        const start = fold(word.slice(0, length))

        if (start.length >= typed.length) {
            if (start !== typed) {
                return 0
            }

            // The marks that follow the last matched letter belong to it
            const marks = word.slice(length).match(/^\p{M}+/u)
            return length + (marks ? marks[0].length : 0)
        }
    }

    return 0
}

/**
 * The parts of `text` to mark for the typed words, as `[start, end)` offsets in order: the start of
 * each word, or of each camelCase hump in one, that begins with a typed word, the longest of them.
 */
export function matchedRanges(text, words) {
    if (words.length === 0) {
        return []
    }

    const ranges = []

    for (const word of text.matchAll(WORD)) {
        const starts = [0, ...[...word[0].matchAll(HUMP)].map((m) => m.index)]

        for (const start of starts) {
            const rest = word[0].slice(start)
            const length = Math.max(
                ...words.map((typed) => matchedLength(rest, typed)),
            )

            if (length > 0) {
                const offset = word.index + start
                ranges.push([offset, offset + length])
                break
            }
        }
    }

    return ranges
}

/** Wraps what the typed words match in the text of `el` in `<mark>` elements */
function markMatches(el, words) {
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
    const textNodes = []
    while (walker.nextNode()) {
        textNodes.push(walker.currentNode)
    }

    textNodes.forEach((node) => {
        const text = node.data
        const ranges = matchedRanges(text, words)
        if (ranges.length === 0) {
            return
        }

        const parts = []
        let position = 0
        ranges.forEach(([start, end]) => {
            const mark = document.createElement("mark")
            mark.textContent = text.slice(start, end)
            parts.push(text.slice(position, start), mark)
            position = end
        })
        node.replaceWith(...parts, text.slice(position))
    })
}

/** What an option or a chip carries of the name it stands for */
function carriedBy(el) {
    return {
        id: el.getAttribute(Const.attributes.optionId),
        label: el.getAttribute(Const.attributes.optionLabel),
        coverUrl: el.getAttribute(Const.attributes.optionCoverUrl),
    }
}

export function filterCombobox() {
    // The latest request for the options; an earlier one's answer is dropped
    let loadToken = 0
    // The request waiting for typing to pause
    let loadTimer = null

    return {
        // The listbox popover is open (the browser's state, followed through `toggle`)
        expanded: false,
        // The IDs of the chips, in order
        chosen: [],
        // The active option's element ID, which Enter picks: `aria-activedescendant`; null with none
        activeOptionId: null,
        // What a screen reader is told of a chip added or removed, and of a full field
        status: "",
        // The input's placeholder while nothing is chosen, its markup's ("anyone")
        emptyPlaceholder: "",

        init() {
            this.emptyPlaceholder = this.$refs.input.placeholder
            this.chosen = this.chipEls().map((chipEl) => carriedBy(chipEl).id)
        },

        /** A request in flight or waiting is dropped with the field */
        destroy() {
            clearTimeout(loadTimer)
            loadToken++
        },

        get optionsUrl() {
            return this.$root.getAttribute(Const.attributes.optionsUrl)
        },

        /** The field takes several names; otherwise a pick replaces the one chosen */
        get multiple() {
            return this.$root.hasAttribute(Const.attributes.multiple)
        },

        /** The field holds as many names as one search takes (`data-max-chosen`) */
        get isFull() {
            const max = Number(
                this.$root.getAttribute(Const.attributes.maxChosen),
            )
            return this.multiple && max > 0 && this.chosen.length >= max
        },

        /** What the empty input says: what the field means with nothing chosen, then that more can be added */
        get placeholder() {
            if (this.chosen.length === 0) {
                return this.emptyPlaceholder
            }

            return this.multiple ? MORE_PLACEHOLDER : ""
        },

        /** Two names or more are chosen: the People field shows its match switch */
        get hasSeveral() {
            return this.chosen.length > 1
        },

        /** What is typed, as the options are asked for */
        query() {
            return this.$refs.input.value.trim()
        },

        chipEls() {
            return [...this.$refs.chips.children]
        },

        /** The options shown, in order */
        optionEls() {
            return [...this.$refs.listbox.querySelectorAll('[role="option"]')]
        },

        activeOptionEl() {
            return this.$refs.listbox.querySelector('[aria-selected="true"]')
        },

        /**
         * Asks for the options of what is typed and shows them, at once or, while typing, once it
         * pauses. `activate` names the option to make active when they arrive, `first` or `last`,
         * for the arrow that opened the listbox. A full field asks for nothing and says it is full.
         */
        loadOptions({ whenTypingPauses = false, activate = null } = {}) {
            clearTimeout(loadTimer)
            const token = ++loadToken

            if (this.isFull) {
                this.showOptions("", activate)
                return
            }

            const load = async () => {
                try {
                    const response = await http.get(this.optionsUrl, {
                        params: { q: this.query() },
                    })
                    if (token === loadToken) {
                        this.showOptions(response.data, activate)
                    }
                } catch (error) {
                    if (token !== loadToken) {
                        return
                    }

                    console.error(
                        `Error loading the options of ${this.optionsUrl}: ${getHttpErrorMessage(error)}`,
                    )
                    showErrorSnackBar("Could not load the options")
                }
            }

            if (whenTypingPauses) {
                loadTimer = setTimeout(load, LOAD_DELAY_MS)
            } else {
                load()
            }
        },

        /**
         * Puts the options fragment into the listbox and opens it: the options already chosen leave
         * the list, the typed words are marked in the rest, and with none left a line says why.
         * Enter picks the first option of what was typed; with nothing typed no option is active
         * until an arrow moves to one.
         */
        showOptions(html, activate) {
            const listboxEl = this.$refs.listbox
            const query = this.query()
            const words = typedWords(query)

            listboxEl.innerHTML = html
            this.optionEls().forEach((optionEl) => {
                if (this.chosen.includes(carriedBy(optionEl).id)) {
                    optionEl.remove()
                    return
                }

                optionEl.setAttribute("aria-selected", "false")
                markMatches(optionEl.querySelector(".name"), words)
            })

            const options = this.optionEls()
            if (options.length === 0) {
                listboxEl.append(this.buildNotice(query))
            }

            if (activate === "last") {
                this.setActive(options.at(-1))
            } else {
                this.setActive(
                    activate === "first" || query ? options[0] : null,
                )
            }

            this.showListbox()
        },

        /** The line in place of the options: the field is full, nothing matches, or there is nothing to offer */
        buildNotice(query) {
            const noticeEl = document.createElement("li")
            noticeEl.className = "empty"
            noticeEl.setAttribute("role", "presentation")

            if (this.isFull) {
                noticeEl.textContent = this.$root.getAttribute(
                    Const.attributes.maxChosenMessage,
                )
                this.status = noticeEl.textContent
            } else {
                noticeEl.textContent = query ? NO_MATCH : NOTHING_TO_CHOOSE
            }

            return noticeEl
        },

        /** Makes `optionEl` the active option, or none of them */
        setActive(optionEl) {
            this.activeOptionEl()?.setAttribute("aria-selected", "false")
            optionEl?.setAttribute("aria-selected", "true")
            this.activeOptionId = optionEl?.id ?? null
        },

        moveActive(delta) {
            const options = this.optionEls()
            if (options.length === 0) {
                return
            }

            const current = options.indexOf(this.activeOptionEl())
            const next =
                current < 0
                    ? delta > 0
                        ? 0
                        : options.length - 1
                    : (current + delta + options.length) % options.length

            this.setActive(options[next])
            options[next].scrollIntoView({ block: "nearest" })
        },

        showListbox() {
            const listboxEl = this.$refs.listbox
            if (listboxEl.isConnected && !listboxEl.matches(":popover-open")) {
                listboxEl.showPopover()
            }
        },

        /** Closes the listbox; a request for its options still out is dropped, so its answer does not open it again */
        hideListbox() {
            clearTimeout(loadTimer)
            loadToken++

            const listboxEl = this.$refs.listbox
            if (listboxEl.matches(":popover-open")) {
                listboxEl.hidePopover()
            }
        },

        /** The browser owns the popover's visibility; `expanded` follows it */
        onToggle(event) {
            this.expanded = event.newState === "open"
            if (!this.expanded) {
                this.setActive(null)
            }
        },

        onInput() {
            this.loadOptions({ whenTypingPauses: true })
        },

        /**
         * Down and Up move the active option, wrapping, and open the listbox when it is closed, on
         * its first or its last option; Enter picks the active option, or opens the listbox when it
         * is closed, and never submits the panel; Escape closes an open listbox alone, and is
         * consumed, so the panel stays; Backspace in an empty input removes the last chip.
         */
        onKeydown(event) {
            if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                event.preventDefault()
                const down = event.key === "ArrowDown"

                if (this.expanded) {
                    this.moveActive(down ? 1 : -1)
                } else {
                    this.loadOptions({ activate: down ? "first" : "last" })
                }
            } else if (event.key === "Enter") {
                event.preventDefault()

                if (!this.expanded) {
                    this.loadOptions()
                } else if (this.activeOptionEl()) {
                    this.pick(this.activeOptionEl())
                }
            } else if (event.key === "Escape" && this.expanded) {
                event.preventDefault()
                event.stopPropagation()
                this.hideListbox()
            } else if (event.key === "Backspace" && event.target.value === "") {
                const lastChipEl = this.chipEls().at(-1)
                if (lastChipEl) {
                    this.removeChip(lastChipEl)
                }
            }
        },

        /** A click on the box, outside a chip, puts the caret in the input and opens the listbox */
        onBoxClick(event) {
            if (event.target.closest(`[${Const.attributes.optionId}]`)) {
                return
            }

            this.$refs.input.focus()
            if (!this.expanded) {
                this.loadOptions()
            }
        },

        /** A click on an option picks it; the listbox's `mousedown` is prevented, so focus stays in the input */
        onOptionClick(event) {
            const optionEl = event.target.closest('[role="option"]')
            if (optionEl) {
                this.pick(optionEl)
            }
        },

        /** The pointer over an option makes it the active one, as the arrows would */
        onOptionHover(event) {
            const optionEl = event.target.closest('[role="option"]')
            if (optionEl && optionEl.id !== this.activeOptionId) {
                this.setActive(optionEl)
            }
        },

        /** A click on a chip's × removes the chip */
        onChipClick(event) {
            if (event.target.closest("button")) {
                this.removeChip(
                    event.target.closest(`[${Const.attributes.optionId}]`),
                )
            }
        },

        /** Focus leaving the field (Tab, a click elsewhere) closes the listbox; what was typed stays */
        onFocusOut(event) {
            if (!this.$root.contains(event.relatedTarget)) {
                this.hideListbox()
            }
        },

        /**
         * Chooses the option: its chip joins the others, or takes the place of the one chosen in a
         * field that holds one, the typed text is cleared and the listbox closes. A full field
         * chooses nothing.
         */
        pick(optionEl) {
            const option = carriedBy(optionEl)

            if (!this.multiple) {
                this.chipEls().forEach((chipEl) => chipEl.remove())
            } else if (this.isFull) {
                return
            }

            this.$refs.chips.append(
                this.buildChip(option, optionEl.querySelector(".cover")),
            )
            this.syncChosen()
            this.status = `${option.label} added`
            this.$refs.input.value = ""
            this.hideListbox()
        },

        /**
         * A chip for the option, from the field's template: what the option carried, its label as
         * the chip's text and in the name of its ×, led by a copy of the option's cover (`coverEl`)
         * when the option has one.
         */
        buildChip({ id, label, coverUrl }, coverEl) {
            const chipEl =
                this.$refs.chipTemplate.content.firstElementChild.cloneNode(
                    true,
                )
            const removeEl = chipEl.querySelector("button")

            chipEl.setAttribute(Const.attributes.optionId, id)
            chipEl.setAttribute(Const.attributes.optionLabel, label)
            if (coverUrl) {
                chipEl.setAttribute(Const.attributes.optionCoverUrl, coverUrl)
            }

            chipEl.querySelector(".name").textContent = label
            removeEl.setAttribute(
                "aria-label",
                `${removeEl.getAttribute("aria-label")} ${label}`,
            )
            if (coverEl) {
                chipEl.prepend(coverEl.cloneNode(true))
            }

            return chipEl
        },

        removeChip(chipEl) {
            this.status = `${carriedBy(chipEl).label} removed`
            chipEl.remove()
            this.syncChosen()
            this.$refs.input.focus()

            // The name removed is offered again
            if (this.expanded) {
                this.loadOptions()
            }
        },

        /** Clear: no chip, nothing typed, the listbox closed */
        clear() {
            this.chipEls().forEach((chipEl) => chipEl.remove())
            this.syncChosen()
            this.$refs.input.value = ""
            this.hideListbox()
        },

        /** The chips are the state: their IDs, in order, are the hidden input's value */
        syncChosen() {
            this.chosen = this.chipEls().map((chipEl) => carriedBy(chipEl).id)
            this.$refs.ids.value = this.chosen.join(",")
        },
    }
}
