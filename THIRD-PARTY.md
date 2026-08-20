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
| `dfu-programmer` 1.1.0 (macOS + Windows) | GPL-2.0-or-later | <https://dfu-programmer.github.io/> · <https://github.com/dfu-programmer/dfu-programmer> |
| `libusb` 1.0.29 (inside the Windows `dfu-programmer`; also `libusb-1.0.0.dylib` on macOS) | LGPL-2.1-or-later | <https://libusb.info/> · <https://github.com/libusb/libusb> |
| `SendMIDI` (macOS + Windows) | GPL-3.0 | <https://github.com/gbevin/SendMIDI> |
| `Zadig` 2.8 (Windows) | GPL-3.0 | <https://zadig.akeo.ie/> · <https://github.com/pbatard/libwdi> |

`dfu-programmer` and `libusb` are built here from unmodified upstream source
rather than taken from Buchla's kits. macOS gets a universal binary, x86_64
and arm64 in one file. Windows is cross-compiled with mingw-w64 and links
libusb and its C runtime statically, so it needs no `libusb-1.0.dll`, no
`msvcp140.dll` and no Visual C++ redistributable; the only DLLs it imports
are KERNEL32 and the Universal CRT, which are part of Windows itself from
Windows 10 on.

`SendMIDI` and `Zadig` are Buchla's kit binaries as published. Neither kit's
own material — the firmware image, Buchla's updater scripts, their
documentation — is redistributed here.

## Source code

**Written offer.** For any GPL or LGPL binary in this repository, you may
obtain the complete corresponding source code from the upstream project
linked above, which is where each project publishes the source for its
releases. If an upstream link has gone dark and you need the source for a
specific binary here, open an issue on this repository and it will be
provided.

None of these binaries has been modified. The two we build ourselves come
from these exact revisions:

| Binary | Source revision |
|---|---|
| `dfu-programmer` | commit `c204739`, one docs-only commit after the `v1.1.0` tag |
| `libusb` | tag `v1.0.29`, `libusb-1.0.29.tar.bz2` sha256 `5977fc950f8d1395ccea9bd48c06b3f808fd3c2c961b44b0c2e6e29fc3a70a85` |

### How the Windows build is produced

```sh
# libusb, static, no DLL
./bootstrap.sh
./configure --host=x86_64-w64-mingw32 --prefix="$PREFIX" \
            --enable-static --disable-shared --disable-udev
make && make install

# dfu-programmer, linked against it, runtime included
autoreconf -ivf
./configure --host=x86_64-w64-mingw32 \
            CPPFLAGS="-I$PREFIX/include" \
            LDFLAGS="-static -L$PREFIX/lib" \
            LIBS="-lsetupapi -lole32 -ladvapi32 -lcfgmgr32"
make
```

`configure` finds libusb with `AC_SEARCH_LIBS`, not pkg-config, which is why
the include path and libusb's own Windows libraries are given explicitly.

## Why these and not others

`VC_redist.x64.exe` and `VC_redist.x86.exe` ship in Buchla's kit but not here,
and neither does `msvcp140.dll`. Both existed to satisfy a Visual C++-built
`dfu-programmer`. Ours carries its runtime inside the executable, so there is
nothing left to install and 38 MB of Microsoft installer to leave out.

## Typefaces on the builder page

**IBM Plex Mono** — `web/fonts/`, SIL Open Font License 1.1, licence text
alongside the files. Used for checksums, cent values and anything else where
digits need to line up. Redistributed here, which the OFL permits.

**Euclid Circular A** — Swiss Typefaces, licensed to Triglav Modular. It is
**not** in this repository, and is not covered by the Unlicense. The page loads
it from `triglavmodular.hu`, where that licence already applies, which needs the
font files to be sent with a permissive CORS header.

That site runs **IIS behind Cloudflare**, so there are two places it can be set.
Cloudflare is the easier one and needs no server access:

**Cloudflare, transform rule** — Rules → Overview → Create rule → Modify
Response Header. Set *If* to `URI Path ends with .woff2`, and *Then* to Set
static, `Access-Control-Allow-Origin` =
`https://triglav-modular.github.io`.

That is the origin only — scheme and host, no path and no trailing slash. A
single origin is all the header can carry, which is enough here: the fonts are
same-origin on triglavmodular.hu itself, and same-origin requests are not
subject to CORS at all, so restricting this to the builder does not affect the
main site. If the builder ever moves to a custom domain, this value moves with
it.

**Cloudflare, snippet** — if transform rules are not offered, Rules → Snippets
does the same thing. Filter on `URI Path ends with .woff2` and use:

```js
export default {
  async fetch(request) {
    const response = await fetch(request);
    const out = new Response(response.body, response);
    out.headers.set("Access-Control-Allow-Origin",
                    "https://triglav-modular.github.io");
    return out;
  }
};
```

Either way the files are cached at the edge (`cf-cache-status: HIT`), so purge
the cache afterwards or the old header-less responses keep being served until
they expire.

**IIS** — a `web.config` in `wp-content/uploads/`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
  <system.webServer>
    <httpProtocol>
      <customHeaders>
        <add name="Access-Control-Allow-Origin" value="https://triglav-modular.github.io" />
      </customHeaders>
    </httpProtocol>
  </system.webServer>
</configuration>
```

To check it worked:

```bash
curl -sI https://triglavmodular.hu/wp-content/uploads/EuclidCircularA-Regular-WebXL.woff2 \
  | grep -i access-control
```

Without that header — and offline, opening `web/index.html` from a clone — the
page falls back to the system sans and everything still works. Nothing depends
on the face being present.
