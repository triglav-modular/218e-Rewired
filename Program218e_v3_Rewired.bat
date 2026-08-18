@ECHO OFF
SETLOCAL EnableDelayedExpansion
TITLE Buchla 218e V3 - Rewired firmware

REM Experimental Buchla 218e V3 v36.9 firmware flasher for Windows.
REM
REM Mirrors Program218e_v3_Rewired.command: it verifies the image checksum,
REM confirms the bootloader region is protected, programs, and validates by
REM read-back before it lets the instrument leave DFU.  Buchla's own
REM ProgramLEM218.bat does none of those and flashes whatever .hex it finds
REM first, which is why this is a separate script.

SET "EXPECTED_SHA256=9474624bdaa85e20502e65f67471f500879ceda1bbc08bcd9aa5d59394bfe391"
SET "FIRMWARE_VERSION=Rewired 1.0.0 (9474624b)"
SET "DFU_SESSION_ACTIVE=0"
SET "FLASH_VALIDATED=0"
SET "ERASE_STARTED=0"
SET "SCRIPT_DIR=%~dp0"
SET "LOG_FILE=%SCRIPT_DIR%218e_v3_Rewired_flash_log_win.txt"

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
    ECHO That folder ships with this package and contains the flashing tools.
    ECHO If it is missing, re-download the package rather than assembling it
    ECHO by hand - the tools have to sit together for Windows to find the
    ECHO DLLs beside them.
    GOTO :fail_early
)

SET "FIRMWARE_DIR=%PACKAGE_ROOT%firmware"
SET "FIRMWARE_NAME=218eV3_v369_Rewired_DFU.hex"
SET "FIRMWARE="
SET "TOTAL_STEPS=7"
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
CALL :ok %FIRMWARE_VERSION%

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
ECHO ======================================================================
ECHO   THIS IS FOR THE BUCHLA 218e VERSION 3 ONLY, RUNNING v36.9
ECHO.
ECHO   Not the 218.  Not the 218r.  Not the 218e v1 or v2.  Not any other
ECHO   touchplate controller.  Flashing this into anything else will not
ECHO   work and may leave it unusable.
ECHO.
ECHO   YOU DO THIS ENTIRELY AT YOUR OWN RISK.
ECHO.
ECHO   This is experimental, unofficial firmware.  It is not made by or
ECHO   supported by Buchla.  It has been tested on ONE instrument.  It can
ECHO   brick your keyboard.  Recovery may need JTAG hardware and opening
ECHO   the instrument, and may not be possible at all.
ECHO.
ECHO   No warranty of any kind.  Nobody is liable for damage, loss of use,
ECHO   or a keyboard that no longer works.
ECHO ======================================================================
ECHO.
SET "CONSENT="
SET /P "CONSENT=  Type YES (capitals) to accept and continue: "
IF NOT "%CONSENT%"=="YES" (
    ECHO   Not confirmed.  Nothing was changed.
    GOTO :fail_early
)

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
ECHO   2. Run ReadLEM218_Rewired.command; with no key held, turn knob 1
ECHO      and type 'settings' until floor/ceiling read near 592/893 - the built-in calibration,
ECHO      at about 78%% of knob travel.
ECHO   3. Play light/mid/max touches; knob 1 scales the whole window,
ECHO      so one control follows a change in how the instrument couples.
ECHO   4. Turn knob 4 right to taste, then leave edit mode to save.
REM --- END GENERATED SUMMARY ---
ECHO.
ECHO Before continuing:
ECHO   - the DFU device must be bound to WinUSB, or this cannot see the
ECHO     instrument at all.  If you have not done that on this machine, run
ECHO       %TOOLS%\zadig-2.8.exe
ECHO     pick the AT32UC3B DFU device and install WinUSB.  Once per machine.
ECHO   - use stable instrument power; do not switch off the boat
ECHO   - connect USB directly if possible; avoid a loose cable or unpowered hub
ECHO   - do not unplug anything until this script reports verified success
ECHO.
PAUSE

REM --- can we talk to the bootloader at all? ------------------------------
REM This runs BEFORE the SysEx.  Windows will not let dfu-programmer near the
REM DFU device until it is bound to WinUSB, and there is no way to probe that
REM binding while the device is absent - so the question has to be asked.
REM Getting it wrong the other way round means the instrument reboots into a
REM bootloader nothing here can reach: recoverable with a power cycle, but
REM avoidable entirely.
CALL :step Checking the DFU tools

