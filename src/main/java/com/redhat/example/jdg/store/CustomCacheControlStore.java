package com.redhat.example.jdg.store;

import java.util.concurrent.Executor;

import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.persistence.spi.AdvancedLoadWriteStore;
import org.infinispan.persistence.spi.InitializationContext;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import com.redhat.example.jmx.MXBeanFactory;

public class CustomCacheControlStore  implements AdvancedLoadWriteStore {
	static Log log = LogFactory.getLog(CustomCacheControlStore.class);
	
	InitializationContext context;
	MXBeanFactory factory = new MXBeanFactory();
	EmbeddedCacheManager manager;

	@Override
	public void init(InitializationContext context) {
		log.infof("### init(): context=%s", context);
		this.context = context;
		Cache cache = context.getCache();
		manager = cache.getCacheManager();
		log.infof("### init(): manager=%s, cache=%s", manager, cache.getClass());
	}

	@Override
	public boolean contains(Object key) {
		return false;
	}

	@Override
	public MarshalledEntry load(Object key) {
		return null;
	}

	@Override
	public void start() {
		log.info("### start()");
		factory.registerMBean(manager);
	}

	@Override
	public void stop() {
		factory.unregisterMBean();
	}

	@Override
	public boolean delete(Object key) {
		return false;
	}

	@Override
	public void write(MarshalledEntry entry) {
	}

	@Override
	public void process(KeyFilter filter, CacheLoaderTask task, Executor executor,
			boolean fetchValue, boolean fetchMetaData) {
	}

	@Override
	public int size() {
		return 0;
	}

	@Override
	public void clear() {
	}

	@Override
	public void purge(Executor executor, PurgeListener listener) {
	}
	
	public Object getField(Object o, String field) {
		return null;
	}

}
