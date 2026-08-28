// Re-run the clock regressions while a changed preset is still held.
// An unfinished edit must not write; its release is tested separately.
//@category Buchla218.Tests
public class PersistenceClockRegression extends ClockRegression {
    @Override void fresh(int divisor,int hz) throws Exception {
        super.fresh(divisor,hz);
        w(0x613a,2,777);
        w(S+0x30a,2,777);
        w(0x46f0,1,2);
        w(0x614a,1,1);
        w(0x62f9,1,1);
    }
    @Override void step() throws Exception {
        if(pc()==0x800108fcL) throw new Exception("unfinished preset edit entered flash");
        super.step();
    }
}
