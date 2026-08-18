# Third-party tools

This repository's own work is in the public domain — see [UNLICENSE](UNLICENSE).
The flashing tools bundled with it are **not** ours, and each keeps its own
licence. They are redistributed here because their licences permit it, and
because a flasher you have to assemble from three downloads is a flasher people
get wrong.

Nothing here is legal advice.

## The tools

| Tool | Licence | Upstream |
|---|---|---|
| `dfu-programmer` (macOS + Windows) | GPL-2.0-or-later | <https://dfu-programmer.github.io/> · <https://github.com/dfu-programmer/dfu-programmer> |
| `SendMIDI` (macOS + Windows) | GPL-3.0 | <https://github.com/gbevin/SendMIDI> |
| `Zadig` 2.8 (Windows) | GPL-3.0 | <https://zadig.akeo.ie/> · <https://github.com/pbatard/libwdi> |
| `libusb` (`libusb-1.0.dll`, `libusb-*.dylib`) | LGPL-2.1-or-later | <https://libusb.info/> · <https://github.com/libusb/libusb> |
| `msvcp140.dll` (Windows) | Microsoft VC++ redistributable terms | <https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist> |

The macOS binaries came from Buchla's macOS flashing kit; the Windows ones from
their Windows kit. Neither kit's own material — the firmware image, Buchla's
updater scripts, their documentation — is redistributed here.

## Source code

**Written offer.** For any GPL or LGPL binary in this repository, you may
obtain the complete corresponding source code from the upstream project linked
above, which is where these binaries were built from and where each project
publishes the source for its releases. If an upstream link has gone dark and
you need the source for a specific binary here, open an issue on this
repository and it will be provided.

None of these binaries has been modified.

## Why these and not others

`VC_redist.x64.exe` and `VC_redist.x86.exe` ship in Buchla's kit but not here:
they are 38 MB of Microsoft installer, and Microsoft distributes them
themselves at the link above. `msvcp140.dll` is included because the flasher
needs the runtime present and app-local deployment is the normal way to
satisfy that.
