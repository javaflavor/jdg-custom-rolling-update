package com.redhat.example.jmx;

import org.infinispan.factories.ComponentRegistry;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.persistence.manager.PersistenceManager;
import org.infinispan.persistence.remote.RemoteStore;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import com.redhat.example.jdg.upgrade.HotRodSourceMigratorEx;
import com.redhat.example.jdg.upgrade.HotRodTargetMigratorEx;

public class RollingUpgradeManagerExImpl implements RollingUpgradeManagerExMXBean {
	static Log log = LogFactory.getLog(RollingUpgradeManagerExImpl.class);
	
	EmbeddedCacheManager manager;

	public RollingUpgradeManagerExImpl(EmbeddedCacheManager manager) {
		this.manager = manager;
	}
	
	@Override
	public void recordKnownGlobalKeyset(String cacheName) throws Exception {
		log.infof("### recordKnownGlobalKeyset('%s'): called.", cacheName);
		HotRodSourceMigratorEx migrator = new HotRodSourceMigratorEx(manager.getCache(cacheName));
		migrator.recordKnownGlobalKeyset();
	}

	@Override
	public void synchronizeData(String cacheName) throws Exception {
		log.infof("### synchronizeData('%s'): called.", cacheName);
		HotRodTargetMigratorEx migrator = new HotRodTargetMigratorEx();
		migrator.synchronizeData(manager.getCache(cacheName));
	}
	
	public void disconnectSource(String cacheName) throws Exception {
	      ComponentRegistry cr = manager.getCache(cacheName).getAdvancedCache().getComponentRegistry();
	      PersistenceManager loaderManager = cr.getComponent(PersistenceManager.class);
	      loaderManager.disableStore(RemoteStore.class.getName());		
	}
}
