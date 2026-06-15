/*
 * proxy.c — Experiment 0: a proxy dinput8.dll that proves an x86_64 Windows PE
 * DLL can be force-loaded INTO the game process (dmc3.exe) under Box64/Wine on
 * Android, via WINEDLLOVERRIDES=dinput8=n,b.
 *
 * It does TWO things:
 *   1. On DLL_PROCESS_ATTACH, write a proof file to the Wine temp dir with the
 *      PID and the module base of the host exe — visible proof we ran INSIDE the
 *      game's address space.
 *   2. Forward all real DINPUT8 exports to the genuine builtin dinput8.dll so the
 *      game's input keeps working (otherwise DInput init fails and the game dies).
 *
 * RUNTIME-PROXY design (not static .def forwarders): in DllMain we LoadLibrary
 * the real builtin dinput8 from an ABSOLUTE system32 path and GetProcAddress each
 * export, exposing thin asm-free C stubs. This is the Wine-safe variant — it does
 * not depend on a renamed *_orig.dll or on the linker resolving forwarders.
 *
 * Build (x86_64 PE):
 *   x86_64-w64-mingw32-gcc -shared -O2 -s -o dinput8.dll proxy.c dinput8.def
 *
 * DINPUT8 public exports (the full set):
 *   DirectInput8Create, DllCanUnloadNow, DllGetClassObject,
 *   DllRegisterServer, DllUnregisterServer, GetdfDIJoystick
 */
#include <windows.h>
#include <stdio.h>

/* Defined in cheat.c — starts the in-process freeze/poll thread. */
void cheat_engine_start(void);

static HMODULE g_real = NULL;

/* Pointers to the real builtin exports, resolved once at attach. */
static FARPROC p_DirectInput8Create   = NULL;
static FARPROC p_DllCanUnloadNow      = NULL;
static FARPROC p_DllGetClassObject    = NULL;
static FARPROC p_DllRegisterServer    = NULL;
static FARPROC p_DllUnregisterServer  = NULL;
static FARPROC p_GetdfDIJoystick      = NULL;

/* Write the proof file. Tries a few temp locations so at least one is writable
 * inside the Wine prefix. The Android side reads it back via run-as. */
static void write_proof(void)
{
    const wchar_t *candidates[] = {
        L"C:\\users\\steamuser\\Temp\\cheat_proof.txt",
        L"C:\\windows\\temp\\cheat_proof.txt",
        L"C:\\cheat_proof.txt",
    };

    char modpath[MAX_PATH]; modpath[0] = '\0';
    GetModuleFileNameA(NULL, modpath, sizeof(modpath));   /* the HOST exe (dmc3.exe) */
    HMODULE self = GetModuleHandleW(NULL);

    char line[1024];
    snprintf(line, sizeof(line),
             "CHEATDLL LOADED\r\npid=%lu\r\nhost_exe=%s\r\nimage_base=%p\r\nreal_dinput8=%p\r\n",
             (unsigned long)GetCurrentProcessId(),
             modpath,
             (void *)self,
             (void *)g_real);

    for (int i = 0; i < 3; i++) {
        HANDLE h = CreateFileW(candidates[i], GENERIC_WRITE, FILE_SHARE_READ,
                               NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
        if (h != INVALID_HANDLE_VALUE) {
            DWORD wrote = 0;
            WriteFile(h, line, (DWORD)strlen(line), &wrote, NULL);
            CloseHandle(h);
            /* Also emit to the debugger channel (WINEDEBUG / Box64 log). */
            OutputDebugStringA("[cheatdll] proof written");
            return;
        }
    }
    OutputDebugStringA("[cheatdll] FAILED to write any proof file");
}

static void load_real(void)
{
    /* Resolve the genuine builtin dinput8 by absolute path so we never recurse
     * into ourselves. system32 is the canonical location in the Wine prefix. */
    wchar_t sys[MAX_PATH];
    UINT n = GetSystemDirectoryW(sys, MAX_PATH);   /* ...\windows\system32 */
    if (n == 0 || n >= MAX_PATH) {
        lstrcpyW(sys, L"C:\\windows\\system32");
    }
    lstrcatW(sys, L"\\dinput8.dll");

    g_real = LoadLibraryW(sys);
    if (!g_real) {
        /* Fall back to a plain name; Wine will resolve the builtin. */
        g_real = LoadLibraryW(L"dinput8");
    }
    if (g_real) {
        p_DirectInput8Create  = GetProcAddress(g_real, "DirectInput8Create");
        p_DllCanUnloadNow     = GetProcAddress(g_real, "DllCanUnloadNow");
        p_DllGetClassObject   = GetProcAddress(g_real, "DllGetClassObject");
        p_DllRegisterServer   = GetProcAddress(g_real, "DllRegisterServer");
        p_DllUnregisterServer = GetProcAddress(g_real, "DllUnregisterServer");
        p_GetdfDIJoystick     = GetProcAddress(g_real, "GetdfDIJoystick");
    }
}

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID reserved)
{
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(inst);
        load_real();
        write_proof();
        cheat_engine_start();   /* start the in-process freeze/poll engine */
    }
    return TRUE;
}

/* ---- forwarding stubs ----
 * These match the real DINPUT8 signatures closely enough to forward. We cast the
 * resolved FARPROC to the right prototype and tail-call it. If the real export
 * was not resolved we fail gracefully (return an error HRESULT / FALSE).
 */

typedef HRESULT (WINAPI *pfnDirectInput8Create)(HINSTANCE, DWORD, REFIID, LPVOID *, LPUNKNOWN);
typedef HRESULT (WINAPI *pfnDllGetClassObject)(REFCLSID, REFIID, LPVOID *);
typedef HRESULT (WINAPI *pfnHR_void)(void);
typedef LPVOID  (WINAPI *pfnGetdfDIJoystick)(void);

__declspec(dllexport) HRESULT WINAPI DirectInput8Create(
        HINSTANCE hinst, DWORD dwVersion, REFIID riidltf, LPVOID *ppvOut, LPUNKNOWN punkOuter)
{
    if (!p_DirectInput8Create) return E_FAIL;
    return ((pfnDirectInput8Create)p_DirectInput8Create)(hinst, dwVersion, riidltf, ppvOut, punkOuter);
}

__declspec(dllexport) HRESULT WINAPI DllCanUnloadNow(void)
{
    if (!p_DllCanUnloadNow) return S_FALSE;  /* keep us loaded */
    return ((pfnHR_void)p_DllCanUnloadNow)();
}

__declspec(dllexport) HRESULT WINAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, LPVOID *ppv)
{
    if (!p_DllGetClassObject) return E_FAIL;
    return ((pfnDllGetClassObject)p_DllGetClassObject)(rclsid, riid, ppv);
}

__declspec(dllexport) HRESULT WINAPI DllRegisterServer(void)
{
    if (!p_DllRegisterServer) return E_FAIL;
    return ((pfnHR_void)p_DllRegisterServer)();
}

__declspec(dllexport) HRESULT WINAPI DllUnregisterServer(void)
{
    if (!p_DllUnregisterServer) return E_FAIL;
    return ((pfnHR_void)p_DllUnregisterServer)();
}

__declspec(dllexport) LPVOID WINAPI GetdfDIJoystick(void)
{
    if (!p_GetdfDIJoystick) return NULL;
    return ((pfnGetdfDIJoystick)p_GetdfDIJoystick)();
}
