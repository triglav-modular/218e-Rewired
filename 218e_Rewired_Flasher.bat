@ECHO OFF
REM Before delayed expansion exists to eat it: a folder with an exclamation
REM mark in its name corrupts every percent-expanded path below, and the
REM flasher used to blame the download ("re-download the package") for what
REM is the folder's name.  Checked here, where %~dp0 is still literal.
SET "RAW_DIR=%~dp0"
ECHO "%RAW_DIR%" | FINDSTR /C:"!" >NUL && (
    ECHO   This folder's path contains an exclamation mark:
    REM Quoted, because this line sits inside a parenthesised block and a
    REM bracket in the unquoted path would close the block at parse time -
    REM the very bug class this guard exists for.
    ECHO     "%RAW_DIR%"
    ECHO   cmd cannot carry that through this script.  Rename the folder -
    ECHO   plain letters, digits, dots and dashes - and run it again.
    PAUSE
    EXIT /B 1
)
SETLOCAL EnableDelayedExpansion
TITLE Buchla 218e V3 - Rewired firmware

REM Experimental Buchla 218e V3 v36.9 firmware flasher for Windows.
REM
REM Mirrors Program218e_v3_Rewired_macOS.command: it validates the image against
REM what dfu-programmer's own parser accepts, confirms the bootloader region is
REM protected, programs, and validates by read-back before it lets the
REM instrument leave DFU.  Buchla's own ProgramLEM218.bat does none of those and
REM flashes whatever .hex it finds first, which is why this is a separate
REM script.

SET "EXPECTED_SHA256=149921c3debc86aff759a5e2eacdc73b7e43639d43bdb83a46b79f715427254b"
REM Buchla's own v36.9 image.  Recognised so that going back to stock is an
REM offered choice rather than something to be identified by hand.
SET "FACTORY_SHA256=565f2d0c3466edfd13ddc1626cb7a74204723ff3a01f65eac34a9db99901dd47"
SET "FIRMWARE_VERSION=Rewired 2.0.0 (149921c3)"
SET "DFU_SESSION_ACTIVE=0"
SET "FLASH_VALIDATED=0"
SET "ERASE_STARTED=0"
SET "SCRIPT_DIR=%~dp0"
SET "LOG_FILE=%SCRIPT_DIR%218e_v3_Rewired_flash_log_win.txt"

REM A download puts everything the flasher runs in one tools\ folder beside
REM this script.  A checkout is arranged for the repository instead, with the
REM executables under windows\support\ and the scripts under tools\, so both
REM shapes are looked for.  The images are in firmware\ either way.
IF EXIST "%SCRIPT_DIR%tools\dfu-programmer.exe" (
    SET "TOOLS=%SCRIPT_DIR%tools"
    SET "PACKAGE_ROOT=%SCRIPT_DIR%"
) ELSE IF EXIST "%SCRIPT_DIR%windows\support\dfu-programmer.exe" (
    SET "TOOLS=%SCRIPT_DIR%windows\support"
    SET "PACKAGE_ROOT=%SCRIPT_DIR%"
) ELSE IF EXIST "%SCRIPT_DIR%support\dfu-programmer.exe" (
    SET "TOOLS=%SCRIPT_DIR%support"
    SET "PACKAGE_ROOT=%SCRIPT_DIR%..\"
) ELSE (
    ECHO Could not find dfu-programmer.exe in tools\ beside this script.
    ECHO.
    ECHO That folder ships with this package and holds the flashing tools.
    ECHO Re-download the package rather than assembling it by hand.
    GOTO :fail_early
)

REM The PowerShell helpers sit with the executables in a download and under
REM tools\ in a checkout.
IF EXIST "%TOOLS%\Scan-Images.ps1" (
    SET "PSTOOLS=%TOOLS%"
) ELSE (
    SET "PSTOOLS=%PACKAGE_ROOT%tools"
)

REM The images, and nowhere else.  Loose beside the flasher meant every hex
REM in the same folder was a candidate, including ones that had nothing to do
REM with this package.
SET "FIRMWARE_DIR=%PACKAGE_ROOT%firmware"

