<#
    Drives the real arrow menu and checks the answer it writes.

    The menu had never once run on Windows: it needs a key, .NET will not read
    one while stdin is redirected, and the flasher redirects the helper's stdin
    from NUL on purpose.  Both times that was found it was found by someone
    running the flasher, because nothing here could see it - CI captures the
    output of every step, and the helper stands aside when its output is
    captured.

    So this test builds the situation instead of waiting for it: give this
    process a console of its own, put key presses into that console's input
    buffer, and run Show-Menu.ps1 against it with its stdin redirected exactly
    as the flasher redirects it.  The answer lands in a file, which is how the
    helper reports one, so nothing needs to read the screen.
#>
param([string] $Report = '')

$ErrorActionPreference = 'Stop'

Add-Type -Namespace Rewired -Name Test -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool AllocConsole();
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool FreeConsole();
[DllImport("kernel32.dll", SetLastError = true)] public static extern IntPtr GetStdHandle(int which);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetStdHandle(int which, IntPtr h);
[DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
public static extern IntPtr CreateFileW(string name, uint access, uint share,
    IntPtr security, uint disposition, uint flags, IntPtr template);
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool WriteConsoleInputW(IntPtr handle, KeyRecord[] records,
    uint count, out uint written);
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

# The offsets are written out by hand in both scripts.  If they are wrong the
# reads still succeed and return plausible rubbish, so check the size the
# runtime settled on against what the API expects.
$size = [Runtime.InteropServices.Marshal]::SizeOf([Type] 'Rewired.Test+KeyRecord')
if ($size -ne 20) { Write-Output "FAILED: INPUT_RECORD is $size bytes, expected 20"; exit 1 }

# What this prints has to outlive the console it is about to attach to, so
# the real stdout is put aside first and every message is held until it is
# back.  Without this the step running the test captured an empty file and
# could report only that something had gone wrong.
$stdout = [Rewired.Test]::GetStdHandle(-11)
$stderr = [Rewired.Test]::GetStdHandle(-12)
$said = @()
function Finish([int] $code, [string] $what) {
    $null = [Rewired.Test]::SetStdHandle(-11, $stdout)
    $null = [Rewired.Test]::SetStdHandle(-12, $stderr)
    $null = [Rewired.Test]::FreeConsole()
    if ($script:dir -and (Test-Path $script:dir)) {
        Remove-Item -Recurse -Force $script:dir -ErrorAction SilentlyContinue
    }
    $lines = $script:said + @($what)
    # To a file as well as to stdout: whether a restored handle carries the
    # text back to whoever started this is precisely the thing that went wrong
    # the first time, and a test that cannot say why it failed is not much of
    # a test.
    if ($Report) {
        Set-Content -Path $Report -Value $lines -ErrorAction SilentlyContinue
    }
    foreach ($line in $lines) { [Console]::Out.WriteLine($line) }
    [Console]::Out.Flush()
    exit $code
}

$null = [Rewired.Test]::FreeConsole()
if (-not [Rewired.Test]::AllocConsole()) {
    Finish 0 'SKIP: no console could be allocated on this machine'
}

# The child inherits these, and the helper stands aside if its output is
# captured - so both ends have to be the console itself.
$conin  = [Rewired.Test]::CreateFileW('CONIN$',  3221225472, 3, [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)
$conout = [Rewired.Test]::CreateFileW('CONOUT$', 3221225472, 3, [IntPtr]::Zero, 3, 0, [IntPtr]::Zero)
if ($conin -eq [IntPtr](-1) -or $conout -eq [IntPtr](-1)) {
    Finish 0 'SKIP: the allocated console has no usable handles'
}
$null = [Rewired.Test]::SetStdHandle(-11, $conout)   # STD_OUTPUT_HANDLE
$null = [Rewired.Test]::SetStdHandle(-12, $conout)   # STD_ERROR_HANDLE

function New-Key([int] $code, [char] $ch) {
    $r = New-Object Rewired.Test+KeyRecord
    $r.EventType = 1          # KEY_EVENT
    $r.KeyDown = 1
    $r.RepeatCount = 1
    $r.VirtualKeyCode = $code
    $r.UnicodeChar = $ch
    return $r
}

$dir = Join-Path $env:TEMP ('rewired_menu_' + [Guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $dir
$menu = Join-Path $dir 'menu.txt'
$out  = Join-Path $dir 'pick.txt'
Set-Content -Path $menu -Value @('first entry', 'about the first', '--',
                                 'second entry', 'about the second', '--',
                                 'third entry')

# Down, down, up, enter: lands on the second entry, and proves the arrows move
# in both directions rather than the first key happening to be the answer.
$keys = @((New-Key 40 ([char] 0)), (New-Key 40 ([char] 0)),
          (New-Key 38 ([char] 0)), (New-Key 13 ([char] 13)))
$written = [uint32] 0
if (-not [Rewired.Test]::WriteConsoleInputW($conin, $keys, [uint32] $keys.Count, [ref] $written)) {
    Finish 1 ('FAILED: could not put key presses into the console, error ' +
              [Runtime.InteropServices.Marshal]::GetLastWin32Error())
}
$said += "wrote $written of $($keys.Count) key events" 

# Stdin from NUL, which is what made .NET refuse to read a key in the first
# place.  Testing it any other way would test something the flasher never does.
$empty = Join-Path $dir 'empty.txt'
Set-Content -Path $empty -Value '' -NoNewline
$show = Join-Path $PSScriptRoot 'Show-Menu.ps1'
try {
    $errs = Join-Path $dir 'stderr.txt'
    $p = Start-Process -FilePath 'powershell' -PassThru -Wait -NoNewWindow `
        -RedirectStandardInput $empty -RedirectStandardError $errs `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $show,
                        '-Path', $menu, '-Out', $out, '-Title', 'pick one')
    $said += "the helper exited $($p.ExitCode)"
    if (Test-Path $errs) {
        $text = (Get-Content $errs -Raw)
        if ($text -and $text.Trim()) {
            $said += '--- what the helper said on stderr ---'
            $said += $text.Trim()
            $said += '--------------------------------------'
        }
    }
} catch {
    Finish 1 ('FAILED: could not start the helper: ' + $_.Exception.Message)
}

if (-not (Test-Path $out)) {
    Finish 1 'FAILED: the menu wrote no answer - it stood aside on a real console'
}
$pick = (Get-Content $out -Raw).Trim()
if ($pick -ne '2') {
    Finish 1 "FAILED: arrow keys chose [$pick], expected 2"
}
Finish 0 'PASS: the arrow menu reads keys and answers with the right entry'
