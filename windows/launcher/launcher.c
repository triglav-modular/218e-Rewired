/* The double-clickable face of the Windows flasher.
 *
 * The flasher is a batch file, and a batch file has nowhere to put an icon
 * or a signature - the format has no slot for either.  This launcher exists
 * to carry both: it finds Program218e_v3_Rewired_Windows.bat beside itself,
 * runs it in the console this process was given, waits, and hands back the
 * script's exit code.  Nothing else.  Running the .bat directly stays
 * exactly equivalent.
 *
 * Console subsystem on purpose: double-clicking opens a console window and
 * the script inherits it, prompts and menu included.
 *
 * Built by tools/make-launcher.sh with mingw-w64.
 */
#include <windows.h>
#include <stdio.h>
#include <wchar.h>

static const wchar_t SCRIPT[] = L"Program218e_v3_Rewired_Windows.bat";

int wmain(void)
{
    /* The script lives beside this executable, not beside whatever the
     * current directory happens to be - a shortcut can point anywhere. */
    wchar_t path[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, path, MAX_PATH);
    if (n == 0 || n >= MAX_PATH)
        return 1;
    wchar_t *slash = wcsrchr(path, L'\\');
    if (slash == NULL)
        return 1;
    slash[1] = L'\0';
    if (wcslen(path) + wcslen(SCRIPT) + 1 > MAX_PATH)
        return 1;
    wcscat(path, SCRIPT);

    if (GetFileAttributesW(path) == INVALID_FILE_ATTRIBUTES) {
        fwprintf(stderr,
                 L"%ls\n"
                 L"is not beside this launcher.  The launcher only starts that\n"
                 L"script - keep the whole package together, or re-download it.\n",
                 path);
        /* Double-clicked, the window would close before the line is read. */
        system("pause");
        return 1;
    }

    /* cmd /c with the whole quoted path wrapped in one more pair of quotes,
     * which is cmd's own convention for a quoted command line.  ComSpec
     * rather than a bare "cmd.exe" so PATH cannot substitute another one. */
    wchar_t shell[MAX_PATH] = L"cmd.exe";
    GetEnvironmentVariableW(L"ComSpec", shell, MAX_PATH);
    wchar_t cmd[2 * MAX_PATH + 16];
    _snwprintf(cmd, sizeof cmd / sizeof cmd[0], L"\"%ls\" /c \"\"%ls\"\"",
               shell, path);

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof si);
    si.cb = sizeof si;
    if (!CreateProcessW(NULL, cmd, NULL, NULL, TRUE, 0, NULL, NULL, &si, &pi)) {
        fwprintf(stderr, L"Could not start cmd.exe (error %lu).\n",
                 GetLastError());
        system("pause");
        return 1;
    }
    CloseHandle(pi.hThread);
    WaitForSingleObject(pi.hProcess, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hProcess);
    return (int)code;
}
