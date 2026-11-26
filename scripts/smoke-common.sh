# shellcheck shell=bash
#
# What the smoke tests share (smoke-docker.sh, smoke-jar.sh): the checks' failure and logging, the calls through the API that
# run Setup and import, and the first import's checks of faces and Scene embeddings. Sourced, never run.
#
# The script that sources it sets, before it calls any of these:
#   SMOKE_NAME   its name, which starts each line it prints
#   repo_root    the repository's root
#   work         a scratch folder of its own, for the bodies and lists these write
#   password     the Admin's password in the scratch instance
# and these set or read:
#   url          the server's base URL
#   token        the Admin's session token (run_setup, sign_in)
#   repo         the Library's ID (run_setup)
#   first_import the ID of the import of photos with faces and a video (import_faces_and_video)
#
# Every tool used is in POSIX or in both GNU and BSD alike (sed -n, grep -E, find without -printf, head -c), so a script that
# sources it runs on Linux, macOS and Git Bash on Windows.

photos_with_faces="$repo_root/altitude/test/resources/import/people"
other_photos="$repo_root/altitude/test/resources/import/images"

say() { printf '%s\n' "$SMOKE_NAME: $*"; }

fail() {
    printf '\n%s\n' "$SMOKE_NAME: FAILED: $1" >&2
    shift
    for detail in "$@"; do printf '%s\n' "$detail" | sed 's/^/    /' >&2; done
    exit 1
}

# The last lines of a server's log on stdin, without DEBUG, to stderr: the log's own lines alone, each of which starts with its
# date, so a crash report's hundreds of lines are left out
print_log_tail() {
    {
        printf '\n%s\n' "$SMOKE_NAME: the last lines of the server's log, without DEBUG:"
        grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}T' | grep -v ' DEBUG ' | tail -n 30 | cut -c1-300 || true
    } >&2
}

require_tools() {
    for tool in "$@"; do
        command -v "$tool" >/dev/null 2>&1 || fail "the host's tools" "$tool is not installed"
    done
    [ -d "$photos_with_faces" ] && [ -d "$other_photos" ] || fail "the test photos" "not found under $repo_root/altitude/test/resources/import"
}

# Altitude's folder of the per-user cache, as UserCache.folder has it: $XDG_CACHE_HOME/altitude, else ~/.cache/altitude. A
# relative XDG_CACHE_HOME is ignored. Its models/ is the models folder with models.dir empty, and its natives/ a native jar's.
user_cache_dir() {
    case "${XDG_CACHE_HOME:-}" in
        /* | [A-Za-z]:[/\\]*) printf '%s' "$XDG_CACHE_HOME/altitude" ;;
        *) printf '%s' "$HOME/.cache/altitude" ;;
    esac
}

# request <file for the body> <curl arguments...>: prints the status code, 000 when there was no answer, which the caller's
# check then fails on by its name
request() {
    local body="$1"
    shift
    : >"$body"
    curl --silent --show-error --max-time 120 --output "$body" --write-out '%{http_code}' "$@" || true
}

# The session token of a response's Set-Cookie header, sent back as a Bearer token: the cookie is Secure, which curl would not
# send over plain HTTP
token_of() { sed -n 's/^[Ss]et-[Cc]ookie: auth_token=\([^;]*\);.*/\1/p' "$1" | tr -d '\r' | head -n 1; }

# Signs the Admin in again: sets $token. The session key is made anew at each start, so a restart signs everyone out.
sign_in() {
    local status
    status="$(request "$work/body" --dump-header "$work/headers" --data-urlencode 'login=smoke' \
        --data-urlencode "password=$password" "$url/login")"
    token="$(token_of "$work/headers")"
    if [ "$status" != 302 ] || [ -z "$token" ]; then
        fail "signing in after a restart" "status $status and no session cookie"
    fi
}

# Runs Setup through the API: the Admin and a Library. Sets $repo and $token.
run_setup() {
    local setup status
    setup='{"repositoryName":"Smoke","adminEmail":"smoke@example.com","adminName":"Smoke Test","adminUsername":"smoke"'
    setup="$setup,\"password\":\"$password\",\"password2\":\"$password\"}"
    status="$(request "$work/body" --dump-header "$work/headers" -H 'Content-Type: application/json' --data "$setup" \
        "$url/htmx/setup")"
    repo="$(sed -n 's/^[Hh][Xx]-[Rr]edirect: \/r\/\([^[:space:]]*\).*/\1/p' "$work/headers" | tr -d '\r' | head -n 1)"
    token="$(token_of "$work/headers")"
    if [ "$status" != 200 ] || [ -z "$repo" ] || [ -z "$token" ]; then
        fail "Setup" "status $status, and no Library to go to or no session cookie: $(head -c 300 "$work/body")"
    fi
}

# Opens an Import of the files listed in a file (one path a line), uploads them and closes it: prints the Import's ID
import_files() {
    local list="$1" url="$2" token="$3" count status id file file_name
    count="$(grep -c . "$list" || true)"
    status="$(request "$work/body" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
        --data "{\"expectedFiles\":$count}" "$url/import/r/$repo/imports")"
    id="$(sed -n 's/.*"id":"\([^"]*\)".*/\1/p' "$work/body")"
    if [ "$status" != 200 ] || [ -z "$id" ]; then
        fail "opening an Import" "status $status: $(head -c 300 "$work/body")"
    fi

    while IFS= read -r file; do
        file_name="$(basename "$file")"
        case "$file_name" in
            *[!A-Za-z0-9._-]*) fail "uploading $file_name" "the script sends a file's name in the URL as it is: letters, digits, dot, dash and underscore alone" ;;
        esac
        status="$(request "$work/body" -H "Authorization: Bearer $token" -H 'Content-Type: application/octet-stream' \
            --data-binary "@$file" "$url/import/r/$repo/imports/$id/files?relPath=$file_name")"
        [ "$status" = 200 ] || fail "uploading $file_name" "status $status: $(head -c 300 "$work/body")"
    done <"$list"

    status="$(request "$work/body" -H "Authorization: Bearer $token" -X POST "$url/import/r/$repo/imports/$id/close")"
    [ "$status" = 200 ] || fail "closing Import $id" "status $status: $(head -c 300 "$work/body")"
    printf '%s' "$id"
}

# The Import history, as the Import page shows it, into $work/history
read_history() {
    local status
    status="$(request "$work/history" -H "Authorization: Bearer $2" "$1/htmx/import/r/$repo/history")"
    [ "$status" = 200 ] || fail "reading the Import history" "status $status: $(head -c 300 "$work/history")"
}

# Whether the history last read shows the Import as in progress
in_progress() { grep -q "class=\"import in-progress\" data-import-id=\"$1\"" "$work/history"; }

# How many files of an Import the history last read counts under an outcome (imported, duplicate, unsupported, failed,
# pending): the number on that outcome's chip, 0 when there is none
outcome_count() {
    local count
    count="$(sed -n "/id=\"importStatus-$1\"/,/<\/ul>/p" "$work/history" |
        sed -n "s/.*<li class=\"$2\">\([0-9,]*\) .*/\1/p" | tr -d ',' | head -n 1)"
    printf '%s' "${count:-0}"
}

