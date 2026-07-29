<p align="center">
  <img src="docs/logo.png" alt="AppAlive" width="200" height="200">
</p>

<h1 align="center">AppAlive</h1>

<p align="center">
  Keeps a configured set of Android apps running — and keeps itself running —<br>
  against aggressive OEM task killers.<br>
  <br>
  Installed over adb. No root. Provisions itself as <b>Device Owner</b> for the
  strongest privileges adb can hand out.
</p>

```bash
./build.sh
./install.sh --targets com.example.one,com.example.two
./status.sh
./uninstall.sh
```

On Windows, `install.cmd` / `uninstall.cmd` / `status.cmd` / `build.cmd` wrap the
same scripts through Git Bash.

`--targets` is optional — the installer opens a configuration app on the device
where the apps and every setting can be chosen by hand. Use the flag when you are
scripting a fleet.

---

## Configuring on the device

The installer lands you on a real settings screen, so whoever ends up holding the
phone never has to touch adb:

- **Choose apps** — a searchable list of installed apps with tick boxes. System
  packages are behind a toggle.
- **Per-app escalation** — tap an app to pick *never open it*, *only when the
  screen is off* (default), or *always*, plus **Revive now** and **Remove**.
- **Behaviour** — hold apps in memory, restart after a manual Force Stop, the
  watchdog helper notification, keep the CPU awake.
- **Protection** — Device Owner state, block uninstall, disable Force Stop.
- **Manufacturer settings** — the vendor auto-start screen, battery optimisation,
  and the overlay permission.
- **Diagnostics** — the full watchdog dump, and a **Copy exemption command**
  button.

One thing the screen is deliberately honest about: an app added **in the UI** is
watched and revived, but does **not** get the battery exemptions. Setting app ops
or the power allowlist for *another* package needs signature-level permissions
that no ordinary app can hold — only adb can. Such apps are flagged `NOT EXEMPT`,
and Diagnostics hands you the exact `./install.sh --targets …` line to fix it.

---

## The one thing this cannot do for you

On Xiaomi/HyperOS, Huawei/EMUI, Oppo/ColorOS, Vivo and Samsung, the vendor's own
**auto-start allowlist** is guarded by signature-level permissions. It cannot be
set by adb, and Device Owner does not help either. **Only a user tap works.**

Open AppAlive on the device and use **"Manufacturer auto-start settings"**, then
allow AppAlive *and every target app*. If something is still being killed after
installation, this is almost always why. Everything below is what we can do; this
is the part we can't.

---

## How it works

Four layers, weakest assumption first.

### 1. Prevention — fewer deaths in the first place

The installer applies, **to AppAlive and to every target**:

| Lever | Effect |
|---|---|
| `dumpsys deviceidle whitelist +pkg` | Doze / app-standby exemption |
| `am set-standby-bucket pkg active` | Keeps the app in the active bucket |
| `RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND` | Bypasses forced app standby |
| `system_exempt_from_power_restrictions` | Power-restriction exemption |
| `system_exempt_from_hibernation` | No auto-hibernation |
| `system_exempt_from_suspension` | Cannot be suspended |
| `system_exempt_from_activity_bg_start_restriction` | Background activity starts |
| `system_exempt_from_dismissible_notifications` | Notification can't be swiped away |
| `AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore` | No permission auto-revoke |

Those five `system_exempt_*` ops are exactly what
`DevicePolicyManager.setApplicationExemptions()` sets. That API is `@SystemApi`
behind `MANAGE_DEVICE_POLICY_APP_EXEMPTIONS` and unreachable to us — but reading
`DevicePolicyManagerService` shows it does nothing except `setMode()` on those
ops, which adb can do directly.

Device Owner then adds `setUninstallBlocked()` and the `DISALLOW_APPS_CONTROL`
user restriction, which **greys out the Force Stop button in Settings**.

**The tether** is the strongest no-root lever. AppAlive holds a persistent
`bindService(BIND_AUTO_CREATE | BIND_IMPORTANT)` to every target that exposes a
bindable service. That raises the *target's* `oom_adj` to AppAlive's own
foreground-service level, so the low-memory killer picks other victims first.
It costs RAM — tethered targets stay resident. Turn it off with
`tether_enabled=false`.

### 2. Detection

1. **Tether disconnect** — `onServiceDisconnected` fires the instant a target
   dies. Zero latency.
2. **Exit records** — `getHistoricalProcessExitReasons()` gives exact death
   records for other packages *provided we hold `android.permission.DUMP`*,
   which the installer grants. Includes the reason: `LOW_MEMORY`, `SIGNALED`,
   `USER_REQUESTED`, `OTHER`… Android 11+ only.
3. **Blind sweep** — below API 30, or if the DUMP grant failed, the silent
   ladder simply runs every 5 minutes. Starting a live process is a no-op.

There is no positive liveness query for other apps without root:
`REAL_GET_TASKS` is `signature|privileged` and `/proc` is hidepid-restricted.

### 3. Revival

