@ECHO OFF
SETLOCAL EnableDelayedExpansion
TITLE Buchla 218e V3 - PressureFix firmware

REM Experimental Buchla 218e V3 v36.9 firmware flasher for Windows.
REM
REM Mirrors ProgramLEM218_PressureFix.command: it verifies the image checksum,
REM confirms the bootloader region is protected, programs, and validates by
REM read-back before it lets the instrument leave DFU.  Buchla's own
REM ProgramLEM218.bat does none of those and flashes whatever .hex it finds
REM first, which is why this is a separate script.

SET "EXPECTED_SHA256=e0edd5fbd33eb1e9fda4920c3521b7416b473b06033c2094b9d2b57ed76fd1cc"
SET "DFU_SESSION_ACTIVE=0"
SET "FLASH_VALIDATED=0"
SET "SCRIPT_DIR=%~dp0"
SET "LOG_FILE=%SCRIPT_DIR%LEM218_PressureFix_fwflash_win_log.txt"

REM The Windows tools come from Buchla's own kit; this repository does not
REM redistribute them.  Support being run from the package root or windows\.
IF EXIST "%SCRIPT_DIR%windows\support\dfu-programmer.exe" (
    SET "TOOLS=%SCRIPT_DIR%windows\support"
    SET "PACKAGE_ROOT=%SCRIPT_DIR%"
) ELSE IF EXIST "%SCRIPT_DIR%support\dfu-programmer.exe" (
    SET "TOOLS=%SCRIPT_DIR%support"
    SET "PACKAGE_ROOT=%SCRIPT_DIR%..\"
) ELSE (
    ECHO Could not find windows\support\dfu-programmer.exe
    ECHO.
    ECHO The Windows flashing tools are part of Buchla's official kit and are
    ECHO not redistributed here.  Copy that kit's windows\ folder in beside
    ECHO this script.
    GOTO :fail_early
)

SET "FIRMWARE=%PACKAGE_ROOT%firmware\218eV3_v369_PressureFix_DFU.hex"
SET "DFU=%TOOLS%\dfu-programmer.exe"
SET "SENDMIDI=%TOOLS%\sendmidi.exe"

ECHO [%DATE% %TIME%] Starting PressureFix programming.> "%LOG_FILE%"

IF NOT EXIST "%FIRMWARE%" (
    ECHO No firmware image found at:
    ECHO   %FIRMWARE%
    ECHO.
    ECHO None ships with this package: the patched image is Buchla's firmware
    ECHO with our changes in it, so it is not ours to redistribute.  Build it
    ECHO from your own copy of the factory image:
    ECHO.
    ECHO   1. copy your 218eV3_v369_DFU.hex into firmware\
    ECHO   2. python tools\build.py --no-ghidra
    ECHO.
    GOTO :fail_early
)

REM --- checksum, before anything is touched -----------------------------
SET "ACTUAL_SHA256="
FOR /F "skip=1 tokens=* delims=" %%H IN ('certutil -hashfile "%FIRMWARE%" SHA256 ^| findstr /R "^[0-9a-f ]*$"') DO (
    IF NOT DEFINED ACTUAL_SHA256 SET "ACTUAL_SHA256=%%H"
)
SET "ACTUAL_SHA256=!ACTUAL_SHA256: =!"
IF /I NOT "!ACTUAL_SHA256!"=="%EXPECTED_SHA256%" (
    ECHO Firmware checksum mismatch; refusing to erase the instrument.
    ECHO   expected %EXPECTED_SHA256%
    ECHO   found    !ACTUAL_SHA256!
    GOTO :fail_early
)
ECHO Verified patched firmware SHA-256: !ACTUAL_SHA256!
ECHO Verified SHA-256 !ACTUAL_SHA256!>> "%LOG_FILE%"

ECHO.
ECHO This is EXPERIMENTAL firmware based on Buchla 218e V3 v36.9.
REM --- BEGIN GENERATED SUMMARY (tools/build.py rewrites this block) ---
ECHO Ordinary edit mode provides the pressure calibration:
ECHO   knob 1 = pressure calibration, scaling both endpoints (592/893 at centre)
ECHO   knob 3 = factory behaviour
ECHO   knob 4 = curve, linear (left) to full 218r (right), default 0
ECHO Outside edit mode all four knobs keep their factory behaviour.
ECHO.
ECHO Calibrating, in ordinary edit mode:
ECHO   1. Knob 4 fully left for a linear response.
ECHO   2. Run ReadLEM218_Pressure.command; with no key held, turn knob 1
ECHO      and type 'settings' until floor/ceiling read near 592/893 - the built-in calibration,
ECHO      at about 78%% of knob travel.
ECHO   3. Play light/mid/max touches; knob 1 scales the whole window,
ECHO      so one control follows a change in how the instrument couples.
ECHO   4. Turn knob 4 right to taste, then leave edit mode to save.
REM --- END GENERATED SUMMARY ---
ECHO.
ECHO Before continuing:
ECHO   - use stable instrument power; do not switch off the boat
ECHO   - connect USB directly if possible; avoid a loose cable or unpowered hub
ECHO   - do not unplug anything until this script reports verified success
ECHO.
PAUSE

