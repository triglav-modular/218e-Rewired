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

if ($Title) { Write-Host ('  ' + $Title) }

# A console without a keyboard - piped, or a scheduled run - cannot be driven
# by arrow keys, so it gets a numbered list and a plain read.
$interactive = $true
try { $null = [Console]::CursorTop } catch { $interactive = $false }
if ([Console]::IsInputRedirected) { $interactive = $false }

# Without a keyboard there is nothing useful to do here, and reading the
# answer is not an option either: cmd buffers redirected input, so a child
# process started from a batch file sees none of what was piped into it.  Say
# nothing and write nothing - the caller notices the missing answer and asks
# in the way that does work under redirection.
if (-not $interactive) { exit }

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
        $key = [Console]::ReadKey($true)
        $chosen = $false
        switch ($key.Key) {
            'UpArrow'   { if ($sel -gt 0) { $sel-- } }
            'DownArrow' { if ($sel -lt $entries.Count - 1) { $sel++ } }
            'Enter'     { $chosen = $true }
            'Escape'    { $sel = -1; $chosen = $true }
            default {
                $c = $key.KeyChar
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
