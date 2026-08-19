@ECHO OFF
SETLOCAL EnableDelayedExpansion
TITLE Buchla 218e V3 - Rewired firmware

REM Experimental Buchla 218e V3 v36.9 firmware flasher for Windows.
REM
REM Mirrors Program218e_v3_Rewired_macOS.command: it verifies the image checksum,
REM confirms the bootloader region is protected, programs, and validates by
REM read-back before it lets the instrument leave DFU.  Buchla's own
REM ProgramLEM218.bat does none of those and flashes whatever .hex it finds
REM first, which is why this is a separate script.

SET "EXPECTED_SHA256=9474624bdaa85e20502e65f67471f500879ceda1bbc08bcd9aa5d59394bfe391"
REM Buchla's own v369 image.  Recognised so that going back to stock is an
REM offered choice rather than something to be identified by hand.
SET "FACTORY_SHA256=565f2d0c3466edfd13ddc1626cb7a74204723ff3a01f65eac34a9db99901dd47"
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

SET "DFU=%TOOLS%\dfu-programmer.exe"
SET "SENDMIDI=%TOOLS%\sendmidi.exe"
SET "FIRMWARE_DIR=%PACKAGE_ROOT%firmware"
SET "FIRMWARE_NAME=218eV3_v369_Rewired_DFU.hex"
SET "FIRMWARE="
SET "TOTAL_STEPS=7"
SET "STEP=0"

ECHO.
ECHO ======================================================================
ECHO   THIS FIRMWARE IS ONLY FOR THE BUCHLA 218e V3
ECHO.
ECHO   It won't work on the 218, the 218r, the 218e v1 or v2, or any other
ECHO   touchplate controller.
ECHO.
ECHO   YOU DO THIS ENTIRELY AT YOUR OWN RISK.
ECHO.
ECHO   This is experimental, unofficial firmware, not made or supported by
ECHO   Buchla.  It has been tested on ONE instrument.  It can brick your
ECHO   keyboard.  Recovering a bricked unit may need JTAG hardware and
ECHO   opening the instrument, and may not be possible at all.
ECHO.
ECHO   A failed flash usually leaves the keyboard in DFU mode, where the
ECHO   flasher can try again, but there is no guarantee that it will
ECHO   succeed.  If losing the use of your 218e would be a problem, stop
ECHO   here and keep the factory firmware.
ECHO.
ECHO   No warranty of any kind.  Not the authors, not Buchla, nobody is
ECHO   liable for damage, loss of use, or a keyboard that no longer works.
ECHO ======================================================================
ECHO.
SET "CONSENT="
SET /P "CONSENT=  Type YES (capitals) to accept and continue: "
IF NOT "%CONSENT%"=="YES" (
    ECHO   Not confirmed.  Nothing was changed.
    GOTO :fail_early
)

REM --- find the image ----------------------------------------------------
REM Every .hex in reach is validated and listed newest first.  When more than
REM one is flashable the choice is made explicitly: picking silently is how the
REM wrong firmware gets installed, because a stale build in firmware\ would
REM always win on checksum alone.
CALL :step Locating the firmware image

SET "IMG_COUNT=0"
FOR /F "tokens=1,2,* delims=|" %%A IN ('powershell -NoProfile -ExecutionPolicy Bypass -File "%PACKAGE_ROOT%tools\Scan-Images.ps1" -DirList "%FIRMWARE_DIR%;%SCRIPT_DIR%.;%USERPROFILE%\Downloads;%USERPROFILE%\Desktop" 2^>NUL') DO (
    SET /A IMG_COUNT+=1
    CALL SET "IMG_WHEN_%%IMG_COUNT%%=%%A"
    CALL SET "IMG_SHA_%%IMG_COUNT%%=%%B"
    CALL SET "IMG_PATH_%%IMG_COUNT%%=%%C"
)

IF "%IMG_COUNT%"=="0" GOTO :no_image

