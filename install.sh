#!/usr/bin/env bash
#
# Install AppAlive over adb and apply every keep-alive lever adb can reach.
#
#   ./install.sh --targets com.example.one,com.example.two
#   ./install.sh --targets com.foo:always,com.bar:never --poll 15000
#
# Per-target escalation policy is a ":suffix" on the package name:
#   :screen_off  (default) launch the app's activity only while the screen is
#                off or locked, after the silent methods have failed
#   :never       silent methods only, never show UI
#   :always      launch the activity as soon as silent methods fail
#
# Options:
#   --targets LIST          comma-separated packages to keep alive
#   --serial ID             target a specific device
#   --apk PATH              install this APK instead of dist/appalive.apk
#   --poll MS               watchdog tick interval (default 30000)
#   --no-build              fail rather than build a missing APK
#   --no-device-owner       skip Device Owner provisioning
#   --force-device-owner    clear provisioning flags to force it through;
#                           can make the setup wizard reappear
#   --aggressive            also disable device-wide app standby / adaptive
#                           battery (restored by uninstall.sh)
#   --oem-settings          open the app afterwards for the vendor allowlist
#
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/common.sh"

TARGETS=""
APK=""
DO_BUILD=1
WANT_DO=1
FORCE_DO=0
AGGRESSIVE=0
OPEN_OEM=0
POLL_MS=""
SERIAL="${SERIAL:-}"

usage() { sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0; }

while [ $# -gt 0 ]; do
    case "$1" in
        --targets)            TARGETS="$2"; shift ;;
        --serial)             SERIAL="$2"; shift ;;
        --apk)                APK="$2"; shift ;;
        --poll)               POLL_MS="$2"; shift ;;
        --no-build)           DO_BUILD=0 ;;
        --no-device-owner)    WANT_DO=0 ;;
        --force-device-owner) FORCE_DO=1 ;;
        --aggressive)         AGGRESSIVE=1 ;;
        --oem-settings)       OPEN_OEM=1 ;;
        -h|--help)            usage ;;
        *) die "unknown option: $1  (try --help)" ;;
    esac
    shift
done

validate_targets "$TARGETS"

wp() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

# --------------------------------------------------------------------- setup

head_ "Device"
find_adb
select_device
describe_device
state_reset
state_put serial "$SERIAL"
state_put targets "$TARGETS"

# ---------------------------------------------------------------- build/apk

if [ -z "$APK" ]; then APK="$SCRIPT_ROOT/dist/appalive.apk"; fi
if [ ! -f "$APK" ] && [ "$DO_BUILD" = "1" ]; then
    head_ "Building APK"
    "$SCRIPT_ROOT/build.sh" >/dev/null || die "build.sh failed. Run it directly to see why."
    ok "built $(basename "$APK")"
fi
[ -f "$APK" ] || die "APK not found: $APK  (run ./build.sh, or pass --apk)"

# ------------------------------------------------- device owner pre-flight

DO_BLOCKERS=""
if [ "$WANT_DO" = "1" ]; then
    head_ "Device Owner pre-flight"
    if is_device_owner; then
        ok "$PKG is already device owner"
    else
        existing="$(device_owner_line)"
        if [ -n "$existing" ]; then
            bad "another app already owns this device: $existing"
            DO_BLOCKERS="$DO_BLOCKERS existing-owner"
        fi
        accounts="$(dsh dumpsys account | grep -c 'Account {' || true)"
        if [ "${accounts:-0}" -gt 0 ] 2>/dev/null; then
            warn "$accounts account(s) on the device - provisioning will be refused"
            DO_BLOCKERS="$DO_BLOCKERS accounts"
        else
            ok "no accounts registered"
        fi
        users="$(dsh pm list users | grep -c 'UserInfo' || true)"
        if [ "${users:-1}" -gt 1 ] 2>/dev/null; then
            warn "$users users on the device - provisioning may be refused"
            DO_BLOCKERS="$DO_BLOCKERS multi-user"
        fi
        setup_done="$(dsh settings get secure user_setup_complete)"
        [ "$setup_done" = "1" ] && warn "setup wizard already completed (use --force-device-owner if needed)"
    fi
fi

# ------------------------------------------------------------------ install

head_ "Installing"
out="$(adb_ install -r -g "$(wp "$APK")" 2>&1 | tr -d '\r')"
if echo "$out" | grep -qi 'Success'; then
    ok "installed $(basename "$APK")"
elif echo "$out" | grep -qE 'UPDATE_INCOMPATIBLE|signatures do not match'; then
    # keystore/ is not committed, so a colleague's build is signed with a
    # different key. Say so plainly instead of failing cryptically.
    die "An AppAlive built with a different signing key is already installed.
