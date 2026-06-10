# Changelog

All notable changes to the IIC fork of GameNative are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [1.12.0-IIC] — current

_(versionCode 38 — see app/build.gradle.kts)_

No standalone release notes yet; see the v1.11.x entries for the most recent shipped
changes. This version incorporates all fixes from the 1.11.x line.

---

## [1.11.4-IIC]

### Fixed
- Auto-Tuner: reduced `GoalCard` parameter count (18 → 11) to eliminate `VerifyError`
  risk at runtime; removed dead code paths in the sweep UI

---

## [1.11.3-IIC]

### Fixed
- Game-entry crash (`VerifyError` in `AppScreenContent`) introduced in 1.11.0

---

## [1.11.2-IIC]

### Fixed
- Three main-thread I/O bugs found in a launch-path audit (blocking reads moved off the
  main thread)

---

## [1.11.1-IIC]

### Fixed
- Crash when entering a game via the UI (race condition in collection-launch `saveData`
  path)

---

## [1.11.0-IIC]

### Changed
- Auto-Tuner redesign: 7 selectable optimization intents (COMPAT_PROBE, MAX_FPS,
  FPS_STABLE, FPS_BATTERY, FPS_COOL, LOW_END, CUSTOM) replacing the earlier single-goal
  sweep
- Rich results UX: per-trial FPS, stability, battery, and temperature data displayed on
  the results screen with full ranked list

---

## [1.10.3-IIC]

### Added
- **Crash Classifier**: analyzes Wine debug output after an abnormal exit and surfaces a
  human-readable suggestion with a one-tap fix action (DLL override, registry fix, etc.)
- DLL-override fix type added to the game-fix pipeline
- Multi-game collection QOL improvements: "Games in this collection" picker shown for
  known collections

### Fixed
- Crash classifier integrated into the post-game exit flow without blocking the UI

---

## [1.10.2-IIC]

### Added
- Auto-Tuner: multi-run averaging per config (reduces measurement noise)
- Community result sharing: sweep outcomes can be shared for comparison

---

## [1.10.1-IIC]

### Added
- Auto-Tuner: auto-close trials (hands-off mode — game windows close automatically
  between trials)
- DMC HD Collection three-game support in `CollectionRegistry` (DMC1, DMC2, DMC3;
  `dmcLauncher.exe` excluded)

---

## [1.10.0-IIC]

### Added
- **Container Auto-Tuner**: empirically sweeps Wine/graphics configs to find the best
  setup per optimization goal; measures real FPS and stability on device

---

## [1.9.3-IIC]

### Fixed
- Clamp session minutes to valid range (prevents negative or overflow values)
- Downgrade attestation log level (reduces logcat noise)
- Various robustness hardening

---

## [1.9.2-IIC]

### Added
- **BestConfigService / BestConfigApplier**: auto-fetches a GPU-matched recommended
  config from the upstream GameNative API and applies it on every game launch
  (background thread, no UI block); skipped when an intent-supplied config is already
  present
- **JsonGameFixLoader + GameFixesSyncWorker**: OTA-updatable game-fix registry synced
  from `gamefixes/registry.json` at this repo root; compiled-in fixes always take
  priority
- **DeviceProfileDetector** generalized to 6 GPU classes: Adreno 830/8Elite,
  Adreno 750/8Gen3, Adreno 740/8Gen2, Adreno 6xx/865-888, Mali-G7xx/Dimensity 9x00,
  Mali lower/Helio
- `SessionFeedbackBroadcaster`: notifies the Is It Compatible? app at session end for
  journal pre-fill
- Controller auto-detect toast (virtual-device aware)

---

## [1.9.x — gamefixes OTA hosting]

### Added
- `gamefixes/registry.json` hosted at repo root for OTA sync

---

## [1.8.2-IIC] — initial fork release

### Added
- Fork packaging: `applicationIdSuffix = ".iic"` on the modern flavor so the fork
  installs alongside the official `app.gamenative` package
- **Virtual-controller / AYN Odin support**: accepts virtual input devices as valid
  gamepads; upstream required physical input flags which hardware handhelds don't declare
- **evshim package-name fix**: shim derives shared-memory base path from
  `/proc/self/cmdline` at runtime instead of a hardcoded path, so the controller works
  under `app.gamenative.iic`
- **Controller button release-edge fix**: synthetic release edge flushed before
  re-attach to prevent sticky buttons after a reconnect
- **dxwrapper self-heal**: auto-selects the newest available dxwrapper version if the
  configured version is no longer present in the bundled asset set
- **Force-internal-storage**: new containers default to internal storage to avoid FUSE
  latency and `SIGKILL` races on external storage
- **suspendPolicy auto**: new containers default to `auto` instead of `manual`
- **BOM-safe config parsing**: strips UTF-8 BOM before JSON/INI parsing
- **DeviceProfileDetector (Adreno 830)**: initial one-shot tuned defaults for AYN Odin /
  Snapdragon 8 Elite (Turnip wrapper, 4 GB VRAM, mailbox present mode)
- Per-game fix: Devil May Cry HD Collection (Steam 631510) — deletes stale shader-cache
  files that cause a hard crash on first launch after an update
- Ko-fi nag suppressed
- Upstream GitHub Actions workflows removed (OAuth scope concerns)
