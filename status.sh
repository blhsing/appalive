#!/usr/bin/env bash
#
# Show what AppAlive is doing on the device.
#
#   ./status.sh            # state + per-target detail
#   ./status.sh --log      # also tail the recent AppAlive logcat
#   ./status.sh --watch    # refresh every 5s
#
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/common.sh"

SHOW_LOG=0
WATCH=0
SERIAL="${SERIAL:-}"

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift ;;
        --log)    SHOW_LOG=1 ;;
        --watch)  WATCH=1 ;;
        -h|--help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "unknown option: $1" ;;
    esac
    shift
done

find_adb
select_device

report() {
    head_ "Device"
    describe_device

    head_ "Package"
    if is_installed "$PKG"; then
        ok "installed: $(dsh dumpsys package "$PKG" | grep -m1 versionName | tr -d ' ')"
    else
        bad "$PKG is not installed"
        return
    fi

    if is_device_owner; then ok "device owner: yes"; else warn "device owner: no"; fi

    head_ "Exemptions"
    for p in "$PKG" $(state_get targets | tr ',' ' '); do
        pkg="${p%%:*}"
        [ -n "$pkg" ] || continue
        idle="$(dsh dumpsys deviceidle whitelist | grep -c "$pkg" || true)"
        bucket="$(get_standby_bucket "$pkg")"
        bg="$(dsh cmd appops get "$pkg" RUN_ANY_IN_BACKGROUND | head -1)"
        printf '  %-38s allowlist=%s bucket=%-4s %s\n' \
            "$pkg" "$([ "${idle:-0}" -gt 0 ] && echo yes || echo NO)" \
            "${bucket:-?}" "${bg:-}"
    done

    head_ "Service state"
    out="$(dsh dumpsys activity service "$SERVICE_COMPONENT")"
    if echo "$out" | grep -q 'AppAlive'; then
        echo "$out" | sed -n '/^AppAlive/,$p' | sed 's/^/  /'
    else
        bad "service is not running"
        say "  start it with: adb shell am start-foreground-service -n $SERVICE_COMPONENT"
    fi

    if [ "$SHOW_LOG" = "1" ]; then
        head_ "Recent log"
        adb_ logcat -d -v time -s AppAlive:V 2>/dev/null | tr -d '\r' | tail -40 | sed 's/^/  /'
    fi
}

if [ "$WATCH" = "1" ]; then
    while true; do
        clear
        report
        sleep 5
    done
else
    report
fi
