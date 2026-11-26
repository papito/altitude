#!/usr/bin/env bash
#
# Smoke-tests a native jar (make smoke-jar, after ./mill altitude.platformJar): copies it into a scratch folder and starts it
# with `java -jar` from another, with a scratch home folder, user cache and temp folder, the models folder named and a free
# port, as a person would start it: the environment holds no ALTITUDE_ variable but the models folder and the port, and no
# ORT_DISABLE_TELEMETRY, so the launcher's second JVM is part of what it tests. It runs Setup, imports photos with faces and a
# video, stops the jar, starts it a second time and stops it again, and checks what the jar promises:
#
#   - the server listens on localhost alone;
#   - the launcher started a second JVM with ONNX Runtime's telemetry off, and a stop of the launcher reaches it and drains;
#   - data/ is beside the jar, and nothing else is written there or in the folder it was started from;
#   - the natives are unpacked into the user cache's natives/ folder, and nothing of Altitude's goes anywhere else: the home
#     folder holds javacpp's cache alone, and the real home folder, user cache and temp folder are left as they were;
#   - nothing is left in the temp folder after a stop;
#   - a second start writes nothing in the natives folder.
#
# On the host it needs a Java 21 or later (a JDK's jar tool, or unzip, reads the jar's manifest), curl, ffmpeg, bash with the usual
# tools (sed, grep, find, mktemp), ss (Linux) or netstat (macOS, Windows) for the address check, and pgrep (Linux, macOS) or
# PowerShell and taskkill (Windows) to find and end the jar's JVMs. It runs on Linux, on macOS and in Git Bash on Windows.
#
# On Windows no signal can ask a JVM to drain from outside its console: the launcher passes a Ctrl-C in the console on to the
# server, and a stop from elsewhere is TerminateProcess. So there the stop is `taskkill /F /T`, which ends the launcher and the
# server at once, and Windows skips two checks and loosens a third:
#   - that the log holds the stop's drain, its shutdown lines, when the launcher ends;
#   - that the launcher ends with the 143 of a JVM ended by SIGTERM;
#   - the temp folder may keep ONNX Runtime's empty onnxruntime-java folder, which a JVM that exits by itself removes.
# Everything else is checked as on Linux and macOS, the second JVM and no JVM left after a stop among them.
#
# All it makes is in one scratch folder, removed when the script ends, whether it passes or fails, with any JVM it started. It
# exits 0 when every check passes and 1 at the first that fails, naming it.
#
#   JAR                  the jar to test, or the script's first argument: unset, the one ./mill altitude.platformJar built for
#                        this machine's platform (out/altitude/platformJar.dest/altitude-<version>-<platform>.jar)
#   JAVA                 the java to start it with: $JAVA_HOME/bin/java, else the java on the PATH
#   ALTITUDE_MODELS_DIR  the models folder the jar is given: the per-user models cache unless set ($XDG_CACHE_HOME/altitude/models,
#                        else ~/.cache/altitude/models). An empty one is filled by the first start's download, about 1.6 GB.
#   START_TIMEOUT        the seconds a start may take, the model download included (1200)
#   STOP_TIMEOUT         the seconds a stop may take (120, compose.yaml's stop_grace_period)
#   KEEP                 set to 1 to leave the scratch folder in place after the run, for a look at a failure
#
# The checks read the server's log for lines of ServerShutdown, QueuedPipeline, NativeLibraries, SceneEmbedder and App by their
# text: a reworded line fails its check here. A line that must not be there is first found in the sources (forbid_own_line in
# smoke-common.sh, which holds what this script shares with smoke-docker.sh).

set -euo pipefail

START_TIMEOUT="${START_TIMEOUT:-1200}"
STOP_TIMEOUT="${STOP_TIMEOUT:-120}"

case "$(uname -s)" in
    Linux) os=linux ;;
    Darwin) os=macosx ;;
    MINGW* | MSYS* | CYGWIN*) os=windows ;;
    *) os="$(uname -s)" ;;
esac
case "$(uname -m)" in
    x86_64 | amd64) arch=x86_64 ;;
    aarch64 | arm64) arch=arm64 ;;
    *) arch="$(uname -m)" ;;
esac
platform="$os-$arch"

