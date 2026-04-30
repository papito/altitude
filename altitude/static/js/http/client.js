// window.axios is provided by /static/js/lib/axios.min.js loaded as a plain script
export const http = window.axios.create({
    headers: {
        "Content-Type": "application/json",
    },
})

const defaultValidateStatus =
    http.defaults.validateStatus ?? ((status) => status >= 200 && status < 300)

export function allowHttpStatuses(...statuses) {
    return (status) =>
        defaultValidateStatus(status) || statuses.includes(status)
}

export function getHttpErrorMessage(error) {
    const status = error?.response?.status
    const statusText = error?.response?.statusText

    if (status && statusText) {
        return `HTTP ${status} ${statusText}`
    }

    if (status) {
        return `HTTP ${status}`
    }

    return error?.message ?? String(error)
}
