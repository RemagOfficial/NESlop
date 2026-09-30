# AI NES (NESlop)

A from-scratch **Nintendo Entertainment System / Famicom emulator** for Android, written in Kotlin
with a Jetpack Compose UI. The CPU (6502), PPU (2C02), APU (2A03), memory bus, controllers and the
NROM (mapper 0) and MMC3 (mapper 4) mappers are all implemented directly in this repository — there
is no libretro core or native emulation library underneath it.

It boots, plays sound, supports on-screen and physical controllers, and can save/load whole-machine
states that are permanently tied to the game that created them. Super Mario Bros. (NROM) and Super
Mario Bros. 3 (MMC3, including its mid-scanline status-bar raster effect) are the reference titles
used during development.

![Title screen](<real_screenshots/title_screen.png>)

---

## ⚠️ ROMs are not included — and cannot be

This repository contains **no `.nes` ROM files and no prebuilt APKs**, and never will.

* Game ROMs are copyrighted and cannot be redistributed here. `.gitignore` deliberately excludes
  every `*.nes`, so the built-in assets (`Super Mario Bros.nes`, `AccuracyCoin.nes`) and any test
  cartridge you drop in are never committed.
* A built APK bundles whatever is in `app/src/main/assets/`, so shipping an APK would ship the ROMs
  too. For that reason there are **no release downloads** — you build the APK yourself from source
  (see [Building](#building)).

You must supply your own ROM dumps of games you legally own. The sections below explain where to put
them so the emulator can run them.

---

## Features

* **Full NTSC NES architecture** — cycle-counted 6502 CPU, dot-granular 2C02 PPU (sprite 0 hit,
  scanline IRQs, mid-scanline writes), and a 2A03 APU with band-limited pulse/triangle/noise
  synthesis and a resampled 44.1 kHz output.
* **Open-bus and read-buffer emulation** for games and test ROMs that rely on undocumented timing.
* **Mapper support:**
  * **Mapper 0 (NROM)** — Super Mario Bros., most early titles.
  * **Mapper 4 (MMC3)** — Super Mario Bros. 3 and many later Nintendo games.
  * Support is partial and still maturing: a game on even these mappers may render incorrectly or
    crash where the emulation isn't accurate yet. Other mappers are not implemented.
* **Controllers** — on-screen D-pad + A/B/Start/Select, keyboard fallbacks, and Bluetooth/USB gamepads
  with fully remappable buttons.
* **Save states** — one snapshot per game, restore-safe across launches (see
  [Save states](#save-states)).
* **Portrait and landscape layouts** with a scaling 256×240 display and optional FPS counter.

---

## Nintendo home console emulators

This tracks the Nintendo home consoles I've built emulators for, from the NES up to the GameCube.
A ticked entry links to the repository for that emulator (this repo for the NES); unticked entries
are consoles I have not emulated yet.

- [x] **Nintendo Entertainment System / Famicom** (NES, 1983) — [this repository](https://github.com/RemagOfficial/NESlop)
- [ ] **Super Nintendo Entertainment System / Super Famicom** (SNES, 1990)
- [ ] **Nintendo 64** (N64, 1996)
- [ ] **Nintendo GameCube** (GameCube, 2001)

---

## Getting your ROMs running

There are two ways to feed the emulator a game. You only need the **runtime loader** for casual play;
the **assets** route is only if you want the app to boot a title automatically.

### Option 1 — Load any ROM at runtime (no rebuild needed)

This is the recommended path and works with any supported game, including MMC3 titles like Super
Mario Bros. 3 that are *not* baked into the app.

1. Copy your `.nes` file(s) onto the device (internal storage or SD card).
2. In the app, tap the **⚙ Settings** button (top-right of the controller area).
3. Tap **Load Custom ROM (.nes)** and pick the file with the system document picker.

The chosen ROM boots immediately on a fresh machine. Nothing about this method requires a rebuild, so
you can switch between as many games as you like.

> The compatibility warning shown before the picker reflects reality: the emulator supports NROM
> (mapper 0) and MMC3 (mapper 4) cartridges, but that support is partial and still maturing. Even a
> game on a supported mapper may render incorrectly or crash, because some hardware behaviours aren't
> yet emulated accurately. Mappers beyond 0 and 4 are not implemented and will not work.

### Option 2 — Add built-in assets (recompile required)

The app hard-codes two built-in cartridge names:

* **`Super Mario Bros.nes`** — this is the ROM the emulator **boots on first launch**.
* **`AccuracyCoin.nes`** — the second entry cycled by the **"Switch Built-in"** button in Settings.

If you want those titles available as built-ins (or want to change which game auto-starts), place
files with those **exact names and casing** into:

```
app/src/main/assets/
├── Super Mario Bros.nes
└── AccuracyCoin.nes
```

Then rebuild and reinstall (see [Building](#building)). Because `.gitignore` excludes `*.nes`, these
files stay on your machine and are never pushed to GitHub.

**Important behaviour to know about:** if `Super Mario Bros.nes` is missing from `assets/`, the app
does **not** crash. The loader falls back to synthesising a blank 40 KB NROM cartridge, which you'll
see as a black/silent screen. If the emulator starts to a dead screen, that's why — add the asset or
just use **Option 1** to load a real ROM.

### About `AccuracyCoin.nes`

`AccuracyCoin` is a third-party NES hardware-accuracy test cartridge (144 tests) used during
development to validate CPU timing, open bus, PPU read-buffer/vblank/sprite behaviour, and APU
register semantics. It is **not** authored for or distributed by this project — obtain it from its
author, [100thCoin on GitHub](https://github.com/100thCoin/AccuracyCoin), and download the ROM
directly ([AccuracyCoin.nes](https://github.com/100thCoin/AccuracyCoin/blob/main/AccuracyCoin.nes)).
The on-screen meanings of its error codes are documented in the bundled
[`test_rom_readme.md`](test_rom_readme.md), and it ships a separate, non-`*.nes` doc file (that one is
committed so you can read it without a ROM).

---

## Building

### Requirements

* **Android Studio** (or the Android SDK) — project was developed on a recent AGP.
* **JDK 17+** (the project toolchain resolves via Gradle; OpenJDK 21/25 both work).
* **Minimum Android API 24**, target/compile SDK **37**.

### With Android Studio

1. **File → Open** and select this project folder.
2. Let Gradle sync. Optionally drop ROMs into `app/src/main/assets/` (Option 2 above) for built-ins,
   or just build without them and load games at runtime.
3. **Run ▶** on a device/emulator, or **Build → Build APK(s)**. The debug APK is written to
   `app/build/outputs/apk/debug/`.

### With the command line

```bash
# Linux / macOS
./gradlew assembleDebug

# Windows (PowerShell)
.\gradlew.bat assembleDebug
```

The signed-debug APK lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> The `release` build type currently has code/resource **optimization disabled** (see
> `app/build.gradle.kts`). If you produce a release APK for your own use, remember it will contain any
> ROMs sitting in `assets/`, so don't share it.

---

## Controls

* **On-screen** — D-pad, A, B, START, SELECT are always drawn in the controller area (below the screen
  in portrait, beside it in landscape).
* **Keyboard** — sensible fallbacks are built in: `Enter`/`Space` = A, `Esc` = B, `Tab` = Select,
  `Menu` = Start, `W/A/S/D` = D-pad, plus native gamepad buttons.
* **Gamepads** — a connected Bluetooth/USB controller works out of the box, and every button can be
  rebound under **⚙ Settings → Remap Bluetooth Controller**. Analog sticks and the hat both drive the
  D-pad.

---

## Save states

The emulator can snapshot and restore the entire machine (CPU, RAM, PPU latches and nametables, the
MMC3 registers, WRAM and the full APU state).

* **Buttons:** portrait controllers show **SAVE** and **LOAD** stacked beneath the D-pad/action buttons,
  right under the game screen.
* **Key bindings:** in **⚙ Settings → Remap Bluetooth Controller**, under *Emulator actions*, you can
  bind Save State / Load State to any controller or keyboard button. They have **no default binding**
  — bind them only if you want physical-button shortcuts (useful in landscape, where the on-screen
  buttons aren't shown).
* **Saves are locked to their game.** Each save file is named after, and internally stamped with, a
  64-bit identity derived from the ROM's bytes. Loading only succeeds when the running cartridge's
  identity matches the file, so a save made in one game can never be restored into another — even a
  different ROM that happens to share a filename. Attempting a mismatched load reports *"No save for
  this game"* rather than corrupting the machine.
* **Where they live:** in the app's private storage (`files/saves/`), one file per game. Clearing the
  app's data/storage removes them.
* Saves are taken at a frame boundary, so SMB3's scanline IRQs and split-screen effects resume
  correctly.

---

## Testing

Standard Android unit/instrumentation layout:

* `app/src/test/` — JVM unit tests covering CPU opcodes (e.g. `ROR`), PPU raster splits, sprite 0 hit,
  palettes, rendering masks, open bus, MMC3 mirroring (note MMC3's `$A000` mirror bit is *inverted*
  relative to the iNES header), the APU triangle channel, and the save-state round-trip
  (`SaveStateRoundTripTest`, which verifies cross-game rejection and corrupt-file handling).

Run them with:

```bash
./gradlew testDebugUnitTest          # Windows: .\gradlew.bat testDebugUnitTest
```

`AccuracyCoin` doubles as an end-to-end hardware-accuracy harness once you add the ROM (Option 2).

---

## Project layout

```
app/src/main/java/com/remag/aines/
├── emulator/
│   ├── Cpu6502.kt        # MOS 6502 core + unofficial opcodes
│   ├── Ppu2c02.kt        # 2C02 video: dot raster, sprites, sprite-0 hit, NMI
│   ├── Apu2a03.kt        # 2A03 audio: pulses, triangle, noise, frame counter
│   ├── Bus.kt            # Memory map, open bus, OAM DMA
│   ├── Cartridge.kt      # iNES header, NROM + MMC3 mapper, WRAM
│   ├── Controller.kt     # 4016/4017 serial pad
│   └── NesMachine.kt     # Wires components together, drives the frame loop
├── save/SaveState.kt     # Versioned whole-machine snapshot codec
└── MainActivity.kt       # Compose UI, emulator thread, settings, key handling
```

---

## Tech stack

* **Language:** Kotlin
* **UI:** Jetpack Compose + Material 3
* **Architecture:** `AndroidViewModel` + Compose state; the emulator runs a self-timed ~60 fps frame
  loop on `Dispatchers.Default`, presenting a fresh bitmap each completed frame.
* **Audio:** `AudioTrack` streaming from an APU ring buffer.

---

## Disclaimer

This project is for educational and preservation purposes. Emulators are legal; distributing or
downloading copyrighted game ROMs you do not own is not. Provide your own legally dumped ROMs.
"AI NES", Nintendo, and game titles are trademarks of their respective owners and are not affiliated
with or endorsed by this project.
