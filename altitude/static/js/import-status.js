/**
 * The Import page's status feed (`hx-ws:connect="/import/status"` in `pipeline.scala.html`, the
 * vendored `lib/hx-ws.js`). The extension reconnects a socket the server closed with a code worth
 * trying again on (1013: the account changed, `ImportStatusWsActor.CloseAllFor`), but it would go
 * on forever after a handshake the server refuses (an account deactivated meanwhile): after
 * `MAX_RECONNECTS` reconnects in a row that failed, it is stopped here, with no patch to the
 * library, by cancelling the next one (`htmx:ws:before:connection`, whose `connection.attempt`
 * counts the reconnects since the socket last opened), and the status area says to reload. An open
 * socket hides the notice again. A refused session may send the window to the login first, through
 * the nav's poll (`common/session.js`).
 */
export const MAX_RECONNECTS = 5

export function registerImportStatus() {
    const notice = document.getElementById("importStatusLost")

    htmx.on("htmx:ws:before:connection", (event) => {
        const connection = event.detail.connection
        if (connection.attempt > MAX_RECONNECTS) {
            console.debug(
                `Import status: ${MAX_RECONNECTS} reconnects failed, giving up`,
            )
            connection.cancelled = true
            event.preventDefault()
            if (notice) {
                notice.hidden = false
            }
        }
    })

    htmx.on("htmx:ws:after:connection", () => {
        if (notice) {
            notice.hidden = true
        }
    })
}
