# wifiadb — AGENTS.md

Lab-only Android app. Re-enables **Wireless debugging** automatically after reboot
on the two ONN tablets, by tapping its Quick Settings tile via an
AccessibilityService. Never for Play (accessibility policy). Sideload + two
one-time manual-adjacent grants, then unattended.

Tablets: `onntab1` 192.168.1.101 (USB available), `onntab2` 192.168.1.102
(wireless only). IPs static, **ADB ports rotate on every adbd restart**
(`adbd` picks a random high port 32768–60999; even a toggle cycle rotates it).
Wrappers: `~/bin/onntab1`, `~/bin/onntab2` (preferred over shell aliases; work
non-interactively). Always confirm identity after connecting:
`getprop ro.serialno` → onntab1 `VHG02613012985`, onntab2 `VHG02613005678`.

## Why wireless debugging dies at boot (verified on-device, 2026-09-30)

`/system/etc/init/hw/init.usb.configfs.rc`, first stanza, unconditional:

```
on boot
    setprop persist.adb.tls_server.enable 0
```

And the ROM's `AdbService.systemReady()` (disassembled from
`/system/framework/services.jar` via dexdump — see `../temp/rom-analysis/`)
copies that property over `Settings.Global.adb_wifi_enabled` at every boot.
The persisted `1` in the settings DB is clobbered, not honored. The toggle's
live path is global-only (the property stays empty in every state we measured).

Permission fence (AOSP main + on-device SELinux policy agree):
`MANAGE_DEBUGGING` is `signature|privileged` (platform key + priv-app only —
our Play upload key is a different principal, useless here).
`persist.adb.tls_server.enable` is `system_adbd_prop`, writable only by
`system_server`/`init`, fenced by `neverallow` for shell and apps. Shizuku
(shell uid) can write the *global* but that doesn't survive boot either.
**The Settings UI is the only permitted writer, hence the tap-automation design.**
No root, locked bootloader, AVB green, `release-keys` — platform signing and
`su` are both closed on three independent axes each.

## App design (what's in this repo)

- `WifiAdbService` (AccessibilityService): finds the QS tile
  (`android.widget.Switch`, content-desc `"Wireless debugging"`, state in text
  `", On"`/`", Off"` + `checked`), taps only when off, verifies via
  `Settings.Global` (readable without permission), retries with Handler-delayed
  state machine (never blocks). Every path ends with `GLOBAL_ACTION_HOME` —
  tablet drops to launcher, nothing left open. Failure → high-importance
  notification; success is silent (result in prefs, shown in MainActivity).
- Triggers: `onServiceConnected` self-check (boot-time dedup via stored boot
  epoch) + manifest `BootReceiver` → same idempotent entry. Broadcasts
  `com.jocala.wifiadb.RUN_ENSURE` (idempotent) / `RUN_CYCLE` (force off/on,
  test only) — send with `am broadcast -a … -p com.jocala.wifiadb`.
- Zero dependencies, no AndroidX. `compileSdk/targetSdk 36`, `minSdk 30`
  (Android 11 is the floor: the QS tile this taps does not exist below it),
  AGP 8.10.1 / Kotlin 1.9.24 / Gradle 8.11.1 (mirrors `source/aquarium/android`).
  R8/minify on + `proguard-rules.pro` — that is what holds the APK to ~45 KB.

## Prerequisites per tablet (one-time, by hand or shell)

1. **Lockscreen → None** (Settings → Security → Screen lock). REQUIRED, not
   cosmetic: a locked boot means no CE storage, no app process, no binds, and
   the accessibility entry gets *pruned*. Proven: locked reboot stayed dark
   12+ min; unlocked boots heal in ~1 min. Cannot be done via `settings put`
   (lock method lives in locksettings store, not the settings provider).
2. **QS tile present**: Settings → Developer options → "Quick settings developer
   tiles" → tick Wireless debugging. `settings put secure sysui_qs_tiles …`
   is silently rejected on this ROM — the hand tap is the documented path.
3. **Grants**: accessibility service on (Settings → Accessibility → Installed
   services → wifiadb) + notification permission. Shell equivalents that DO
   stick (use when adb is up):
   `settings put secure enabled_accessibility_services "<existing>:com.jocala.wifiadb/com.jocala.wifiadb.WifiAdbService"`
   (colon-append — never overwrite; onntab1 has a Lawnchair entry to preserve),
   `settings put secure accessibility_enabled 1`,
   `pm grant com.jocala.wifiadb android.permission.POST_NOTIFICATIONS`.

## Keystore (stable signature matters)

`keystore/wifiadb.jks` + gitignored `keystore/env.txt` (mirrors aquarium
convention). The a11y grant is signature-bound: reinstalls under the same key
keep working; a new key silently disables the service on both tablets.
Build: `set -a; source keystore/env.txt; ./gradlew assembleRelease`.
APK: `app/build/outputs/apk/release/app-release.apk`.

## Install / reinstall procedure

