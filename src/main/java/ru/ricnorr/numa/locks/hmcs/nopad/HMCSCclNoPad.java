package ru.ricnorr.numa.locks.hmcs.nopad;

import ru.ricnorr.numa.locks.Utils;
import ru.ricnorr.numa.locks.hmcs.AbstractHMCS;

/**
 * HMCS, иерархия только на ccl'ях, то есть поток берет лок на своей ccl, затем глобальный лок.
 */
public class HMCSCclNoPad extends AbstractHMCS<HMCSQNodeNoPad> {

    public HMCSCclNoPad() {
        super(HMCSQNodeNoPad::new, Utils::getKunpengCCLId, Utils.CCL_CNT);
        var root = new HNode(null, new HMCSQNodeNoPad());
        for (int i = 0; i < Utils.CCL_CNT; i++) {
            leafs[i] = new HNode(root, null);
        }
    }
}