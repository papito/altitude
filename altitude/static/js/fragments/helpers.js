export function findFragmentRoots(root, fragmentName) {
    if (!(root instanceof Element || root instanceof Document)) {
        return []
    }

    const selector = `[data-app-fragment="${fragmentName}"]`
    const roots = []

    if (root instanceof Element && root.matches(selector)) {
        roots.push(root)
    }

    roots.push(...root.querySelectorAll(selector))

    return roots
}

export function focusFragmentElement(
    fragmentEl,
    selector,
    selectOnFocus = false,
) {
    if (!selector) {
        return
    }

    const focusEl = fragmentEl.matches(selector)
        ? fragmentEl
        : fragmentEl.querySelector(selector)

    if (!focusEl) {
        return
    }

    focusEl.focus()

    if (selectOnFocus && typeof focusEl.select === "function") {
        focusEl.select()
    }
}

export function parseFragmentDetail(jsonValue) {
    if (!jsonValue) {
        return {}
    }

    try {
        return JSON.parse(jsonValue)
    } catch (error) {
        console.error("Error parsing fragment detail", error)
        return {}
    }
}

/**
 * The success event an element declares for the requests issued from it, or null.
 *
 *   data-app-success-event="FOLDER_ADDED_EVENT"          the event name, verbatim (Const.events)
 *   data-app-success-detail='{"parentId": "..."}'         the detail, as JSON
 *   data-app-success-detail-target-attr-face-id="data-face-id"
 *                                                         a detail value read from an attribute of
 *                                                         `sourceEl`, the element that issued the request
 */
export function readSuccessEvent(el, sourceEl) {
    const name = el?.dataset.appSuccessEvent
    if (!name) {
        return null
    }

    const detail = parseFragmentDetail(el.dataset.appSuccessDetail)
    const keyPrefix = "appSuccessDetailTargetAttr"

    Object.entries(el.dataset).forEach(([key, attributeName]) => {
        if (!key.startsWith(keyPrefix)) {
            return
        }

        const detailKey = datasetKeySuffixToDetailKey(
            key.slice(keyPrefix.length),
        )
        const value = sourceEl?.getAttribute?.(attributeName)

        if (value !== null && value !== undefined) {
            detail[detailKey] = value
        }
    })

    return { name, detail }
}

/**
 * The literals an element declares under a dataset `prefix`, keyed by what follows it: with prefix
 * `appSearch`, `data-app-search-person-id="abc"` gives `{ personId: "abc" }`. The bare prefix and
 * the `reserved` keys are skipped.
 */
export function literalsOf(el, prefix, reserved = new Set()) {
    const literals = {}

    Object.entries(el.dataset).forEach(([key, value]) => {
        if (!key.startsWith(prefix) || key === prefix || reserved.has(key)) {
            return
        }

        literals[datasetKeySuffixToDetailKey(key.slice(prefix.length))] = value
    })

    return literals
}

export function datasetKeySuffixToDetailKey(keySuffix) {
    if (!keySuffix) {
        return ""
    }

    return keySuffix.charAt(0).toLowerCase() + keySuffix.slice(1)
}
