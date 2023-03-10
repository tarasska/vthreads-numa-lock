package ru.ricnorr.numa.locks.tas_cna;

import ru.ricnorr.numa.locks.NumaLock;
import ru.ricnorr.numa.locks.Utils;
import ru.ricnorr.numa.locks.basic.TestTestAndSetLock;
import ru.ricnorr.numa.locks.cna.CNANodeNoPad;
import ru.ricnorr.numa.locks.cna.CnaNumaNoPad;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Комбинация TTAS на уровне CCL и CNA на уровне NUMA
 */
public class TtasCclAndCnaNuma implements NumaLock {

    final TestTestAndSetLock[] ttasLocksForCcl;

    final CnaNumaNoPad cnaNumaNoPad;

    final boolean isLight;

    ThreadLocal<Integer> cclIdThreadLocal = ThreadLocal.withInitial(Utils::getKunpengCCLId);

    public TtasCclAndCnaNuma(boolean useLightThreads) {
        ttasLocksForCcl = new TestTestAndSetLock[Runtime.getRuntime().availableProcessors() / 4];
        isLight = useLightThreads;
        for (int i = 0; i < ttasLocksForCcl.length; i++) {
            ttasLocksForCcl[i] = new TestTestAndSetLock();
        }
        cnaNumaNoPad = new CnaNumaNoPad();
    }

    @Override
    public Object lock() {
        var carrierThread = Utils.getCurrentCarrierThread();
        if (ThreadLocalRandom.current().nextInt() % 5431 == 0) {
            Utils.setByThreadToThreadLocal(cclIdThreadLocal, carrierThread, Utils.getKunpengCCLId());
        }
        var cclId = Utils.getByThreadFromThreadLocal(cclIdThreadLocal, carrierThread);
        ttasLocksForCcl[cclId].lock();
        var node = cnaNumaNoPad.lock();
        return new Pair((CNANodeNoPad) node, cclId);
    }

    @Override
    public void unlock(Object obj) {
        var pair = (Pair) obj;
        ttasLocksForCcl[pair.cclId].unlock(null);
        cnaNumaNoPad.unlock(pair.cnaNodeNoPad);
    }

    private class Pair {
        final CNANodeNoPad cnaNodeNoPad;

        final int cclId;

        public Pair(CNANodeNoPad cnaNodeNoPad, int cclId) {
            this.cnaNodeNoPad = cnaNodeNoPad;
            this.cclId = cclId;
        }
    }
}
