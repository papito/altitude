/**
 * The Change combobox of a People row in Asset detail: an ARIA combobox (focus stays in the input)
 * whose listbox is a manual top-layer popover, so the panel's scrolling never clips it. For a
 * nameless Person a pick acts on the whole Person unless "This face only" is on; two nameless
 * people are never merged from here. A merge cannot be undone, so it takes two picks: the first
 * arms it. A hidden status region says the armed label, since text changing under
 * `aria-activedescendant` is not announced reliably.
 */
import { Const } from "../../constants.js"
import { context } from "../../context.js"
import { nameKey, partialMatchKey } from "../../common/name-key.js"
import { showErrorSnackBar } from "../../common/snackbar.js"
import { getHttpErrorMessage, http } from "../../http/client.js"

// The open listbox is found by this class
export const LISTBOX_CLASS = "face-listbox"

// The most matches rendered at once: the list can hold thousands of people, and the query narrows it
export const MAX_SHOWN_OPTIONS = 50

// The Create row's option key; no Person ID is this word
export const CREATE_KEY = "create"

/** With `faceId` the answer leads with the Suggested group: the people nearest that Face */
export function assignablePeopleUrl({ repoId, faceId }) {
    const url = `/htmx/people/r/${repoId}/assignable`
    return faceId ? `${url}?faceId=${encodeURIComponent(faceId)}` : url
}

/** The name keys are computed once here rather than on every keystroke */
export function parseAssignablePeople(html) {
    const doc = new DOMParser().parseFromString(html, "text/html")

    return [...doc.querySelectorAll('[role="option"][data-person-id]')].map(
        (optionEl) => {
            const name = optionEl.dataset.personName ?? ""
            return {
                personId: optionEl.dataset.personId,
                name,
                nameKey: nameKey(name),
                partialMatchKey: partialMatchKey(name),
                named: optionEl.dataset.personNamed === "true",
                hidden: optionEl.dataset.personHidden === "true",
                suggested: optionEl.dataset.suggested !== undefined,
                coverUrl:
                    optionEl.querySelector("img")?.getAttribute("src") ?? null,
            }
        },
    )
}

/** Returns whether a listbox was open */
export function closeOpenFaceCombobox() {
    const listboxEl = document.querySelector(`.${LISTBOX_CLASS}:popover-open`)
    if (!listboxEl) {
        return false
    }

    listboxEl.hidePopover()
    return true
}

/**
 * Returns whether a row was selected. Focus goes to the row first, since the control that held it
 * may be going; clicking the selected row deselects it.
 */
export function clearSelectedFaceRow() {
    const rowEl = document.querySelector(
        `[${Const.attributes.faceRow}][aria-pressed="true"]`,
    )
    if (!rowEl) {
        return false
    }

    rowEl.focus({ preventScroll: true })
    rowEl.click()
    return true
}

function assetCountText(count) {
    return `${count} ${count === 1 ? "asset" : "assets"}`
}

