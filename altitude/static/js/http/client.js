import { goToLogin, isUnauthorized } from "../common/session.js"

// window.axios comes from lib/axios.min.js, loaded as a plain script
export const http = window.axios.create({
    headers: {
        "Content-Type": "application/json",
    },
})

// A 401 sends the window to the login; the caller still sees the failure, made moot by the page leaving
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

/** The reason of a JSON refusal (`{"error": ...}`, every JSON route's shape), else the status */
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