Either use the original keystore/appalive.jks, or remove the old install first:
    ./uninstall.sh
Device said: $out"
else
    warn "adb install failed, retrying via pm install"
    say "    $out"
    adb_ push "$(wp "$APK")" /data/local/tmp/appalive.apk >/dev/null 2>&1
    out="$(dsh_err pm install -r -g --bypass-low-target-sdk-block /data/local/tmp/appalive.apk)"
    dsh rm -f /data/local/tmp/appalive.apk >/dev/null 2>&1
    if echo "$out" | grep -qi 'Success'; then
        ok "installed via pm install"
    else
        die "install failed: $out"
    fi
fi

# --------------------------------------------------------------- harden self

grant_perm() { # pkg perm label
    local o; o="$(dsh_err pm grant "$1" "$2")"
    if [ -z "$o" ]; then ok "$3"; else skip "$3 - $o"; fi
}

# Ops that let an app run unmolested in the background.
BG_OPS="RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND"
# The five ops DevicePolicyManager.setApplicationExemptions() sets. That API is
# @SystemApi and unreachable to us, but it does nothing except setMode() on
# these - which adb can do directly, for our targets as well as ourselves.
EXEMPT_OPS="android:system_exempt_from_power_restrictions
android:system_exempt_from_hibernation
android:system_exempt_from_suspension
android:system_exempt_from_activity_bg_start_restriction
android:system_exempt_from_dismissible_notifications"

harden() { # pkg  is_self
    local p="$1" self="$2"
    for op in $BG_OPS; do apply_appop "$p" "$op" allow "appop $op"; done
    for op in $EXEMPT_OPS; do
        apply_appop "$p" "$op" allow "appop ${op#android:}"
    done
    apply_appop "$p" AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore "appop no-hibernation"
    if [ "$self" = "1" ]; then
        apply_appop "$p" SYSTEM_ALERT_WINDOW allow "appop SYSTEM_ALERT_WINDOW (bg activity starts)"
        apply_appop "$p" GET_USAGE_STATS allow "appop GET_USAGE_STATS"
        apply_appop "$p" START_FOREGROUND allow "appop START_FOREGROUND"
        apply_appop "$p" SCHEDULE_EXACT_ALARM allow "appop SCHEDULE_EXACT_ALARM"
    fi
    if power_allowlist_add "$p"; then ok "power allowlist (doze exemption)"
    else warn "could not add $p to the power allowlist"; fi
    local prev; prev="$(get_standby_bucket "$p")"
    state_put bucket "$p|$prev"
    if set_standby_bucket "$p" active; then ok "standby bucket -> active (was ${prev:-unknown})"
    else skip "standby buckets unsupported on API $API"; fi
}

head_ "Hardening $PKG"
grant_perm "$PKG" android.permission.DUMP "granted DUMP (cross-package death detection)"
grant_perm "$PKG" android.permission.PACKAGE_USAGE_STATS "granted PACKAGE_USAGE_STATS"
harden "$PKG" 1

# ------------------------------------------------------------ harden targets

if [ -n "$TARGETS" ]; then
    for spec in $(echo "$TARGETS" | tr ',' ' '); do
        t="${spec%%:*}"
        head_ "Hardening target $t"
        if is_installed "$t"; then
            harden "$t" 0
        else
            bad "$t is not installed on this device"
        fi
    done
else
    warn "no --targets given; AppAlive will run but watch nothing"
fi

# ------------------------------------------------------- device owner set-up

if [ "$WANT_DO" = "1" ] && ! is_device_owner; then
    head_ "Provisioning Device Owner"
    out="$(dsh_err dpm set-device-owner "$ADMIN_COMPONENT")"
    if echo "$out" | grep -qi 'success'; then
        ok "device owner set"
    elif [ "$FORCE_DO" = "1" ]; then
        warn "normal provisioning refused, retrying with provisioning flags cleared"
        op="$(dsh settings get global device_provisioned)"
        os="$(dsh settings get secure user_setup_complete)"
        state_put setting "global|device_provisioned|$op"
        state_put setting "secure|user_setup_complete|$os"
        dsh settings put global device_provisioned 0 >/dev/null 2>&1
        dsh settings put secure user_setup_complete 0 >/dev/null 2>&1
        out="$(dsh_err dpm set-device-owner "$ADMIN_COMPONENT")"
        dsh settings put global device_provisioned "${op:-1}" >/dev/null 2>&1
        dsh settings put secure user_setup_complete "${os:-1}" >/dev/null 2>&1
        if echo "$out" | grep -qi 'success'; then ok "device owner set (forced)"
        else bad "forced provisioning also failed: $out"; fi
    else
        bad "device owner provisioning failed"
        say "    $out"
        say ""
        say "    ${C_YEL}To fix, on the device:${C_OFF}"
        say "      1. Remove every account (Settings > Accounts) - this is the usual cause"
        say "      2. Remove any secondary users / work profiles"
        say "      3. Re-run this installer"
        say "    Or re-run with --force-device-owner, which temporarily clears the"
        say "    provisioning flags. That can make the setup wizard reappear."
        say ""
        say "    AppAlive still works without Device Owner, but loses: uninstall"
        say "    protection, the Force Stop block, and the guaranteed background"
        say "    activity-start exemption used for escalation."
    fi