# A path as both this shell and the JVM read it. On Windows, C:/like/this: Git Bash takes it, and so does a Windows program,
# to which Git Bash passes it unconverted, where it would rewrite a /c/like/this one only where it looks like a path.
host_path() {
    if [ "$os" = windows ]; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

SMOKE_NAME=smoke-jar
repo_root="$(host_path "$(cd "$(dirname "$0")/.." && pwd)")"
# shellcheck source=scripts/smoke-common.sh
. "$repo_root/scripts/smoke-common.sh"

work=""
passed=0
# The Admin's password in the scratch instance
password="smoke-test-password"
# Set by the functions below
url=""
token=""
pid=""
log=""
exit_code=""
stop_took=0

# The process IDs of the JVMs running the scratch jar, one a line: the launcher and its second JVM while the server runs. They are
# found by the scratch folder's name in their command lines, which mktemp made of letters, digits and dots alone: by pgrep on
# Linux and macOS, and on Windows, where the second JVM's command line names the jar with backslashes, in the process table.
jar_pids() {
    local name query
    name="$(basename "$work")"
    if [ "$os" = windows ]; then
        query="Get-CimInstance Win32_Process | Where-Object { \$_.Name -eq 'java.exe' -and \$_.CommandLine -like '*$name*' } |"
        query="$query ForEach-Object { \$_.ProcessId }"
        powershell -NoProfile -NonInteractive -Command "$query" | tr -d '\r'
    else
        pgrep -f "$name/app/altitude-" || true
    fi
}

# How many JVMs run the scratch jar
jar_processes() { jar_pids | grep -c . || true; }

# Ends what is left of the server: the launcher, then any JVM still running the scratch jar, such as a second JVM whose launcher
# was killed
stop_leftovers() {
    local leftover
    if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
        if [ "$os" = windows ]; then
            taskkill //F //T //PID "$(cat "/proc/$pid/winpid")" >/dev/null 2>&1 || true
        else
            kill -TERM "$pid" 2>/dev/null || true
            for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
        fi
    fi
    [ -n "$work" ] || return 0
    for leftover in $(jar_pids 2>/dev/null || true); do
        if [ "$os" = windows ]; then
            taskkill //F //PID "$leftover" >/dev/null 2>&1 || true
        else
            kill -KILL "$leftover" 2>/dev/null || true
        fi
    done
}

cleanup() {
    status=$?
    trap - EXIT INT TERM
    if [ "$passed" != 1 ] && [ -n "$log" ] && [ -f "$log" ]; then print_log_tail <"$log" || true; fi
    stop_leftovers
    if [ "${KEEP:-0}" = 1 ]; then
        [ -z "$work" ] || say "KEEP=1: left $work"
    elif [ -n "$work" ]; then
        # On Windows a JVM's files stay locked for a moment after it is ended
        for _ in 1 2 3 4 5; do rm -rf "$work" 2>/dev/null && break; sleep 2; done
        [ ! -e "$work" ] || say "could not remove $work"
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# ---------------------------------------------------------------------------------------------------------------------
# What the checks are built from

# The local addresses of the TCP sockets listening on a port, one a line, without the port: none when nothing listens there
listeners_on() {
    local port="$1"
    case "$os" in
        linux)
            ss -ltn | awk -v p="$port" 'NR > 1 { n = split($4, a, ":"); if (a[n] == p) { sub(":" p "$", "", $4); print $4 } }' ;;
        windows)
            # TCP    127.0.0.1:20123    0.0.0.0:0    LISTENING, and [::1]:20123 for IPv6
            netstat -an | tr -d '\r' |
                awk -v p="$port" '$1 == "TCP" && $4 == "LISTENING" { n = split($2, a, ":"); if (a[n] == p) { sub(":" p "$", "", $2); print $2 } }' ;;
        *)
            # BSD's: tcp4  0  0  127.0.0.1.20123  *.*  LISTEN, and *.20123 for every address
            netstat -an -p tcp |
                awk -v p="$port" '$NF == "LISTEN" { n = split($4, a, "."); if (a[n] == p) { sub("\\." p "$", "", $4); print $4 } }' ;;
    esac
}

