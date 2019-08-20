# Custom Rolling Upgrade Tool for JDG

## Preface

This sample code is an implementation of a custom rolling upgrade that avoids the problem of implementing Rolling Upgrade of JDG 6.6.0.

Rolling Upgrade of the standard implementation of JDG 6.6.0 creates the key list of the cache to be migrated as a single entry and tries to transfer that entry to the destination cluster with the Hot Rod protocol. However, the current Hot Rod protocol has a constraint that the payload of the message must be 2 GB or less, and there is a problem that migration of a cache whose byte array marshalling the key list exceeds 2 GB can not be performed (Bug 1293575, fixed in JDG 6.6.1 and 7.0). This sample code is implemented without 2 GB constraints on the key list.

Features of the sample code are as follows.

1. When acquiring the key list on the source cluster side (executing the `recordKnownGlobalKeyset()` operation), the key list is saved by dividing it into multiple entries so as not to exceed the 2 GB. The chunk size when dividing the key list can be customized by properties.
2. The standard `recordKnownGlobalKeyset()` saves the key list in the same cache of the source cluster, but the custom implementation is possible to prepare a separate cache dedicated to saving the key list and obtain the key list there. Whether to use an acquisition source cache or a dedicated separate cache can be changed by property.
3. You can customize the key name prefix of the keylist entry to be saved.
4. Since the key list acquired by the `recordKnownGlobalKeyset()` operation can be referred from the Hot Rod client, it is possible to investigate the values of all entries from outside the JDG server regardless of the cache size.
5. The basic work flow of the rolling upgrade is almost the same as the standard one.

## Tested Configurations

This sample code is tested on the following configurations.

|Source Cluster|Target Cluster|
|--------------|--------------|
|JDG 6.6.2     |JDG 6.6.2     |
|JDG 6.6.2     |RHDG 7.2.3    |
|RHDG 7.2.3    |RHDG 7.2.3    |

## How to use

### Preparation

Apache Maven is used to build the tool of the project. Check if JDK 8 and Apache Maven are available in your environment.

~~~
$  mvn --version
Apache Maven 3.5.4 (1edded0938998edf8bf061f1ceb3cfdeccf443fe; 2018-06-18T03:33:14+09:00)
		:
$ javac -version
javac 1.8.0_151
~~~

### Review the configuration

The tool reads the following property file as the configuration.

* `migration.properties`

The contents of each setting are as follows. Please change the setting according to your environment.

`src/main/resources/migration.properties:`

~~~
# Cache name to be store key list.
# If this property is set like "keyCahce", any keys of any target
# cache is dumped into the same cache named "keyCache".
# If this property is undefined, the keys are stored in the same
# cache of the target cache.
migration.key_cache_name = default

# Cache name suffix to be store key list, which is effective
# only if migration.key_cache_name property is undefined.
# For example,
# if migration.key_cache_name_suffix = #keys,
#     dumped cache name: <target cache name> + "#keys".
# If  migration.key_cache_name_suffix = null or emply,
#     dumpled cache name: <target cache name>
#
# migration.key_cache_name_suffix = #keys
migration.key_cache_name_suffix = 

# Prefix of indexed dumped keys.
# If migration.key_cache_name is null or empty, the storing keys are:
#     <migration.key_name_prefix> + <index>
#     ex.: "___KEYS___0", "___KEYS___1", ... 
# Otherwise, the storing keys are:
#     <migration.key_name_prefix> + <target cache name> + <index>
#     ex.: "___KEYS___userinfo0", "___KEYS___userinfo1", ... 
migration.key_name_prefix = ___KEYS___

# Max number of keys to be stored in each entry.
# If this property = 1, each key are stored in one entry.
# Otherwise, the stored values are List<Object>.
migration.max_num_keys = 10000

# Marshaller class
# If you use custom marshaller at Hot Rod client, the same marshaller
# class name must be specified here. Otherwise, default marshaller is used.
migration.marshaller =

# Timeout(min) for operation recordKnownGlobalKeyset().
migration.record_timeout_min = 10

# Timeout(min) for operation synchronizeData().
migration.synchronize_timeout_min = 10

# Dumpkeys throttling: Do sleep per entries while scanning. This value must be less than migration.max_num_keys.
migration.dumpkeys_sleep_per_entries = 100

