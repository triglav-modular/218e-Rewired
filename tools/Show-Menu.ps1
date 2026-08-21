<#
    An arrow-key menu for the Windows flasher.

    Batch cannot read an arrow key, so the menu is drawn here and the answer
    is left in a file.  A file rather than stdout on purpose: the caller must
    not redirect this, or the menu would be captured instead of shown.

    The menu is read from a file too, which keeps entry text out of the
    batch quoting rules entirely.  Entries are separated by a line holding
    only "--"; the first line of an entry is its label and the rest are the
    lines shown underneath it.

    Writes the 1-based choice to -Out, or 0 if nothing was chosen.
#>
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Out,
    [string]$Title = ''
)

$ErrorActionPreference = 'Stop'

$entries = @()
$current = @()
foreach ($line in [IO.File]::ReadAllLines($Path)) {
    if ($line -eq '--') {
        if ($current.Count) { $entries += , $current }
        $current = @()
    } else {
        $current += $line
    }
}
if ($current.Count) { $entries += , $current }

if ($entries.Count -eq 0) { [IO.File]::WriteAllText($Out, "0"); exit }

function Write-Menu($sel) {
    for ($i = 0; $i -lt $entries.Count; $i++) {
        $entry = $entries[$i]
        if ($i -eq $sel) {
            Write-Host '    > ' -NoNewline -ForegroundColor Yellow
            Write-Host $entry[0] -ForegroundColor White
        } else {
            Write-Host ('      ' + $entry[0]) -ForegroundColor DarkGray
        }
        for ($j = 1; $j -lt $entry.Count; $j++) {
            Write-Host ('        ' + $entry[$j]) -ForegroundColor DarkGray
        }
    }
}

function Measure-Lines {
    $n = 0
    foreach ($entry in $entries) { $n += $entry.Count }
    return $n
}

# A console without a keyboard - piped, or a scheduled run - cannot be driven
# by arrow keys.  Decide before printing anything: the caller prints its own
# heading when it takes over, and two headings is worse than none.
#
# Not IsInputRedirected.  The caller redirects this script's stdin from NUL on
# purpose, so that it cannot swallow answers piped to the flasher itself - which
# made that test always true, so the arrow menu never ran and every run fell
# back to the typed list.  Output being redirected is the honest signal: it
# means someone is capturing this rather than reading it.
$interactive = -not [Console]::IsOutputRedirected
if ($interactive) {
    try { $null = [Console]::CursorTop } catch { $interactive = $false }
}

# Without a keyboard there is nothing useful to do here, and reading the
# answer is not an option either: cmd buffers redirected input, so a child
# process started from a batch file sees none of what was piped into it.  Say
# nothing and write nothing - the caller notices the missing answer and asks
# in the way that does work under redirection.
if (-not $interactive) { exit }

if ($Title) { Write-Host ('  ' + $Title) }

# .NET refuses to read a key whenever stdin is redirected, and this script's
# stdin is redirected on purpose - from NUL, so that it cannot drain the
# answers piped to the flasher itself.  Those two facts together meant the
# arrow menu had never once run: KeyAvailable threw on every launch and the
# script quietly exited, leaving the typed list to do the asking.
#
# The keyboard is not stdin though.  CONIN$ is the console's own input buffer,
# and a process can open it whatever its stdin happens to be, so the keys are
# read from there and .NET is not asked for an opinion.  The macOS flasher
# needs none of this because its menu is the script doing the asking, not a
# second process started by one.
#
# Anything going wrong here means no arrow menu, which is exactly what this
# replaces: the caller sees no answer file and asks in its own way.
try {
    # No -PassThru: it hands back every type in the assembly, the nested struct
    # included, and :: on a two-element array is not a static call.
    Add-Type -Namespace Rewired -Name Conin -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
public static extern IntPtr CreateFileW(string name, uint access, uint share,
    IntPtr security, uint disposition, uint flags, IntPtr template);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool ReadConsoleInputW(IntPtr handle, out KeyRecord record,
    uint count, out uint read);
// INPUT_RECORD.  The union after the event type is four-byte aligned, which
// is where the gap at offset 2 comes from; the offsets are written out rather
// than left to the compiler because getting them wrong reads plausible
// rubbish rather than failing.
[StructLayout(LayoutKind.Explicit)]
public struct KeyRecord {
    [FieldOffset(0)]  public ushort EventType;
    [FieldOffset(4)]  public int    KeyDown;
    [FieldOffset(8)]  public ushort RepeatCount;
    [FieldOffset(10)] public ushort VirtualKeyCode;
    [FieldOffset(12)] public ushort VirtualScanCode;
    [FieldOffset(14)] public char   UnicodeChar;
    [FieldOffset(16)] public uint   ControlKeyState;
}
'@
    # GENERIC_READ|GENERIC_WRITE, FILE_SHARE_READ|FILE_SHARE_WRITE,
    # OPEN_EXISTING.  In decimal, because PowerShell reads a hex literal that
    # fills 32 bits as a signed int: 0xC0000000 is -1073741824 to it, and
    # converting that to the uint the call wants throws.
    $conin = [Rewired.Conin]::CreateFileW('CONIN$', 3221225472, 3,
                                          [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)
    if ($conin -eq [IntPtr]::Zero -or $conin -eq [IntPtr](-1)) { exit }
} catch { exit }

# One key press, as a virtual key code and the character it typed.  Everything
# that is not a key going down - releases, mouse movement, the window being
# resized - is read past rather than mistaken for an answer.
function Read-Key {
    $rec = New-Object Rewired.Conin+KeyRecord
    $got = [uint32] 0
    while ($true) {
        if (-not [Rewired.Conin]::ReadConsoleInputW($conin, [ref] $rec, 1,
                                                    [ref] $got)) {
            return $null
        }
        if ($got -eq 1 -and $rec.EventType -eq 1 -and $rec.KeyDown -ne 0) {
            return @{ Code = [int] $rec.VirtualKeyCode; Char = $rec.UnicodeChar }
        }
    }
}

$sel = 0
$top = [Console]::CursorTop
$lines = Measure-Lines
# Drawing the last line of a full screen scrolls it, which would leave the
# cursor origin pointing at the wrong row on every redraw.
if ($top + $lines -ge [Console]::BufferHeight) {
    $top = [Math]::Max(0, [Console]::BufferHeight - $lines - 1)
}
[Console]::CursorVisible = $false
try {
    Write-Menu $sel
    while ($true) {
        $key = Read-Key
        if ($null -eq $key) { $sel = -1; break }
        $chosen = $false
        # VK_UP, VK_DOWN, VK_RETURN, VK_ESCAPE.
        switch ($key.Code) {
            38 { if ($sel -gt 0) { $sel-- } }
            40 { if ($sel -lt $entries.Count - 1) { $sel++ } }
            13 { $chosen = $true }
            27 { $sel = -1; $chosen = $true }
            default {
                $c = $key.Char
                if ($c -eq 'q' -or $c -eq 'Q') { $sel = -1; $chosen = $true }
                elseif ($c -ge '1' -and $c -le '9') {
                    $n = [int]::Parse($c)
                    if ($n -le $entries.Count) { $sel = $n - 1; $chosen = $true }
                }
            }
        }
        [Console]::SetCursorPosition(0, $top)
        Write-Menu $sel
        if ($chosen) { break }
    }
} finally {
    [Console]::CursorVisible = $true
}

if ($sel -lt 0) { [IO.File]::WriteAllText($Out, "0") }
else { [IO.File]::WriteAllText($Out, "$($sel + 1)") }