```
adb -s <transport> install [-r] app/build/outputs/apk/release/app-release.apk
# grants per §Prerequisites; then force a rebind (this ROM does NOT rebind on
# install, reinstall, or force-stop — only on a settings-list change or boot):
adb -s <transport> shell 'settings put secure enabled_accessibility_services "<same value>"'
# verify: dumpsys accessibility | grep -m1 "Bound services"  (lists wifiadb)
#         logcat -d | grep "I/WifiAdb"                        (onServiceConnected + ensure lines)
# NOTE: update-installs (`install -r`) drop the bind too, same as reinstalls —
# the entry stays in settings but nothing binds until the re-put. Always re-put
# after ANY install, then verify via logcat (dumpsys line is long; logcat is decisive).
```

## Reboot test (proven 5× on onn1, 2× on onn2, re-proven 2026-09-30 — all healed with app present)

**The `adb_wifi_enabled` setting does NOT survive reboot** — init sets
`persist.adb.tls_server.enable 0` and `systemReady()` copies it over the
global every boot. So every reboot boots dark, and anything that finds
`wifi=1` afterwards did so by healing it. Do not read "already on" as the
value having persisted; there was nothing to persist.

**Do not chain `settings put … ; reboot`.** Writing the global restarts adbd,
which kills the device-side shell before `reboot` runs — on a wireless-only
tablet the reboot silently never happens. Use plain `adb reboot`. (onn1 masks
this because USB keeps adbd alive, so the command *appears* to work there.)

1. Baseline: `settings get global adb_wifi_enabled` (=1), entry present.
2. `adb -s <transport> reboot` — fire and stand by; the transport dies
   mid-command (expected, not an error). No sleeps/polls on a schedule; wait
   for the human signal or a single check.
3. Rediscover (port always rotates), no root needed: `~/bin/onntab-discover`
   lists serial/ip/port via mDNS, `--connect` also connects. It wraps `dns-sd`
   (Apple's system responder). Do NOT use `adb mdns services` — adb's bundled
   "Openscreen" responder binds UDP 5353 alongside Apple's on macOS and hears
   nothing, so it prints an empty list even while the tablets advertise
   correctly (a helper holding 5353, e.g. Edge, can also block the bind).
   nmap fallback: `sudo nmap -sS -Pn -T4 --min-rate 2000 --max-retries 1
   -p 32768-60999 <ip>`, then `adb connect` each open port — adbd answers
   `getprop ro.serialno`, the pairing port connects but stays `offline`.
4. Verify: `wifi=1`, entry intact, `I/WifiAdb` boot lines, `adb connect` +
   serial. Re-pin `~/bin/onntabN` + `~/.bashrc`/`~/.zshrc`, disconnect stales.

## ROM quirks (all observed, all cost time)

- `logd` drops **every** `DEBUG` line device-wide → app logs at INFO (`I/WifiAdb`).
- Rebind only on settings-list change or boot. Never force-stop (stays stopped).
- Locked boot prunes non-directBootAware a11y entries; Lawnchair's survives.
- `adb disconnect` with no args kills ALL transports; stale `offline` entries
  linger in `adb devices` (cosmetic), and `adb disconnect <ip:port>` here often
  no-ops — `adb kill-server` forces a clean reconnect.
- Toybox `grep` chokes on `\|` alternation — use `grep -E` or separate greps.
- `/sdcard` vanishes while locked (writes go to `/data/local/tmp` instead).
- **Status screen `service enabled` is always wrong — known, unfixed, cosmetic.**
  `MainActivity.buildStatus()` does
  `enabledSvcs.contains("$packageName/.WifiAdbService")`, but the platform
  always stores the *fully-qualified* class
  (`com.jocala.wifiadb/com.jocala.wifiadb.WifiAdbService`), so the substring can
  never match and it prints `false` even when the service is enabled and bound.
  Do NOT use it to decide whether the service is live. The authoritative checks
  are `dumpsys activity services com.jocala.wifiadb` (live `ServiceRecord`) and
  the `onServiceConnected` log line.
- **`dumpsys accessibility` "Bound services" does not list wifiadb on onntab2**
  even when it is genuinely bound. Reading that line produced two completely
  wrong conclusions during this work (that the service "wouldn't bind", and a
  bogus per-device mechanism split). Always confirm with
  `dumpsys activity services` instead.
- **The logcat buffer turns over inside the first minute after boot**, and the
  heal happens in that window — so by the time you have scanned for the new
  port and connected, `logcat -d | grep I/WifiAdb` is usually empty on all four
  buffers. Not evidence of failure. Use `adb reboot` over USB and read logcat
  immediately, or read the app's status screen (last result is persisted in
  SharedPreferences and survives both the buffer and the reboot).

## Provenance of analysis artifacts

`../temp/rom-analysis/`: `services-onntab1.jar` + dexdump, `MtkSettings*.apk`
(both tablets, 92 MB each — same build), `settings-resources.txt`, UI dumps,
screenshots. `../temp/testing/TODO.md`: full session log with per-reboot results.