# Dumpkeys throttling: sleep time (ms) for each migration.dumpkeys_sleep_per_entries scan.
migration.dumpkeys_sleep_ms = 1
~~~

To save the key list in a dedicated cache prepared in advance, set the cache name to **migration.key\_cache\_name** property. If migration.key\_cache\_name is not set, save the key list in the same cache from which the key list was obtained.

The key name prefix for the key list entry is set with the **migration.key\_name\_prefix** property. Saving the key list using the dedicated cache, the key name of the key list has the following format.

* \<migration.key\_name\_prefix\> + \<cache name\> + \<index(0, 1, ...)\>

Example:

~~~
___KEYS___namedCache0
___KEYS___namedCache1
___KEYS___namedCache2
	:
~~~

When storing the key list for each cache (including when using the cache name + suffix), the key name of the key list does not include the cache name.
    	
* \<migration.key_name_prefix\> + \<index(0, 1, ...)\>

Example:

~~~
___KEYS___0
___KEYS___1
___KEYS___2
	:
~~~

In this case, if you do not set `migration.key_name_` prefix, the key name of the key list can only be the index number.

### Build the deployment module

The tool can be built by the following command. The sample code supports different Data Grid versions. Specify the profile option `-Pdg6x` for JDG 6.x, or `-Pdg7x` for RHDG 7.x.

~~~
$ cd jdg-custom-rolling-update

For JDG 6.x:
$ mvn clean package -Pdg6x

For JDG 7.x:
$ mvn clean package -Pdg7x
~~~

Check "BUILD SUCCESS" message.

~~~
	:
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time: 1.989 s
[INFO] Finished at: 2015-11-27T15:57:18+09:00
[INFO] Final Memory: 17M/220M
[INFO] ------------------------------------------------------------------------
~~~

The deployable module can be found in the target directory.

~~~
$ ls target/
./                      generated-sources/      maven-status/
../                     jdg-custom-rolling-update-dg6x.jar
classes/                maven-archiver/
~~~
    
### Deply the module in JDG server

Copy the created module jdg-backup-control.jar into the deployments directory of all JDG instances in the cluster. For rolling upgrades, deploy it to both the source cluster and the destination cluster.

~~~
For JDG 6.x:
$ scp target/jdg-custom-rolling-update-dg6x.jar \
    jboss@server1:/opt/jboss/jboss-datagrid-6.6.0-server/node1/deployments/
$ scp target/jdg-custom-rolling-update-dg6x.jar \
    jboss@server2:/opt/jboss/jboss-datagrid-6.6.0-server/node2/deployments/
    :

For RHDG 7.x:
$ scp target/jdg-custom-rolling-update-dg7x.jar \
    jboss@server1:/opt/jboss/jboss-datagrid-7.2.0-server/node1/deployments/
$ scp target/jdg-custom-rolling-update-dg7x.jar \
    jboss@server2:/opt/jboss/jboss-datagrid-7.2.0-server/node2/deployments/
    :
~~~

Create a dummy cache, called `customCacheController` using the deployed module and restart the server.

The CLI script `add-controller-cache-dg6x.cli` (or, `add-controller-cache-dg7x.cli`) is available for creating cache.

~~~
For JDG 6.x
$ /opt/jboss/jboss-datagrid-6.6.0-server/bin/cli.sh \
	--connect='remoting://<user>:<passwd>@server1:9999' \
	--file=add-controller-cache-dg6x.cli
$ /opt/jboss/jboss-datagrid-6.6.0-server/bin/cli.sh \
	--connect='remoting://<user>:<passwd>@server2:9999' \
	--file=add-controller-cache-dg6x.cli
	:
For RHDG 7.x:
$ /opt/jboss/jboss-datagrid-7.2.0-server/bin/cli.sh -c \
	--controller='server1:9990' \
	--file=add-controller-cache-dg7x.cli
$ /opt/jboss/jboss-datagrid-7.2.0-server/bin/cli.sh -c \
	--controller='server2:9990' \
	--file=add-controller-cache-dg7x.cli
	:
~~~

Please check the configuration file clustered.xml is modified as follows:

~~~
            <distributed-cache name="customCacheController" mode="SYNC" start="EAGER">
                <store class="com.redhat.example.jdg.store.CustomCacheControlStore"/>
            </distributed-cache>
~~~

### Add Remote Store (Target Cluster)

