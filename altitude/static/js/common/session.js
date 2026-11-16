/**
 * A 401 (no session, or one expired, revoked or of an account since deactivated or deleted) sends the window
 * to the login, which returns to this page. The server's `HX-Redirect` covers htmx requests; the
 * client handles every 401, axios calls included, so no refused request is left reported as an
 * error.
 */
export const UNAUTHORIZED = 401

export function isUnauthorized(status) {
    return status === UNAUTHORIZED
}

/** Returns to this page's path and query, never a partial's */
export function goToLogin() {
    const here = window.location.pathname + window.location.search
    window.location.assign(`/login?redirect=${encodeURIComponent(here)}`)
}