# A port nothing listens on, outside every system's range of ephemeral ports (Linux's from 32768, macOS' and Windows' from 49152)
free_port() {
    local candidate
    for _ in $(seq 1 20); do
        candidate=$((20000 + RANDOM % 12000))
        if [ -z "$(listeners_on "$candidate")" ]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    fail "a free port" "20 ports picked at random between 20000 and 32000 were all taken"
}

# Whether the launcher is still running
running() { kill -0 "$pid" 2>/dev/null; }

# Starts the scratch jar in the background as a person would, from the run folder, with the scratch home, cache and temp folders
# and nothing else of the caller's that steers Altitude: its log, both JVMs' output, is $work/log-<run>
start_server() {
    log="$work/log-$1"
    (
        cd "$run_dir"
        for variable in $(compgen -e); do
            case "$variable" in ALTITUDE_* | ORT_DISABLE_TELEMETRY) unset "$variable" ;; esac
        done
        export ALTITUDE_MODELS_DIR="$models_dir" ALTITUDE_PORT="$port"
        # javacpp caches under user.home, which Java takes from the system's account and not from $HOME: the property too
        export HOME="$home" XDG_CACHE_HOME="$cache" TMPDIR="$temp" TMP="$temp" TEMP="$temp"
        exec "$java" "-Duser.home=$home" "-Djava.io.tmpdir=$temp" -jar "$app/$jar_name"
    ) >"$log" 2>&1 &
    pid=$!
}

# Waits until /api/health answers ok. A launcher that exits fails at once.
wait_until_healthy() {
    local what="$1" waited=0 status
    while :; do
        if ! running; then
            exit_code=0
            wait "$pid" || exit_code=$?
            fail "$what" "the jar exited with status $exit_code before it answered"
        fi
        # Refused until the server listens, which curl would say at every try
        status="$(request "$work/health" --max-time 5 "$url/api/health" 2>/dev/null)"
        if [ "$status" = 200 ] && grep -q '"status":"ok"' "$work/health"; then return 0; fi
        if [ "$waited" -ge "$START_TIMEOUT" ]; then
            fail "$what" "$url/api/health did not answer ok within $START_TIMEOUT seconds (START_TIMEOUT): $status"
        fi
        # A first start downloads the model files: show that it is moving
        if [ "$waited" -gt 0 ] && [ $((waited % 30)) = 0 ]; then
            say "  still starting after ${waited}s: $(tail -n 1 "$log" | cut -c1-160)"
        fi
        sleep 2
        waited=$((waited + 2))
    done
}

# Stops the server as a person would and waits for the launcher to end, at most STOP_TIMEOUT seconds; sets $exit_code and
# $stop_took. On Linux and macOS it is a SIGTERM to the launcher, which passes it on to the second JVM and waits for it: the
# launcher must end once the server has, with the 143 of a JVM ended by SIGTERM, and the log must hold the stop's drain then.
# On Windows it is taskkill /F /T, a kill of both (the script's header says why).
stop_server() {
    local what="$1" started
    started="$(date +%s)"
    if [ "$os" = windows ]; then
        taskkill //F //T //PID "$(cat "/proc/$pid/winpid")" >/dev/null || fail "$what" "taskkill could not end the jar"
    else
        kill -TERM "$pid"
    fi
    while running; do
        if [ $(($(date +%s) - started)) -ge "$STOP_TIMEOUT" ]; then
            fail "$what" "the jar was still running $STOP_TIMEOUT seconds after the stop (STOP_TIMEOUT)"
        fi
        sleep 1
    done
    exit_code=0
    wait "$pid" || exit_code=$?
    stop_took=$(($(date +%s) - started))
    pid=""
    # As the launcher ended: no JVM of the jar is left, and the server had drained and stopped
    cp "$log" "$log-at-exit"
    [ "$(jar_processes)" = 0 ] || fail "$what" "a JVM of the jar is still running after the launcher ended"
    [ "$os" = windows ] && return 0
    if [ "$exit_code" != 143 ]; then
        fail "$what" "the launcher's exit status is $exit_code, not 143, after $stop_took seconds"
    fi
    require_drain_lines "$log-at-exit" "$what"
}