SET "PICK=1"
IF %IMG_COUNT% GTR 1 (
    ECHO.
    ECHO   %IMG_COUNT% flashable images found.  Newest first:
    ECHO.
    FOR /L %%I IN (1,1,%IMG_COUNT%) DO (
        SET "MARK="
        IF /I "!IMG_SHA_%%I!"=="%EXPECTED_SHA256%" SET "MARK=  ^<- the default Rewired build"
        IF /I "!IMG_SHA_%%I!"=="%FACTORY_SHA256%"  SET "MARK=  ^<- FACTORY firmware, back to stock v369"
        ECHO     %%I^) !IMG_WHEN_%%I!   !IMG_SHA_%%I:~0,8!
        ECHO        !IMG_PATH_%%I!!MARK!
    )
    ECHO.
    SET "PICK="
    SET /P "PICK=  Which one? [1-%IMG_COUNT%, or Enter to stop] "
    IF NOT DEFINED PICK (
        ECHO   Nothing chosen.
        GOTO :fail_early
    )
    REM Reject anything that is not a plain number in range, rather than
    REM letting SET /A turn junk into 0 and index nothing.
    ECHO !PICK!| FINDSTR /R "^[1-9][0-9]*$" >NUL || (
        ECHO   Not a number.
        GOTO :fail_early
    )
    IF !PICK! GTR %IMG_COUNT% (
        ECHO   No image !PICK! in the list.
        GOTO :fail_early
    )
)

CALL SET "FIRMWARE=%%IMG_PATH_!PICK!%%"
CALL SET "CHOSEN_SHA=%%IMG_SHA_!PICK!%%"

REM Any valid 218e image can be flashed.  The checksum this flasher was built
REM with is only a label for the build that shipped with the package, so it can
REM be told apart in the list above.  It is not a gate.
IF /I NOT "!CHOSEN_SHA!"=="%EXPECTED_SHA256%" (
    SET "CUSTOM=1"
    SET "FIRMWARE_VERSION=image !CHOSEN_SHA:~0,8!"
    IF /I "!CHOSEN_SHA!"=="%FACTORY_SHA256%" (
        SET "FIRMWARE_VERSION=factory firmware v369"
        ECHO.
        ECHO   This is Buchla's stock image: it removes every Rewired change.
    )
)
GOTO :have_image

:no_image
ECHO.
ECHO   Looked in firmware\, beside this script, Downloads and Desktop.
ECHO   No flashable 218e image is there.
ECHO.
ECHO   No firmware ships with this package - the patched image is Buchla's
ECHO   firmware with our changes in it, so it is not ours to redistribute.
ECHO   Build one from your own factory image with the page in web\, or:
ECHO     python tools\build.py --no-ghidra
ECHO.
GOTO :fail_early

:have_image
CALL :ok Found !FIRMWARE!
CALL :ok !FIRMWARE_VERSION!
ECHO     !CHOSEN_SHA!

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
    ECHO   dfu-programmer.exe would not run.
    ECHO.
    ECHO   Tried:     %DFU%
    ECHO   It exited %PROBE_RC% without reporting "no device present",
    ECHO   which is what a working copy says when no instrument is attached.
    ECHO.
    ECHO   Check that the windows\support folder is beside this script with its
    ECHO   DLLs intact, and that the Microsoft C++ runtime is installed.
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

REM dfu-programmer could not open it, but that does not mean it is not there.
REM Ask Windows directly before assuming the instrument is still in MIDI mode:
REM once it is in DFU there is no MIDI port at all, so looking for one and
REM telling someone to power-cycle is exactly the wrong advice.
SET "USBSTATE="
FOR /F "delims=" %%S IN ('powershell -NoProfile -ExecutionPolicy Bypass -File "%PACKAGE_ROOT%tools\Find-DfuDevice.ps1" 2^>NUL') DO SET "USBSTATE=%%S"

