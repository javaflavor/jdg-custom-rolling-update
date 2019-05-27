# カスタムローリングアップグレード実装のサンプル

## はじめに

本サンプルコードは、JDG 6.6.0のRolling Upgradeの実装の問題を回避したカスタムのローリングアップグレードの実装です。

JDG 6.6.0の標準実装のRolling Upgradeでは、マイグレーション対象のキャッシュのキー一覧を１つのエントリとして作成し、そのエントリを移行先クラスタにリモートキャッシュのHot Rodプロトコルで転送しようとします。しかしながら、現状のHot Rodプロトコルはメッセージのペイロードが2GB以下でなければならないという制約があり、キー一覧をマーシャリングしたバイト配列が2GBを超えるキャッシュのマイグレーションができないという問題があります。このサンプル実装は、キー一覧の2GB制約を受けることなく、安全にキャッシュデータの移行を行うことを目的として実装されたものです。

サンプル実装の特徴は以下の通りです。

1. ソースクラスタ側でキー一覧を取得する（recordKnownGlobalKeyset()オペレーションを実行する）際、2GB制約を受けないように複数のエントリに分割してキー一覧を保存するようにした。キー一覧を分割するときのチャンクサイズはプロパティによってカスタマイズ可能。
2. 標準のrecordKnownGlobalKeyset()は、キー一覧をキーの取得元と同じキャッシュに保存するが、キー一覧保存専用の別キャッシュを用意し、そこにキー一覧を取得することも可能。取得元キャッシュを使うか専用の別キャッシュを使うかはプロパティによって変更可能。
3. 保存されるキー一覧エントリのキー名のプリフィックスはカスタマイズ可能。
4. recordKnownGlobalKeyset()オペレーションで取得したキー一覧は、Hot Rodクライアントから参照することができるため、キャッシュの規模に関わらず、JDGサーバの外部から全てのエントリの値を調査することが可能。
5. ローリングアップグレードの基本的な作業の流れは標準のものとほぼ同じ。

## 動作確認済みJDGバージョン

このサンプルコードは以下のJDGバージョンの見合わせで、動作確認を行いました。

|移行元クラスタ|移行先クラスタ|
|--------------|--------------|
|JDG 6.6.2     |JDG 6.6.2     |
|JDG 6.6.2     |RHDG 7.2.3    |
|RHDG 7.2.3    |RHDG 7.2.3    |

## サンプルコードの動作確認

### 準備

サンプルコードはJavaで記述されており、プロジェクトのビルドツールにはMavenを使用しています。サンプルのツールを実行できるようにするためには、まずJDK 8とApache Mavenが利用可能である状態にしてください。以下のコマンドを実行し、

	$  mvn --version
    Apache Maven 3.5.4 (1edded0938998edf8bf061f1ceb3cfdeccf443fe; 2018-06-18T03:33:14+09:00)
		:
	$ javac -version
    javac 1.8.0_151

### 設定ファイルの確認

本サンプルコードは、以下のプロパティ形式の設定ファイルを読み込んで使用します。

* migration.properties

それぞれの設定内容は以下の通りです。ご利用の環境に合わせて設定を変更してください。

src/main/resources/migration.properties:

    # Cache name to be store key list.
    # If this property is set like "keyCahce", any keys of any target
    # cache is dumped into the same cache named "keyCache".
    # If this property is undefined, the keys are stored in the same
    # cache of the target cache.
    migration.key_cache_name = 
    
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
    migration.max_num_keys = 1000
    
    # Marshaller class
    # If you use custom marshaller at Hot Rod client, the same marshaller
    # class name must be specified here. Otherwise, default marshaller is used.
    migration.marshaller =
    
    # Timeout(min) for operation recordKnownGlobalKeyset().
    migration.record_timeout_min = 10
    
    # Timeout(min) for operation synchronizeData().
    migration.synchronize_timeout_min = 10

