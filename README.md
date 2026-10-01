# wifiadb

Re-enables Android **Wireless debugging** automatically after reboot. A sideload
utility, not a Play app.

## The problem

Some Android ROMs clear the wireless-debugging switch at every boot, so it never
survives a reboot. On the devices this was built against, the sequence is:

1. `/system/etc/init/hw/init.usb.configfs.rc` runs `setprop
   persist.adb.tls_server.enable 0` on every boot, unconditionally.
2. `AdbService.systemReady()` copies that property over
   `Settings.Global.adb_wifi_enabled`, clobbering the persisted `1`.

The result is that the Developer-options switch reads off after a power cycle
and wireless debugging never comes back on its own. This is ROM-specific, not
universal — stock builds generally don't do it.

Separately, ADB **ports** rotate on every adbd restart (a random high port), so
even a healthy device moves around the LAN. That is unrelated to the app, which
restores the switch but not the port.

## Why an app, and why it works this way

No normal app can set this flag directly. `IAdbService` is `@hide`/`@SystemApi`
behind `MANAGE_DEBUGGING` (`signature|privileged` — platform key or priv-app
only), and `persist.adb.tls_server.enable` is `system_adbd_prop`, writable only
by `system_server`/`init` behind a `neverallow`. On these devices platform
signing and `su` are both closed on several independent axes, so there is no
root and no privileged route.

That leaves the Settings UI, which is the only writer the ROM permits — and the
Wireless debugging switch is a real UI control, so an `AccessibilityService`
can operate it.

The app opens Quick Settings, finds the tile, taps it once, then reads the
setting back to confirm the tap actually took. It is idempotent: if the switch
is already on, it does nothing and returns to the launcher.

The trigger is the accessibility service's own `onServiceConnected`. The system
binds enabled accessibility services early at boot — earlier than
`BOOT_COMPLETED` — which is what makes this reliable where a boot receiver was
not. A `BootReceiver` is kept as belt-and-braces; both funnel into the same
entry point, deduplicated by boot time.

Verified healing unattended from a cold boot on two ONN tablets (onn 36018341,
Android 16).

## How it works

- `WifiAdbService` is an `AccessibilityService`. On `onServiceConnected` it
  checks whether it has already handled *this* boot (by elapsed-realtime boot
  time) and, if not, runs the ensure cycle.
- The ensure cycle is a small state machine on the main thread. It reads
  `Settings.Global adb_wifi_enabled`; if already `1` it finishes immediately.
  Otherwise it opens Quick Settings, polls the accessibility tree for the
  `Wireless debugging` switch (10 s budget), taps it, then polls the setting
  until it reads `1` (30 s budget).
- Every path ends by returning to the launcher, so nothing is left on screen.
- A failure posts a notification with the reason, so a miss is visible rather
  than silent.
- `ACTION_RUN_CYCLE` additionally turns the switch off and back on. That is a
  diagnostic, not part of normal boot handling.
- `MainActivity` is a compact status dialog: whether the service is enabled, the
  current value of the setting, and the result of the last run. "Run now"
  triggers the same code path on demand.

## Requirements

- **Android 11 (API 30) or newer.** This is a hard floor, not a preference: the
  Wireless debugging Quick Settings tile, and the boot-time clobbering this app
  exists to undo, are Android 11+ behaviour.
- Sideload or `adb install`. Not distributed through Google Play.

## Setup — once per device

1. **Enable the Quick Settings tile.** Developer options → *Quick settings
   developer tiles* → **Wireless debugging**. Without this the service has
   nothing to tap and will report `tile not found in quick settings`.
2. **Install the APK and enable the accessibility service.** Open the app and
   tap *Open accessibility settings*, then switch **wifiadb** on. From a host
   with adb access the service entry can be written directly, appending to any
   existing services rather than replacing them:
   ```sh
   adb install wifiadb-release.apk
   adb shell settings put secure enabled_accessibility_services \
     "com.jocala.wifiadb/com.jocala.wifiadb.WifiAdbService:$(adb shell settings get secure enabled_accessibility_services)"
   ```
   Note that a host-side write does not always take effect immediately; toggling
   it in Settings is the supported path.
3. **Set the lock screen to None** on devices that lock at boot. A locked boot
   runs no user code, so nothing can heal it. This cannot be done with
   `settings put` — the lock method lives in the locksettings store, not the
   settings provider.

No root, and no special permission to provision — the accessibility service
toggle in step 3 is the entire setup.

## Verify

```sh
adb shell settings get global adb_wifi_enabled   # expect 1
```

Expected on a boot that needed fixing:

```
onServiceConnected enter
onServiceConnected receiver registered
ensure start cycle=false reason=service-connected
global adb_wifi_enabled isOn=false cycle=false
tile found checked=false needOffFirst=false
ensure finish ok=true turned on
```

A boot where the switch was already on ends `ensure finish ok=true already on`.

> **The logcat buffer turns over inside the first minute after boot on these
> devices, and the heal happens inside that window.** By the time you can
> connect — which itself takes a port scan — the lines are usually gone. Two
> ways around it: read `logcat -d` from a USB transport immediately after boot
> (USB reconnects in a second or two), or just read the app's own status
> screen, which shows the last result and its timestamp from
> SharedPreferences and so survives both the buffer and the reboot.

### A note on accessibility

An accessibility service can read the screen and act on it. That is a real
capability and it is granted here deliberately, to an app whose entire source
is a few hundred lines in this repository. Read
`app/src/main/java/com/jocala/wifiadb/` before enabling it. What it touches is
limited to the Quick Settings shade and the one tile it looks for.

## Build

```sh
set -a; source keystore/env.txt; set +a
./gradlew assembleRelease
```

Requires the signing key from `keystore/env.txt` (gitignored). Keep it: the
installed package is bound to the app signature. R8 is enabled, which is what
keeps the APK around 45 KB.

## Notes

- Finding the port after a reboot: `adb mdns services` works on Linux. On macOS
  it returns an empty list even when devices are advertising correctly — adb's
  bundled mDNS responder collides with Apple's system responder on UDP 5353 and
  receives nothing; `dns-sd` works there instead. Portable fallback:
  `nmap -sS -p 32768-60999 <ip>` (needs root) or a plain connect scan, then
  `adb connect` each open port — adbd answers `getprop ro.serialno`, while the
  pairing port connects but stays `offline`.
- Writing `adb_wifi_enabled` from a host restarts adbd, which drops the
  transport mid-command. `adb shell 'settings put … ; reboot'` does **not**
  reboot a wireless-only device — the write kills the shell before `reboot`
  runs. Use plain `adb reboot`.
- Some ROMs drop all `DEBUG`-level log lines system-wide; the app logs at INFO,
  hence `I/WifiAdb`.
- Rebuilding the launcher icon: sources are in `design/`, the adaptive icon is
  the vector drawable in `res/drawable/ic_launcher_foreground.xml`.

See `agents.md` for the operator-facing reference.