You must add Remote Cache configuration to the target cluster just like the standard rolling upgrade procedure. Please refer to the documentation of the target Data Grid version for adding Remote Cache.

### Test Fright

Instead of using custom MBeans, the rolling upgrade procedure is the same as using the standard MBean. Refer to the JDG manual for the rolling upgrade procedure. The specific procedure is shown below.

#### Dump keys in source cluster

Acquire key list and data synchronization between old and new clusters using the deployed custom JMX interface. By deploying the above module, a custom MBean with the following name will be registered.

~~~
com.redhat.example:name=RollingUpgradeManagerEx
~~~

Using JConsole, you can see that an MBean named `RollingUpgradeManagerEx` has been added, by selecting "com.redhat.example" in the left pane of JConsole.

To retrieve the key list, specify the target cache name in the **recordKnownGlobalKeyset(cacheName)** operation of this MBean and execute it.

Please note that you can access any instance in the JDG cluster. The dump keys request calls the distributed executor and spans the request to all of the instances in the cluster.

#### Synchronize both clusters

When synchronizing the data between the old cluster and the new cluster, you need to set up the remote store to connect to the old cluster on the new cluster side as shown in the product documentation. After setting up the remote store and restarting the new cluster, execute the **synchronizeData(cacheName)** operation of the `RollingUpgradeManagerEx` MBean to retrieve all data of the old cluster.

In addition, sh script for calling the JMX API for acquiring the key list and synchronizing the data of the old and new clusters is also included

For JDG 6.x:

* bin/jdg-dumpkeys-dg6x.sh	    (for dumpkeys)
* bin/jdg-synchronize-dg6x.sh	(for synchronizing data)

For RHDG 7.x:

* bin/jdg-dumpkeys-dg7x.sh	    (for dumpkeys)
* bin/jdg-synchronize-dg7x.sh	(for synchronizing data)

The connection information of the sh script above is defined in `bin/cachecontrol-dg6x.config` (or, `bin/cachecontrol-dg7x.config`). Please correct the settings according to your environment.

~~~
// Any server to connect using JMX protocol.

var source_server = "localhost:9999"
var target_server = "localhost:10099"

// Authentication info: username and password.

var username = "admin"
var password = "welcome1!"
~~~

For each sh script, specify the target cache name as an argument. For example, to get the list of keys of `default` cache, execute as follows.

~~~
For JDG 6.x:
$ bin/jdg-dumpkeys-dg6x.sh default

For RHDG 7.x:
$ bin/jdg-dumpkeys-dg7x.sh default
~~~

To synchronize data between clusters, execute as follows.

~~~
For JDG 6.x:
$ bin/jdg-synchronize-dg6x.sh default

For RHDG 7.x:
$ bin/jdg-synchronize-dg7x.sh default
~~~

#### Disconnect Source Cluster

After data synchronization finished, you must disconnect source cluster using CLI or JMX operation. Please refer to the documentation of the target Data Grid version for disconnectiong source cluster.


## Warning

### Migration from JDG 6.x to RHDG 7.2.x

If you setup the configuration with JDG 6.x as source cluster and RHDG 7.2.x as target cluster, Accessing the `numberOfEntries` attribute of MBean `Staticstics` will not return.

In order to avoid this issue, use `numberOfEntriesInMemory` attribute, instead of `numberOfEntries`, for Remote Store configured cache. After synchronization finished, disconnect source cluster and delete Remote Store configuration immediately as follows:

~~~
In case of default cache:

$ /opt/jboss/jboss-datagrid-7.2.0-server/bin/cli.sh -c --controller=localhost:10090
[standalone@localhost:10090 /] /subsystem=datagrid-infinispan/cache-container=clustered/distributed-cache=default:disconnect-source(migrator-name=hotrod)
[standalone@localhost:10090 /] /subsystem=datagrid-infinispan/cache-container=clustered/configurations=CONFIGURATIONS/distributed-cache-configuration=default/remote-store=REMOTE-STORE:remove()
~~~

## Appendix

Please note the following points when using this sample.

* If you want to change the logic of the `jdg-backup-control` module and apply it to the JDG server, only restarting the cacheController is required after rebuilding and redeploying the module. Restarting the JDG server is unnecessary and no rebalance is triggered for cache containing the business data is not triggered. The script `restart-controller-cache-dg6x.cli` that restarts the customCacheController cache is included in the package. 