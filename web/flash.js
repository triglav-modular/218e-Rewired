// Getting the instrument into DFU, and talking to it once it is there.
//
// SCOPE: this file does the pre-flight only — ask the running firmware to
// reboot into DFU (Web MIDI), attach to the bootloader (WebUSB), and read its
// state.  It does NOT erase or program.  See web/README.md for why.
//
// Every request here is either a standard DFU 1.1 class request or an Atmel
// READ command.  Nothing in this file writes flash, fuses or security bits.
var FLASH = (function () {
    'use strict';

    var ATMEL_VID = 0x03EB;          // confirmed from dfu-programmer's own output
    var DFU_DETACH_SYSEX = [0xF0, 0x00, 0x02, 0x55, 0x02, 0x01, 0x01, 0xF7];

    // Standard DFU 1.1 class requests.
    var DFU = { DNLOAD: 1, UPLOAD: 2, GETSTATUS: 3, CLRSTATUS: 4, GETSTATE: 5, ABORT: 6 };
    var STATE_NAMES = {
        0: 'appIDLE', 1: 'appDETACH', 2: 'dfuIDLE', 3: 'dfuDNLOAD-SYNC',
        4: 'dfuDNBUSY', 5: 'dfuDNLOAD-IDLE', 6: 'dfuMANIFEST-SYNC',
        7: 'dfuMANIFEST', 8: 'dfuMANIFEST-WAIT-RESET', 9: 'dfuUPLOAD-IDLE',
        10: 'dfuERROR'
    };

    // --- step 1: ask the running firmware to reboot into the bootloader ---
    // The same SysEx ProgramLEM218_PressureFix.command sends. If the keyboard
    // is already in DFU there is no MIDI port and this is skipped.
    function enterDFU(log) {
        if (!navigator.requestMIDIAccess) {
            return Promise.reject(new Error(
                'This browser has no Web MIDI. Chrome, Edge or Safari 17.4+ are needed ' +
                'to ask the instrument to enter DFU.'));
        }
        return navigator.requestMIDIAccess({ sysex: true }).then(function (midi) {
            var port = null;
            midi.outputs.forEach(function (o) {
                if (!port && /218e/i.test(o.name || '')) port = o;
            });
            if (!port) {
                throw new Error(
                    'No 218e MIDI output found.\n\n' +
                    'If the instrument is already in DFU that is expected — skip this step. ' +
                    'Otherwise check it is powered and connected by USB directly, not ' +
                    'through an unpowered hub.');
            }
            log('Asking "' + port.name + '" to enter DFU…');
            port.send(DFU_DETACH_SYSEX);
            log('SysEx sent. The instrument should disappear from MIDI and ' +
                'reappear as a USB DFU device.');
        });
    }

    // --- step 2: attach to the bootloader ---------------------------------
    function connect(log) {
        if (!navigator.usb) {
            return Promise.reject(new Error(
                'This browser has no WebUSB. Chrome or Edge are needed; Safari and ' +
                'Firefox have both declined to implement it.'));
        }
        var device;
        return navigator.usb.requestDevice({ filters: [{ vendorId: ATMEL_VID }] })
            .then(function (d) {
                device = d;
                log('Found ' + (d.productName || 'Atmel DFU device') +
                    ' (VID 0x' + d.vendorId.toString(16) +
                    ', PID 0x' + d.productId.toString(16) + ')');
                return d.open();
            })
            .then(function () {
                return device.configuration ? null : device.selectConfiguration(1);
            })
            .then(function () { return device.claimInterface(0); })
            .then(function () { return { device: device, iface: 0 }; });
    }

    function getStatus(session) {
        return session.device.controlTransferIn({
            requestType: 'class', recipient: 'interface',
            request: DFU.GETSTATUS, value: 0, index: session.iface
        }, 6).then(function (result) {
            var d = result.data;
            return {
                status: d.getUint8(0),
                pollTimeout: d.getUint8(1) | (d.getUint8(2) << 8) | (d.getUint8(3) << 16),
                state: d.getUint8(4),
                stateName: STATE_NAMES[d.getUint8(4)] || ('unknown(' + d.getUint8(4) + ')')
            };
        });
    }

    function clearStatus(session) {
        return session.device.controlTransferOut({
            requestType: 'class', recipient: 'interface',
            request: DFU.CLRSTATUS, value: 0, index: session.iface
        });
    }

    // Read the bootloader's idea of where it is, clearing a latched error
    // first so a previous aborted session does not look like a fault.
    function readState(session, log) {
        return getStatus(session).then(function (s) {
            if (s.state === 10) {
                log('Bootloader was in dfuERROR (status ' + s.status + ') — clearing.');
                return clearStatus(session).then(function () { return getStatus(session); });
            }
            return s;
        }).then(function (s) {
            log('Bootloader state: ' + s.stateName + ' (status ' + s.status + ')');
            return s;
        });
    }

    function disconnect(session) {
        if (!session || !session.device) return Promise.resolve();
        return session.device.releaseInterface(session.iface)
            .catch(function () {})
            .then(function () { return session.device.close(); })
            .catch(function () {});
    }

    return {
        enterDFU: enterDFU, connect: connect, readState: readState,
        getStatus: getStatus, disconnect: disconnect,
        available: {
            midi: typeof navigator !== 'undefined' && !!navigator.requestMIDIAccess,
            usb: typeof navigator !== 'undefined' && !!navigator.usb
        }
    };
})();