# How many lines of a file hold a fixed text
lines_with() { grep -c -F -e "$1" "$2" || true; }

require_line() { grep -q -F -e "$2" "$1" || fail "$3" "no line of the log says \"$2\""; }

forbid_line() {
    if grep -q -F -e "$2" "$1"; then
        fail "$3" "the log says: $(grep -F -e "$2" "$1" | head -n 3 | cut -c1-300)"
    fi
}

# forbid_line for a line of the server's own. Once that line is reworded in the sources, no log holds the old text and the
# check would pass whatever the server did: so the text must still be in a string of the sources, or this fails.
forbid_own_line() {
    grep -r -F -h -e "$2" "$repo_root/altitude/src" | grep -q '"' || fail "the script's own check of \"$3\"" \
        "no string in altitude/src says \"$2\": the line was reworded, and this check would pass whatever the log held"
    forbid_line "$@"
}

# The log lines no run may hold, whatever it stopped by: a crash report, an OutOfMemoryError, ONNX Runtime's telemetry, an image
# not embedded, an ERROR
forbid_failure_lines() {
    local log="$1"
    forbid_line "$log" 'A fatal error has been detected by the Java Runtime Environment' "no crash report"
    forbid_line "$log" 'SIGSEGV' "no crash report"
    forbid_line "$log" 'SIGABRT' "no crash report"
    forbid_line "$log" 'terminate called' "no crash report"
    forbid_line "$log" 'java.lang.OutOfMemoryError' "no OutOfMemoryError"
    # The start warns when ORT_DISABLE_TELEMETRY is not in force, and ONNX Runtime complains on a read-only filesystem when its
    # telemetry is on
    forbid_own_line "$log" "ONNX Runtime's telemetry is on" "ONNX Runtime's telemetry is off"
    forbid_line "$log" 'Failed to persist telemetry device ID' "ONNX Runtime's telemetry is off"
    forbid_own_line "$log" ' could not embed ' "every image was embedded"
    forbid_line "$log" ' ERROR ' "no error logged"
}

