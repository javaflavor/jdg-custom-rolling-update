package com.redhat.example.jdg.upgrade;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.infinispan.Cache;
import org.infinispan.commons.CacheException;
import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.commons.marshall.jboss.GenericJBossMarshaller;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.distexec.DefaultExecutorService;
import org.infinispan.distexec.DistributedCallable;
import org.infinispan.distexec.DistributedExecutorService;
import org.infinispan.distexec.DistributedTask;
import org.infinispan.upgrade.SourceMigrator;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * 
 */
public class HotRodSourceMigratorEx implements SourceMigrator {
	static Log log = LogFactory.getLog(HotRodSourceMigratorEx.class);
	static MigrationConfiguration config = MigrationConfiguration.getInstance();
	
	private final Cache<Object, Object> cache;

	public HotRodSourceMigratorEx(Cache<Object, Object> cache) {
		this.cache = cache;
	}

	@Override
	public void recordKnownGlobalKeyset() {
		try {
			// First of all, remove all previous KNOWN_KEY+i.
			removeAllrecordKeys();

			CacheMode cm = cache.getCacheConfiguration().clustering().cacheMode();
			if (cm.isReplicated() || !cm.isClustered()) {
				// If cache mode is LOCAL or REPL, dump local keyset.
				// Defensive copy to serialize and transmit across a network
				// keys = new HashSet<Object>(cache.keySet());
				new GlobalKeysetTaskEx(cache).call();
			} else {
				// If cache mode is DIST, use a map/reduce task
				DistributedExecutorService des = new DefaultExecutorService(cache);
				Callable<Object> call = new GlobalKeysetTaskEx();
				DistributedTask<Object> task = des.createDistributedTaskBuilder(call)
						.timeout(config.recordTimeoutMin, TimeUnit.MINUTES).build();
				List<Future<Object>> futures = des.submitEverywhere(task);

				for (Future<Object> future : futures) {
					future.get(); // Just wait the remote task.
				}

			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt(); // reset
		} catch (Exception e) {
			log.error("Unable to record all known keys.", e);
			throw new CacheException("Unable to record all known keys", e);
		}
	}

	void removeAllrecordKeys() throws Exception {
		Cache keyCache = cache.getCacheManager().getCache(config.getKeyCacheName(cache.getName()));
		log.infof("Removing previously recorded keys for '%s' in key cache '%s'.",
				cache.getName(), keyCache.getName());
		Marshaller marshaller = (Marshaller)config.marshallerClass.newInstance();
		for (int i = 0; i < Integer.MAX_VALUE; i++) {
			byte knownKey[] = marshaller.objectToByteBuffer(config.getKnownKey(cache.getName()) + i);
			if (keyCache.containsKey(knownKey)) {
				keyCache.remove(knownKey);
			} else {
				break;
			}
		}
	}

	@Override
	public String getCacheName() {
		return cache.getName();
	}
	
	static class GlobalKeysetTaskEx implements DistributedCallable<Object, Object, Object>, Serializable {
		private static final long serialVersionUID = 3417322994971069900L;

		Cache<Object, Object> cache;
		Marshaller marshaller;

		GlobalKeysetTaskEx() {
		}

		GlobalKeysetTaskEx(Cache<Object, Object> cache) {
			this.cache = (Cache<Object, Object>) cache;
		}

		@Override
		public void setEnvironment(Cache<Object, Object> cache, Set<Object> inputKeys) {
			this.cache = (Cache<Object, Object>) cache;
		}

		@Override
		public Object call() throws Exception {
			// Do not use huge keySet as return value.
			// Instead, store it in an entry here.
			// return new HashSet<Object>(cache.keySet());
			storeKeysAsPertitioned();
			return null; // Does not use return value.
		}

		void storeKeysAsPertitioned() throws Exception {
			marshaller = (Marshaller)config.marshallerClass.newInstance();
			Set<?> keys = cache.keySet();
			Cache<Object,Object> keyCache = cache.getCacheManager().getCache(config.getKeyCacheName(cache.getName()));
			Set<Object> partitionedKeys = new HashSet<Object>();
			String knownKey = config.getKnownKey(cache.getName()); 
			long partitionIndex = 0;
			int count = 0;
			for (Object key : keys) {
				Object okey = marshaller.objectFromByteBuffer((byte[])key);
				if (okey instanceof String && ((String)okey).startsWith(knownKey)) {
					log.tracef("Ignoring saved key: %s", okey);
					continue;
				}
				if (!cache.getAdvancedCache().getDistributionManager().getPrimaryLocation(key).equals(cache.getCacheManager().getAddress())) {
					log.tracef("Ignoring non-primary key: %s", okey);
					continue;
				}
				partitionedKeys.add(okey);
				count++;
				if (count == config.maxNumKeys) {
					// Challenge saving partitioned keys.
					// Should be more efficient way to avoid key name collision?
					byte keybytes[] = marshaller.objectToByteBuffer(knownKey + partitionIndex);
					byte valbytes[] = config.maxNumKeys == 1 ?
							marshaller.objectToByteBuffer(partitionedKeys.iterator().next()) :	// use key itself.
							marshaller.objectToByteBuffer(partitionedKeys);						// use List<key>.
					while (keyCache.putIfAbsent(keybytes, valbytes) != null) {
						keybytes = marshaller.objectToByteBuffer(knownKey + (++partitionIndex));
					}
					log.infof("Saved partitioned keyset (count = %d) for '%s' as key '%s' in cache '%s'",
							count, cache.getName(), knownKey + partitionIndex, keyCache.getName());
					// Reset partition info.
					partitionedKeys.clear();
					count = 0;
				}
				if (config.dumpkeysSleepPerEntries > 0 && config.dumpkeysSleepMs > 0 &&
						count % config.dumpkeysSleepPerEntries == 0) {
					log.tracef("Sleeping %d (ms)", config.dumpkeysSleepMs);
					TimeUnit.MILLISECONDS.sleep(config.dumpkeysSleepMs);
				}
			}
			if (partitionedKeys.size() > 0) {
				// Save the last partitioned keys.
				byte keybytes[] = marshaller.objectToByteBuffer(knownKey + partitionIndex);
				byte valbytes[] = config.maxNumKeys == 1 ?
						marshaller.objectToByteBuffer(partitionedKeys.iterator().next()) :	// use key itself.
						marshaller.objectToByteBuffer(partitionedKeys);						// use List<key>.
				while (keyCache.putIfAbsent(keybytes, valbytes) != null) {
					keybytes = marshaller.objectToByteBuffer(knownKey + (++partitionIndex));
				}
				log.infof("Saved the last partitioned keyset (count = %d) for '%s' as key '%s' in cache '%s'",
						count, cache.getName(), knownKey + partitionIndex, keyCache.getName());
			}
		}

	}

}
