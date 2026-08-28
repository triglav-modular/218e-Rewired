// Re-run the clock regressions with a pending musical-data save throughout.
// The firmware must not even enter the flash driver during performance.
//@category Buchla218.Tests
public class PersistenceClockRegression extends ClockRegression {
    @Override void fresh(int divisor,int hz) throws Exception {
        super.fresh(divisor,hz);
        w(0x613a,2,777);
        w(0x62e0,1,1);
    }
    @Override void step() throws Exception {
        if(pc()==0x800108fcL) throw new Exception("flash entered during clock performance");
        super.step();
    }
}
