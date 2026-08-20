<#
Enumerate flashable 218e images for the Windows flasher.

Windows always has PowerShell, so this keeps the flasher working on a machine
without Python, and it can sort by timestamp across several directories at
once - something cmd cannot do, since DIR only orders within one folder.

Validation mirrors tools/validate_hex.py exactly: every record's own checksum,
hex-only content, an end-of-file record, and data confined to the
AT32UC3B1256 application flash.  CI checks the two agree.

Prints one line per valid image, newest first:
    yyyy-MM-dd HH:mm|<sha256>|<full path>
#>
param(
    # One semicolon-separated string, not a string[].  powershell.exe -File
    # passes arguments literally, so -Dirs "a","b" arrives as the single string
    # a,b rather than an array, and every directory is then missed.
    [Parameter(Mandatory=$true)][string]$DirList,
    # Write a per-file verdict to stderr.  Silence is otherwise indistinguishable
    # from "found nothing", which is exactly the case that needs explaining.
    [switch]$Explain
)

$ErrorActionPreference = 'Stop'
# Decimal, and 64-bit: PowerShell parses an 8-digit hex literal as Int32, so
# 0x80002000 would arrive already wrapped to a negative number.
$APP_LOW  = [int64]2147491840   # 0x80002000
$APP_HIGH = [int64]2147745791   # 0x8003FFFF

function Test-IntelHex {
    param([string]$Path)
    $reason = 'ok' 
    $upper = 0; $lo = $null; $hi = $null; $sawEof = $false; $covered = 0; $prevEnd = $null; $FLASH_BASE = [int64]0x80000000
    try { $lines = [System.IO.File]::ReadAllLines($Path) } catch { return $false }
    if ($lines.Count -eq 0) { $script:LastReason = 'empty file'; return $false }
    foreach ($line in $lines) {
        $t = $line.Trim()
        if ($t.Length -eq 0) { continue }
        if ($t[0] -ne ':') { $script:LastReason = 'not an Intel HEX record'; return $false }
        $body = $t.Substring(1)
        if ($body.Length -lt 10 -or $body.Length % 2) { $script:LastReason = 'malformed record'; return $false }
        $raw = New-Object byte[] ($body.Length / 2)
        for ($i = 0; $i -lt $raw.Length; $i++) {
            try { $raw[$i] = [Convert]::ToByte($body.Substring($i * 2, 2), 16) }
            catch { $script:LastReason = 'non-hex characters'; return $false }
        }
        $sum = 0; foreach ($b in $raw) { $sum += $b }
        if ($sum -band 0xFF) { $script:LastReason = 'record checksum mismatch'; return $false }
        # [int] casts are load-bearing: -shl keeps the left operand's type, so
        # [byte]0x20 -shl 8 truncates back to a byte and yields 0, not 0x2000.
        $len = $raw[0]
        $addr = ([int]$raw[1] -shl 8) -bor [int]$raw[2]
        $kind = $raw[3]
        # A record that lies about its own length lies about its coverage.
        if ($raw.Length -ne $len + 5) {
            $script:LastReason = "record declares $len bytes but carries $($raw.Length - 5)"
            return $false
        }
        if ($sawEof) {
            $script:LastReason = "records after the end-of-file record - the flasher stops there and would never write them"
            return $false
        }
        # dfu-programmer keeps one address offset and lets type 4 AND type 5
        # set it, masking bit 31 off.  Type 5 is nominally the entry point, so
        # ignoring it here while the flasher acts on it is a way to approve one
        # file and write another.  FLASH_BASE puts the masked bit back.
        if ($kind -eq 4) {
            # Multiply rather than -shl 16: shifting 0x8000 left overflows
            # Int32 and lands negative, which put every app-region image
            # "below" 0x80002000 and rejected all of them.
            $upper = ([int64](([int]$raw[4] -shl 8) -bor [int]$raw[5]) * 65536) -band 0x7FFFFFFF
        } elseif ($kind -eq 5) {
            $upper = ([int64]$raw[4] * 16777216 + [int64]$raw[5] * 65536 +
                      [int64]$raw[6] * 256 + [int64]$raw[7]) -band 0x7FFFFFFF
        } elseif ($kind -eq 0) {
            $a = (($upper + [int64]$addr) -band 0x7FFFFFFF) + $FLASH_BASE
            if ($null -eq $lo -or $a -lt $lo) { $lo = $a }
            $end = $a + $len - 1
            if ($null -eq $hi -or $end -gt $hi) { $hi = $end }
            # Count real ground, not declared lengths: the same record
            # repeated would otherwise add up to a plausible image while
            # covering a few bytes of flash.
            if ($null -ne $prevEnd -and $a -lt $prevEnd) {
                $script:LastReason = "record overwrites flash already written - real images do not overlap"
                return $false
            }
            $prevEnd = $a + $len
            $covered += $len
        } elseif ($kind -eq 1) {
            $sawEof = $true
        } elseif ($kind -ne 5) {
            $script:LastReason = "record type $kind is not AVR32 firmware"
            return $false
        }
    }
    if (-not $sawEof) { $script:LastReason = 'no end-of-file record'; return $false }
    if ($null -eq $lo) { $script:LastReason = 'no data records'; return $false }
    if ($lo -lt $APP_LOW -or $hi -gt $APP_HIGH) {
        $script:LastReason = "outside the application flash (lo=$lo hi=$hi low=$APP_LOW high=$APP_HIGH)"
        return $false
    }
    # Valid records inside the window are not enough.  A four-byte file passed
    # all of the above, and passing means the flasher erases the application
    # and writes four bytes over it.  Real images start at the reset vector and
    # carry tens of thousands of bytes.
    if ($lo -ne $APP_LOW) {
        $script:LastReason = "starts at $lo, not the reset vector - a partial image would erase the application and not replace it"
        return $false
    }
    if ($covered -lt 16384) {
        $script:LastReason = "only $covered bytes of firmware - flashing this would leave the instrument unbootable"
        return $false
    }
    return $true
}

# Collected with the foreach STATEMENT, not ForEach-Object: assigning to a
# variable inside a ForEach-Object block writes to a copy scoped to that block,
# leaving the outer list empty.
$Dirs = $DirList -split ';' | Where-Object { $_ -ne '' }

$candidates = foreach ($dir in $Dirs) {
    if (-not (Test-Path -LiteralPath $dir)) { continue }
    Get-ChildItem -LiteralPath $dir -Filter *.hex -File -ErrorAction SilentlyContinue
}

$seen = @{}
$found = foreach ($f in $candidates) {
    $key = $f.FullName.ToLower()
    if ($seen.ContainsKey($key)) { continue }
    $seen[$key] = $true
    $script:LastReason = 'ok'
    if (Test-IntelHex $f.FullName) {
        if ($Explain) { [Console]::Error.WriteLine("ok      $($f.Name)") }
        $f
    } elseif ($Explain) {
        [Console]::Error.WriteLine("reject  $($f.Name): $script:LastReason")
    }
}

$found | Sort-Object LastWriteTime -Descending | Select-Object -First 12 | ForEach-Object {
    $sha = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLower()
    '{0}|{1}|{2}' -f $_.LastWriteTime.ToString('yyyy-MM-dd HH:mm'), $sha, $_.FullName
}