"%DFU%" at32uc3b1256 get bootloader-version >"%TEMP%\rewired_probe.txt" 2>&1
SET "PROBE_RC=%ERRORLEVEL%"
FINDSTR /I /C:"no device present" "%TEMP%\rewired_probe.txt" >NUL 2>&1
SET "PROBE_ABSENT=%ERRORLEVEL%"
DEL "%TEMP%\rewired_probe.txt" >NUL 2>&1
IF NOT "%PROBE_RC%"=="0" IF NOT "%PROBE_ABSENT%"=="0" (
    ECHO   dfu-programmer.exe would not run.  Check that Buchla's windows\ kit
    ECHO   is beside this script and that its VC++ redistributables are
    ECHO   installed ^(support\VC_redist.x64.exe^).
    GOTO :fail_early
)
CALL :ok dfu-programmer.exe runs


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
REM Not appearing almost always means one thing on Windows: the DFU device is
REM not bound to WinUSB, so libusb cannot open it.  Zadig fixes that, and it
REM can only do so while the device is present - which it is, right now.  So
REM launch it here rather than sending anyone away to read instructions.
IF "!FOUND!"=="0" (
    ECHO.
    ECHO   The AT32UC3B DFU device is not reachable yet.
    ECHO.
    ECHO   On Windows that nearly always means the DFU device still needs the
    ECHO   WinUSB driver.  Zadig can install it, and the keyboard is in DFU
    ECHO   right now, which is the only time Zadig can see it.
    ECHO.
    ECHO   Zadig is GPLv3, from https://zadig.akeo.ie/ , and ships with
    ECHO   Buchla's kit.  Nothing has been erased.
    ECHO.
    PAUSE
    IF NOT EXIST "%TOOLS%\zadig-2.8.exe" (
        ECHO   zadig-2.8.exe is not in %TOOLS%.
        ECHO   Copy Buchla's windows\ folder in beside this script and retry.
        GOTO :dfu_unreachable
    )
    ECHO   Starting Zadig.  In its window:
    ECHO     1. Options - List All Devices, if the list looks empty
    ECHO     2. select the AT32UC3B DFU device
    ECHO     3. choose WinUSB and press Install Driver
    ECHO     4. close Zadig and come back here
    ECHO.
    START /WAIT "" "%TOOLS%\zadig-2.8.exe"
    ECHO.
    PAUSE
    ECHO   Looking for the DFU device again...
    FOR /L %%I IN (1,1,10) DO (
        IF "!FOUND!"=="0" (
            "%DFU%" at32uc3b1256 get bootloader-version >NUL 2>&1
            IF NOT ERRORLEVEL 1 SET "FOUND=1"
            IF "!FOUND!"=="0" PING -n 2 127.0.0.1 >NUL
        )
    )
)

IF "!FOUND!"=="0" GOTO :dfu_unreachable
GOTO :in_dfu

:dfu_unreachable
ECHO.
ECHO   Still cannot reach the DFU device.
ECHO.
ECHO   Nothing was erased and nothing was written.  The keyboard is most
ECHO   likely still in DFU, which is why it has vanished from MIDI.
ECHO   Power-cycle it and it comes back up normally.
ECHO.
ECHO   Worth checking: USB connected directly rather than through a hub, and
ECHO   that WinUSB really was installed against the AT32UC3B DFU device
ECHO   rather than another entry in the Zadig list.
GOTO :fail_early

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
ECHO   Ready to erase the application flash.
ECHO.
ECHO   From here on, if anything goes wrong - including closing this window or
ECHO   pressing Ctrl-C - the instrument stays in DFU mode, because START is
ECHO   only sent after the write has been validated.  Reconnect power if you
ECHO   have to, then run this script again.
ECHO.
PAUSE

CALL :step Erasing the application flash
SET "ERASE_STARTED=1"
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

REM A record of what is actually on the instrument, beside the image.
> "%FIRMWARE_DIR%\INSTALLED.txt" ECHO %FIRMWARE_VERSION%
>> "%FIRMWARE_DIR%\INSTALLED.txt" ECHO flashed  %DATE% %TIME%
>> "%FIRMWARE_DIR%\INSTALLED.txt" ECHO image    %EXPECTED_SHA256%

ECHO.
ECHO   Flashing complete.  %FIRMWARE_VERSION% is now on the instrument.
ECHO   Log: %LOG_FILE%
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
IF "!FLASH_VALIDATED!"=="1" GOTO :started_but_not_launched
ECHO.
ECHO ======================================================================
ECHO   RECOVERY-SAFE STOP
ECHO   No START command was sent, so the 218e is still in DFU.
ECHO.
IF "!ERASE_STARTED!"=="0" (
    REM Nothing erased: the application is intact.  A power cycle will NOT
    REM bring it back, because reading the fuses set ISP_FORCE - START does.
    ECHO   Your firmware was not touched.  Nothing was erased.
    ECHO.
    ECHO   To put the instrument back to normal right now:
    ECHO     "%DFU%" at32uc3b1256 start
    ECHO.
    ECHO   Power-cycling alone will not do it: reading the fuses set ISP_FORCE,
    ECHO   so the 218e returns to DFU until START is sent.  Or just run this
    ECHO   script again to try the flash.
    ECHO Stopped before erase; application intact; START not sent.>> "%LOG_FILE%"
) ELSE (
    ECHO   The application flash has been erased.
    ECHO.
    ECHO   Do NOT send START and do not expect it to boot - there is nothing
    ECHO   to boot yet.  Leave it in DFU and run this script again to finish
    ECHO   the flash.  If power was lost, reconnect it: ISP_FORCE returns the
    ECHO   instrument to DFU.
    ECHO Stopped after erase; application not valid; START not sent.>> "%LOG_FILE%"
)
ECHO ======================================================================
PAUSE
ENDLOCAL
EXIT /B 1

:started_but_not_launched
REM The image is written and read-back validated; only the launch failed, so
REM there is nothing to recover - the instrument just has not left DFU yet.
ECHO.
ECHO   The firmware IS written and has passed read-back validation.
ECHO   Only the restart command failed.  Power-cycle the 218e and it should
ECHO   come up on the new firmware; if it returns to DFU instead, run this
ECHO   script again.
ECHO Flash validated; START failed.>> "%LOG_FILE%"
PAUSE
ENDLOCAL
EXIT /B 1

:fail_early
REM Defensive: every current path here runs before the DFU session opens, but
REM if one ever does not, "nothing was erased" would be a dangerous thing to
REM print.  Hand those to the recovery-safe stop instead.
IF "!DFU_SESSION_ACTIVE!"=="1" GOTO :recovery_safe_stop
ECHO.
ECHO Nothing was erased.  The instrument was not touched.
PAUSE
ENDLOCAL
EXIT /B 1
