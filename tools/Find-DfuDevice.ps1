<#
Report whether the Atmel DFU device is on the USB bus, and which driver owns it.

dfu-programmer can only say "no device present", which conflates three very
different situations: the instrument is not in DFU, it is in DFU but no driver
is bound, or it is in DFU behind the wrong driver.  Windows knows the
difference, so ask Windows.

The AT32UC3B bootloader enumerates as VID 03EB (Atmel) PID 2FF6.  Prints one
line:

    ABSENT
    PRESENT|<driver service>|<status>|<friendly name>

A driver service of WinUSB is what libusb-1.0 needs.  Anything else - usbccgp,
a vendor MIDI driver, or nothing at all - means dfu-programmer cannot open it.
#>
$ErrorActionPreference = 'SilentlyContinue'

$dev = Get-PnpDevice -PresentOnly |
       Where-Object { $_.InstanceId -match 'USB\\VID_03EB&PID_2FF6' } |
       Select-Object -First 1

if (-not $dev) {
    # Some systems report the bootloader without the PID matching, so fall back
    # to any Atmel device whose name mentions DFU.
    $dev = Get-PnpDevice -PresentOnly |
           Where-Object { $_.InstanceId -match 'VID_03EB' -and $_.FriendlyName -match 'DFU' } |
           Select-Object -First 1
}

if (-not $dev) { 'ABSENT'; exit 0 }

$service = (Get-PnpDeviceProperty -InstanceId $dev.InstanceId -KeyName 'DEVPKEY_Device_Service').Data
if (-not $service) { $service = '(none)' }
'PRESENT|{0}|{1}|{2}' -f $service, $dev.Status, $dev.FriendlyName
