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
static, `Access-Control-Allow-Origin` = `*`.

**Cloudflare, snippet** — if transform rules are not offered, Rules → Snippets
does the same thing. Filter on `URI Path ends with .woff2` and use:

```js
export default {
  async fetch(request) {
    const response = await fetch(request);
    const out = new Response(response.body, response);
    out.headers.set("Access-Control-Allow-Origin", "*");
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
        <add name="Access-Control-Allow-Origin" value="*" />
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
