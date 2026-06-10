# GameNative (IIC) — Is It Compatible? Fork

A personal fork of [GameNative](https://github.com/utkarshdalal/GameNative) packaged as
`app.gamenative.iic` so it installs **alongside** the official GameNative app without
conflicting. Built for the **Is It Compatible?** companion workflow — bakes in per-game
auto-fixes and device-tuned defaults, especially for AYN Odin / Adreno 830 /
Snapdragon 8 Elite hardware.

> **Note:** This fork cannot update an existing official GameNative installation in-place.
> Because it is signed with a different key, Android treats it as a separate app. Install
> it alongside the official build; your saves and container data are stored per-package.

---

## What is GameNative?

GameNative lets you run PC games from your Steam, Epic, and GOG libraries directly on
Android — no streaming required. It is built on top of the
[Winlator](https://github.com/brunodev85/winlator) Wine/Box86 stack, extended with
cloud saves, auto-applied game configs, and a native Android UI. See the upstream repo
for full documentation.

This fork (`v1.8.2-IIC`) tracks the upstream source closely and adds the patches
described below.

---

## Key changes vs upstream

### Virtual-controller / hardware controller support (AYN Odin)

The upstream input stack expects controllers to declare `INPUT_PROP_POINTING_STICK` or a
similar physical flag. Hardware gamepads on the AYN Odin (and similar Android handhelds)
present as **virtual input devices**, which the upstream code skipped. This fork accepts
virtual-input devices as valid gamepads so the Odin built-in controller works without
pairing an external Bluetooth pad.

### evshim shared-memory path derived from the running package

The `evshim` native shim (which bridges hardware joystick state into the Wine
environment via shared memory) previously used a hardcoded base path baked for
`app.gamenative`. When the app runs as `app.gamenative.iic` the path diverged and the
controller was silently dead. The shim now derives the base path from the process
`/proc/self/cmdline` at runtime, falling back to `EVSHIM_BASE_PATH` env override, then
the hardcoded path — so the controller works correctly under any package name.

### Controller button release-edge fix

Button-up events (key release) were occasionally swallowed when the SDL virtual joystick
re-attached mid-session. The updater thread now flushes a synthetic release edge before
re-attaching, preventing sticky buttons after a reconnect.

### dxwrapper version self-heal

If a container's configured dxwrapper version is no longer present in the bundled asset
set (e.g. after an app update removes an old variant), the app now auto-selects the
newest available version instead of falling through to a silent failure at launch.

### Force-internal-storage

Containers are created in internal storage by default to avoid FUSE/vold latency and the
`SIGKILL` storms that can occur on some devices when Android's external-storage daemon
races the Wine process during startup.

### suspendPolicy auto-default

New containers default `suspendPolicy` to `auto` (the system decides based on
foreground/background transitions) rather than the upstream `manual`, which required
users to explicitly resume after switching away from the game.

### BOM-safe container config parsing

The container config parser now strips a UTF-8 byte-order mark if present before
attempting JSON/INI parsing. A BOM written by Windows-side tools (e.g. Notepad) caused
silent parse failures and reset containers to defaults.

### Per-game GameFixes

| Game | Fix |
|---|---|
| Devil May Cry HD Collection — Steam App ID 631510 | Deletes a stale shader-cache file that causes a hard crash on first launch after an update |

### Adreno 830 / Snapdragon 8 Elite tuned defaults

On first run, `DeviceProfileDetector` detects Adreno 830-class GPUs
(`GPUInformation.isAdreno8Elite()`) and writes optimised preference defaults:
SD-8-Elite driver track, Turnip via adrenotools wrapper, 4 GB VRAM allocation, Vulkan
backend. These only apply once and can be overridden per-container.

### Ko-fi nag disabled

The periodic Ko-fi support prompt is suppressed in this build. Please consider supporting
the upstream project directly at [ko-fi.com/gamenative](https://ko-fi.com/gamenative).

---

## Installation

1. Download the `.apk` from the [Releases](../../releases) page (or build from source —
   see below).
2. Enable "Install from unknown sources" in Android settings.
3. Install the APK. It will appear as a separate app alongside any existing GameNative
   install.
4. Log in to Steam and play.

---

## Building from source

Standard Android Studio project. Tested with AGP 8.x and NDK `27.3.13750724`.

```sh
# Optional: add a SteamGridDB API key for game artwork
echo "STEAMGRIDDB_API_KEY=your_key_here" >> local.properties

./gradlew assembleModernIicDebug
```

The `modern` + `iic` flavor combination produces `app.gamenative.iic`. The `gold` flavor
produces `app.gamenative.gold` (an unrelated build variant retained from upstream).

> **Large asset note:** `app/src/legacy/assets/extras.tzst` (~82 MB) is excluded from
> this repository via `.gitignore` because it exceeds GitHub's per-file warning threshold.
> It is pulled in during the Gradle build from the upstream asset pipeline. To build
> locally, copy it from an upstream checkout or a Gradle cache into the expected path.

---

## License

This repository is a fork of GameNative and is distributed under the same terms:
**[GNU General Public License v3.0](LICENSE)** — see the `LICENSE` file.

See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for attributions, copyleft source offers,
and notices about third-party and proprietary components bundled with the app (including
the Winlator/Wine lineage).

---

## Credits

- **GameNative** — original project by [utkarshdalal](https://github.com/utkarshdalal/GameNative).
  All core functionality, the upstream game-compatibility pipeline, cloud saves, and the
  Android UI are their work. This fork only adds the patches described above.
- **Winlator** — Wine-on-Android container runtime by
  [brunodev85](https://github.com/brunodev85/winlator), which GameNative builds upon.
- Built with assistance from Claude (Anthropic).

---

**Disclaimer:** This software is for playing games you legally own. Do not use it for
piracy or any other unlawful purpose. The fork maintainer takes no responsibility for
misuse.
