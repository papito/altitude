/**
 * The asset-detail Video's playback beyond its element: unloading it, and describing it to the OS
 * media controls (hardware media keys, the browser's and the system's media overlays) through the
 * Media Session API.
 */

// The OS controls' skip step, the player's `j` / `l` step
const SEEK_OFFSET_SECONDS = 10
const ACTIONS = [
    "play",
    "pause",
    "seekto",
    "seekbackward",
    "seekforward",
    "previoustrack",
    "nexttrack",
]

// Stops the position updates of the Video the session describes
let positionUpdates = null

/** Stops and unloads a video, so nothing keeps playing, downloading, or holding the OS media controls */
export function unloadVideo(videoEl) {
    videoEl.pause()
    videoEl.removeAttribute("src")
    videoEl.load()
}

/**
 * Describes the shown Video to the OS: its title and Preview, play, pause and seeking, and
 * previous/next asset when `previous` and `next` are given (a detail opened from a map pin cannot
 * step).
 */
export function describeInMediaSession({
    videoEl,
    title,
    artworkUrl,
    play,
    previous,
    next,
}) {
    if (!("mediaSession" in navigator)) {
        return
    }

    clearMediaSession()
    navigator.mediaSession.metadata = new MediaMetadata({
        title,
        artwork: [{ src: artworkUrl }],
    })
    setActionHandlers({
        play,
        pause: () => videoEl.pause(),
        seekto: ({ seekTime }) => (videoEl.currentTime = seekTime),
        seekbackward: ({ seekOffset = SEEK_OFFSET_SECONDS }) =>
            (videoEl.currentTime = Math.max(
                videoEl.currentTime - seekOffset,
                0,
            )),
        seekforward: ({ seekOffset = SEEK_OFFSET_SECONDS }) =>
            (videoEl.currentTime = Math.min(
                videoEl.currentTime + seekOffset,
                videoEl.duration,
            )),
        previoustrack: previous,
        nexttrack: next,
    })

    // The OS seek bar extrapolates from the last position it was given; `timeupdate` also fires on
    // seeking and pausing
    positionUpdates = new AbortController()
    videoEl.addEventListener("timeupdate", () => updatePositionState(videoEl), {
        signal: positionUpdates.signal,
    })
}

/** Leaves the OS media controls with nothing from asset detail */
export function clearMediaSession() {
    if (!("mediaSession" in navigator)) {
        return
    }

    positionUpdates?.abort()
    positionUpdates = null
    navigator.mediaSession.metadata = null
    setActionHandlers({})
    navigator.mediaSession.setPositionState()
}

function setActionHandlers(handlers) {
    for (const action of ACTIONS) {
        try {
            navigator.mediaSession.setActionHandler(
                action,
                handlers[action] ?? null,
            )
        } catch {
            console.debug(`Media Session action "${action}" is not supported`)
        }
    }
}

function updatePositionState(videoEl) {
    if (Number.isFinite(videoEl.duration)) {
        navigator.mediaSession.setPositionState({
            duration: videoEl.duration,
            playbackRate: videoEl.playbackRate,
            position: Math.min(videoEl.currentTime, videoEl.duration),
        })
    }
}
