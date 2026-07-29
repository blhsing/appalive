#!/usr/bin/env bash
#
# Host-side self-test for the parsing logic in common.sh. Needs no device.
#
#   ./scripts/selftest.sh
#
# These are the parts that would silently misbehave on a real device: the
# device-owner detection has to scope itself to the "Device Owner:" block
# (we also appear in the admin list), and the control readback has to survive
# `am broadcast` output.
#
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG="com.tmc.appalive"

PASS=0
FAIL=0
check() { # label expected actual
    if [ "$2" = "$3" ]; then
        echo "  ok   $1"
        PASS=$((PASS + 1))
    else
        echo "  FAIL $1"
        echo "         expected: [$2]"
        echo "         actual:   [$3]"
        FAIL=$((FAIL + 1))
    fi
}

# --- device owner detection -------------------------------------------------
# Mirrors the awk in common.sh: is_device_owner().
do_check() {
    awk -v pkg="$PKG" '
        /[Dd]evice [Oo]wner/ { n = 8; next }
        n > 0 { if (index($0, pkg)) { found = 1 } n-- }
        END { exit(found ? 0 : 1) }'
}

echo "device owner detection"

WE_OWN='Current Device Policy Manager state:
  Enabled Device Admins (User 0, provisioningState: 3):
    admin=com.tmc.appalive/.AdminReceiver
      policies:
  Device Owner:
    admin=ComponentInfo{com.tmc.appalive/com.tmc.appalive.AdminReceiver}
    name=
    package=com.tmc.appalive'
printf '%s\n' "$WE_OWN" | do_check && r=yes || r=no
check "we are device owner" "yes" "$r"

# The trap this guards against: we are an active admin but NOT device owner.
# A naive grep for the package over the whole dump would say yes.
ADMIN_ONLY='Current Device Policy Manager state:
  Enabled Device Admins (User 0, provisioningState: 0):
    admin=com.tmc.appalive/.AdminReceiver
      policies:
  Device Owner:
    admin=ComponentInfo{com.other.mdm/com.other.mdm.Admin}
    package=com.other.mdm'
printf '%s\n' "$ADMIN_ONLY" | do_check && r=yes || r=no
check "admin but another app owns the device" "no" "$r"

NO_OWNER='Current Device Policy Manager state:
  Enabled Device Admins (User 0, provisioningState: 0):
    admin=com.tmc.appalive/.AdminReceiver
      policies:'
printf '%s\n' "$NO_OWNER" | do_check && r=yes || r=no
check "no device owner at all" "no" "$r"

# --- control readback -------------------------------------------------------
echo "control channel readback"

parse_data() { sed -n 's/.*data="\(.*\)".*/\1/p'; }

AM_OUT='Broadcasting: Intent { act=com.tmc.appalive.action.CONTROL flg=0x20 cmp=com.tmc.appalive/.ControlReceiver (has extras) }
Broadcast completed: result=0, data="OK targets=com.a,com.b"'
check "extracts result data" "OK targets=com.a,com.b" \
    "$(printf '%s\n' "$AM_OUT" | parse_data)"

AM_ERR='Broadcast completed: result=1, data="ERROR unknown cmd: bogus"'
check "extracts error data" "ERROR unknown cmd: bogus" \
    "$(printf '%s\n' "$AM_ERR" | parse_data)"

AM_NODATA='Broadcast completed: result=0'
check "no data is empty, not garbage" "" \
    "$(printf '%s\n' "$AM_NODATA" | parse_data)"

# --- state file -------------------------------------------------------------
echo "install state file"

STATE_FILE="$(mktemp)"
state_put() { printf '%s\t%s\n' "$1" "$2" >> "$STATE_FILE"; }
state_get() { awk -F'\t' -v k="$1" '$1==k {print $2; exit}' "$STATE_FILE"; }
state_all() { awk -F'\t' -v k="$1" '$1==k {print $2}' "$STATE_FILE"; }

state_put serial "ABC123"
state_put targets "com.a,com.b:always"
state_put bucket "com.a|10"
state_put bucket "com.b|40"
state_put setting "global|app_standby_enabled|1"

check "state_get serial"  "ABC123"             "$(state_get serial)"
check "state_get targets" "com.a,com.b:always" "$(state_get targets)"
check "state_all bucket"  "com.a|10 com.b|40"  "$(state_all bucket | tr '\n' ' ' | sed 's/ $//')"
check "bucket lookup"     "40" \
    "$(state_all bucket | awk -F'|' -v p=com.b '$1==p {print $2; exit}')"
check "setting round-trip" "global|app_standby_enabled|1" "$(state_all setting)"
rm -f "$STATE_FILE"

# --- target spec parsing ----------------------------------------------------
echo "target spec parsing"

specs() { echo "$1" | tr ',' ' '; }
out=""
for s in $(specs "com.a,com.b:always,com.c:never"); do out="$out ${s%%:*}"; done
check "package extraction" " com.a com.b com.c" "$out"

valid() {
    case "$1" in
        *[!A-Za-z0-9_.,:-]*) echo invalid ;;
        *) echo valid ;;
    esac
}
check "accepts normal list"   "valid"   "$(valid 'com.a,com.b:always')"
check "rejects shell metachar" "invalid" "$(valid 'com.a;rm -rf /')"
check "rejects spaces"         "invalid" "$(valid 'com.a com.b')"
check "rejects backtick"       "invalid" "$(valid 'com.a`id`')"

# ----------------------------------------------------------------------------
echo ""
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] || exit 1