ECHO %USBSTATE% | FINDSTR /B /C:"PRESENT" >NUL 2>&1
IF NOT ERRORLEVEL 1 (
    FOR /F "tokens=2,3,* delims=|" %%A IN ("%USBSTATE%") DO (
        ECHO.
        ECHO   The instrument IS in DFU mode - Windows can see it:
        ECHO     %%C
        ECHO     driver: %%A    status: %%B
        ECHO.
        IF /I "%%A"=="WinUSB" (
            ECHO   WinUSB is bound, which is what the flashing tool needs, but the
            ECHO   tool still could not open it.  That is usually another process
            ECHO   holding the device: close any other flashing or MIDI software,
            ECHO   unplug and replug the USB cable, and run this again.  The
            ECHO   instrument stays in DFU across a replug.
        ) ELSE (
            ECHO   That driver is not WinUSB, so the flashing tool cannot open the
            ECHO   device.  Zadig can rebind it: pick the AT32UC3B DFU device -
            ECHO   not the 218e's MIDI entry - choose WinUSB, and Install Driver.
        )
        ECHO.
        ECHO   Nothing was erased.  No MIDI request is needed: it is already in DFU.
    )
    GOTO :dfu_present_unreachable
)

"%SENDMIDI%" list > "%TEMP%\rewired_midi.txt" 2>&1
FINDSTR /I "218e" "%TEMP%\rewired_midi.txt" >NUL
IF ERRORLEVEL 1 (
    ECHO.
    ECHO   No MIDI output port with "218e" in its name.
    ECHO.
    ECHO   These are the MIDI output ports Windows is offering:
    ECHO.
    REM Showing the list turns "unavailable" into something diagnosable: either
    REM the instrument is absent, or it is present under a name this does not
    REM match.
    TYPE "%TEMP%\rewired_midi.txt"
    ECHO.
    ECHO   If nothing is listed at all, the 218e is not connected as a MIDI
    ECHO   device: check the cable, use a directly connected port, and make sure
    ECHO   the instrument is powered on.
    ECHO.
    ECHO   If the 218e is listed above under some other name, tell us the name -
    ECHO   this looks for "218e" and Windows may be presenting it differently.
    ECHO.
    ECHO   If it used to work and stopped after running Zadig, Zadig was very
    ECHO   likely pointed at the 218e's own MIDI interface instead of the
    ECHO   AT32UC3B DFU device.  That replaces the MIDI driver with WinUSB and
    ECHO   the port disappears.  To undo it: Device Manager, find the 218e,
    ECHO   Uninstall device with "delete the driver" ticked, then unplug and
    ECHO   replug so Windows reinstalls its own driver.
    ECHO.
    DEL "%TEMP%\rewired_midi.txt" >NUL 2>&1
    ECHO   Nothing was erased.
    GOTO :fail_early
)
DEL "%TEMP%\rewired_midi.txt" >NUL 2>&1
ECHO Asking the 218e to enter DFU mode over MIDI.
REM SendMIDI can report a failure in its output while still exiting zero, so
REM the text is checked as well as the status - the macOS flasher has always
REM done this and the Windows one did not.
"%SENDMIDI%" dev 218e syx 0 2 55 2 1 1 > "%TEMP%\rewired_syx.txt" 2>&1
SET "SYX_RC=%ERRORLEVEL%"
TYPE "%TEMP%\rewired_syx.txt" >> "%LOG_FILE%" 2>&1
FINDSTR /I /C:"Couldn't find" /C:"No valid MIDI" "%TEMP%\rewired_syx.txt" >NUL 2>&1
SET "SYX_BAD=%ERRORLEVEL%"
IF NOT "%SYX_RC%"=="0" GOTO :syx_failed
IF "%SYX_BAD%"=="0" GOTO :syx_failed
DEL "%TEMP%\rewired_syx.txt" >NUL 2>&1
GOTO :syx_sent

:syx_failed
ECHO.
ECHO   SendMIDI could not deliver the DFU request.
TYPE "%TEMP%\rewired_syx.txt" 2>NUL
DEL "%TEMP%\rewired_syx.txt" >NUL 2>&1
ECHO.
ECHO   Nothing was erased; power-cycle the 218e and retry.
GOTO :fail_early

:syx_sent

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

:dfu_present_unreachable
REM In DFU and visible to Windows, but not openable.  Distinct from
REM :dfu_unreachable, which is reached when the device never appeared at all.
ECHO.
ECHO   Run this again once the driver or the conflicting program is sorted out;
ECHO   the instrument is already in DFU, so it will go straight to flashing.
ECHO.
PAUSE
EXIT /B 1

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
