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

export function parseFragmentTargetDetail(
    fragmentEl,
    event,
    keyPrefix = "appModalSuccessDetailTargetAttr",
) {
    const detail = {}

    Object.entries(fragmentEl.dataset).forEach(([key, value]) => {
        if (!key.startsWith(keyPrefix)) {
            return
        }

        const detailKey = datasetKeySuffixToDetailKey(key.slice(keyPrefix.length))

        detail[detailKey] =
            event.target?.getAttribute?.(value) ?? detail[detailKey]
    })

    return detail
}

export function datasetKeySuffixToDetailKey(keySuffix) {
    if (!keySuffix) {
        return ""
    }

    return keySuffix.charAt(0).toLowerCase() + keySuffix.slice(1)
}