キー一覧を予め用意した専用のキャッシュに保存する場合は、そのキャッシュ名を**migration.key\_cache\_name**プロパティに設定します。migration.key_cache_nameが未設定の場合は、キー一覧取得元のキャッシュと同じキャッシュにキー一覧を保存します。

キー一覧のエントリのためのキー名のプリフィックスは、**migration.key\_name\_prefix**プロパティで設定します。キー一覧を保存する専用キャッシュを使用する場合の、キー一覧のキー名は以下のような形式になります。

* \<migration.key_name_prefixの値\> + \<キャッシュ名\> + \<インデックス(0, 1, ...)\>

例：

    ___KEYS___namedCache0
    ___KEYS___namedCache1
    ___KEYS___namedCache2
    	:

キー一覧をキャッシュ毎に保存する場合（キャッシュ名+サフィックスを使用する場合を含む）、キー一覧のキー名にはキャッシュ名は含まれません。
    	
* \<migration.key_name_prefixの値\> + \<インデックス(0, 1, ...)\>

例：

    ___KEYS___0
    ___KEYS___1
    ___KEYS___2
    	:

この場合、migration.key_name_prefixを未設定にすると、キー一覧のキー名はインデックスの数字のみにすることも可能です。

### サンプルコードのビルド

サンプルコードはJDG 6.x用にビルドする場合と、RHDG 7.x用にビルドする場合で、コマンドラインオプションが異なります。具体的には、6.x用にビルドする場合はプロファイル`dg6x`を指定し、7.x用にビルドする場合はプロファイル`dg7x`を指定してビルドします。

	$ cd jdg-custom-rolling-update
	
	JDG 6.x用の場合：
	$ mvn clean package -Pdg6x
	
	JDG 7.x用の場合：
	$ mvn clean package -Pdg7x

以下のような"BUILD SUCCESS"のメッセージが出力されたら、ビルドは成功です。

   		:
   	    [INFO] ------------------------------------------------------------------------
    [INFO] BUILD SUCCESS
    [INFO] ------------------------------------------------------------------------
    [INFO] Total time: 1.989 s
    [INFO] Finished at: 2015-11-27T15:57:18+09:00
    [INFO] Final Memory: 17M/220M
    [INFO] ------------------------------------------------------------------------

上記により、JDGサーバにデプロイ可能なバックアップ用モジュールがtargetディレクトリ配下に作成されます。モジュール名は6.x用と7.x用で異なります。6.xのモジュール名は`jdg-custom-rolling-update-dg6x.jar`に、7.x用のモジュール名は`jdg-custom-rolling-update-dg7x.jar`になります。

    $ ls target/
    ./                      generated-sources/      maven-status/
    ../                     jdg-custom-rolling-update-dg6x.jar
    classes/                maven-archiver/
    
### モジュールのJDGサーバへのデプロイ

上記で出来上がったモジュール`jdg-backup-control-dg6x.jar`(または、`jdg-backup-control-dg7x.jar`)をクラスタ内の全てのJDGインスタンスのdeploymentsディレクトリにコピーします。ローリングアップグレードを行う場合は、移行元クラスタと移行先クラスタの両方にデプロイします。デプロイ方法はJDG 6.xとRHDG 7.xで違いはありません。

	JDG 6.xの場合：
    $ scp target/jdg-custom-rolling-update-dg6x.jar \
        jboss@server1:/opt/jboss/jboss-datagrid-6.6.0-server/node1/deployments/
    $ scp target/jdg-custom-rolling-update-dg6x.jar \
        jboss@server2:/opt/jboss/jboss-datagrid-6.6.0-server/node2/deployments/
        :
        
	RHDG 7.xの場合：
    $ scp target/jdg-custom-rolling-update-dg7x.jar \
        jboss@server1:/opt/jboss/jboss-datagrid-7.2.0-server/node1/deployments/
    $ scp target/jdg-custom-rolling-update-dg7x.jar \
        jboss@server2:/opt/jboss/jboss-datagrid-7.2.0-server/node2/deployments/
        :

