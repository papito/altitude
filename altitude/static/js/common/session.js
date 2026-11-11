/**
 * A request the server refused for want of a login (HTTP 401: no session, or one that expired, was
 * revoked or belongs to an account deactivated or deleted since) sends the window to the login,
 * which comes back to this page once the account logs in again. The server answers an htmx request
 * so with `HX-Redirect` to the same place (`decorators.requireLogin`); the client does it for every
 * 401, an axios call's (`http/client.js`) included, so no refused request is left reported as an
 * error on a page that can do nothing more.
 */
export const UNAUTHORIZED = 401

export function isUnauthorized(status) {
    return status === UNAUTHORIZED
}

/** The login, to come back to this page: its path and query, never a partial's */
export function goToLogin() {
    const here = window.location.pathname + window.location.search
    window.location.assign(`/login?redirect=${encodeURIComponent(here)}`)
}
