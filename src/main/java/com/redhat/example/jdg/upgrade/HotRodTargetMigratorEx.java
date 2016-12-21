package com.redhat.example.jdg.upgrade;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.commons.CacheException;
import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.distexec.DefaultExecutorService;
import org.infinispan.distexec.DistributedCallable;
import org.infinispan.distexec.DistributedExecutorService;
import org.infinispan.distexec.DistributedTask;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.persistence.manager.PersistenceManager;
import org.infinispan.persistence.remote.RemoteStore;
import org.infinispan.persistence.remote.configuration.RemoteStoreConfiguration;
import org.infinispan.remoting.transport.Address;
import org.infinispan.upgrade.TargetMigrator;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

public class HotRodTargetMigratorEx implements TargetMigrator {
	static Log log = LogFactory.getLog(HotRodTargetMigratorEx.class);
	static MigrationConfiguration config = MigrationConfiguration.getInstance();

	public HotRodTargetMigratorEx() {
	}

	@Override
	public String getName() {
		return "hotrod";
	}

	@Override
	public long synchronizeData(final Cache<Object, Object> cache) throws CacheException {
		try {
			CacheMode cm = cache.getCacheConfiguration().clustering().cacheMode();
			long count = 0;
			if (cm.isReplicated() || !cm.isClustered()) {
				count = new SynchronizeDataTask(cache).call();
			} else {
				// If cache mode is DIST, use a map/reduce task
				DistributedExecutorService des = new DefaultExecutorService(cache);
				Callable<Long> call = new SynchronizeDataTask();
				DistributedTask<Long> task = des.createDistributedTaskBuilder(call)
						.timeout(config.synchronizeTimeoutMin, TimeUnit.MINUTES).build();
				List<Future<Long>> futures = des.submitEverywhere(task);
				for (Future<Long> future : futures) {
					count += future.get(); // Collect total count.
				}
			}
			log.infof("Migrated total %d entries of cache %s, including primaries and backups.", count, cache.getName());
			return count;
		} catch (Exception e) {
			log.error("Unable to collect all known entries.", e);
			throw new CacheException("Unable to collect all known entries.", e);
		}
	}

	static class SynchronizeDataTask implements DistributedCallable<Object, Object, Long>, Serializable {
		private static final long serialVersionUID = -1066657803906434600L;

		Cache<Object, Object> cache;

		SynchronizeDataTask() {
		}

		SynchronizeDataTask(Cache<Object, Object> cache) {
			this.cache = cache;
		}

		@Override
		public Long call() throws Exception {
			return synchronizeData();
		}

		@Override
		public void setEnvironment(Cache<Object, Object> cache, Set<Object> inputKeys) {
			this.cache = cache;
		}

		public long synchronizeData() throws CacheException {
			log.infof("Start synchronizeData() for cache %s", cache.getName());
			int threads = Runtime.getRuntime().availableProcessors();
			ComponentRegistry cr = cache.getAdvancedCache().getComponentRegistry();
			PersistenceManager loaderManager = cr.getComponent(PersistenceManager.class);
			Set<RemoteStore> stores = loaderManager.getStores(RemoteStore.class);
			String knownKey = config.getKnownKey(cache.getName());
			for (RemoteStore store : stores) {
				final RemoteCache<Object, Object> keyCache = store.getRemoteCache().getRemoteCacheManager().getCache(config.getKeyCacheName(cache.getName()));
				log.infof("Source key cache: %s, target cache: %s", keyCache.getName(), cache.getName());
				RemoteStoreConfiguration storeConfig = store.getConfiguration();
				if (!storeConfig.hotRodWrapping()) {
					throw new CacheException(String.format(
							"The RemoteCacheStore for cache %s should be configured with hotRodWrapping enabled",
							cache.getName()));
				}
				try {
					Marshaller marshaller = (Marshaller)config.marshallerClass.newInstance();
					final AtomicInteger count = new AtomicInteger(0);
					int clusterSize = cache.getCacheManager().getMembers().size();
					int memberIndex = getMemberIndex();
					for (long partitionIndex = 0; partitionIndex < Long.MAX_VALUE; partitionIndex++) {
						byte[] knownKeys = marshaller
								.objectToByteBuffer(knownKey + partitionIndex);
						if (!keyCache.containsKey(knownKeys)) {
							log.debugf("Not found key '%s' in source cluster. Exit process.",
									knownKey + partitionIndex);
							break; // Exit loop.
						}
						if (config.maxNumKeys == 1) {
							// The entries of key cache has only single key.
							Object okey = marshaller.objectFromByteBuffer((byte[]) keyCache.get(knownKeys));
							byte[] key = marshaller.objectToByteBuffer(okey);
							log.infof("Processing keys: '%s' contains only single key.", knownKey + partitionIndex);
							try {
								if (!cache.getAdvancedCache().getDistributionManager().locate(key).contains(cache.getCacheManager().getAddress())) {
									// Ignore if this node is not for primary nor backup owner for the key.
									continue;
								}
								cache.get(key);
								int i = count.getAndIncrement();
								if (log.isDebugEnabled() && i % 100 == 0)
									log.debugf(">>    Moved %s keys\n", i);
							} catch (Exception e) {
								log.keyMigrationFailed(Util.toStr(key), e);
							}
						} else {
							// The entries of key cache has chunked multiple keys.
							@SuppressWarnings("unchecked")
							Set<Object> keys = (Set<Object>) marshaller
									.objectFromByteBuffer((byte[]) keyCache.get(knownKeys));
							log.infof("Processing keys: '%s' contains %d keys.", knownKey + partitionIndex,
									keys.size());

							ExecutorService es = Executors.newFixedThreadPool(threads);
							for (Object okey : keys) {
								final byte key[] = marshaller.objectToByteBuffer(okey);
								if (!cache.getAdvancedCache().getDistributionManager().locate(key).contains(cache.getCacheManager().getAddress())) {
									// Ignore if this node is not for primary nor backup owner for the key.
									continue;
								}
								es.submit(new Runnable() {

									@Override
									public void run() {
										try {
											cache.get(key);
											int i = count.getAndIncrement();
											if (log.isDebugEnabled() && i % 100 == 0)
												log.debugf(">>    Moved %s keys\n", i);
										} catch (Exception e) {
											log.keyMigrationFailed(Util.toStr(key), e);
										}
									}
								});
							}
							es.shutdown();
							while (!es.awaitTermination(500, TimeUnit.MILLISECONDS))
								;
						}
					}
					log.infof("Processed %d entries of cache %s", count.longValue(), cache.getName());
					return count.longValue();
				} catch (Exception e) {
					throw new CacheException(e);
				}
			}
			throw log.missingMigrationData(cache.getName());
		}

		int getMemberIndex() {
			Address self = cache.getCacheManager().getAddress();
			List<Address> members = cache.getCacheManager().getMembers();
			int index = 0;
			for (int i = 0; i < members.size(); i++) {
				if (self.equals(members.get(i))) {
					index = i;
					break;
				}
			}
			return index;
		}
	}

	@Override
	public void disconnectSource(Cache<Object, Object> cache) throws CacheException {
		ComponentRegistry cr = cache.getAdvancedCache().getComponentRegistry();
		PersistenceManager loaderManager = cr.getComponent(PersistenceManager.class);
		loaderManager.disableStore(RemoteStore.class.getName());
	}
}