SET "DFU=%TOOLS%\dfu-programmer.exe"
SET "SENDMIDI=%TOOLS%\sendmidi.exe"
SET "FIRMWARE="
SET "TOTAL_STEPS=7"
SET "STEP=0"

REM The console opens shorter than the banner, so its top scrolls away before
REM anyone sees it.  A no-op under Windows Terminal, which is already taller.
REM <NUL for the same reason the menu helper gets it: anything started here
REM inherits this script's input, and the answers piped in for the prompts
REM below must still be there when they ask.
MODE CON: COLS=80 LINES=40 <NUL >NUL 2>&1
ECHO.
CALL :banana

REM What to do comes before the warning, because the warning is about flashing
REM and getting a stuck keyboard out of DFU is not that: it writes nothing.
SET "MENU_FILE=%TEMP%\rewired_menu_%RANDOM%.txt"
SET "MENU_OUT=%TEMP%\rewired_pick_%RANDOM%.txt"
> "%MENU_FILE%" (
    ECHO Flash firmware onto the 218e V3
    ECHO Erases the chip and writes a new image.
    ECHO --
    ECHO Get the keyboard out of DFU mode
    ECHO For a keyboard left in DFU by an interrupted flash.
    ECHO Sends START. Flashes nothing, erases nothing.
)
ECHO.
CALL :show_menu "What would you like to do?"
IF "!PICK!"=="2" (
    CALL :rescue
    SET "RESCUE_RC=!ERRORLEVEL!"
    ECHO.
    PAUSE
    EXIT /B !RESCUE_RC!
)
IF NOT "!PICK!"=="1" (
    ECHO   Nothing chosen.  The instrument was not touched.
    GOTO :fail_early
)
ECHO.

ECHO ======================================================================
ECHO   READ THIS BEFORE YOU FLASH ANYTHING
ECHO.
ECHO   THIS FIRMWARE IS FOR THE BUCHLA 218e V3 ONLY.  It won't work on the
ECHO   218, the 218r, the 218e V1 or V2, or any other touchplate controller.
ECHO.
ECHO   USING THIS TOOL AND FIRMWARE IS ENTIRELY AT YOUR OWN RISK.  This is an
ECHO   experimental, unofficial firmware, not made or supported by Buchla.
ECHO   Flashing it will probably void your warranty.  Recovering a bricked
ECHO   unit may need JTAG hardware and opening the instrument, or may not be
ECHO   possible at all.
ECHO.
ECHO   A failed flash usually leaves the keyboard in DFU mode, where the
ECHO   flasher can try again, but THERE IS NO GUARANTEE THAT IT WILL SUCCEED.
ECHO   If losing the use of your 218e V3 would be a problem, stop here and keep
ECHO   the factory firmware.
ECHO ======================================================================
ECHO.
SET "CONSENT="
SET /P "CONSENT=  Type YES (capitals) to accept and continue: "
IF NOT "%CONSENT%"=="YES" (
    ECHO   Not confirmed.  Nothing was changed.
    GOTO :fail_early
)

REM --- find the image ----------------------------------------------------
REM The firmware folder beside this script, and nowhere else.  Searching
REM Downloads and the Desktop too meant every old image on the machine turned
REM up in the list - four of them, when the package carries two.
REM Every .hex there is validated and listed newest first,  When more than
REM one is flashable the choice is made explicitly: picking silently is how the
REM wrong firmware gets installed, because a stale build in firmware\ would
REM always win on checksum alone.
CALL :step Locating the firmware image
CALL :searched_note

