// Verification probe: the pad-4 chord's armed freeze must hold at the
// SOURCE - the factory press-time selection at 0x8000a784 - not merely be
// repaired a scan later.  Runs the real routine with the arm set and clear.
// Usage: -postScript PadGuardProbe.java
//@category Buchla218.Tests
import java.util.*;

public class PadGuardProbe extends ControlRegression {
    public void run() throws Exception {
        setup(0,false,0);
        w(S+0x342,1,1); w(S+0x2ef,1,1);           // octaves, pad 2 active
        w(0x6154,2,300); w(0x6156,1,1); w(0x6159,1,1); // chord armed, holds pad 2
        e.writeRegister("R12",2); call(0x8000a784L,0x100);
        long armed=r(S+0x2ef,1);
        call(0x80003590L);
        long target=r(S+0x352,2);
        println("armed press of pad 3: active="+armed+" target="+target);
        w(0x6156,1,0);
        e.writeRegister("R12",2); call(0x8000a784L,0x100);
        long free=r(S+0x2ef,1);
        println("unarmed press of pad 3: active="+free);
        println((armed==1&&free==2 ? "GUARD HOLDS" : "GUARD FAILS")
                +": armed="+armed+" (want 1), unarmed="+free+" (want 2)");
        println("PAD GUARD PROBE DONE");
    }
}
