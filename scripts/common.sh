#!/usr/bin/env bash
# Shared helpers for install.sh / uninstall.sh / status.sh.
# Sourced, not executed.

PKG="com.tmc.appalive"
ADMIN_COMPONENT="$PKG/.AdminReceiver"
SERVICE_COMPONENT="$PKG/.AliveService"
CONTROL_COMPONENT="$PKG/.ControlReceiver"
ACTION_CONTROL="com.tmc.appalive.action.CONTROL"

SCRIPT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_FILE="$SCRIPT_ROOT/.appalive-install-state"

# ------------------------------------------------------------------ output

if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
    C_RED=$'\033[31m'; C_GRN=$'\033[32m'; C_YEL=$'\033[33m'
    C_BLU=$'\033[36m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
    C_RED=; C_GRN=; C_YEL=; C_BLU=; C_DIM=; C_OFF=
fi

FAILURES=0

say()  { echo "$*"; }
head_() { echo ""; echo "${C_BLU}==> $*${C_OFF}"; }
ok()   { echo "  ${C_GRN}ok${C_OFF}   $*"; }
skip() { echo "  ${C_DIM}--   $*${C_OFF}"; }
warn() { echo "  ${C_YEL}warn${C_OFF} $*"; }
bad()  { echo "  ${C_RED}FAIL${C_OFF} $*"; FAILURES=$((FAILURES+1)); }
die()  { echo ""; echo "${C_RED}ERROR:${C_OFF} $*" >&2; exit 1; }

# --------------------------------------------------------------------- adb

find_adb() {
    if [ -n "${ADB:-}" ] && [ -x "$ADB" ]; then return; fi
    local candidates=()
    for base in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [ -n "$base" ] || continue
        if command -v cygpath >/dev/null 2>&1; then base="$(cygpath -u "$base")"; fi
        candidates+=("$base/platform-tools/adb.exe" "$base/platform-tools/adb")
    done
    candidates+=("$HOME/Android/Sdk/platform-tools/adb"
                 "$HOME/Library/Android/sdk/platform-tools/adb"
                 "/usr/local/bin/adb" "/usr/bin/adb")
    for c in "${candidates[@]}"; do
        if [ -x "$c" ]; then ADB="$c"; return; fi
    done
    if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return; fi
    die "adb not found. Set ANDROID_HOME, or ADB=/path/to/adb."
}

adb_() { "$ADB" ${SERIAL:+-s "$SERIAL"} "$@"; }

# Device shell with CRLF stripped (adb on Windows emits \r).
dsh() { adb_ shell "$@" 2>/dev/null | tr -d '\r'; }
# ...and a variant that keeps stderr, for commands whose errors we want to read.
dsh_err() { adb_ shell "$@" 2>&1 | tr -d '\r'; }

select_device() {
    local list count
    list="$(adb_ devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')"
    count="$(printf '%s\n' "$list" | grep -c . || true)"
    if [ "$count" = "0" ]; then
        die "No device in 'device' state. Check: $ADB devices"
    fi
    if [ -z "${SERIAL:-}" ] && [ "$count" != "1" ]; then
        echo "Multiple devices attached:" >&2
        printf '  %s\n' $list >&2
        die "Pick one with --serial <id>."
    fi
    [ -n "${SERIAL:-}" ] || SERIAL="$(printf '%s\n' "$list" | head -1)"
}

API=0
describe_device() {
    API="$(dsh getprop ro.build.version.sdk | tr -d ' ')"
    : "${API:=0}"
    say "  device      : $SERIAL"
    say "  model       : $(dsh getprop ro.product.manufacturer) $(dsh getprop ro.product.model)"
    say "  android     : $(dsh getprop ro.build.version.release) (API $API)"
    local miui hyper
    miui="$(dsh getprop ro.miui.ui.version.name)"
    hyper="$(dsh getprop ro.mi.os.version.name)"
    [ -n "$miui$hyper" ] && say "  oem skin    : MIUI/HyperOS ${miui}${hyper}"
}