export function faceCombobox() {
    // The latest load of the options; an earlier one's answer is dropped
    let loadToken = 0

    return {
        CREATE_KEY,

        // Shown in place of Search, Change and Remove
        changing: false,
        // Follows the popover's state through `toggle`
        expanded: false,
        query: "",
        // Loaded on each opening
        options: [],
        // The option Enter picks; set through `setActive`
        activeKey: null,
        // "This face only"; off at every opening
        faceOnly: false,
        // The option a first pick armed a merge for
        armedKey: null,

        /** An anchor name per Face, since a panel holds a row per Face */
        init() {
            const anchorName = `--face-combobox-${this.faceId}`
            this.$refs.input.style.setProperty("anchor-name", anchorName)
            this.$refs.listbox.style.setProperty("position-anchor", anchorName)
        },

        get faceId() {
            return this.rowEl()?.getAttribute(Const.attributes.faceId) ?? ""
        },

        /** `assetCount` counts the Person's assets outside the trash */
        get rowPerson() {
            const rowEl = this.rowEl()
            return {
                personId: rowEl?.getAttribute(Const.attributes.personId) ?? "",
                name: rowEl?.getAttribute(Const.attributes.personName) ?? "",
                named:
                    rowEl?.getAttribute(Const.attributes.personNamed) ===
                    "true",
                assetCount: Number(
                    rowEl?.getAttribute(Const.attributes.personAssetCount) ?? 0,
                ),
            }
        },

        get wholePerson() {
            return !this.rowPerson.named && !this.faceOnly
        },

        mergesInto(option) {
            return (
                this.wholePerson &&
                option.named &&
                context.holds(Const.permission.organize)
            )
        },

        get listboxId() {
            return `faceListbox-${this.faceId}`
        },

        get suggestedHeadingId() {
            return `faceSuggested-${this.faceId}`
        },

        /** Nearest the Face first; shown only while nothing is typed */
        get suggested() {
            return nameKey(this.query) === ""
                ? this.options.filter((option) => option.suggested)
                : []
        },

        /** In the server's order (named first, then Unknowns); the query matches in any case or Unicode form */
        get matches() {
            const key = partialMatchKey(this.query)

            return this.options
                .filter((option) =>
                    key === ""
                        ? !option.suggested
                        : option.partialMatchKey.includes(key),
                )
                .slice(0, MAX_SHOWN_OPTIONS)
        },

        /**
         * The Face's own Person is no option, so their name is offered too, and the server refuses it
         * as already theirs.
         */
        get createName() {
            const name = this.query.trim()
            const { min, max } = Const.people.nameLength

            if (name.length < min || name.length > max) {
                return null
            }

            const key = nameKey(name)
            return this.options.some((option) => option.nameKey === key)
                ? null
                : name
        },

        get createLabel() {
            const { name, assetCount } = this.rowPerson
            return this.wholePerson
                ? `Name ${name} '${this.createName}' (${assetCountText(assetCount)})`
                : `Create '${this.createName}'`
        },

        /** What a pick does, while picks act on the whole Person; the armed option's label says it all */
        optionSuffix(option) {
            if (!this.wholePerson || this.isArmed(option.personId)) {
                return ""
            }

            const { name, assetCount } = this.rowPerson
            return this.mergesInto(option)
                ? `· merge ${name} (${assetCountText(assetCount)})`
                : "· this face"
        },

        /** On the armed option, the question its second pick answers */
        optionLabel(option) {
            return this.isArmed(option.personId) ? this.armedLabel : option.name
        },

        /** The status region says it too */
        get armedLabel() {
            const option =
                this.armedKey &&
                this.options.find((o) => o.personId === this.armedKey)

            return option
                ? `Merge ${this.rowPerson.name} into ${option.name}? Pick again`
                : ""
        },

        get keys() {
            return [
                ...this.suggested.map((option) => option.personId),
                ...this.matches.map((option) => option.personId),
                ...(this.createName ? [CREATE_KEY] : []),
            ]
        },

        /** None when the active option left the list */
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

        isArmed(key) {
            return this.armedKey === key
        },

        /** Another option than the armed one disarms the merge */
        setActive(key) {
            if (key !== this.activeKey) {
                this.armedKey = null
            }
            this.activeKey = key
        },

        rowEl() {
            return this.$root.closest(`[${Const.attributes.faceId}]`)
        },

        async open() {
            this.changing = true
            this.query = ""
            this.activeKey = null
            this.faceOnly = false
            this.armedKey = null
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
            await this.untilShown()
            if (token !== loadToken || !this.changing) {
                return
            }

            this.$refs.input.focus()
            this.showListbox()
        },

        /**
         * Alpine's `x-show` shows on the next animation frame (a timeout in a hidden tab). Focusing the
         * input sooner would not take, the hidden Change button would lose focus to the body, and that
         * `focusout` would close the listbox. A wait scheduled now runs after Alpine's.
         */
        async untilShown() {
            if (this.$refs.input.checkVisibility()) {
                return
            }

            const nextFrame =
                document.visibilityState === "visible"
                    ? requestAnimationFrame
                    : setTimeout
            await new Promise((resolve) => nextFrame(resolve))
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

        /** Closing disarms a merge */
        onToggle(event) {
            this.expanded = event.newState === "open"
            if (!this.expanded) {
                this.armedKey = null
            }
        },

        /** Also reopens a listbox Escape closed */
        onInput(event) {
            this.query = event.target.value
            this.armedKey = null
            this.activeKey = this.defaultActiveKey()
            this.showListbox()
        },

        /**
         * A press on the switch or its label keeps focus in the input (`mousedown` is prevented and the
         * label's click comes here), so Enter still picks.
         */
        toggleFaceOnly() {
            this.faceOnly = !this.faceOnly
            this.armedKey = null
        },

        /** The option Enter picks before the arrows move it */
        defaultActiveKey() {
            const key = nameKey(this.query)
            if (key === "") {
                return null
            }

            const exact = this.matches.find((option) => option.nameKey === key)

            return (
                exact?.personId ??
                this.matches[0]?.personId ??
                (this.createName ? CREATE_KEY : null)
            )
        },

        /** Enter on a closed listbox only opens it. Escape is handled in `global.js`. */
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
            this.setActive(keys[next])

            this.$nextTick(() =>
                document
                    .getElementById(this.optionId(this.activeKey))
                    ?.scrollIntoView({ block: "nearest" }),
            )
        },

        /** The listbox's `mousedown` is prevented, so focus stays in the input */
        onOptionClick(event) {
            const key = this.optionKeyOf(event.target)
            if (key) {
                this.pick(key)
            }
        },

        onOptionHover(event) {
            const key = this.optionKeyOf(event.target)
            if (key) {
                this.setActive(key)
            }
        },

        optionKeyOf(target) {
            return (
                target.closest?.("[data-option-key]")?.dataset.optionKey ?? null
            )
        },

        /** Focus leaving the combobox closes the listbox; the input keeps its text */
        onFocusOut(event) {
            if (!this.$root.contains(event.relatedTarget)) {
                this.hideListbox()
            }
        },

        /** A merging option merges on its second pick; an option no longer shown picks nothing */
        pick(key) {
            const faceId = this.faceId
            const sourcePersonId = this.rowPerson.personId

            if (key === CREATE_KEY) {
                if (!this.createName) {
                    return
                }

                this.announcePick(
                    this.wholePerson
                        ? {
                              faceId,
                              action: "name",
                              sourcePersonId,
                              name: this.createName,
                          }
                        : { faceId, action: "move", name: this.createName },
                )
                return
            }

            const option = this.keys.includes(key)
                ? this.options.find((o) => o.personId === key)
                : null
            if (!option) {
                return
            }

            if (!this.mergesInto(option)) {
                this.announcePick({ faceId, action: "move", personId: key })
            } else if (this.armedKey !== key) {
                this.setActive(key)
                this.armedKey = key
            } else {
                this.announcePick({
                    faceId,
                    action: "merge",
                    sourcePersonId,
                    destPersonId: key,
                })
            }
        },

        announcePick(detail) {
            this.hideListbox()
            document.body.dispatchEvent(
                new CustomEvent(Const.events.facePersonPicked, { detail }),
            )
        },

        /** The X: the panel clears the selection on the event */
        cancel() {
            const faceId = this.faceId
            this.reset()
            document.body.dispatchEvent(
                new CustomEvent(Const.events.faceChangeCancelled, {
                    detail: { faceId },
                }),
            )
        },

        /** A load in flight is dropped */
        reset() {
            loadToken++
            this.hideListbox()
            this.changing = false
            this.query = ""
            this.activeKey = null
            this.faceOnly = false
            this.armedKey = null
            this.options = []
        },
    }
}
