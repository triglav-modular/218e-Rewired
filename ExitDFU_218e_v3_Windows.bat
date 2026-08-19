@ECHO OFF
REM Get a 218e out of DFU mode and back to being an instrument.
REM
REM A 218e lands in DFU when a flash was started and interrupted, and reading
REM the safety fuses sets ISP_FORCE, so a power cycle alone brings it straight
REM back into DFU.  The one thing that releases it is the START command, which
REM this script sends.  It flashes nothing and erases nothing.

SETLOCAL
SET "SCRIPT_DIR=%~dp0"
IF EXIST "%SCRIPT_DIR%windows\support\dfu-programmer.exe" (
    SET "TOOLS=%SCRIPT_DIR%windows\support"
) ELSE IF EXIST "%SCRIPT_DIR%support\dfu-programmer.exe" (
    SET "TOOLS=%SCRIPT_DIR%support"
) ELSE (
    ECHO Could not find windows\support\dfu-programmer.exe next to this script.
    ECHO Keep this script inside the 218e-Rewired package.
    PAUSE
    EXIT /B 1
)

ECHO Looking for the 218e...

"%TOOLS%\sendmidi.exe" list 2>NUL | FINDSTR /I "218e" >NUL
IF NOT ERRORLEVEL 1 (
    ECHO The 218e is already running its firmware - it has a MIDI port.
    ECHO Nothing to do.
    PAUSE
    EXIT /B 0
)

"%TOOLS%\dfu-programmer.exe" at32uc3b1256 get bootloader-version >NUL 2>&1
IF ERRORLEVEL 1 (
    ECHO No 218e in DFU mode and no 218e MIDI port.
    ECHO.
    ECHO Check the USB cable and that the instrument is powered on.  If the
    ECHO flasher said the instrument was in DFU but this cannot see it, the
    ECHO DFU device may not be bound to WinUSB - run the flasher, which
    ECHO diagnoses that and opens Zadig at the right moment.
    PAUSE
    EXIT /B 1
)

ECHO Found the 218e in DFU mode.  Sending START...
"%TOOLS%\dfu-programmer.exe" at32uc3b1256 start
IF ERRORLEVEL 1 (
    ECHO Could not send START.  Power-cycle the instrument and run this again.
    PAUSE
    EXIT /B 1
)

ECHO START sent.  Waiting for the instrument to come back...
REM A label loop, not FOR /L: %VAR% inside a parenthesised FOR block expands
REM when the block is parsed, so an in-loop exit guard never fires.
SET "TRIES=0"
:wait_midi
SET /A TRIES+=1
PING -n 2 127.0.0.1 >NUL
"%TOOLS%\sendmidi.exe" list 2>NUL | FINDSTR /I "218e" >NUL
IF NOT ERRORLEVEL 1 (
    ECHO The 218e is back as a MIDI device.  Done.
    PAUSE
    EXIT /B 0
)
IF %TRIES% LSS 10 GOTO :wait_midi

ECHO START was sent but the MIDI port has not appeared yet.
ECHO Power-cycle the instrument once - after START that brings it up
ECHO normally, because leaving DFU cleared the forced-DFU flag.
PAUSE
EXIT /B 0
