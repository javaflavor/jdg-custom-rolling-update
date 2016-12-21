package com.redhat.example.jmx;

import java.lang.management.ManagementFactory;

import javax.annotation.PreDestroy;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

public class MXBeanFactory {
	static Log log = LogFactory.getLog(MXBeanFactory.class);
	
	static final String MBEAN_NAME = "com.redhat.example:name=RollingUpgradeManagerEx";
	
	MBeanServer mbeanServer;
	ObjectName objectName;
	
	RollingUpgradeManagerExImpl rollingUpgradeManagerEx;
	
    public void registerMBean(EmbeddedCacheManager manager) {
        try {
            objectName = new ObjectName(MBEAN_NAME);
            mbeanServer = ManagementFactory.getPlatformMBeanServer();
            
            rollingUpgradeManagerEx = new RollingUpgradeManagerExImpl(manager);
            mbeanServer.registerMBean(rollingUpgradeManagerEx, objectName);
            log.infof("### Custom MBean registered: %s", objectName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to register monitoring MBean.", e);
        }
    }

    @PreDestroy
    public void unregisterMBean() {
        try {
            mbeanServer.unregisterMBean(objectName);
            log.infof("### Custom MBean unregistered: %s", objectName);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to unregister monitoring MBean.", e);
        }
    }
}