# require_drain_lines <log> <check's name>: the shutdown's lines, which a stop that drained and closed in order logs, from the
# log's last line saying the stop began to its end. Writes the log from that line on to <log>-stop, and up to it to
# <log>-before-stop.
require_drain_lines() {
    local log="$1" stopping_at
    stopping_at="$(grep -n -F 'Stopping: new requests are answered 503' "$log" | tail -n 1 | cut -d: -f1 || true)"
    [ -n "$stopping_at" ] || fail "$2" "no line of the log says the shutdown began"
    tail -n "+$stopping_at" "$log" >"$log-stop"
    head -n "$stopping_at" "$log" >"$log-before-stop"
    require_line "$log-stop" 'Draining the import queue' "$2"
    require_line "$log-stop" 'The import queue has finished' "$2"
    forbid_own_line "$log-stop" 'did not finish within' "$2: every queue drained"
    require_line "$log-stop" 'Cleaned up' "$2: the database closed"
    require_line "$log-stop" 'The HTTP server has stopped' "$2: ended in order"
}

# Makes $1 photos in the new folder $2 and adds them to the list $3: frames of the moving lavfi test pattern $4, each a picture of
# its own, so none is a duplicate of another
make_photos() {
    mkdir "$2"
    ffmpeg -nostdin -loglevel error -y -f lavfi -i "$4=size=640x480:rate=25" -frames:v "$1" -q:v 3 "$2/frame-%03d.jpg" ||
        fail "making photos" "ffmpeg -f lavfi -i $4 failed"
    find "$2" -type f | sort >>"$3"
}

# Imports the test photos with faces and a short video, waits for the import to settle, and checks that every file was
# imported, the video among them, that faces were found and a face's image is served, and that a Describe search finds images
# by the Scene embeddings the import wrote. Sets $first_import.
import_faces_and_video() {
    local first_count waited imported status people face described

    # A few seconds of a test pattern, in whatever codec this ffmpeg writes an .mp4 in: the server's FFmpeg opens, seeks and
    # decodes it
    ffmpeg -nostdin -loglevel error -y -f lavfi -i 'testsrc2=duration=3:size=640x360:rate=25' \
        -pix_fmt yuv420p "$work/clip.mp4" || fail "making the video" "ffmpeg -f lavfi failed"

    find -H "$photos_with_faces" -type f | sort >"$work/first"
    echo "$work/clip.mp4" >>"$work/first"
    first_count="$(grep -c . "$work/first" || true)"
    first_import="$(import_files "$work/first" "$url" "$token")"

    waited=0
    while :; do
        read_history "$url" "$token"
        if ! in_progress "$first_import"; then break; fi
        [ "$waited" -lt 300 ] || fail "the first import" "still in progress after 300 seconds"
        sleep 1
        waited=$((waited + 1))
    done
    imported="$(outcome_count "$first_import" imported)"
    [ "$imported" = "$first_count" ] || fail "the first import" \
        "$imported of $first_count files imported: $(sed -n "/id=\"importStatus-$first_import\"/,/<\/ul>/p" "$work/history" | grep '<li' | tr -s ' \n' ' ')"
    say "   $imported files imported"

    status="$(request "$work/body" -H "Authorization: Bearer $token" "$url/htmx/search/r/$repo?triaged=true&rpp=100")"
    [ "$status" = 200 ] || fail "listing the imported assets" "status $status"
    [ "$(lines_with 'data-media-type="video"' "$work/body")" = 1 ] || fail "the video" "the results hold no video asset"

    # Faces found: a person for each face not matched to another, and a cover face's image served from the Data directory
    status="$(request "$work/body" -H "Authorization: Bearer $token" "$url/htmx/people/r/$repo/list?buckets=matched,unmatched&triaged=true")"
    people="$(lines_with ' data-person-id="' "$work/body")"
    if [ "$status" != 200 ] || [ "$people" -lt 1 ]; then
        fail "faces found" "status $status, and the People list holds $people people"
    fi
    face="$(sed -n "s/.*<img src=\"\(\/content\/r\/$repo\/[^\"]*\)\".*/\1/p" "$work/body" | head -n 1)"
    [ -n "$face" ] || fail "faces found" "no person of the People list has a cover face"
    status="$(request "$work/body" -H "Authorization: Bearer $token" "$url$face")"
    if [ "$status" != 200 ] || [ ! -s "$work/body" ]; then
        fail "a face's image" "$face answered $status"
    fi
    say "   $people people found, and a face's image is served"

    # Scene embeddings written: a Describe search ranks the images by their SigLIP 2 embeddings, which the import wrote
    status="$(request "$work/body" -H "Authorization: Bearer $token" "$url/htmx/search/r/$repo?describe=a%20person&triaged=true&rpp=100")"
    described="$(sed -n 's/.*data-results-total="\([0-9]*\)".*/\1/p' "$work/body" | head -n 1)"
    if [ "$status" != 200 ] || [ "${described:-0}" -lt 1 ]; then
        fail "Scene embeddings written" "a Describe search answered $status with ${described:-no} results"
    fi
    say "   a Describe search finds $described images"
}