# The Altitude-Build of a jar's manifest, read with the jar tool of the JDK that $java is part of, else with unzip; empty when
# neither can read it
manifest_build_of() {
    local home tool
    home="$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p' | tr -d '\r' | head -n 1)"
    tool="$(host_path "${home:-.}")/bin/jar"
    mkdir -p "$work/manifest"
    if [ -n "$home" ] && { [ -x "$tool" ] || [ -x "$tool.exe" ]; }; then
        (cd "$work/manifest" && "$tool" xf "$1" META-INF/MANIFEST.MF) || true
    elif command -v unzip >/dev/null 2>&1; then
        unzip -q -o "$1" META-INF/MANIFEST.MF -d "$work/manifest" || true
    fi
    [ ! -f "$work/manifest/META-INF/MANIFEST.MF" ] ||
        tr -d '\r' <"$work/manifest/META-INF/MANIFEST.MF" | sed -n 's/^Altitude-Build: //p'
}

# What is in a folder, one name a line, sorted, the hidden ones among them
contents() { (cd "$1" && ls -A) | sort; }

# The leftovers in the scratch temp folder: none, but for the JVM's own hsperfdata folder, which is no file of Altitude's and on
# Windows is in the temp folder, and, on Windows, where the stop is a kill, ONNX Runtime's empty folder, which a JVM that exits
# by itself removes
temp_leftovers() {
    if [ "$os" = windows ]; then
        find -H "$temp" -mindepth 1 -maxdepth 1 ! -name 'hsperfdata_*' ! \( -type d -name 'onnxruntime-java*' -empty \)
    else
        find -H "$temp" -mindepth 1 -maxdepth 1 ! -name 'hsperfdata_*'
    fi
}

