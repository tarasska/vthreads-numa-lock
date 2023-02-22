package ru.ricnorr.numa.locks.hclh;


import ru.ricnorr.numa.locks.Utils;

public class HCLHNuma extends AbstractHCLHLock {
    public HCLHNuma(boolean isLight) {
        super(isLight, Utils::getClusterID);
    }
}
