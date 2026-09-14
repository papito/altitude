/**
 * Accessors for the request context that htmx 4 attaches to its lifecycle events.
 *
 * htmx 4 carries everything under `event.detail.ctx` (see `#createRequestContext` in
 * htmx.js): `request.action` is the URL that was actually requested, `response.status`
 * the HTTP status once a response arrived, `text` the response body, and `target` the
 * resolved swap target. Listeners go through these helpers rather than reaching into
 * the detail so the shape is spelled out in one place.
 */

/**
 * The requested URL, including any query string htmx appended for GET/DELETE.
 */
export function getRequestPath(event) {
    return event.detail.ctx.request.action
}

export function getResponseStatus(event) {
    return event.detail.ctx.response?.status
}

/**
 * Whether a non-error response arrived; mirrors htmx 2's `detail.successful`.
 */
export function isRequestSuccessful(event) {
    const status = getResponseStatus(event)

    return status !== undefined && status < 400
}

export function getResponseText(event) {
    return event.detail.ctx.text
}

/**
 * The `HX-Retarget` response header, if the server redirected the swap to another target.
 * Modal forms use `this` to replace themselves with a validated copy.
 */
export function getResponseRetarget(event) {
    return event.detail.ctx.hx?.retarget
}

/** A response header by name (fetch `Headers`, case-insensitive), or null before a response arrived. */
export function getResponseHeader(event, name) {
    return event.detail.ctx.response?.headers?.get(name) ?? null
}

/** The element that issued the request, if it is still known. */
export function getRequestSource(event) {
    return event.detail.ctx.sourceElement ?? null
}
