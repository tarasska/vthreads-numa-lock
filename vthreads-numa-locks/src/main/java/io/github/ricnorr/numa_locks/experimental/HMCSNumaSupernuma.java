package io.github.ricnorr.numa_locks.experimental;

import java.util.ArrayList;
import java.util.List;

import io.github.ricnorr.numa_locks.LockUtils;

public class HMCSNumaSupernuma extends AbstractHMCS {


  public HMCSNumaSupernuma() {
    super(HMCSQNode::new, LockUtils::getNumaNodeId, LockUtils.NUMA_NODES_CNT);
    int superNumaCnt = LockUtils.NUMA_NODES_CNT / 2;
    var root = new HNode(null, new HMCSQNode());
    List<HNode> superNumaNodes = new ArrayList<>();
    for (int i = 0; i < superNumaCnt; i++) {
      superNumaNodes.add(new HNode(root, new HMCSQNode()));
    }
    for (int i = 0; i < LockUtils.NUMA_NODES_CNT; i++) {
      leafs[i] = new HNode(superNumaNodes.get(i / superNumaCnt), new HMCSQNode());
    }
  }
}