**Killed ≠ force-stopped.** A process killed by the LMK or an OEM cleaner is not
in the stopped state and every method below works. A *force-stopped* app has
`FLAG_STOPPED`; Android 15 additionally cancels its pending intents and intends
it to leave that state only by user action. `DISALLOW_APPS_CONTROL` is the main
defence against that case.

Silent tiers, ordered so the ones that *prove* a process started come first:

| Order | Method | Confirms? |
|---|---|---|
| 1 | `bindService()` via the tether | yes, via `onServiceConnected` |
| 2 | `acquireUnstableContentProviderClient()` | **yes, immediately** |
| 3 | Explicit-component broadcast + `FLAG_INCLUDE_STOPPED_PACKAGES` | no |
| 4 | `startService()` | no |

Tier 3 is the widest net. With an **explicit `ComponentName`** the framework
starts the target's process to deliver the intent *even though the action
matches no intent filter* — the receiver can ignore it entirely and you still
get the process back. Explicit broadcasts are also exempt from the Android 8+
implicit-broadcast ban.

These restore the **process**, not necessarily the app's **work**. Most apps
re-arm alarms/WorkManager in `Application.onCreate()`, so it usually resumes.

**Escalation.** If the silent tiers fail 3 times, AppAlive launches the target's
launcher activity — but by default **only while the screen is off or the
keyguard is locked**, so you never see it. There is no supported way to start
another app's activity invisibly; this is the honest workaround. Per-target
override via `:never` / `:screen_off` / `:always`.

Failures are only counted from *evidence* (a tether disconnect, or a fresh death
record after an attempt). An unconfirmable target never triggers an escalation
storm.

### 4. Keeping itself alive

- Foreground service, `START_STICKY`, `stopWithTask="false"`.
- A second process (`:guard`). The two bind each other and `linkToDeath` on the
  peer's binder, so killing one is noticed immediately by the other.
- A self-rescheduling exact alarm (default 120 s).
- A persisted 15-minute `JobScheduler` job.
- `BOOT_COMPLETED` + `LOCKED_BOOT_COMPLETED`, all `directBootAware` with config
  in device-protected storage — so it comes back after a reboot *before* first
  unlock.
- Device Owner: uninstall blocked, Force Stop disabled.

---

## Why targetSdk 28

Deliberate, and the single knob `TARGET_SDK` in `build.sh`. Targeting 28 exempts
the app from restrictions that otherwise assist the task killer:

- Android 14+ foreground-service type restrictions
- Android 15's `BOOT_COMPLETED` foreground-service limits
- `POST_NOTIFICATIONS` runtime gating (notification is on by default)
- The Android 12+ exact-alarm permission gate
- Android 11+ package-visibility filtering

It is installable on all current Android versions (the minimum installable
targetSdk is 24). The manifest still declares the modern permissions, so raising
`TARGET_SDK` will not silently break anything.

---

## Configuration

Set at install time, or any time afterwards over the control channel.

| Key | Default | Meaning |
|---|---|---|
| `targets` | *(empty)* | `pkg[:never\|screen_off\|always]`, comma-separated |
| `enabled` | `true` | Master switch |
| `poll_interval_ms` | `30000` | Watchdog tick |
| `ensure_interval_ms` | `300000` | Blind sweep period (no-DUMP mode) |
| `min_revive_interval_ms` | `45000` | Cooldown floor |
| `max_revive_interval_ms` | `900000` | Backoff ceiling |
| `escalate_after_failures` | `3` | Silent failures before activity launch |
| `revive_on_user_requested` | `true` | Fight deliberate force-stops too |
| `tether_enabled` | `true` | Hold `BIND_IMPORTANT` bindings |
| `alarm_interval_ms` | `120000` | Heartbeat alarm period |
| `hold_wakelock` | `false` | Hold a partial wake lock (costs battery) |
| `guard_foreground` | `true` | Guard process is also a foreground service |
| `block_uninstall` | `true` | Device Owner: block uninstall of self + targets |
| `disallow_apps_control` | `true` | Device Owner: disable Force Stop **device-wide** |
| `grant_target_permissions` | `false` | Device Owner: auto-grant targets' dangerous permissions |

### Control channel

The receiver is guarded by `android:permission="android.permission.DUMP"`.
`com.android.shell` holds DUMP, so adb can reach it; an ordinary app cannot,
because DUMP is `signature|privileged|development`.

```bash
A="adb shell am broadcast -f 32 -a com.tmc.appalive.action.CONTROL -n com.tmc.appalive/.ControlReceiver"

$A --es cmd set-targets --es targets "com.a,com.b:always"
$A --es cmd set --es key poll_interval_ms --es value 15000
$A --es cmd revive --es pkg com.a
$A --es cmd status
$A --es cmd stop
$A --es cmd start
$A --es cmd release      # ordered Device Owner teardown
```

Full state readback needs no extra plumbing — the service implements `dump()`:

```bash
adb shell dumpsys activity service com.tmc.appalive/.AliveService
adb logcat -s AppAlive:V
```