IF NOT EXIST "%PSTOOLS%\Scan-Images.ps1" (
    ECHO   Scan-Images.ps1 is missing.  The flasher needs the whole
    ECHO   repository beside it, not just this script - re-clone or re-download
    ECHO   the package and run it from there.
    GOTO :fail_early
)
SET "IMG_COUNT=0"
FOR /F "tokens=1,2,* delims=|" %%A IN ('powershell -NoProfile -ExecutionPolicy Bypass -File "%PSTOOLS%\Scan-Images.ps1" -DirList "%FIRMWARE_DIR%" -Prefer "%EXPECTED_SHA256%" 2^>NUL') DO (
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
    REM "Rewired 1.0.0 (sha)" -> "1.0", the way the page shows it.
    FOR /F "tokens=2" %%V IN ("%FIRMWARE_VERSION%") DO SET "REWIRED_VER=%%V"
    FOR /F "tokens=1,2 delims=." %%a IN ("!REWIRED_VER!") DO (
        SET "REWIRED_MMV=%%a.%%b"
        IF "%%b"=="" SET "REWIRED_MMV=%%a"
    )
    SET "MENU_FILE=%TEMP%\rewired_images_%RANDOM%.txt"
    SET "MENU_OUT=%TEMP%\rewired_imgpick_%RANDOM%.txt"
    IF EXIST "!MENU_FILE!" DEL /Q "!MENU_FILE!" >NUL 2>&1
    FOR /L %%I IN (1,1,%IMG_COUNT%) DO (
        SET "MARK="
        IF /I "!IMG_SHA_%%I!"=="%EXPECTED_SHA256%" SET "MARK=  <- REWIRED firmware v!REWIRED_MMV!"
        IF /I "!IMG_SHA_%%I!"=="%FACTORY_SHA256%"  SET "MARK=  <- FACTORY firmware v36.9"
        IF %%I GTR 1 >>"!MENU_FILE!" ECHO --
        REM The filename is the label - it is the thing a person recognises -
        REM and the name only: every image in the list is in the same folder,
        REM so the path in front of each was the same long string over and
        REM over.  The date and checksum go underneath.
        FOR %%N IN ("!IMG_PATH_%%I!") DO >>"!MENU_FILE!" ECHO %%~nxN!MARK!
        >>"!MENU_FILE!" ECHO !IMG_WHEN_%%I!   !IMG_SHA_%%I:~0,8!
        REM What the image was built with, when the download that carried it
        REM said so.  Two images a minute apart are otherwise told apart only
        REM by a checksum nobody can read.
        CALL :image_options "!IMG_PATH_%%I!" "!IMG_SHA_%%I!" "!MENU_FILE!"
    )
    CALL :show_menu ""
    IF "!PICK!"=="0" (
        ECHO   Nothing chosen.  The instrument was not touched.
        GOTO :fail_early
    )
)

CALL SET "FIRMWARE=%%IMG_PATH_!PICK!%%"
CALL SET "CHOSEN_SHA=%%IMG_SHA_!PICK!%%"

REM The path travelled through CALL SET under delayed expansion, which eats
REM any ! (and can mangle ^) in a filename the scanner itself handled fine.
REM A path that no longer resolves must stop here, while nothing has been
REM erased - carried further it would fail at the flash step, after the
REM erase.
IF NOT EXIST "!FIRMWARE!" (
    ECHO   The chosen file's name could not be carried through cmd - a
    ECHO   character in it, likely an exclamation mark or caret, was eaten
    ECHO   on the way.  Rename the file to plain letters, digits, dots and
    ECHO   dashes, and run this again.
    GOTO :fail_early
)

REM Any valid 218e V3 image can be flashed.  The checksum this flasher was built
REM with is only a label for the build that shipped with the package, so it can
REM be told apart in the list above.  It is not a gate.
IF /I NOT "!CHOSEN_SHA!"=="%EXPECTED_SHA256%" (
    SET "CUSTOM=1"
    SET "FIRMWARE_VERSION=image !CHOSEN_SHA:~0,8!"
    IF /I "!CHOSEN_SHA!"=="%FACTORY_SHA256%" (
        SET "FIRMWARE_VERSION=factory firmware v36.9"
        ECHO.
        ECHO   This is Buchla's stock image: it removes every Rewired change.
    )
)
GOTO :have_image

:no_image
ECHO.
ECHO   No flashable 218e V3 image is in that folder.
ECHO.
ECHO   No firmware ships with this package.  Build one from your own
ECHO   factory image with the page in web\ and put it there, or
ECHO   build locally:
ECHO     python tools\build.py --no-ghidra
ECHO   then copy build\218eV3_v369_Rewired_DFU.hex into firmware\.
ECHO.
GOTO :fail_early