### ダミーキャッシュ(customCacheController)の作成

デプロイしたモジュール(カスタムストア)を使用したダミーのキャッシュ(名前：customCacheController)を作成し、JDGサーバを順にリスタートします。

キャッシュ作成とJDGサーバ再起動をスクリプトにしたファイル`add-controller-cache-dg6x.cli`(または、`add-controller-cache-dg7x.cli`)を同梱していますので、以下のように実行してください。

	JDG 6.xの場合：
    $ /opt/jboss/jboss-datagrid-6.6.0-server/bin/cli.sh \
    	--connect='remoting://<user>:<passwd>@server1:9999' \
    	--file=add-controller-cache-dg6x.cli
    $ /opt/jboss/jboss-datagrid-6.6.0-server/bin/cli.sh \
    	--connect='remoting://<user>:<passwd>@server2:9999' \
    	--file=add-controller-cache-dg6x.cli
    	:
	RHDG 7.xの場合：
    $ /opt/jboss/jboss-datagrid-7.2.0-server/bin/cli.sh \
    	--controller='server1:9990' \
    	--file=add-controller-cache-dg7x.cli
    $ /opt/jboss/jboss-datagrid-7.2.0-server/bin/cli.sh \
    	--controller='server2:9990' \
    	--file=add-controller-cache-dg7x.cli
    	:

JDG 6.xの場合、--connectオプションの接続先URLの管理ポート番号はデフォルトが9999です。複数インスタンスを起動して、ポートオフセットを設定している場合は、そのオフセットの値を9999に加算した値を使用してください。

RHDG 7.xにおける接続先は、--controllerオプションを使用する点に注意してください。また、JDG 6.xとRHDG 7.xではデフォルトの管理ポートが変更になってい点もご注意ください。なお、RHDG 7.xの場合は、サーバの再起動は不要のため`add-controller-cache-dg7x.cli`からは`reload()`コマンドが省略されています。

修正されたclustered.xmlファイルは以下の部分が追加されていることを確認してください。

                <distributed-cache name="customCacheController" mode="SYNC" start="EAGER">
                    <store class="com.redhat.example.jdg.store.CustomCacheControlStore"/>
                </distributed-cache>

### Remote Storeの追加(移行先クラスタのみ)

移行元クラスタから移行先クラスタへのデータ転送には、標準のローリングアップグレードと同様Remote Storeを使用します。Remote Storeの設定方法については、移行先バージョンの製品ドキュメントを参照してください。


### サンプルの実行

ローリングアップグレードの手順は、標準のMBeanを使用する代わりに、カスタムのMBeanを使用すること以外に違いはありません。ローリングアップグレードの手順はJDGのマニュアルを参照してください。以下に具体的な手順を示します。

#### キー一覧の取得

キー一覧の取得および新旧クラスタ間のデータ同期は、デプロイしたカスタムのJMXインタフェースを使用してキックします。上記モジュールをデプロイしたことにより、以下の名前のカスタムMBeanが登録されます。

    com.redhat.example:name=RollingUpgradeManagerEx

JConsoleが接続できる場合は、JConsoleの左ペインで`"com.redhat.example"`を選択すると、`RollingUpgradeManagerEx`という名前のMBeanが追加されていることが分かると思います。

キー一覧の取得を行う場合は、このMBeanの**recordKnownGlobalKeyset(cacheName)**オペレーションに対象のキャッシュ名を指定して実行します。

JMX接続する先のJDGインスタンスは、クラスタ内のどのインスタンスでも構いません。接続したJDGインスタンスを起点として、Distributed Executorが起動され、全てのJDGインスタンスで平行してキー一覧取得処理が開始されます。

#### 新旧クラスタのデータ同期の実行

旧クラスタと新クラスタのデータを同期する場合は、`RollingUpgradeManagerEx` MBeanの**synchronizeData(cacheName)**オペレーションを実行することで、新クラスタのデータが全て取り込まれます。