# What the runs wrote of Altitude's in the caller's own home folder, user cache and temp folders since the script began: there,
# javacpp's cache and the native libraries' folder, and in a temp folder a library or a spooled file
written_elsewhere() {
    local real_cache folder
    case "${XDG_CACHE_HOME:-}" in
        /* | [A-Za-z]:[/\\]*) real_cache="$(host_path "$XDG_CACHE_HOME")" ;;
        *) real_cache="$(host_path "$HOME")/.cache" ;;
    esac
    for folder in "$(host_path "$HOME")/.javacpp" "$real_cache/altitude/natives"; do
        [ ! -d "$folder" ] || find -H "$folder" -newer "$work/began" 2>/dev/null | head -n 5
    done
    for folder in /tmp "${caller_tmpdir:-}" "${caller_tmp:-}" "${caller_temp:-}"; do
        [ -n "$folder" ] && [ -d "$folder" ] || continue
        find -H "$folder" -mindepth 1 -maxdepth 1 -newer "$work/began" ! -name 'altitude-smoke*' \( -iname '*onnxruntime*' \
            -o -iname '*sqlite*' -o -iname '*javacpp*' -o -iname '*.so' -o -iname '*.so.*' -o -iname '*.dylib' -o -iname '*.jnilib' \
            -o -iname '*.dll' -o -iname 'undertow*' -o -iname 'apache-tika*' \) 2>/dev/null | head -n 5
    done
}

# ---------------------------------------------------------------------------------------------------------------------
# Before anything is made

require_tools curl ffmpeg
case "$os" in
    linux) require_tools ss ;;
    macosx | windows) require_tools netstat ;;
    *) fail "the host" "$(uname -s) is neither Linux, macOS nor Windows" ;;
esac
if [ "$os" = windows ]; then require_tools powershell taskkill; else require_tools pgrep; fi

if [ -n "${JAVA:-}" ]; then
    java="$JAVA"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$(host_path "$JAVA_HOME")/bin/java" ]; then
    java="$(host_path "$JAVA_HOME")/bin/java"
else
    java="$(command -v java)" || fail "the host's tools" "no java on the PATH, and neither JAVA nor JAVA_HOME names one"
fi

jar="${1:-${JAR:-}}"
if [ -n "$jar" ]; then
    jar="$(host_path "$jar")"
else
    built="$repo_root/out/altitude/platformJar.dest"
    for candidate in "$built"/altitude-*-"$platform".jar; do
        [ -f "$candidate" ] || fail "the jar" "no altitude-<version>-$platform.jar in $built: run ./mill altitude.platformJar first"
        [ -z "$jar" ] || fail "the jar" "more than one jar for $platform in $built: name one in JAR"
        jar="$candidate"
    done
fi
[ -f "$jar" ] || fail "the jar" "$jar is not a file"
jar_name="$(basename "$jar")"
case "$jar_name" in
    *-"$platform".jar) ;;
    *) fail "the jar" "$jar_name is no jar for this machine, $platform" ;;
esac

# On Windows the variable may be a D:\like\this path, as a workflow's runner.temp is
models_dir="$(host_path "${ALTITUDE_MODELS_DIR:-$(default_models_dir)}")"
mkdir -p "$models_dir"
models_dir="$(host_path "$(cd "$models_dir" && pwd)")"

# The caller's temp folders, before the jar's are named: written_elsewhere looks in them
caller_tmpdir="$(host_path "${TMPDIR:-/tmp}")"
caller_tmp="$(host_path "${TMP:-/tmp}")"
caller_temp="$(host_path "${TEMP:-/tmp}")"

work="$(host_path "$(mktemp -d "${TMPDIR:-/tmp}/altitude-smoke-jar.XXXXXX")")"
# The jar's folder, the folder it is started from, and the scratch home, user cache and temp folders: each its own, so each
# check can tell who wrote what
app="$work/app"
run_dir="$work/run"
home="$work/home"
cache="$work/cache"
temp="$work/temp"
mkdir "$app" "$run_dir" "$home" "$cache" "$temp"
cp "$jar" "$app/"
touch "$work/began"
port="$(free_port)"
url="http://localhost:$port"
say "$jar_name on $platform with $("$java" -version 2>&1 | head -n 1), port $port, models in $models_dir"

# ---------------------------------------------------------------------------------------------------------------------
say "1. the first start"

start_server 1
wait_until_healthy "the first start"
say "   healthy, and $url/api/health answers ok"

processes="$(jar_processes)"
[ "$processes" = 2 ] || fail "the launcher's second JVM" "$processes processes run the jar, not the launcher and its second JVM"
say "   the launcher runs the server in a second JVM"

# Where the server listens: the start's line, and every socket listening on the port a loopback one
require_line "$log" "Altitude is listening on http://localhost:$port " "the server listens on localhost"
listeners="$(listeners_on "$port")"
[ -n "$listeners" ] || fail "the server listens on localhost" "no socket listens on port $port, by $( [ "$os" = linux ] && echo ss || echo netstat)"
wildcard="$(printf '%s\n' "$listeners" | grep -v -E '^(127\.[0-9.]+|\[?::1\]?|\[?::ffff:127\.[0-9.]+\]?)$' || true)"
[ -z "$wildcard" ] || fail "the server listens on localhost" "port $port is listened on at another address than a loopback one:" "$wildcard"
say "   it listens on $(printf '%s\n' "$listeners" | tr '\n' ' ')alone"

# ---------------------------------------------------------------------------------------------------------------------
say "2. Setup"

run_setup
say "   the Admin and Library $repo exist"

# ---------------------------------------------------------------------------------------------------------------------
say "3. an import of photos with faces and a video"

import_faces_and_video

# ---------------------------------------------------------------------------------------------------------------------
say "4. a stop"

stop_server "the first stop"
if [ "$os" = windows ]; then
    say "   ended by taskkill in ${stop_took}s: the drain is not checked on Windows"
else
    say "   the launcher passed the stop on and ended with the server, in ${stop_took}s with exit status 143, and the stop drained"
fi
log_1="$log"
require_line "$log_1" 'Unpacked ' "the first start unpacked the natives"
require_line "$log_1" "Data directory: " "the start names its Data directory"
forbid_failure_lines "$log_1"

# ---------------------------------------------------------------------------------------------------------------------
say "5. what the run wrote, and where"

# data/ beside the jar, which is the root of a relative Data directory in prod, whatever folder the jar is started from
[ "$(contents "$app")" = "$(printf '%s\n%s' "$jar_name" data | sort)" ] ||
    fail "data/ is beside the jar" "the jar's folder holds:" "$(contents "$app")"
[ -f "$app/data/db/altitude.db" ] || fail "data/ is beside the jar" "data/db/altitude.db is not there; data/ holds:" "$(contents "$app/data")"
[ -z "$(contents "$run_dir")" ] || fail "nothing is written where the jar is started" "the folder it was started from holds:" "$(contents "$run_dir")"
say "   data/ is beside the jar, and the folder it was started from is empty"

leftovers="$(temp_leftovers)"
[ -z "$leftovers" ] || fail "nothing is left in the temp folder" "after the stop it holds:" "$leftovers"
say "   the temp folder is empty"

# The user cache holds the natives alone: the models are where ALTITUDE_MODELS_DIR says, and ONNX Runtime, its telemetry off,
# keeps no device ID in it
[ "$(contents "$cache")" = altitude ] && [ "$(contents "$cache/altitude")" = natives ] ||
    fail "the natives alone are in the user cache" "it holds:" "$(cd "$cache" && find . | sort | head -n 20)"
natives="$cache/altitude/natives"
builds="$(contents "$natives" | grep -v -x -F .lock || true)"
if ! printf '%s' "$builds" | grep -q -x -E "[0-9a-f]{16}-$platform"; then
    fail "the natives are unpacked into natives/<build>-<platform>/" "natives/ holds:" "$(contents "$natives")"
fi
build_dir="$natives/$builds"
manifest_build="$(manifest_build_of "$app/$jar_name")"
[ -n "$manifest_build" ] || fail "the jar's Altitude-Build" \
    "neither the JDK's jar tool (next to $java) nor unzip read Altitude-Build from the jar's manifest"
if [ "$builds" != "$manifest_build-$platform" ]; then
    fail "the natives are unpacked into natives/<build>-<platform>/" "the folder is $builds, and the jar's Altitude-Build $manifest_build"
fi
require_line "$log_1" "Native libraries: " "the start names the natives' folder"
grep -F 'Native libraries: ' "$log_1" | grep -q -F "$builds" ||
    fail "the start loads the natives from the user cache" "the start's line names another folder: $(grep -F 'Native libraries: ' "$log_1" | head -n 1)"
unpacked="$(contents "$build_dir")"
for library in onnxruntime sqlitejdbc vector; do
    printf '%s\n' "$unpacked" | grep -q -i "$library" || fail "the natives are unpacked" "no $library library in $builds:" "$unpacked"
done
if printf '%s\n' "$unpacked" | grep -q -F .part; then
    fail "the natives are unpacked whole" "a .part file is left in $builds:" "$unpacked"
fi
say "   natives/$builds holds $(printf '%s\n' "$unpacked" | grep -c .) libraries, and the user cache nothing else"

# javacpp keeps its cache in the home folder, and nothing else of the run's is there
[ "$(contents "$home")" = .javacpp ] || fail "nothing but javacpp's cache is in the home folder" "it holds:" "$(contents "$home")"
elsewhere="$(written_elsewhere)"
[ -z "$elsewhere" ] || fail "nothing of Altitude's went elsewhere" "written in the caller's own folders during the run:" "$elsewhere"
say "   the home folder holds javacpp's cache alone, and the caller's home, cache and temp folders are left as they were"

# ---------------------------------------------------------------------------------------------------------------------
say "6. a second start writes nothing in the natives folder"

touch "$work/second-began"
before="$(contents "$build_dir")"
start_server 2
wait_until_healthy "the second start"
stop_server "the second stop"
log_2="$log"
forbid_own_line "$log_2" 'Unpacked ' "the second start writes no native library"
require_line "$log_2" "Native libraries: " "the second start loads the natives from the user cache"
grep -F 'Native libraries: ' "$log_2" | grep -q -F "$builds" ||
    fail "the second start loads the natives from the user cache" "its line names another folder: $(grep -F 'Native libraries: ' "$log_2" | head -n 1)"
[ "$(contents "$build_dir")" = "$before" ] || fail "the second start writes nothing in the natives folder" \
    "before it:" "$before" "after it:" "$(contents "$build_dir")"
rewritten="$(find -H "$build_dir" -mindepth 1 -newer "$work/second-began")"
[ -z "$rewritten" ] || fail "the second start writes nothing in the natives folder" "written again:" "$rewritten"
forbid_failure_lines "$log_2"
leftovers="$(temp_leftovers)"
[ -z "$leftovers" ] || fail "nothing is left in the temp folder" "after the second stop it holds:" "$leftovers"
[ -z "$(contents "$run_dir")" ] || fail "nothing is written where the jar is started" "the folder it was started from holds:" "$(contents "$run_dir")"
say "   the natives were found in place and none was written again; the temp folder is empty"

passed=1
say "PASSED"