REM --- into DFU ----------------------------------------------------------
"%DFU%" at32uc3b1256 get bootloader-version >NUL 2>&1
IF NOT ERRORLEVEL 1 (
    ECHO The 218e is already in DFU mode.
    GOTO :in_dfu
)

"%SENDMIDI%" list 2>&1 | findstr /I "218e" >NUL
IF ERRORLEVEL 1 (
    ECHO The 218e MIDI port is unavailable.  Nothing was erased; power-cycle the
    ECHO 218e, reconnect USB directly, and retry.
    GOTO :fail_early
)
ECHO Asking the 218e to enter DFU mode over MIDI.
"%SENDMIDI%" dev 218e syx 0 2 55 2 1 1 >> "%LOG_FILE%" 2>&1
IF ERRORLEVEL 1 (
    ECHO SendMIDI could not deliver the DFU request.  Nothing was erased;
    ECHO power-cycle the 218e and retry.
    GOTO :fail_early
)

ECHO Waiting for the AT32UC3B DFU device to appear on USB.
SET "FOUND=0"
FOR /L %%I IN (1,1,30) DO (
    IF "!FOUND!"=="0" (
        "%DFU%" at32uc3b1256 get bootloader-version >NUL 2>&1
        IF NOT ERRORLEVEL 1 SET "FOUND=1"
        IF "!FOUND!"=="0" PING -n 3 127.0.0.1 >NUL
    )
)
IF "!FOUND!"=="0" (
    ECHO The AT32UC3B DFU device did not appear.
    ECHO.
    ECHO On Windows the DFU device needs the WinUSB driver.  Run
    ECHO   %TOOLS%\zadig-2.8.exe
    ECHO select the AT32UC3B DFU device, install WinUSB, then run this again.
    ECHO Nothing was erased.
    GOTO :fail_early
)

:in_dfu
SET "DFU_SESSION_ACTIVE=1"
ECHO AT32UC3B DFU device detected.

REM Each accepted ISP command sets ISP_FORCE=1.  START is the only operation
REM below that clears it, and it runs only after read-back validation passes.
REM That is what makes an interrupted flash recoverable over USB.
ECHO Reading bootloader version and safety fuses before erase.
"%DFU%" at32uc3b1256 get bootloader-version >> "%LOG_FILE%" 2>&1

CALL :read_fuse BOOTPROT
IF NOT "!FUSE_VALUE!"=="3" (
    ECHO BOOTPROT is "!FUSE_VALUE!", not 3 ^(8 KiB^); refusing to erase.
    GOTO :recovery_safe_stop
)
ECHO Verified BOOTPROT=3: the 8 KiB DFU bootloader region is protected.

CALL :read_fuse ISP_FORCE
IF NOT "!FUSE_VALUE!"=="1" (
    ECHO ISP_FORCE did not read back as 1; refusing to erase.
    GOTO :recovery_safe_stop
)
ECHO Verified ISP_FORCE=1: an interrupted session should boot back into DFU.

ECHO.
ECHO Ready to erase the application flash.
PAUSE

ECHO Erasing AT32UC3B1256 application flash.
"%DFU%" at32uc3b1256 erase >> "%LOG_FILE%" 2>&1
IF ERRORLEVEL 1 (
    ECHO Chip erase failed.
    GOTO :recovery_safe_stop
)

ECHO Writing firmware, with read-back validation.
"%DFU%" at32uc3b1256 flash --suppress-bootloader-mem "%FIRMWARE%" >> "%LOG_FILE%" 2>&1
IF ERRORLEVEL 1 (
    ECHO Firmware programming failed.  Do not disconnect; run this script again
    ECHO while the unit remains in DFU mode.
    GOTO :recovery_safe_stop
)

SET "FLASH_VALIDATED=1"
ECHO Programming and read-back validation completed successfully.
ECHO.
ECHO The patched application has passed read-back validation.
ECHO Only now is it safe to leave DFU mode.
PAUSE

"%DFU%" at32uc3b1256 start >> "%LOG_FILE%" 2>&1
IF ERRORLEVEL 1 (
    ECHO The DFU start command failed.
    GOTO :recovery_safe_stop
)
SET "DFU_SESSION_ACTIVE=0"

ECHO.
ECHO PressureFix flashing is complete.
ECHO If the 218e does not reappear, power-cycle the instrument.
PAUSE
ENDLOCAL
EXIT /B 0

REM --- helpers -----------------------------------------------------------
:read_fuse
SET "FUSE_VALUE="
FOR /F "tokens=* delims=" %%L IN ('"%DFU%" at32uc3b1256 getfuse %1 2^>^&1') DO (
    ECHO %%L>> "%LOG_FILE%"
    FOR %%T IN (%%L) DO SET "FUSE_VALUE=%%T"
)
EXIT /B 0

:recovery_safe_stop
ECHO.
ECHO RECOVERY-SAFE STOP
ECHO Do NOT run "start".  Do NOT disconnect or power-cycle the 218e.
ECHO Leave it in DFU mode and run this script again.
ECHO If power was already lost, reconnect power: ISP_FORCE should return it to DFU.
ECHO No START command was sent.>> "%LOG_FILE%"
PAUSE
ENDLOCAL
EXIT /B 1

:fail_early
ECHO.
ECHO Nothing was erased.
PAUSE
ENDLOCAL
EXIT /B 1