fi

# ------------------------------------------------------------------- launch

head_ "Starting"
# A freshly installed app sits in the stopped state and receives no broadcasts.
# Launching the activity once is what clears it.
dsh am start -n "$PKG/.MainActivity" >/dev/null 2>&1
sleep 2
dsh am start-foreground-service -n "$SERVICE_COMPONENT" >/dev/null 2>&1 \
    || dsh am startservice -n "$SERVICE_COMPONENT" >/dev/null 2>&1
sleep 1
ok "app launched"

# ------------------------------------------------------------------- config

head_ "Configuration"
if [ -n "$TARGETS" ]; then
    r="$(control_data set-targets --es targets "$TARGETS")"
    if [ -n "$r" ]; then ok "targets: $r"; else ok "targets pushed: $TARGETS"; fi
fi
if [ -n "$POLL_MS" ]; then
    r="$(control_data set --es key poll_interval_ms --es value "$POLL_MS")"
    ok "poll_interval_ms=$POLL_MS ${r:+($r)}"
fi
control start >/dev/null 2>&1
ok "watchdog enabled"

# ---------------------------------------------------------- aggressive mode

if [ "$AGGRESSIVE" = "1" ]; then
    head_ "Aggressive mode (device-wide battery settings)"
    warn "these are device-wide, not app-scoped; uninstall.sh restores them"
    put_setting() { # ns key value
        local orig; orig="$(dsh settings get "$1" "$2")"
        state_put setting "$1|$2|$orig"
        dsh settings put "$1" "$2" "$3" >/dev/null 2>&1
        ok "$1.$2 = $3 (was ${orig:-unset})"
    }
    put_setting global app_standby_enabled 0
    put_setting global adaptive_battery_management_enabled 0
    if [ -n "$(dsh getprop ro.miui.ui.version.name)$(dsh getprop ro.mi.os.version.name)" ]; then
        put_setting global miui_optimization 0
    fi
fi

# ------------------------------------------------------------- verification

head_ "Verification"
dumpout="$(dsh dumpsys activity service "$SERVICE_COMPONENT")"
if echo "$dumpout" | grep -q 'AppAlive'; then
    ok "service is up and answering dumpsys"
else
    bad "service did not respond to dumpsys - it may not have started"
fi
if echo "$dumpout" | grep -q 'deathDetection=getHistoricalProcessExitReasons'; then
    ok "process-death detection active"
elif [ "$API" -ge 30 ] 2>/dev/null; then
    bad "death detection inactive (DUMP grant did not take) - falling back to blind polling"
else
    skip "death detection needs API 30+; this device is API $API (blind polling mode)"
fi
if [ "$WANT_DO" = "1" ]; then
    if is_device_owner; then ok "device owner active"; else bad "device owner NOT active"; fi
fi

say ""
echo "$dumpout" | sed -n '/^AppAlive/,/recent events/p' | sed 's/^/    /'

if [ "$OPEN_OEM" = "1" ]; then
    dsh am start -n "$PKG/.MainActivity" >/dev/null 2>&1
    say ""
    say "  Opened AppAlive - tap 'Manufacturer auto-start settings' on the device."
fi

# ---------------------------------------------------------------- wrap up

say ""
if [ "$FAILURES" -eq 0 ]; then
    echo "${C_GRN}Install complete.${C_OFF}"
else
    echo "${C_YEL}Install completed with $FAILURES problem(s) - see FAIL lines above.${C_OFF}"
fi
say ""
say "  IMPORTANT, and not something adb or Device Owner can do for you:"
say "  on Xiaomi/Huawei/Oppo/Vivo/Samsung, open AppAlive on the device and tap"
say "  'Manufacturer auto-start settings', then allow AppAlive and every target."
say "  That vendor allowlist is the usual reason apps still get killed."
say ""
say "  status : ./status.sh"
say "  remove : ./uninstall.sh"

[ "$FAILURES" -eq 0 ] || exit 1
