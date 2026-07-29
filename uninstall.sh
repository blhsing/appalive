#!/usr/bin/env bash
#
# Remove AppAlive and undo everything install.sh changed.
#
#   ./uninstall.sh
#   ./uninstall.sh --keep-target-tweaks   # leave targets allowlisted
#
# Device Owner blocks pm uninstall, so the order here is load-bearing:
# user restrictions and uninstall-blocking must be cleared while we are still
# device owner, because clearing device owner first would strand them.
#
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/common.sh"

KEEP_TARGETS=0
KEEP_SETTINGS=0
SERIAL="${SERIAL:-}"

usage() { sed -n '2,/^set -uo pipefail/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0; }

while [ $# -gt 0 ]; do
    case "$1" in
        --serial)             SERIAL="$2"; shift ;;
        --keep-target-tweaks) KEEP_TARGETS=1 ;;
        --keep-settings)      KEEP_SETTINGS=1 ;;
        -h|--help)            usage ;;
        *) die "unknown option: $1  (try --help)" ;;
    esac
    shift
done

head_ "Device"
find_adb
select_device
describe_device

INSTALLED=1
is_installed "$PKG" || INSTALLED=0
[ "$INSTALLED" = "1" ] || warn "$PKG is not installed; will still undo device-side changes"

# ------------------------------------------------------- stop the watchdog

if [ "$INSTALLED" = "1" ]; then
    head_ "Stopping"
    control stop >/dev/null 2>&1
    sleep 2
    ok "watchdog disabled"
fi

# --------------------------------------------------- release device owner

if [ "$INSTALLED" = "1" ]; then
    head_ "Releasing Device Owner"
    if is_device_owner; then
        r="$(control_data release)"
        sleep 2
        if [ -n "$r" ]; then say "    $r"; fi
        if is_device_owner; then
            warn "in-app release did not take; trying dpm remove-active-admin"
            out="$(dsh_err dpm remove-active-admin "$ADMIN_COMPONENT")"
            say "    $out"
            sleep 1
        fi
        if is_device_owner; then
            bad "still device owner - pm uninstall will be refused"
            say ""
            say "    ${C_YEL}Manual recovery:${C_OFF}"
            say "      adb shell dpm remove-active-admin $ADMIN_COMPONENT"
            say "    If that is refused on this build, remove it on the device at"
            say "    Settings > Security > Device admin apps, then re-run this script."
        else
            ok "device owner released"
        fi
    else
        skip "not device owner"
    fi
fi

# -------------------------------------------------------------- uninstall

if [ "$INSTALLED" = "1" ]; then
    head_ "Uninstalling"
    dsh am force-stop "$PKG" >/dev/null 2>&1
    out="$(adb_ uninstall "$PKG" 2>&1 | tr -d '\r')"
    if echo "$out" | grep -qi 'Success'; then
        ok "uninstalled"
    else
        warn "adb uninstall failed: $out"
        out="$(dsh_err pm uninstall --user 0 "$PKG")"
        if echo "$out" | grep -qi 'Success'; then
            ok "uninstalled for user 0"
        else
            bad "uninstall failed: $out"
        fi
    fi
fi

# -------------------------------------------------- restore target tweaks

RESTORE_OPS="RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND
android:system_exempt_from_power_restrictions
android:system_exempt_from_hibernation
android:system_exempt_from_suspension
android:system_exempt_from_activity_bg_start_restriction
android:system_exempt_from_dismissible_notifications
AUTO_REVOKE_PERMISSIONS_IF_UNUSED"

if [ "$KEEP_TARGETS" = "1" ]; then
    head_ "Target tweaks"
    skip "left in place (--keep-target-tweaks)"
else
    TARGETS="$(state_get targets)"
    if [ -n "$TARGETS" ]; then
        for spec in $(echo "$TARGETS" | tr ',' ' '); do
            t="${spec%%:*}"
            head_ "Restoring $t"
            for op in $RESTORE_OPS; do
                set_appop "$t" "$op" default >/dev/null 2>&1 || true
            done
            ok "app ops reset to default"
            power_allowlist_remove "$t"
            ok "removed from power allowlist"
            prev="$(state_all bucket | awk -F'|' -v p="$t" '$1==p {print $2; exit}')"
            case "$prev" in
                ''|*[!0-9]*) prev="" ;;
            esac
            if [ -n "$prev" ]; then
                set_standby_bucket "$t" "$prev" >/dev/null 2>&1 \
                    && ok "standby bucket restored to $prev" \
                    || warn "could not restore standby bucket"
            else
                set_standby_bucket "$t" working_set >/dev/null 2>&1 \
                    && ok "standby bucket -> working_set (original unknown)" || true
            fi
        done
    else
        skip "no recorded targets (no state file from install.sh)"
    fi
fi

# Our own package is gone, but the power allowlist entry outlives it.
power_allowlist_remove "$PKG"

# ------------------------------------------------ restore global settings

if [ "$KEEP_SETTINGS" = "1" ]; then
    head_ "Global settings"
    skip "left as-is (--keep-settings)"
else
    SETTINGS="$(state_all setting)"
    if [ -n "$SETTINGS" ]; then
        head_ "Restoring global settings"
        printf '%s\n' "$SETTINGS" | while IFS='|' read -r ns key orig; do
            [ -n "$ns" ] || continue
            if [ -z "$orig" ] || [ "$orig" = "null" ]; then
                dsh settings delete "$ns" "$key" >/dev/null 2>&1
                ok "$ns.$key deleted (was unset before install)"
            else
                dsh settings put "$ns" "$key" "$orig" >/dev/null 2>&1
                ok "$ns.$key = $orig"
            fi
        done
    fi
fi

# ------------------------------------------------------------ verification

head_ "Verification"
if is_installed "$PKG"; then
    bad "$PKG is STILL INSTALLED"
else
    ok "$PKG is gone"
fi
if dsh dumpsys device_policy 2>/dev/null | grep -q "$PKG"; then
    bad "device policy still references $PKG"
else
    ok "no device-policy references remain"
fi

say ""
if [ "$FAILURES" -eq 0 ]; then
    rm -f "$STATE_FILE"
    echo "${C_GRN}Uninstall complete.${C_OFF}"
else
    echo "${C_YEL}Uninstall finished with $FAILURES problem(s).${C_OFF}"
    echo "State file kept at $STATE_FILE so you can retry."
    exit 1
fi
