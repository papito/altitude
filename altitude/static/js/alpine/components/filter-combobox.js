/**
 * A combobox of the advanced search panel (folder, people, Locations), hand-built on the ARIA
 * combobox pattern as `face-combobox.js` is; focus stays in the input throughout. The fields differ
 * only in their markup's data attributes.
 *
 * The server matches and renders the options; the component only places them, minus the ones
 * already chosen, each with an element ID scoped to its listbox, since several comboboxes on a page
 * can offer the same name. The chips are the state: their IDs, comma-separated, are the hidden
 * input's value, the search parameter.
 */
import { Const } from "../../constants.js"
import { showErrorSnackBar } from "../../common/snackbar.js"
import { getHttpErrorMessage, http } from "../../http/client.js"

// How long typing pauses before the options are requested
const LOAD_DELAY_MS = 150

// The placeholder of a field of several once it holds a chip
const MORE_PLACEHOLDER = "add…"

const NO_MATCH = "No matches"
const NOTHING_TO_CHOOSE = "Nothing to choose"

const OPTION = '[role="option"]'
const CHIP = `[${Const.attributes.optionId}]`

// A word as the server splits one (`SearchWords`): a run of letters or a run of digits
const WORD = /\p{L}[\p{L}\p{M}]*|\p{Nd}+/gu

// A camelCase hump inside a word, where the server starts a word too
const HUMP = /(?<=\p{Ll}\p{M}*)(?=\p{Lu})/gu

/** As the server compares words: no diacritics, lower case */
function fold(text) {
    return text
        .normalize("NFD")
        .replace(/\p{M}+/gu, "")
        .toLowerCase()
}

function typedWords(text) {
    return fold(text).match(WORD) ?? []
}

/**
 * How many characters at the start of `word` fold to `typed`, or 0. Counted in the word's own
 * characters, so an accented letter's marks count with it.
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
 * `[start, end)` ranges of `text` to mark: per word, the first start (the word's or a camelCase
 * hump's) that a typed word matches, by its longest match.
 */