---

## Device Owner

`install.sh` provisions it automatically. It requires **no accounts on the
device** — that is by far the most common reason it fails. The installer
pre-flights for accounts, extra users and completed setup, and tells you exactly
what to remove.

If it fails, the app still installs and runs, the installer says so loudly and
exits non-zero, and you lose: uninstall protection, the Force Stop block, and
the guaranteed background-activity-start exemption.

`--force-device-owner` temporarily clears `device_provisioned` /
`user_setup_complete` and restores them afterwards. It can make the setup wizard
reappear, so it is opt-in.

**Uninstalling is safe.** Device Owner blocks `pm uninstall`, so `uninstall.sh`
undoes things in a specific order — clear `DISALLOW_APPS_CONTROL`, unblock every
package it ever blocked, *then* clear device owner. Doing it the other way round
would strand those settings with no way to reach them. If the in-app release
fails it falls back to `dpm remove-active-admin` and, failing that, prints manual
recovery steps.

---

## Verifying on a device

```bash
./install.sh --targets com.example.one
./status.sh                                    # device owner active, targets tethered

adb shell am kill com.example.one              # simulate an LMK kill
./status.sh --log                              # revived, and by which tier

adb shell am force-stop com.example.one        # the harder, stopped-state case
adb shell am kill com.tmc.appalive             # :guard should resurrect it
adb shell am kill com.tmc.appalive:guard       # main process resurrects the guard
adb reboot                                     # back up without unlocking

./uninstall.sh                                 # fully reversible
```

Host-side logic has its own test, no device needed:

```bash
./scripts/selftest.sh
```

---

## Building

`build.sh` is fully offline: `aapt2` → `javac` → `d8` → `zipalign` →
`apksigner`, using only the Android SDK build-tools, a platform `android.jar`
and a JDK. No Gradle, no network, no AGP download.

It generates a local signing key in `keystore/` on first run. **Keep it** —
reinstalling over an existing install requires the same signature.

### Icons

![AppAlive icon set](docs/logo-preview.png)

The mark is a shield with an ECG pulse knocked out of it — protected, and alive.
Knocking the pulse out rather than drawing it on top keeps the whole thing to
two tones, which is what survives being scaled to a 24dp status-bar glyph.

Everything is generated from `tools/make_icons.py` (stdlib only, a small
supersampled rasteriser) rather than committed as opaque binaries:

- `mipmap-anydpi-v26/ic_launcher.xml` — adaptive icon for API 26+, foreground
  scaled to ~0.55 of the 108dp canvas so a *tall* shield still clears the 66dp
  safe zone under any launcher mask
- `mipmap-*/ic_launcher.png` — legacy launcher icon for API 21–25, which carries
  its own rounded-square background
- `drawable-*/ic_stat_alive.png` — white notification silhouette. A status icon
  is always drawn at 24dp whatever density bucket it came from, so this variant
  uses a **simplified** single-spike trace; the full ECG's secondary bump closes
  up and reads as noise once the stroke is ~2px.

`python tools/make_preview.py` regenerates `docs/logo-preview.png`, the contact
sheet above, at real pixel sizes — including both launcher masks and the glyph
down to 24dp.

A Gradle project is included for Android Studio, but see the warning in
`app/build.gradle`: it needs network access for AGP and **was not verified**,
unlike `build.sh`.

---

## Limitations

Stated plainly:

- **Privileged OEM cleaners win.** MIUI/HyperOS, EMUI and ColorOS cleaners run
  as system components and can kill anything regardless of allowlists, Device
  Owner, or `oom_adj`. The vendor auto-start screen is the only mitigation, and
  only you can tap it.
- **Reviving restores the process, not necessarily the work.** An app whose own
  foreground service died will not restart it unless it chooses to.
- **A target with no exported service, receiver or provider** has no silent
  revival route at all — only the activity escalation.
- **Force-stopped apps on Android 15+** are deliberately hard to restart. Bind
  and provider starts still work; pending intents do not.
- **The tether costs RAM.** Tethered targets stay resident. That is the point,
  but it is a real trade.
- **Truly unkillable needs root** (`oom_score_adj = -1000`) or a system-partition
  install. Neither is in scope here — the device is not rooted.

---

## What was actually verified

Built and checked on this machine, offline:

- `build.sh` produces a signed APK (v1 + v2 + v3 schemes verify).
- `aapt2 dump badging` / `xmltree` confirm targetSdk 28, minSdk 21, the `:guard`
  process, `stopWithTask=false`, `directBootAware=true`, and the DUMP guard on
  `ControlReceiver`.
- `scripts/selftest.sh` — 16 host-side tests of the installer's parsing logic.
- Every AOSP claim in this README was checked against `android15-release`
  sources rather than assumed.

**Not verified:** anything requiring a device. No Android device was attached
during development, so the install, revival, reboot and uninstall flows are
written from verified API behaviour but have not been executed end to end. Run
the sequence under "Verifying on a device" before relying on this.