# ------------------------------------------------------------------- state
# Plain TSV so the uninstaller can restore what we changed without needing a
# JSON parser on the host.

state_reset() { : > "$STATE_FILE"; }
state_put()   { printf '%s\t%s\n' "$1" "$2" >> "$STATE_FILE"; }
state_get()   {
    [ -f "$STATE_FILE" ] || return 0
    awk -F'\t' -v k="$1" '$1==k {print $2; exit}' "$STATE_FILE"
}
state_all()   {
    [ -f "$STATE_FILE" ] || return 0
    awk -F'\t' -v k="$1" '$1==k {print $2}' "$STATE_FILE"
}

# ------------------------------------------------------------ device tweaks

# `cmd appops set` is silent on success and prints a message on failure.
set_appop() { # pkg op mode
    local out
    out="$(dsh_err cmd appops set "$1" "$2" "$3")"
    if echo "$out" | grep -qiE 'error|exception|unknown|not a valid|bad '; then
        return 1
    fi
    return 0
}

apply_appop() { # pkg op mode label
    if set_appop "$1" "$2" "$3"; then
        ok "$4"
    else
        skip "$4 (not supported on API $API)"
    fi
}

power_allowlist_add() { # pkg
    local out
    out="$(dsh_err dumpsys deviceidle whitelist "+$1")"
    if echo "$out" | grep -qiE 'error|exception|unknown'; then return 1; fi
    return 0
}

power_allowlist_remove() { # pkg
    dsh_err dumpsys deviceidle whitelist "-$1" >/dev/null 2>&1 || true
}

get_standby_bucket() { # pkg
    dsh am get-standby-bucket "$1" 2>/dev/null | tr -d ' '
}

set_standby_bucket() { # pkg bucket
    local out
    out="$(dsh_err am set-standby-bucket "$1" "$2")"
    if echo "$out" | grep -qiE 'error|exception|unknown'; then return 1; fi
    return 0
}

is_installed() { # pkg
    [ -n "$(dsh pm list packages "$1" | grep -x "package:$1" || true)" ]
}

# ------------------------------------------------------------ control channel

# Returns the receiver's result data, which `am broadcast` echoes back.
# -f 32 is FLAG_INCLUDE_STOPPED_PACKAGES, so control still reaches us after the
# app has been force-stopped - which matters for `release` during uninstall.
control() { # cmd [extra args...]
    local cmd="$1"; shift
    adb_ shell am broadcast -f 32 -a "$ACTION_CONTROL" -n "$CONTROL_COMPONENT" \
        --es cmd "$cmd" "$@" 2>&1 | tr -d '\r'
}

control_data() { # cmd [extra args...]
    control "$@" | sed -n 's/.*data="\(.*\)".*/\1/p'
}

# ------------------------------------------------------------ device owner

# The "Device Owner:" header is followed by an indented block naming the admin.
# Matching our package anywhere in the whole dump would false-positive (we are
# also listed as an active admin), so scope the search to that block.
device_owner_line() {
    dsh dumpsys device_policy 2>/dev/null | awk '
        /[Dd]evice [Oo]wner/ { n = 8; hdr = $0; next }
        n > 0 { if ($0 ~ /admin=|package=/) { print hdr " " $0; exit } n-- }'
}

is_device_owner() {
    dsh dumpsys device_policy 2>/dev/null | awk -v pkg="$PKG" '
        /[Dd]evice [Oo]wner/ { n = 8; next }
        n > 0 { if (index($0, pkg)) { found = 1 } n-- }
        END { exit(found ? 0 : 1) }'
}

# Guard every target name we pass through `adb shell` against word splitting
# and shell metacharacters on the device side.
validate_targets() { # csv
    case "$1" in
        *[!A-Za-z0-9_.,:-]*)
            die "Invalid --targets value: $1
Expected a comma-separated list of package names, e.g.
  com.example.one,com.example.two:always" ;;
    esac
}
