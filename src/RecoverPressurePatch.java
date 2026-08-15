// Recover and name the functions injected by the 218e pressure patch.
//@category Buchla218

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.SourceType;

public class RecoverPressurePatch extends GhidraScript {
    private void recover(long rawAddress, String name) throws Exception {
        Address address = toAddr(rawAddress);
        new DisassembleCommand(address, null, true).applyTo(currentProgram, monitor);
        if (getFunctionAt(address) == null) {
            new CreateFunctionCmd(address).applyTo(currentProgram, monitor);
        }
        if (getFunctionAt(address) == null) {
            throw new IllegalStateException("Failed to create function at " + address);
        }
        getFunctionAt(address).setName(name, SourceType.USER_DEFINED);
        println(name + " at " + address);
    }

    @Override
    protected void run() throws Exception {
        recover(0x80014300L, "edit_knob3_pressure_floor");
        recover(0x80014380L, "edit_knob4_pressure_curve");
        recover(0x80018d00L, "note_on_reset_pressure_filter");
        recover(0x80018d40L, "source_change_reset_pressure_filter");
        recover(0x800194c0L, "edit_knob1_pressure_ceiling");
        recover(0x80019580L, "pressure_calibrated_curve");
        recover(0x80019740L, "edit_mode_pressure_telemetry");
        recover(0x80019940L, "send_usb_midi_14bit");
        recover(0x80019980L, "pitch_remap_utune");
        recover(0x80019a40L, "tuning_applier_tables");
        recover(0x80019c64L, "pressure_blend_continuum");
        recover(0x80019d44L, "arp_random_knobs");
        recover(0x8001a020L, "arp_order_list");
        recover(0x8001a0a0L, "arp_order_selector");
        analyzeChanges(currentProgram);
    }
}