function matchedRanges(text, words) {
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

function carriedBy(el) {
    return {
        id: el.getAttribute(Const.attributes.optionId),
        label: el.getAttribute(Const.attributes.optionLabel),
    }
}

function countOf(options) {
    return options.length === 1 ? "1 option" : `${options.length} options`
}

export function filterCombobox() {
    // Bumped per request, so an earlier request's answer is dropped
    let loadToken = 0
    let loadTimer = null
    // A request is waiting or out: the options shown, if any, are of the earlier text
    let isLoading = false

    return {
        // Set as the popover is shown and hidden, so a key that follows at once reads it
        expanded: false,
        chosen: [],
        // For `aria-activedescendant`
        activeOptionId: null,
        // The screen reader's live status text
        status: "",
        // The markup's placeholder, shown while nothing is chosen
        emptyPlaceholder: "",

        init() {
            this.emptyPlaceholder = this.$refs.input.placeholder
            this.chosen = this.chipEls().map((chipEl) => carriedBy(chipEl).id)
        },

        destroy() {
            this.dropLoad()
        },

        get optionsUrl() {
            return this.$root.getAttribute(Const.attributes.optionsUrl)
        },

        /** Values of the form inputs named in `data-options-params`, for options that depend on the rest of the form */
        optionsParams() {
            const names =
                this.$root.getAttribute(Const.attributes.optionsParams) ?? ""
            const formEl = this.$root.closest("form")

            return Object.fromEntries(
                names
                    .split(" ")
                    .map((name) => [name, formEl?.elements[name]?.value])
                    .filter(([, value]) => value),
            )
        },

        /** Otherwise a pick replaces the one chosen */
        get multiple() {
            return this.$root.hasAttribute(Const.attributes.multiple)
        },

        get isFull() {
            const max = Number(
                this.$root.getAttribute(Const.attributes.maxChosen),
            )
            return this.multiple && max > 0 && this.chosen.length >= max
        },

        get placeholder() {
            if (this.chosen.length === 0) {
                return this.emptyPlaceholder
            }

            return this.multiple && !this.isFull ? MORE_PLACEHOLDER : ""
        },

        /** The People field shows its match switch */
        get hasSeveral() {
            return this.chosen.length > 1
        },

        query() {
            return this.$refs.input.value.trim()
        },

        chipEls() {
            return [...this.$refs.chips.querySelectorAll(CHIP)]
        },

        optionEls() {
            return [...this.$refs.listbox.querySelectorAll(OPTION)]
        },

        activeOptionEl() {
            return this.$refs.listbox.querySelector('[aria-selected="true"]')
        },

        /**
         * Requests the options of the typed text and shows them. `activate` (`first` or `last`) is
         * the option to make active on arrival, for the arrow that opened the listbox; `pick` chooses
         * the first on arrival, for an Enter that came before them. A full field requests nothing.
         */
        loadOptions({
            whenTypingPauses = false,
            activate = null,
            pick = false,
            announce = true,
        } = {}) {
            this.dropLoad()
            const token = loadToken

            if (this.isFull) {
                this.showOptions("", { activate, announce })
                return
            }

            const load = async () => {
                try {
                    const response = await http.get(this.optionsUrl, {
                        params: { ...this.optionsParams(), q: this.query() },
                    })
                    if (token === loadToken) {
                        isLoading = false
                        this.showOptions(response.data, {
                            activate,
                            pick,
                            announce,
                        })
                    }
                } catch (error) {
                    if (token !== loadToken) {
                        return
                    }

                    isLoading = false
                    console.error(
                        `Error loading the options of ${this.optionsUrl}: ${getHttpErrorMessage(error)}`,
                    )
                    showErrorSnackBar("Could not load the options")
                }
            }

            isLoading = true
            if (whenTypingPauses) {
                loadTimer = setTimeout(load, LOAD_DELAY_MS)
            } else {
                load()
            }
        },

        /** Drops the waiting or pending request: its answer, if one comes, is not shown */
        dropLoad() {
            clearTimeout(loadTimer)
            loadToken++
            isLoading = false
        },

        /**
         * With text typed the first option is active, so Enter picks it; with nothing typed none is
         * active until an arrow moves to one.
         */
        showOptions(html, { activate = null, pick = false, announce = true }) {
            const listboxEl = this.$refs.listbox
            const query = this.query()
            const words = typedWords(query)

            listboxEl.innerHTML = html
            this.optionEls().forEach((optionEl) => {
                if (this.chosen.includes(carriedBy(optionEl).id)) {
                    optionEl.remove()
                    return
                }

                // Scoped to this listbox: another combobox on the page may offer the same name
                optionEl.id = `${listboxEl.id}-${carriedBy(optionEl).id}`
                optionEl.setAttribute("aria-selected", "false")
                markMatches(
                    optionEl.querySelector(`[${Const.attributes.optionName}]`),
                    words,
                )
            })

            const options = this.optionEls()
            if (pick && query && options.length > 0) {
                this.pick(options[0])
                return
            }

            const noticeEl =
                options.length === 0 ? this.buildNotice(query) : null
            if (noticeEl) {
                listboxEl.append(noticeEl)
            }
            if (announce) {
                this.status = noticeEl?.textContent ?? countOf(options)
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

        buildNotice(query) {
            const noticeEl = document.createElement("li")
            noticeEl.className = "empty"
            noticeEl.setAttribute("role", "presentation")

            if (this.isFull) {
                noticeEl.textContent = this.$root.getAttribute(
                    Const.attributes.maxChosenMessage,
                )
            } else {
                noticeEl.textContent = query ? NO_MATCH : NOTHING_TO_CHOOSE
            }

            return noticeEl
        },

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
            this.expanded = listboxEl.matches(":popover-open")
        },

        /** Drops a pending request, so its answer does not reopen the listbox */
        hideListbox() {
            this.dropLoad()

            const listboxEl = this.$refs.listbox
            if (listboxEl.matches(":popover-open")) {
                listboxEl.hidePopover()
            }
            this.expanded = false
            this.setActive(null)
        },

        /** No option is active until the new text's options come, so Enter cannot pick a stale one */
        onInput() {
            this.setActive(null)
            this.loadOptions({ whenTypingPauses: true })
        },

        /**
         * Enter never submits the panel; while options are on their way it requests them at once and
         * picks the first. Escape is consumed, so the panel stays open.
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

                if (isLoading) {
                    this.loadOptions({ pick: true })
                } else if (!this.expanded) {
                    this.loadOptions()
                } else if (this.activeOptionEl()) {
                    this.pick(this.activeOptionEl())
                }
            } else if (event.key === "Escape" && (this.expanded || isLoading)) {
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

        onBoxClick(event) {
            if (event.target.closest(CHIP)) {
                return
            }

            this.$refs.input.focus()
            if (!this.expanded) {
                this.loadOptions()
            }
        },

        /** The listbox's `mousedown` is prevented, so focus stays in the input */
        onOptionClick(event) {
            const optionEl = event.target.closest(OPTION)
            if (optionEl) {
                this.pick(optionEl)
            }
        },

        onOptionHover(event) {
            const optionEl = event.target.closest(OPTION)
            if (optionEl && optionEl.id !== this.activeOptionId) {
                this.setActive(optionEl)
            }
        },

        onChipClick(event) {
            if (event.target.closest(`[${Const.attributes.chipRemove}]`)) {
                this.removeChip(event.target.closest(CHIP))
            }
        },

        onFocusOut(event) {
            if (!this.$root.contains(event.relatedTarget)) {
                this.hideListbox()
            }
        },

        /** A field of one replaces its chip; a full field of several chooses nothing */
        pick(optionEl) {
            const option = carriedBy(optionEl)

            if (!this.multiple) {
                this.chipEls().forEach((chipEl) => chipEl.remove())
            } else if (this.isFull) {
                return
            }

            this.$refs.chips.append(
                this.buildChip(
                    option,
                    optionEl.querySelector(`[${Const.attributes.optionCover}]`),
                ),
            )
            this.syncChosen()
            this.status = `${option.label} added`
            this.$refs.input.value = ""
            this.hideListbox()
        },

        buildChip({ id, label }, coverEl) {
            const chipEl =
                this.$refs.chipTemplate.content.firstElementChild.cloneNode(
                    true,
                )
            const removeEl = chipEl.querySelector(
                `[${Const.attributes.chipRemove}]`,
            )

            chipEl.setAttribute(Const.attributes.optionId, id)
            chipEl.setAttribute(Const.attributes.optionLabel, label)
            chipEl.querySelector(
                `[${Const.attributes.optionName}]`,
            ).textContent = label
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

            // Offer the removed name again, quietly: the status has just announced the removal
            if (this.expanded) {
                this.loadOptions({ announce: false })
            }
        },

        clear() {
            this.chipEls().forEach((chipEl) => chipEl.remove())
            this.syncChosen()
            this.$refs.input.value = ""
            this.hideListbox()
        },

        /** The `change` tells the form the field's value changed */
        syncChosen() {
            this.chosen = this.chipEls().map((chipEl) => carriedBy(chipEl).id)
            this.$refs.ids.value = this.chosen.join(",")
            this.$refs.ids.dispatchEvent(new Event("change", { bubbles: true }))
        },
    }
}
