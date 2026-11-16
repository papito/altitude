/**
 * Accessors for the request context htmx 4 puts under `event.detail.ctx` (htmx's
 * `#createRequestContext`), so its shape is spelled out in one place.
 */

/** The requested URL, including any query string htmx appended for GET/DELETE */
export function getRequestPath(event) {
    return event.detail.ctx.request.action
}

export function getResponseStatus(event) {
    return event.detail.ctx.response?.status
}

/** Whether a non-error response arrived; htmx 2's `detail.successful` */
export function isRequestSuccessful(event) {
    const status = getResponseStatus(event)

    return status !== undefined && status < 400
}

export function getResponseText(event) {
    return event.detail.ctx.text
}

/** The `HX-Retarget` header: a modal form answered `this` replaces itself with a validated copy */
export function getResponseRetarget(event) {
    return event.detail.ctx.hx?.retarget
}

/** A response header by name, case-insensitive; null before a response arrived */
export function getResponseHeader(event, name) {
    return event.detail.ctx.response?.headers?.get(name) ?? null
}

export function getRequestSource(event) {
    return event.detail.ctx.sourceElement ?? null
}