:have_image
FOR %%F IN ("!FIRMWARE!") DO CALL :ok Found %%~nxF
CALL :ok !FIRMWARE_VERSION!
REM With one image in reach there is no menu to read the options off, and this
REM is the last point before the chip is erased at which they can be checked.
CALL :print_options "!FIRMWARE!" "!CHOSEN_SHA!"
ECHO     !CHOSEN_SHA!

REM The image is flashed where it is, as on macOS.  Filing a copy under the
REM canonical name only made it turn up again on the next run as a second
REM entry in the list, checksummed but with nothing to say where it came from.
ECHO Using !FIRMWARE!>> "%LOG_FILE%"

ECHO.
ECHO Before continuing:
ECHO   - Windows needs the WinUSB driver bound to the keyboard's DFU mode.
ECHO     There is nothing to do about that now: the AT32UC3B DFU device does
ECHO     not exist until the instrument is in DFU, which is a step away.  If
ECHO     the driver is needed this script stops there and opens Zadig itself,
ECHO     at the one moment Zadig can see the device.
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
    ECHO   Tried:     "!DFU!"
    ECHO   It exited %PROBE_RC% without reporting "no device present",
    ECHO   which is what a working copy says when no instrument is attached.
    ECHO.
    ECHO   Check that the tools folder is beside this script with its
    ECHO   DLLs intact, and that the Microsoft C++ runtime is installed.
    GOTO :fail_early
)
CALL :ok dfu-programmer.exe runs


REM --- into DFU ----------------------------------------------------------
CALL :step Putting the instrument into DFU
"%DFU%" at32uc3b1256 get bootloader-version >NUL 2>&1
IF NOT ERRORLEVEL 1 (
    ECHO The 218e V3 is already in DFU mode.
    GOTO :in_dfu
)

REM dfu-programmer could not open it, but that does not mean it is not there.
REM Ask Windows directly before assuming the instrument is still in MIDI mode:
REM once it is in DFU there is no MIDI port at all, so looking for one and
REM telling someone to power-cycle is exactly the wrong advice.
SET "USBSTATE="
FOR /F "delims=" %%S IN ('powershell -NoProfile -ExecutionPolicy Bypass -File "%PSTOOLS%\Find-DfuDevice.ps1" 2^>NUL') DO SET "USBSTATE=%%S"

