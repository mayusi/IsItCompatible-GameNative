/*
 * crt_msvcrt_shim.h — route the few C-library calls in proxy.c/cheat.c onto the
 * OLD msvcrt.dll (a Wine builtin that is ALWAYS present and needs NO apiset/CRT
 * init), instead of the UCRT api-ms-win-crt-* apisets.
 *
 * WHY THIS EXISTS (device-diagnosed CRT-init failure):
 *   The proxy was built with the WinLibs *UCRT* mingw flavour. The resulting DLL
 *   imported api-ms-win-crt-convert/heap/private/runtime/stdio/string apisets and
 *   carried a .tls CRT directory. Its PE entry was DllMainCRTStartup -> _CRT_INIT
 *   -> DllMain. On arm64ec Wine, that UCRT _CRT_INIT could not complete in the
 *   emulated x86_64 context, so _CRT_INIT returned early and OUR DllMain body
 *   (write_proof / cheat_engine_start / chain_load_fling) NEVER RAN — no proof
 *   file, no engine, even though the DLL mapped ": native" and the entry point
 *   was correct. Classic "MinGW UCRT DLL won't init under a foreign/minimal
 *   loader" failure.
 *
 * THE FIX (two parts; this header is part 2):
 *   1) Build with `-nostartfiles -e DllMain`: DllMain itself becomes the PE entry
 *      point, so there is NO DllMainCRTStartup / _CRT_INIT to fail. DllMain runs
 *      directly on load. (Done in the gcc link line / README.)
 *   2) Link the C-library calls against msvcrt.dll, NOT the UCRT apisets. We link
 *      `-nodefaultlibs -lmsvcrt -lkernel32` (NO -lucrt, NO -lmingwex). msvcrt.dll
 *      directly exports atof/strtol/strtoul/strtoull/_strtoui64/memset/memcpy/
 *      strchr/strrchr/strcmp/strncmp/strncpy/strlen/_snprintf/_vsnprintf — all of
 *      which Wine's builtin msvcrt provides with zero apiset/CRT init.
 *
 * THE ONE MISMATCH this header papers over:
 *   The sources call C99 `snprintf`, but msvcrt.dll only exports `_snprintf` /
 *   `_vsnprintf` (which, unlike C99, do NOT guarantee NUL-termination on
 *   truncation and return -1 instead of the needed length). Pulling in mingwex's
 *   C99 `snprintf` would drag the api-ms-win-crt-stdio apiset back in (via
 *   __ms_vsnprintf). So we define our own tiny `snprintf` that calls msvcrt's
 *   `_vsnprintf` and then enforces C99 NUL-termination. That keeps the import
 *   table on msvcrt.dll only.
 *
 * Include this AFTER <windows.h>/<stdio.h>/<string.h>/<stdint.h> in proxy.c and
 * cheat.c. It defines nothing the game ever sees; it only affects our own object
 * code's libc binding.
 */
#ifndef GN_CRT_MSVCRT_SHIM_H
#define GN_CRT_MSVCRT_SHIM_H

#include <stdarg.h>
#include <stddef.h>

/* msvcrt.dll's native vsnprintf. It writes at most `count` bytes and may NOT
 * NUL-terminate when the output is truncated; it returns -1 on truncation.
 * Declared here because we are building -nodefaultlibs (no full CRT headers in
 * scope for this symbol) and we resolve it from -lmsvcrt at link time. */
int _vsnprintf(char *buffer, size_t count, const char *format, va_list argptr);

/* C99-compatible snprintf implemented over msvcrt's _vsnprintf. Guarantees the
 * buffer is always NUL-terminated when size > 0 (the property proxy.c/cheat.c
 * rely on; they use the result only for fixed-size status strings, never to size
 * an allocation, so the exact C99 return value is not load-bearing here). */
#ifdef snprintf
#undef snprintf
#endif
#define snprintf gn_snprintf

static int gn_snprintf(char *buf, size_t size, const char *fmt, ...)
{
    va_list ap;
    int n;
    if (size == 0)
        return 0;
    va_start(ap, fmt);
    n = _vsnprintf(buf, size, fmt, ap);
    va_end(ap);
    /* _vsnprintf does not NUL-terminate on truncation -> force it (C99 semantics). */
    if (n < 0 || (size_t)n >= size) {
        buf[size - 1] = '\0';
        n = (int)(size - 1);
    }
    return n;
}

#endif /* GN_CRT_MSVCRT_SHIM_H */
