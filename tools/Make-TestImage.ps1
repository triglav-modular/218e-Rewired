<#
  Write a structurally plausible AT32UC3B1256 image for the CI fixtures.

  The fixtures used to be three echo lines carrying sixteen bytes, which the
  validators accepted - and accepting means the flasher erases the application
  and writes those sixteen bytes.  Now that a real coverage floor is enforced,
  a fixture has to look like firmware: start at the reset vector and carry
  enough of it to be worth flashing.
#>
param(
    [Parameter(Mandatory = $true)][string]$Path,
    [int]$Bytes = 20480,
    [byte]$Fill = 0x5A
)
$APP_LOW = 0x2000            # low half of 0x80002000, the upper half is the ELA
$sb = [System.Text.StringBuilder]::new()

function Add-Record([byte]$len, [int]$addr, [byte]$kind, [byte[]]$data) {
    $rec = @($len, [byte](($addr -shr 8) -band 0xFF), [byte]($addr -band 0xFF), $kind) + $data
    $sum = 0; foreach ($b in $rec) { $sum += $b }
    $rec += [byte]((-$sum) -band 0xFF)
    [void]$sb.AppendLine(':' + (($rec | ForEach-Object { '{0:X2}' -f $_ }) -join ''))
}

Add-Record 2 0 4 @(0x80, 0x00)
$written = 0; $addr = $APP_LOW
while ($written -lt $Bytes) {
    $n = [Math]::Min(16, $Bytes - $written)
    Add-Record ([byte]$n) $addr 0 (,[byte]$Fill * $n)
    $addr += $n; $written += $n
}
Add-Record 0 0 1 @()
[System.IO.File]::WriteAllText($Path, $sb.ToString())
Write-Host "wrote $Path ($written bytes of firmware)"
