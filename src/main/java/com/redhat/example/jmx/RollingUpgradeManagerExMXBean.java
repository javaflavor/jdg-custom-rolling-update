package com.redhat.example.jmx;

import java.util.List;

public interface RollingUpgradeManagerExMXBean {
    public void recordKnownGlobalKeyset(String cacheName) throws Exception;
    
    public void synchronizeData(String cacheName) throws Exception;
    
    public void disconnectSource(String cacheName) throws Exception;
}
