package com.redhat.example.jdg.upgrade;

import java.io.InputStream;
import java.util.Properties;

import org.jboss.logging.Logger;
import org.infinispan.commons.marshall.jboss.GenericJBossMarshaller;

public class MigrationConfiguration {

	static final String PROPERTY_FILE = "migration.properties";
	static final String KEY_CACHE_NAME = "migration.key_cache_name";
	static final String KEY_CACHE_NAME_SUFFIX = "migration.key_cache_name_suffix";
	static final String KEY_NAME_PREFIX = "migration.key_name_prefix";
	static final String MAX_NUM_KEYS = "migration.max_num_keys";
	static final String MARSHALLER = "migration.marshaller";
	static final String RECORD_TIMEOUT_MIN = "migration.record_timeout_min";
	static final String SYNCHRONIZE_TIMEOUT_MIN = "migration.synchronize_timeout_min";

	static Logger log = Logger.getLogger(MigrationConfiguration.class);
	static MigrationConfiguration config = new MigrationConfiguration();

	String keyCacheName;
	String keyCacheNameSuffix;
	String keyNamePrefix;
	int maxNumKeys;
	Class marshallerClass;
	int recordTimeoutMin;
	int synchronizeTimeoutMin;
	
	public static MigrationConfiguration getInstance() {
		return config;
	}
	
	MigrationConfiguration() {
		init();
	}

	void init() {
		Properties props = new Properties();
		try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(PROPERTY_FILE)) {
			props.load(is);
			keyCacheName = "".equals(props.getProperty(KEY_CACHE_NAME)) ? null : props.getProperty(KEY_CACHE_NAME);
			log.info("init: keyCacheName = "+ keyCacheName);
			keyCacheNameSuffix = props.getProperty(KEY_CACHE_NAME_SUFFIX) == null ? "" : props.getProperty(KEY_CACHE_NAME_SUFFIX);
			log.info("init: keyCacheNameSuffix = "+ keyCacheNameSuffix);
			keyNamePrefix = props.getProperty(KEY_NAME_PREFIX) == null ? "" : props.getProperty(KEY_NAME_PREFIX);
			log.info("init: keyNamePrefix = "+ keyNamePrefix);
			maxNumKeys = Integer.parseInt(props.getProperty(MAX_NUM_KEYS));
			log.info("init: maxNumKeys = "+ maxNumKeys);
			String marshaller = "".equals(props.getProperty(MARSHALLER)) ? null : props.getProperty(MARSHALLER);
			marshallerClass = marshaller == null ? GenericJBossMarshaller.class : Class.forName(marshaller);
			log.info("init: marshaller = "+ marshaller);
			recordTimeoutMin = Integer.parseInt(props.getProperty(RECORD_TIMEOUT_MIN));
			log.info("init: recordTimeoutMin = "+ recordTimeoutMin);
			synchronizeTimeoutMin = Integer.parseInt(props.getProperty(SYNCHRONIZE_TIMEOUT_MIN));
			log.info("init: restoreTimeoutMin = "+ synchronizeTimeoutMin);
		} catch (Exception e) {
			throw new IllegalStateException(String.format("Failed to load property file '%s': ", PROPERTY_FILE), e);
		}
	}
	
	public String getKeyCacheName(String cacheName) {
		return keyCacheName == null ?
				cacheName+keyCacheNameSuffix :	// distinguished key cache is used.
				keyCacheName;					// shared key cache is used.
	}
	
	public String getKnownKey(String cacheName) {
		return keyCacheName == null ?
				keyNamePrefix :					// distinguished key cache is used.
				keyNamePrefix + cacheName;		// shared key cache is used.
	}

}
