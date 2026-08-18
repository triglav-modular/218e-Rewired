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

SET "EXPECTED_SHA256=24b76ba0aa5610c81dbb0609f3615e48ff4900366adcbc8879156a8e01a422a7"
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

SET "FIRMWARE_DIR=%PACKAGE_ROOT%firmware"
SET "FIRMWARE_NAME=218eV3_v369_PressureFix_DFU.hex"
SET "FIRMWARE="
SET "TOTAL_STEPS=6"
SET "STEP=0"

REM --- find the image ----------------------------------------------------
REM Searching is safe because the checksum decides: only an image matching the
REM one this flasher was generated for is accepted, so a stray .hex is skipped
REM rather than flashed.  That lets a downloaded file be used where it landed.
CALL :step Locating the firmware image

CALL :try_image "%FIRMWARE_DIR%\%FIRMWARE_NAME%"
CALL :try_image "%SCRIPT_DIR%%FIRMWARE_NAME%"
CALL :try_image "%USERPROFILE%\Downloads\%FIRMWARE_NAME%"
CALL :try_image "%USERPROFILE%\Desktop\%FIRMWARE_NAME%"

REM Then any recent .hex in Downloads or on the Desktop, so a browser that
REM renamed the file to "...(1).hex" still works.
FOR /F "delims=" %%F IN ('DIR /B /O-D "%USERPROFILE%\Downloads\*.hex" 2^>NUL') DO (
    CALL :try_image "%USERPROFILE%\Downloads\%%F"
)
FOR /F "delims=" %%F IN ('DIR /B /O-D "%USERPROFILE%\Desktop\*.hex" 2^>NUL') DO (
    CALL :try_image "%USERPROFILE%\Desktop\%%F"
)

IF NOT DEFINED FIRMWARE (
    ECHO.
    ECHO   Looked in firmware\, beside this script, Downloads and Desktop.
    ECHO   Nothing there matches the image this flasher installs:
    ECHO     %EXPECTED_SHA256%
    ECHO.
    ECHO   No firmware ships with this package - the patched image is Buchla's
    ECHO   firmware with our changes in it, so it is not ours to redistribute.
    ECHO   Build one from your own factory image with the page in web\, or:
    ECHO     python tools\build.py --no-ghidra
    ECHO.
    GOTO :fail_early
)
CALL :ok Found !FIRMWARE!
CALL :ok Checksum matches the image this flasher installs

REM Keep it where it belongs, so the next run finds it first.  A failure here
REM is not fatal: the image is already verified.
IF /I NOT "!FIRMWARE!"=="%FIRMWARE_DIR%\%FIRMWARE_NAME%" (
    IF NOT EXIST "%FIRMWARE_DIR%" MKDIR "%FIRMWARE_DIR%" 2>NUL
    COPY /Y "!FIRMWARE!" "%FIRMWARE_DIR%\%FIRMWARE_NAME%" >NUL 2>&1
    IF NOT ERRORLEVEL 1 (
        SET "FIRMWARE=%FIRMWARE_DIR%\%FIRMWARE_NAME%"
        CALL :ok Copied into firmware\
    )
)
ECHO Using !FIRMWARE!>> "%LOG_FILE%"
ECHO.
ECHO This is EXPERIMENTAL firmware based on Buchla 218e V3 v36.9.
REM --- BEGIN GENERATED SUMMARY (tools/build.py rewrites this block) ---
ECHO Ordinary edit mode provides the pressure calibration:
ECHO   knob 1 = pressure calibration, scaling both endpoints (592/893 at centre)
ECHO   knob 3 = factory behaviour
ECHO   knob 4 = curve, linear (left) to full 218r (right), default 0
ECHO Outside edit mode those knobs control the arpeggiator and vibrato.
ECHO Arp switch: latch / regular / off. In latch, keys toggle by
ECHO sounding pitch, so any octave position can release a note.
ECHO Portamento knob = pressure needed to bend between held notes.
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
CALL :step Putting the instrument into DFU
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
CALL :step Checking the bootloader and safety fuses
"%DFU%" at32uc3b1256 get bootloader-version >> "%LOG_FILE%" 2>&1

CALL :read_fuse BOOTPROT
IF NOT "!FUSE_VALUE!"=="3" (
    ECHO BOOTPROT is "!FUSE_VALUE!", not 3 ^(8 KiB^); refusing to erase.
    GOTO :recovery_safe_stop
)
CALL :ok BOOTPROT=3 - the 8 KiB bootloader region is protected

CALL :read_fuse ISP_FORCE
IF NOT "!FUSE_VALUE!"=="1" (
    ECHO ISP_FORCE did not read back as 1; refusing to erase.
    GOTO :recovery_safe_stop
)
CALL :ok ISP_FORCE=1 - an interrupted session boots back into DFU

ECHO.
ECHO Ready to erase the application flash.
PAUSE

CALL :step Erasing the application flash
"%DFU%" at32uc3b1256 erase >> "%LOG_FILE%" 2>&1
IF ERRORLEVEL 1 (
    ECHO Chip erase failed.
    GOTO :recovery_safe_stop
)

CALL :step Writing and validating the firmware
"%DFU%" at32uc3b1256 flash --suppress-bootloader-mem "%FIRMWARE%" >> "%LOG_FILE%" 2>&1
IF ERRORLEVEL 1 (
    ECHO Firmware programming failed.  Do not disconnect; run this script again
    ECHO while the unit remains in DFU mode.
    GOTO :recovery_safe_stop
)

SET "FLASH_VALIDATED=1"
CALL :ok Written and validated by read-back
CALL :step Restarting the instrument
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
ECHO   Flashing complete.  Log: %LOG_FILE%
ECHO If the 218e does not reappear, power-cycle the instrument.
PAUSE
ENDLOCAL
EXIT /B 0

REM --- helpers -----------------------------------------------------------
:step
SET /A STEP+=1
ECHO.
ECHO [!STEP!/%TOTAL_STEPS%] %*
ECHO === step !STEP!/%TOTAL_STEPS%: %*>> "%LOG_FILE%"
EXIT /B 0

:ok
ECHO   [ok] %*
ECHO OK: %*>> "%LOG_FILE%"
EXIT /B 0

:try_image
IF DEFINED FIRMWARE EXIT /B 0
IF NOT EXIST "%~1" EXIT /B 0
SET "_SHA="
FOR /F "skip=1 tokens=* delims=" %%H IN ('certutil -hashfile "%~1" SHA256 ^| findstr /R "^[0-9a-f ]*$"') DO (
    IF NOT DEFINED _SHA SET "_SHA=%%H"
)
SET "_SHA=!_SHA: =!"
IF /I "!_SHA!"=="%EXPECTED_SHA256%" SET "FIRMWARE=%~1"
EXIT /B 0

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
