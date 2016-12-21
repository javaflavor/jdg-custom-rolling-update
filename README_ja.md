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

## サンプルコードの動作確認

### 準備

サンプルコードはJavaで記述されており、プロジェクトのビルドツールにはMavenを使用しています。サンプルのツールを実行できるようにするためには、まずJDK 8とApache Mavenが利用可能である状態にしてください。以下のコマンドを実行し、

	$  mvn --version
    Apache Maven 3.3.1 (cab6659f9874fa96462afef40fcf6bc033d58c1c; 2015-03-14T05:10:27+09:00)
		:
	$ javac -version
    javac 1.8.0_65

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

サンプルコードは以下のコマンドを実行することでビルドが完了し、サンプルのツールを実行できる状態になります。

	$ cd jdg-custom-rolling-update
	$ mvn clean package

以下のような"BUILD SUCCESS"のメッセージが出力されたら、ビルドは成功です。

   		:
   	    [INFO] ------------------------------------------------------------------------
    [INFO] BUILD SUCCESS
    [INFO] ------------------------------------------------------------------------
    [INFO] Total time: 1.989 s
    [INFO] Finished at: 2015-11-27T15:57:18+09:00
    [INFO] Final Memory: 17M/220M
    [INFO] ------------------------------------------------------------------------

上記により、JDGサーバにデプロイ可能なバックアップ用モジュールがtargetディレクトリ配下に作成されます。

    $ ls target/
    ./                      generated-sources/      maven-status/
    ../                     jdg-custom-rolling-update.jar
    classes/                maven-archiver/
    
### モジュールのJDGサーバへのデプロイ

上記で出来上がったモジュールjdg-backup-control.jarをクラスタ内の全てのJDGインスタンスのdeploymentsディレクトリにコピーします。ローリングアップグレードを行う場合は、移行元クラスタと移行先クラスタの両方にデプロイします。

    $ scp target/jdg-custom-rolling-update.jar \
        jboss@server1:/opt/jboss/jboss-datagrid-6.6.0-server/node1/deployments/
    $ scp target/jdg-custom-rolling-update.jar \
        jboss@server2:/opt/jboss/jboss-datagrid-6.6.0-server/node2/deployments/
        :

このモジュール(カスタムストア)を使用したダミーのキャッシュ(名前：customCacheController)を作成し、JDGサーバを順にリスタートします。

キャッシュ作成とJDGサーバ再起動をスクリプトにしたファイルadd-controller-cache.cliを同梱していますので、以下のように実行してください。

    $ /opt/jboss/jboss-datagrid-6.6.0-server/bin/cli.sh \
    	--connect='remoting://<user>:<passwd>@server1:9999' \
    	--file=add-controller-cache.cli
    $ /opt/jboss/jboss-datagrid-6.6.0-server/bin/cli.sh \
    	--connect='remoting://<user>:<passwd>@server2:9999' \
    	--file=add-controller-cache.cli
    	:

--connectオプションの接続先URLの管理ポート番号はデフォルトが9999です。複数インスタンスを起動して、ポートオフセットを設定している場合は、そのオフセットの値を9999に加算した値を使用してください。

修正されたclustered.xmlファイルは以下の部分が追加されていることを確認してください。

                <distributed-cache name="customCacheController" mode="SYNC" start="EAGER">
                    <store class="com.redhat.example.jdg.store.CustomCacheControlStore"/>
                </distributed-cache>


### サンプルの実行

ローリングアップグレードの手順は、標準のMBeanを使用する代わりに、カスタムのMBeanを使用すること以外に違いはありません。ローリングアップグレードの手順はJDGのマニュアルを参照してください。以下に具体的な手順を示します。

#### キー一覧の取得

キー一覧の取得および新旧クラスタ間のデータ同期は、デプロイしたカスタムのJMXインタフェースを使用してキックします。上記モジュールをデプロイしたことにより、以下の名前のカスタムMBeanが登録されます。

    com.redhat.example:name=RollingUpgradeManagerEx

JConsoleが接続できる場合は、JConsoleの左ペインで"com.redhat.example"を選択すると、RollingUpgradeManagerExという名前のMBeanが追加されていることが分かると思います。

キー一覧の取得を行う場合は、このMBeanの**recordKnownGlobalKeyset(cacheName)**オペレーションに対象のキャッシュ名を指定して実行します。

JMX接続する先のJDGインスタンスは、クラスタ内のどのインスタンスでも構いません。接続したJDGインスタンスを起点として、Distributed Executorが起動され、全てのJDGインスタンスで平行してキー一覧取得処理が開始されます。

#### 新旧クラスタのデータ同期の実行

旧クラスタと新クラスタのデータを同期する場合は、マニュアルに示されているように、新クラスタ側に旧クラスタと接続するリモートストアを設定しておく必要があります。リモートストアを設定し、新クラスタを再起動したら、RollingUpgradeManagerEx MBeanの**synchronizeData(cacheName)**オペレーションを実行することで、新クラスタのデータが全て取り込まれます。

なお、キー一覧の取得および新旧クラスタのデータ同期のJMX APIを呼び出すためのshスクリプトも同梱していますので、合わせてご確認ください。

* bin/jdg-dumpkeys.sh	(キー一覧の取得用)
* bin/jdg-synchronize.sh	(新旧クラスタのデータ同期用)

上記shスクリプトを使用する場合の接続情報は、bin/cachecontrol.jsに定義されています。使用する環境に応じて修正してください。

    // Any server to connect using JMX protocol.
    
    var source_server = "localhost:9999"
    var target_server = "localhost:10199"
    
    // Authentication info: username and password.
    
    var username = "admin"
    var password = "welcome1!"

それぞれのshスクリプトには、作業対象のキャッシュ名を引数に指定します。例えば、namedCacheのキー一覧を取得する時は、以下のように実行します。

	$ bin/jdg-dumpkeys.sh namedCache

新旧クラスタのデータ同期を行う時は、jdg-synchronize.shを実行します。

	$ bin/jdg-synchronize.sh namedCache

## 補足事項

今回のサンプルを使用するにあたり、以下の点に注意してください。

* jdg-backup-controlモジュールのロジックを変更してJDGサーバに適用したい場合は、モジュールを再ビルド、再デプロイした後、cacheControllerキャッシュのみを再起動すれば変更したロジックをJDGサーバに反映することが出来ます。JDGサーバの再起動は必要ありませんし、ビジネスデータを含んだキャッシュのリバランスもトリガされません。cacheControllerキャッシュを再起動するスクリプト restart-controller-cache.cliが同梱されていますので、合わせてご確認ください。

以上