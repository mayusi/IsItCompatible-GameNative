# GameNative (IIC) — Is It Compatible? Fork

**v1.12.0-IIC** | Package: `app.gamenative.iic` | Android 9+ (modern flavor)

A personal fork of [GameNative](https://github.com/utkarshdalal/GameNative) by utkarshdalal,
packaged as `app.gamenative.iic` so it installs **alongside** the official app without
conflicting. Built for the **Is It Compatible?** companion-app workflow — bakes in per-game
auto-fixes, device-tuned defaults across 6 GPU classes, and an empirical Auto-Tuner that
sweeps configs to find what works best for each game on your hardware.

> **Note:** Because this fork is signed with a different key, Android treats it as a separate
> app. It cannot update an existing official GameNative installation in-place. Install it
> alongside; saves and container data are stored per-package.

---

## What is GameNative?

GameNative lets you run PC games from your Steam, Epic, and GOG libraries directly on
Android — no streaming required. It is built on top of the
[Winlator](https://github.com/brunodev85/winlator) Wine/Box64 stack, extended with cloud
saves, auto-applied game configs, and a native Android UI.

All core functionality, the upstream game-compatibility pipeline, cloud saves, and the Android
UI are the work of [utkarshdalal/GameNative](https://github.com/utkarshdalal/GameNative).
This fork adds the IIC-specific patches described below.

---

## IIC Fork Features

### Auto-Tuner

The centerpiece of this fork. The Auto-Tuner empirically sweeps through Wine/graphics
configs and measures real performance on your device to find the best setup for each game.

**Seven optimization goals:**

| Goal | What it optimises |
|---|---|
| Compatibility Probe | "Does it even run?" — fast boot check (~5-8 min), ~6 archetype configs |
| Max FPS | Highest average frame rate |
| FPS + Stability | Best balance of frame rate and smoothness (good for online play) |
| FPS + Battery | Best FPS per watt — extends playtime (device must be unplugged) |
| FPS + Cool | Best FPS while keeping thermals low — good for long sessions |
| Low-End Friendly | Lightest stable config for modest hardware |
| Custom Weights | User-defined FPS/stability/battery/temperature weight sliders |

**Sweep mechanics:**
- Two measurement modes: Auto (hands-off, reads FPS session data) and Manual (user plays,
  taps "Stop Recording")
- Per-trial: warmup phase, measurement window, cooldown with GPU temp tracking
- Black-screen / crash detection — aborted trials are recorded, not silently discarded
- Fix-finding during sweep: if a trial crashes, the engine attempts known fixes and retries
  before marking the config as failed
- Results screen shows all ranked configs with FPS, stability, battery, and temperature data
- Sweep can be canceled mid-run; the best result so far is preserved

### Per-Game GameFixes Registry

A registry of compiled-in fixes covering Steam, GOG, and Epic games. Applied automatically
at launch when a matching game is detected.

**OTA updates:** The registry is also hosted at
[`gamefixes/registry.json`](gamefixes/registry.json) in this repo.
The app syncs this file in the background so new fixes can be deployed without a full app
update. Compiled-in fixes always take priority over OTA entries.

Covers 40+ games across Steam/GOG/Epic (shader-cache cleanup, DLL overrides, registry
keys, Wine env vars, and more).

### DeviceProfileDetector — 6 GPU Classes

On first launch, writes GPU-class-appropriate preference defaults once. Subsequent launches
leave user overrides intact.

| GPU Class | Chip Examples | Driver | VRAM |
|---|---|---|---|
| Adreno 830 / 8 Elite | AYN Odin 2 Pro, SD8 Elite phones | Turnip (Wrapper) | 4 GB |
| Adreno 750 / 8 Gen 3 | SD8 Gen 3 phones | Turnip (Wrapper) | 3 GB |
| Adreno 740 / 8 Gen 2 | SD8 Gen 2 phones | Turnip (Wrapper) | 2 GB |
| Adreno 6xx / 865-888 | SD865/888 phones | Wrapper (system GLES fallback) | 2 GB |
| Mali-G7xx / Dimensity | Dimensity 9000–9300 | System Vulkan | 2 GB |
| Mali lower / Helio | Helio-class | System Vulkan | 1 GB |

If no class matches, stock defaults remain unchanged.

### BestConfigService

On each game launch, fetches a GPU-matched recommended config from the upstream GameNative
API and applies it in a background thread — no UI block. Skipped when an intent already
supplied a config (e.g. Auto-Tuner result).

### Multi-Game Collection Support

For games that ship multiple titles in one install (one Steam/store ID, multiple
executables), the fork presents a "Games in this collection" picker instead of requiring
manual executable path changes.

Built-in collection: **Devil May Cry HD Collection** (Steam 631510) — DMC1, DMC2, DMC3:
Dante's Awakening. The crashing `dmcLauncher.exe` is excluded automatically.

Additional collections can be deployed via OTA (same sync mechanism as the game-fix
registry; `assets/gamefixes/collections.json` bundled in-app).

### Crash Classifier

After a game exits abnormally, the fork classifies the Wine debug output against known
crash patterns and surfaces a human-readable suggestion with a one-tap fix action where
possible (e.g. DLL override, registry fix).

### AYN Odin / Virtual Controller Support

Upstream GameNative expected controllers to declare physical input flags. Hardware
gamepads on AYN Odin (and similar Android handhelds) present as virtual input devices,
which upstream skipped. This fork accepts virtual-input devices as valid gamepads.

The `evshim` native shim now derives its shared-memory base path from the running package
name (`/proc/self/cmdline`) rather than a hardcoded path, so it works correctly under
`app.gamenative.iic`.

### Other Fixes

- **Controller button release-edge fix** — synthetic release edge flushed on re-attach to
  prevent sticky buttons
- **dxwrapper self-heal** — if a container's configured dxwrapper version is no longer
  present, the app auto-selects the newest available version
- **Force-internal-storage** — new containers default to internal storage to avoid FUSE
  latency and `SIGKILL` races on some devices
- **suspendPolicy auto** — new containers default to `auto` rather than `manual`, so the
  system handles foreground/background transitions without manual intervention
- **BOM-safe config parsing** — strips UTF-8 BOM before JSON/INI parsing; prevents silent
  parse failures from Windows-side tools
- **Ko-fi nag disabled** — the periodic Ko-fi prompt is suppressed. Please consider
  supporting the upstream project directly at [ko-fi.com/gamenative](https://ko-fi.com/gamenative)

---

## IIC Fork vs Vanilla GameNative

| Feature | Vanilla GameNative | This Fork (IIC) |
|---|---|---|
| Package ID | `app.gamenative` | `app.gamenative.iic` |
| Install alongside official | — | Yes (separate package) |
| Auto-Tuner | No | Yes (7 goals, 2 modes) |
| Crash classifier | No | Yes (one-tap fixes) |
| Per-game fixes | Upstream registry | 40+ compiled-in + OTA updates |
| Device GPU defaults | No | 6 GPU classes, one-shot |
| Best config on launch | Upstream API | Upstream API + auto-apply |
| Multi-game collections | No | DMC HD + OTA collections.json |
| AYN Odin controller | Partial | Full virtual-device support |
| IIC app session feedback | No | Yes (broadcast at session end) |

---

## Installation

This fork is distributed through the **Is It Compatible?** companion app. Alternatively,
download an APK from the [Releases](../../releases) page if available, or build from
source (see below).

1. Enable "Install from unknown sources" in Android settings.
2. Install the APK. It appears as a separate app alongside any existing GameNative install.
3. Log in to Steam (or Epic/GOG) and play.

---

## OTA Game-Fix Files

Two JSON files are hosted in this repo and synced by the app in the background:

| File | Contents |
|---|---|
| [`gamefixes/registry.json`](gamefixes/registry.json) | Per-game Wine/DLL fixes (delete files, env vars, registry keys, DLL overrides) |
| `assets/gamefixes/collections.json` (bundled in-app) | Multi-game collection definitions |

Compiled-in entries always take priority over OTA entries — there is no regression for
games already covered at build time.

---

## Building from Source

Standard Android Studio project. Tested with AGP 8.x and NDK `27.3.13750724`.

```sh
# Optional: add a SteamGridDB API key for game artwork
echo "STEAMGRIDDB_API_KEY=your_key_here" >> local.properties

./gradlew assembleModernDebug
```

The `modern` flavor produces `app.gamenative.iic` (via `applicationIdSuffix = ".iic"`).
The `legacy` flavor produces `app.gamenative` (same ID as upstream — intended for older
Android builds, not the IIC fork).

> **Large asset note:** `app/src/legacy/assets/extras.tzst` (~82 MB) is excluded from
> this repository via `.gitignore` because it exceeds GitHub's per-file warning threshold.
> Copy it from an upstream checkout or Gradle cache into the expected path before building.

---

## License

Fork of GameNative, distributed under the same terms:
**[GNU General Public License v3.0](LICENSE)** — see the `LICENSE` file.

See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for attributions, copyleft source offers,
and notices about third-party and proprietary components bundled with the app (including
the Winlator/Wine lineage).

---

## Credits

- **GameNative** — original project by [utkarshdalal](https://github.com/utkarshdalal/GameNative).
  All core functionality, the upstream game-compatibility pipeline, cloud saves, and the
  Android UI are their work. This fork only adds the IIC-specific patches described above.
- **Winlator** — Wine-on-Android container runtime by
  [brunodev85](https://github.com/brunodev85/winlator), which GameNative builds upon.
- Built with assistance from Claude (Anthropic).

---

**Disclaimer:** This software is for playing games you legally own. Do not use it for
piracy or any other unlawful purpose. The fork maintainer takes no responsibility for
misuse.