なお、キー一覧の取得および新旧クラスタのデータ同期のJMX APIを呼び出すためのshスクリプトも同梱していますので、合わせてご確認ください。

JDG 6.xの場合：

* bin/jdg-dumpkeys-dg6x.sh	(キー一覧の取得用)
* bin/jdg-synchronize-dg6x.sh	(新旧クラスタのデータ同期用)

RHDG 7.xの場合：

* bin/jdg-dumpkeys-dg7x.sh	(キー一覧の取得用)
* bin/jdg-synchronize-dg7x.sh	(新旧クラスタのデータ同期用)

上記shスクリプトを使用する場合の接続情報は、`bin/cachecontrol-dg6x.js`(または、`bin/cachecontrol-dg7x.js`)に定義されています。使用する環境に応じて修正してください。

    // Any server to connect using JMX protocol.
    
    var source_server = "localhost:9999"
    var target_server = "localhost:10199"
    
    // Authentication info: username and password.
    
    var username = "admin"
    var password = "welcome1!"

それぞれのshスクリプトには、作業対象のキャッシュ名を引数に指定します。例えば、`default`のキー一覧を取得する時は、以下のように実行します。

	JDG 6.xの場合：
	$ bin/jdg-dumpkeys-dg6x.sh default
	
	RHDG 7.xの場合：
	$ bin/jdg-dumpkeys-dg7x.sh default

新旧クラスタのデータ同期を行う時は、以下のように実行します。

	JDG 6.xの場合：
	$ bin/jdg-synchronize-dg6x.sh default
	
	RHDG 7.xの場合：
	$ bin/jdg-synchronize-dg7x.sh default

#### 移行元クラスタの切断

データ同期が完了したら、移行先クラスタから移行元クラスタを切断してください。移行元クラスタを切断するには、CLIを使用する方法とJMXを使用する方法の２通りがあります。移行元クラスタ切断の具体的な方法については、移行先バージョンの製品ドキュメントを参照してください。

## 注意事項

### JDG 6.xを移行元、RHDG 7.2.3を移行先に設定する場合の注意

JDG 6.xを移行元、RHDG 7.2.3を移行先とし、移行先クラスタにRemote Storeを設定した場合、そのキャッシュの`Statistics` MBeanの`numberOfEntries`属性を参照すると該当のJMXオペレーションがハングする問題があります。

この問題を回避するには、`numberOfEntries`属性の代わりに`numberOfEntriesInMemory`属性で代用し、synchronizeオペレーションの完了後直ちに、ソースクラスタの切断とRemote Storeの削除を行えば、それ以降は問題なく`numberOfEntries`属性を参照することができるようになります。

	dafaultキャッシュを使用していた場合：
	
	/opt/jboss/jboss-datagrid-7.2.0-server/bin/cli.sh -c --controller=localhost:10090
	[standalone@localhost:10090 /] /subsystem=datagrid-infinispan/cache-container=clustered/distributed-cache=default:disconnect-source(migrator-name=hotrod)
	[standalone@localhost:10090 /] /subsystem=datagrid-infinispan/cache-container=clustered/configurations=CONFIGURATIONS/distributed-cache-configuration=default/remote-store=REMOTE-STORE:remove()

## 補足事項

今回のサンプルを使用するにあたり、以下の点に注意してください。

* `jdg-backup-control`モジュールのロジックを変更してJDGサーバに適用したい場合は、モジュールを再ビルド、再デプロイした後、cacheControllerキャッシュのみを再起動すれば変更したロジックをJDGサーバに反映することが出来ます。JDGサーバの再起動は必要ありませんし、ビジネスデータを含んだキャッシュのリバランスもトリガされません。cacheControllerキャッシュを再起動するスクリプト `restart-controller-cache-dg6x.cli`が同梱されていますので、合わせてご確認ください。

以上