REM Not ECHO'd through FINDSTR: the reply is pipe-delimited by design, and
REM %USBSTATE% expands before cmd parses pipes, turning the diagnostic into a
REM five-stage pipeline that never matches.  A substring test needs no pipe.
IF "!USBSTATE:~0,7!"=="PRESENT" (
    FOR /F "tokens=2,3,* delims=|" %%A IN ("!USBSTATE!") DO (
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
            ECHO   not the 218e V3's MIDI entry - choose WinUSB, and Install Driver.
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
    ECHO   If nothing is listed at all, the 218e V3 is not connected as a MIDI
    ECHO   device: check the cable, use a directly connected port, and make sure
    ECHO   the instrument is powered on.
    ECHO.
    ECHO   If the 218e V3 is listed above under some other name, tell us the name -
    ECHO   this looks for "218e" and Windows may be presenting it differently.
    ECHO.
    ECHO   If it used to work and stopped after running Zadig, Zadig was very
    ECHO   likely pointed at the 218e V3's own MIDI interface instead of the
    ECHO   AT32UC3B DFU device.  That replaces the MIDI driver with WinUSB and
    ECHO   the port disappears.  To undo it: Device Manager, find the 218e V3,
    ECHO   Uninstall device with "delete the driver" ticked, then unplug and
    ECHO   replug so Windows reinstalls its own driver.
    ECHO.
    DEL "%TEMP%\rewired_midi.txt" >NUL 2>&1
    ECHO   Nothing was erased.
    GOTO :fail_early
)
DEL "%TEMP%\rewired_midi.txt" >NUL 2>&1
ECHO Asking the 218e V3 to enter DFU mode over MIDI.
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
ECHO   Nothing was erased; power-cycle the 218e V3 and retry.
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
    REM Name the driver actually bound.  "Not reachable" plus a pointer at
    REM Zadig is useless once Zadig has been run: the device can be sitting
    REM there under libusb0, which this dfu-programmer cannot open at all.
    CALL :report_driver
    ECHO.
    ECHO   Zadig is GPLv3, from https://zadig.akeo.ie/ , and ships with
    ECHO   Buchla's kit.  Nothing has been erased.
    ECHO.
    PAUSE
    IF NOT EXIST "%TOOLS%\zadig-2.8.exe" (
        ECHO   zadig-2.8.exe is not in "!TOOLS!".
        ECHO   Copy Buchla's windows\ folder in beside this script and retry.
        GOTO :dfu_unreachable
    )
    ECHO   Starting Zadig.  In its window:
    ECHO     1. Options - List All Devices, if the list looks empty
    ECHO     2. select AT32UC3B DFU  ^(USB ID 03EB 2FF6^)
    ECHO     3. set the right-hand box to WinUSB and press the button
    ECHO        it reads Replace Driver, not Install Driver, when something is
    ECHO        already bound.  Press it anyway: an older Buchla kit leaves
    ECHO        libusb0 there, which this tool cannot use.
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

:report_driver
REM What Windows has bound to the Atmel bootloader, in its own words.
SET "USBSTATE="
FOR /F "delims=" %%S IN ('powershell -NoProfile -ExecutionPolicy Bypass -File "%PSTOOLS%\Find-DfuDevice.ps1" 2^>NUL') DO SET "USBSTATE=%%S"
ECHO !USBSTATE! | FINDSTR /B /C:"PRESENT" >NUL 2>&1
IF ERRORLEVEL 1 (
    ECHO   Windows does not see an Atmel DFU device on USB at all, so the
    ECHO   instrument is probably not in DFU.  Power-cycle it and retry.
    EXIT /B 0
)
FOR /F "tokens=2,3,* delims=|" %%A IN ("!USBSTATE!") DO (
    ECHO   Windows does see it:    %%C
    ECHO   Driver currently bound: %%A   ^(status %%B^)
    ECHO.
    IF /I "%%A"=="WinUSB" (
        ECHO   That is the right driver, so something else is holding the device.
        ECHO   Close other MIDI or flashing software, replug the USB cable, and
        ECHO   run this again - it stays in DFU across a replug.
    ) ELSE (
        ECHO   dfu-programmer 1.0.0 dropped libusb0 support and speaks only to
        ECHO   WinUSB, so it cannot open a %%A device.  If an older Buchla kit
        ECHO   flashed this machine before, that is where %%A came from.
        ECHO.
        ECHO   In Zadig: select AT32UC3B DFU ^(USB ID 03EB 2FF6^), set the
        ECHO   right-hand box to WinUSB, and press Replace Driver.  The button
        ECHO   reads Replace, not Install, when a driver is already bound -
        ECHO   leaving it unpressed changes nothing.
    )
)
EXIT /B 0

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
CALL :report_driver
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
ECHO   Ready to erase the application flash and write the firmware.
ECHO.
ECHO   From here on, if anything goes wrong - including closing this window or
ECHO   pressing Ctrl-C - the instrument stays in DFU mode, because START is
ECHO   only sent after the write has been validated.  Reconnect power if you
ECHO   have to, then run this script again.
ECHO.
PAUSE

IF NOT EXIST "!FIRMWARE!" (
    ECHO   The firmware file disappeared between selection and erase.
    ECHO   Nothing has been erased.
    GOTO :fail_early
)
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
REM Beside the image that was flashed, which is where anyone would look.
FOR %%F IN ("!FIRMWARE!") DO SET "RECORD_DIR=%%~dpF"
> "!RECORD_DIR!INSTALLED.txt" ECHO !FIRMWARE_VERSION!
>> "!RECORD_DIR!INSTALLED.txt" ECHO flashed  %DATE% %TIME%
>> "!RECORD_DIR!INSTALLED.txt" ECHO image    !CHOSEN_SHA!

REM Clear first so the good news is the first line in the window rather
REM than the last line of a long scroll.  Success path only, and only
REM after read-back validation has already passed.
CLS
ECHO Flashing successful, enjoy.
ECHO.
CALL :banana
ECHO.
ECHO   %FIRMWARE_VERSION% is now on the instrument.
ECHO.
ECHO   Press the reset button before you play.
ECHO   Flashing does not clear the instrument's own memory, so a held note
ECHO   or a stuck gate from before the update can survive it.  Reset clears
ECHO   that and recalibrates the keys - keep your hands away for the few
ECHO   seconds the pad LEDs are lit.
ECHO.
ECHO   Log: %LOG_FILE%
ECHO   If the 218e V3 does not reappear, power-cycle the instrument.
PAUSE
ENDLOCAL
EXIT /B 0

REM --- helpers -----------------------------------------------------------
:print_options
REM The console version of :image_options - same checksum binding, printed
REM instead of written into a menu.
SET "PO_SHA=%~2"
SET "PO_MANIFEST=%~dp1image.txt"
IF NOT EXIST "%PO_MANIFEST%" EXIT /B 0
REM The manifest describes both images a download carries: the build it was
REM made for, and the stock image it was made from.  Which set of lines to
REM print is decided by checksum.
SET "PO_WANT="
FOR /F "usebackq eol=# tokens=1,* delims==" %%K IN ("%PO_MANIFEST%") DO (
    IF /I "%%K"=="EXPECTED_SHA256" IF /I "%%L"=="%PO_SHA%" SET "PO_WANT=OPTION"
    IF /I "%%K"=="FACTORY_SHA256" IF /I "%%L"=="%PO_SHA%" SET "PO_WANT=FACTORY_OPTION"
)
IF NOT DEFINED PO_WANT EXIT /B 0
FOR /F "usebackq eol=# tokens=1,* delims==" %%K IN ("%PO_MANIFEST%") DO (
    REM Through a quoted SET, like the typed menu: %%L is substituted
    REM before the special-character pass, so a & or > in a line would
    REM execute or redirect instead of printing.
    IF /I "%%K"=="!PO_WANT!" (
        SET "PO_LINE=%%L"
        ECHO(      !PO_LINE!
    )
)
EXIT /B 0

:searched_note
REM Where it looked, and what was there.  An image that does not appear in the
REM list gives no clue why otherwise, and macOS prints the same thing.
SET "NOTE_N=0"
FOR %%F IN ("!FIRMWARE_DIR!\*.hex") DO SET /A NOTE_N+=1
REM Named the way the README names it, rather than by the whole path, which
REM says only where the package was unzipped.  Delayed expansion throughout:
REM a path with brackets in it - which is what Windows calls a second download
REM of the same zip - closes this IF block early when it is substituted at
REM parse time, and half of it prints.
ECHO   Looked in:
IF EXIST "!FIRMWARE_DIR!\" (
    ECHO      !NOTE_N! in the firmware folder beside this script
) ELSE (
    ECHO      -- no firmware folder beside this script
)
ECHO.
EXIT /B 0

:image_options
REM Echo the OPTION lines of the image.txt beside %1 into the file %3, but only
REM when the manifest names the same checksum: a manifest left behind by an
REM earlier download must not describe the wrong file.
SET "IO_SHA=%~2"
SET "IO_OUT=%~3"
SET "IO_MANIFEST=%~dp1image.txt"
IF NOT EXIST "%IO_MANIFEST%" EXIT /B 0
REM The manifest describes both images a download carries: the build it was
REM made for, and the stock image it was made from.  Which set of lines to
REM print is decided by checksum.
SET "IO_WANT="
FOR /F "usebackq eol=# tokens=1,* delims==" %%K IN ("%IO_MANIFEST%") DO (
    IF /I "%%K"=="EXPECTED_SHA256" IF /I "%%L"=="%IO_SHA%" SET "IO_WANT=OPTION"
    IF /I "%%K"=="FACTORY_SHA256" IF /I "%%L"=="%IO_SHA%" SET "IO_WANT=FACTORY_OPTION"
)
IF NOT DEFINED IO_WANT EXIT /B 0
FOR /F "usebackq eol=# tokens=1,* delims==" %%K IN ("%IO_MANIFEST%") DO (
    REM Same quoted-SET idiom as :print_options, and for the same reason.
    IF /I "%%K"=="!IO_WANT!" (
        SET "IO_LINE=%%L"
        >>"%IO_OUT%" ECHO(!IO_LINE!
    )
)
EXIT /B 0

:show_menu
REM Draws MENU_FILE and leaves the 1-based answer in PICK.  The helper is not
REM redirected: it writes its answer to a file precisely so that the menu can
REM go to the console where the arrow keys can be seen to work.
SET "PICK="
IF NOT EXIST "%PSTOOLS%\Show-Menu.ps1" (
    ECHO   Show-Menu.ps1 is missing.  The flasher needs the whole
    ECHO   package beside it, not just this script.
    GOTO :fail_early
)
DEL /Q "%MENU_OUT%" >NUL 2>&1
REM <NUL so the helper gets its own empty input.  It inherits this script's
REM stdin otherwise, and whatever was piped in for the prompts further down is
REM gone by the time they ask for it.
powershell -NoProfile -ExecutionPolicy Bypass -File "%PSTOOLS%\Show-Menu.ps1" -Path "%MENU_FILE%" -Out "%MENU_OUT%" -Title %1 <NUL
IF EXIST "%MENU_OUT%" (
    SET /P "PICK="<"%MENU_OUT%"
    DEL /Q "%MENU_OUT%" >NUL 2>&1
)
REM No answer means there was no keyboard for the helper to read - piped input,
REM or a run with no console.  cmd buffers redirected input and hands a child
REM process none of it, so the batch has to do the asking itself.
IF NOT DEFINED PICK CALL :menu_typed %1
IF NOT DEFINED PICK SET "PICK=0"
DEL /Q "%MENU_FILE%" >NUL 2>&1
EXIT /B 0

:menu_typed
IF NOT "%~1"=="" ECHO   %~1
SET "MENU_N=0"
SET "MENU_LABEL=1"
FOR /F "usebackq delims=" %%L IN ("%MENU_FILE%") DO (
    IF "%%L"=="--" (
        SET "MENU_LABEL=1"
    ) ELSE (
        REM Through a quoted SET, not echoed directly: %%L is substituted
        REM before the special-character pass, so a bare < in a line - and
        REM the marks now carry one - would be read as a redirection there.
        REM Inside quotes it is just a character.
        SET "MT_LINE=%%L"
        IF "!MENU_LABEL!"=="1" (
            REM Air between the entries here too, so the typed fallback reads
            REM the same as the arrow menu.
            IF NOT "!MENU_N!"=="0" ECHO.
            SET /A MENU_N+=1
            ECHO     !MENU_N!^) !MT_LINE!
            SET "MENU_LABEL=0"
        ) ELSE (
            REM The lines under an entry say what the image is.  Printing only
            REM the labels here meant the fallback listed four checksums and
            REM nothing about any of them.
            ECHO        !MT_LINE!
        )
    )
)
SET "PICK="
SET /P "PICK=  Choose [1-!MENU_N!]: "
IF NOT DEFINED PICK EXIT /B 0
ECHO !PICK!| FINDSTR /R "^[1-9][0-9]*$" >NUL || SET "PICK=0"
IF !PICK! GTR !MENU_N! SET "PICK=0"
EXIT /B 0

:rescue
REM Folded in from what used to be a separate ExitDFU script.  A 218e V3 lands in
REM DFU when a flash was started and interrupted, and reading the safety fuses
REM sets ISP_FORCE, so a power cycle brings it straight back into DFU.  The one
REM thing that releases it is the START command.  This flashes nothing and
REM erases nothing.
ECHO.
CALL :step Getting the 218e V3 out of DFU mode
"%SENDMIDI%" list 2>NUL | FINDSTR /I "218e" >NUL
IF NOT ERRORLEVEL 1 (
    CALL :ok The 218e V3 is already running its firmware - it has a MIDI port
    ECHO   Nothing to do.
    EXIT /B 0
)
"%DFU%" at32uc3b1256 get bootloader-version >NUL 2>&1
IF ERRORLEVEL 1 (
    ECHO   No 218e V3 in DFU mode, and no 218e V3 MIDI port.
    ECHO.
    ECHO   Check the USB cable and that the instrument is powered on.  If the
    ECHO   flasher said the instrument was in DFU but this cannot see it, the
    ECHO   DFU device may not be bound to WinUSB - choose "Flash firmware",
    ECHO   which diagnoses that and opens Zadig at the right moment.
    EXIT /B 1
)
CALL :ok Found the 218e V3 in DFU mode
ECHO   Sending START...
"%DFU%" at32uc3b1256 start
IF ERRORLEVEL 1 (
    ECHO   Could not send START.  Power-cycle the instrument and try again.
    EXIT /B 1
)
ECHO   START sent.  Waiting for the instrument to come back...
REM A label loop, not FOR /L: a plain variable inside a parenthesised FOR block
REM expands when the block is parsed, so an in-loop exit guard never fires.
SET "RESCUE_TRIES=0"
:rescue_wait
SET /A RESCUE_TRIES+=1
PING -n 2 127.0.0.1 >NUL
"%SENDMIDI%" list 2>NUL | FINDSTR /I "218e" >NUL
IF NOT ERRORLEVEL 1 (
    CALL :ok The 218e V3 is back as a MIDI device
    EXIT /B 0
)
IF %RESCUE_TRIES% LSS 10 GOTO :rescue_wait
ECHO   START was sent but the MIDI port has not appeared yet.
ECHO   Power-cycle the instrument once.  It will come up normally.
EXIT /B 0

:banana
REM Drawn in one place and called twice - over the warning at the start,
REM and over the result at the end.  The carets are doubled because cmd
REM treats a single one as an escape and would swallow the eyes.
ECHO                                   .-==-:
ECHO                                  -=:...-=:
ECHO                                .=-      .=:
ECHO                                =          =
ECHO                               -=  ^^    ^^  =:
ECHO                               =-          =-
ECHO                              :=.   \__/   =-
ECHO                              =-           =-
ECHO                             -=           .=:
ECHO                       .:---===:          :=.
ECHO                    .--:.  -=-:==:       .-=
ECHO                   :=:    -=-   -=- :-=--:-=
ECHO                  :=.   :==:    :==-:      =-
ECHO                  ==  :==-      -==:       ==
ECHO  .:::::::.......:==-==:      .=: =-       -=
ECHO :=--=::::---------:.       .--.  -=       ==
ECHO == .=:.                  :--.    -=      .=:
ECHO :=-=:::----:::......::-=-:       -=      -=
ECHO  :==:      .....:::...        .:===     -=.
ECHO    :-==:..                .:-==-.-=    -=.
ECHO       .:-====----------===--:.   :=-:-=:
ECHO            .:---=====--:.          ::.
EXIT /B 0

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
REM dfu-programmer prints the value twice: "Bootloader protected area: 0x03 (3)".
REM Take the decimal from between the brackets by splitting on ( and ) - the
REM last whitespace-delimited token is "(3)", brackets included, which then
REM fails every numeric comparison and refuses to erase a perfectly good chip.
REM Lines without brackets yield no second token, so they set nothing.
REM Through a file, not FOR /F's in-line command: FOR /F hands the command
REM to a child cmd /c, whose quote rule strips the outer quotes when any of
REM &<>()@^| appears between them - and a package unzipped into Windows'
REM default "folder (2)" duplicate name puts parentheses in %DFU%.  A plain
REM command line keeps its quotes whatever the path holds.
"%DFU%" at32uc3b1256 getfuse %1 > "%TEMP%\rewired_fuse.txt" 2>&1
FOR /F "usebackq tokens=* delims=" %%L IN ("%TEMP%\rewired_fuse.txt") DO (
    ECHO %%L>> "%LOG_FILE%"
    FOR /F "tokens=2 delims=()" %%V IN ("%%L") DO SET "FUSE_VALUE=%%V"
)
DEL "%TEMP%\rewired_fuse.txt" >NUL 2>&1
EXIT /B 0

:recovery_safe_stop
IF "!FLASH_VALIDATED!"=="1" GOTO :started_but_not_launched
ECHO.
ECHO ======================================================================
ECHO   RECOVERY-SAFE STOP
ECHO   No START command was sent, so the 218e V3 is still in DFU.
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
    ECHO   so the 218e V3 returns to DFU until START is sent.  Or just run this
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
ECHO   Only the restart command failed.  Power-cycle the 218e V3 and it should
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
