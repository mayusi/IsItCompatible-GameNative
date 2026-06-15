# cheatdll — in-game one-tap cheat engine (x86_64 Windows proxy DLL)

This is the cheat engine that powers the one-tap cheats on the Cheats tab. Unlike
`libtrainer.so` (which runs in the Box64/FEXCore **host** and reads `/proc/self/mem`),
this DLL is **Windows x86_64 code that runs INSIDE the game's own process** under
Wine. Memory access is therefore plain in-process pointers — no ptrace, no RPM/WPM,
no SELinux issues, no host/guest boundary.

## How it loads

`CheatStager.kt` (Kotlin) copies `dinput8.dll` next to the game exe at launch and
sets `WINEDLLOVERRIDES=dinput8=n,b`, so the game loads our proxy. `proxy.c` forwards
the real DINPUT8 exports to the builtin so input keeps working, then starts the cheat
thread in `cheat.c`.

## How it's driven

The Android UI (`ProxyCtrl.kt`) writes one command line to `<gameDir>/cheat_ctrl.txt`;
the DLL polls it (~400ms, de-dupes by content) and writes acks to `cheat_out.txt`.

Main command — generic pointer-chain freeze (catalog-driven, works for ANY game):

    chain=<id>|<module>|<basehex>|<off1,off2,...>|<valoffhex>|<vtype i32|u32|f32>|<mode lit|max>|<valbits>

Resolution (standard Cheat-Engine semantics): `ptr = *(moduleBase + base)`, then for
each offset `ptr = *(ptr + off)`, then `valueAddr = ptr + valueOffset`. `mode=max`
writes `*valueAddr = *(maxField)` (maxField at valueAddr - valueOffset + maxOffset);
`mode=lit` writes the literal. Disable: `chainoff=<id>` / `chainclear`.

Also includes a universal value scanner for the "make your own cheat" path:
`snap`, `inc`/`dec`/`chg`, `scani=`/`scanf=`, `nexti=`/`nextf=`, `freezei=`/`freezef=`,
plus diagnostics `readrel=`/`readabs=`/`dmc3diag`/`orbscan=`.

## Build

64-bit only (the guest games are x64 PEs). MinGW-w64 (installed on the dev machine):

    x86_64-w64-mingw32-gcc -shared -O2 -s -o dinput8.dll proxy.c cheat.c -lkernel32

The resulting `dinput8.dll` is committed here AND copied to
`app/src/main/assets/cheatdll/dinput8.dll` (the APK asset that ships to the device).
After editing `cheat.c`/`proxy.c`, rebuild and re-copy the asset before building the APK.

## Not a DInput-only hook

The dinput8 name works because most games import `DINPUT8.dll`. For games that don't,
build the same source as a different proxy name the game DOES import (e.g. `dxgi.dll`,
`winmm.dll`, `version.dll`) and select it per-game in the catalog.
