import { goToLogin, isUnauthorized } from "../common/session.js"

// window.axios is provided by /static/js/lib/axios.min.js loaded as a plain script
export const http = window.axios.create({
    headers: {
        "Content-Type": "application/json",
    },
})

// A request refused for want of a login sends the window to the login (`common/session.js`); the
// caller still sees the failure, which the page leaving makes moot
http.interceptors.response.use(
    (response) => response,
    (error) => {
        if (isUnauthorized(error?.response?.status)) {
            goToLogin()
        }
        return Promise.reject(error)
    },
)

const defaultValidateStatus =
    http.defaults.validateStatus ?? ((status) => status >= 200 && status < 300)

export function allowHttpStatuses(...statuses) {
    return (status) =>
        defaultValidateStatus(status) || statuses.includes(status)
}

/**
 * What a failed request says: the reason a JSON refusal gives (`{"error": ...}`, the shape of every
 * JSON route's refusal, a 403 for a Permission not held among them), else its status
 */
export function getHttpErrorMessage(error) {
    const status = error?.response?.status
    const statusText = error?.response?.statusText
    const reason = error?.response?.data?.error

    if (typeof reason === "string" && reason) {
        return reason
    }

    if (status && statusText) {
        return `HTTP ${status} ${statusText}`
    }

    if (status) {
        return `HTTP ${status}`
    }

    return error?.message ?? String(error)
}
