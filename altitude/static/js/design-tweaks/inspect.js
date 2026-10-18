/**
 * The design tweaks panel's pick-element mode. While it is on, the pointer is a crosshair and the
 * next click on the page is consumed (its pointer events too, so no drag or selection starts): the
 * picked element is outlined and `onPick` gets the tokens that style it. Escape, or the panel's
 * button, leaves the mode.
 *
 * The tokens of an element are found by walking the bundle's rules: a style rule whose selector
 * matches the element or an ancestor of it is kept, the `var(--…)` references in its declarations
 * are collected, and so are the tokens those reference in turn (a role token names its step), so
 * "change this thing" starts from every token the thing reads.
 */
import { referencedNamesOf } from "../common/token-kinds.js"

// A pseudo-element or a pseudo-class of a state the element may not be in right now: a selector is
// matched without them, so the hover and focus looks of the element are found too
const STATE_PSEUDOS =
    /::[\w-]+(?:\([^)]*\))?|:(?:hover|focus|focus-visible|focus-within|active|visited|popover-open)/g
const CONSUMED_EVENTS = [
    "pointerdown",
    "pointerup",
    "mousedown",
    "mouseup",
    "click",
]

export class Inspector {
    /**
     * `bundle` is the stylesheet whose rules are walked; `declaredOf(name)` gives a token's declared
     * text, for the references it holds; `isOwn(target)` tells the panel's own elements, whose
     * clicks are not picks; `outline(rect)` draws the outline (null hides it)
     */
    constructor({ bundle, declaredOf, isOwn, outline, onPick, onLeave }) {
        this.bundle = bundle
        this.declaredOf = declaredOf
        this.isOwn = isOwn
        this.outline = outline
        this.onPick = onPick
        this.onLeave = onLeave
        this.active = false
        this.handleEvent = this.handleEvent.bind(this)
    }

    start() {
        if (this.active) {
            return
        }
        this.active = true
        document.documentElement.style.cursor = "crosshair"
        CONSUMED_EVENTS.forEach((type) =>
            document.addEventListener(type, this.handleEvent, true),
        )
        document.addEventListener("keydown", this.handleEvent, true)
    }

    stop() {
        if (!this.active) {
            return
        }
        this.active = false
        document.documentElement.style.cursor = ""
        CONSUMED_EVENTS.forEach((type) =>
            document.removeEventListener(type, this.handleEvent, true),
        )
        document.removeEventListener("keydown", this.handleEvent, true)
        this.onLeave()
    }

    handleEvent(event) {
        if (event.type === "keydown") {
            if (event.key === "Escape") {
                event.preventDefault()
                event.stopPropagation()
                this.stop()
            }
            return
        }
        const target = event.composedPath()[0]
        if (this.isOwn(target)) {
            return
        }
        event.preventDefault()
        event.stopPropagation()
        if (event.type === "click" && target instanceof Element) {
            this.pick(target)
        }
    }

    pick(element) {
        this.outline(element.getBoundingClientRect())
        const tokens = tokensReferencedBy(element, this.bundle, this.declaredOf)
        this.stop()
        this.onPick({ element, tokens, label: describe(element) })
    }
}

/** The names of the tokens the bundle's rules matching `element` or an ancestor reference, with the ones those reference */
export function tokensReferencedBy(element, bundle, declaredOf) {
    const names = new Set()

    const visit = (rules) => {
        Array.from(rules).forEach((rule) => {
            if (rule.cssRules && !(rule instanceof CSSStyleRule)) {
                visit(rule.cssRules)
                return
            }
            if (
                !(rule instanceof CSSStyleRule) ||
                !matchesOrAncestor(element, rule.selectorText)
            ) {
                return
            }
            referencedNamesOf(rule.style.cssText).forEach((name) =>
                names.add(name),
            )
            if (rule.cssRules) {
                visit(rule.cssRules)
            }
        })
    }
    visit(bundle.cssRules)

    // The tokens a token reads, until nothing new is found
    const queue = [...names]
    while (queue.length) {
        referencedNamesOf(declaredOf(queue.pop()) ?? "").forEach((name) => {
            if (!names.has(name)) {
                names.add(name)
                queue.push(name)
            }
        })
    }
    return names
}

function matchesOrAncestor(element, selectorText) {
    const selector = selectorText.replace(STATE_PSEUDOS, "")
    try {
        return element.closest(selector) !== null
    } catch {
        return false
    }
}

/** `tag#id.class` of an element, as a label */
function describe(element) {
    const id = element.id ? `#${element.id}` : ""
    const classes = Array.from(element.classList)
        .slice(0, 3)
        .map((name) => `.${name}`)
        .join("")
    return `${element.tagName.toLowerCase()}${id}${classes}`
}
