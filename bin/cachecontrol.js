// Check Java version >= 1.8
if (java.lang.System.getProperty("java.specification.version") >= "1.8") {
  // Load java 7 compatible global objects.
  load("nashorn:mozilla_compat.js")
}

importPackage(java.lang)
importPackage(java.nio.file)
importPackage(java.text)
importPackage(java.util)
importPackage(java.util.concurrent)
importPackage(java.util.stream)
importPackage(javax.management)
importPackage(javax.management.remote)

// Check arguments.
if (arguments.length != 3) {
    println("Usage: jrunscript cachecontrol.js cachecontrol.config [recordKnownGlobalKeyset|synchronizeData] <cacheName>")
    exit(1)
}
var config = arguments[0]
var command = arguments[1]
var cacheName = arguments[2]
// Load config (server, username, password).
load(config)

if (command == "recordKnownGlobalKeyset") {
    server = source_server
} else if (command == "synchronizeData") {
    server = target_server
} else {
    println("Command must be recordKnownGlobalKeyset or synchronizeData.")
    exit(1)
}

var connector = getJMXConnector(server, username, password)
var con = connector.getMBeanServerConnection()

System.out.printf("Sending '%s' command to server '%s'.%n", command, server)

var objname = new ObjectName("com.redhat.example:name=RollingUpgradeManagerEx")
var cls = new java.lang.Object().getClass()
var params = java.lang.reflect.Array.newInstance(cls, 1)
params[0] = cacheName
cls = new java.lang.String().getClass()
var types = java.lang.reflect.Array.newInstance(cls, 1)
types[0] = cls.getName()
con.invoke(objname, command, params, types)

function getJMXConnector(server, username, password) {
    var url = new JMXServiceURL("service:jmx:"+jmx_protocol+"://" + server)
    var cls = new java.lang.String().getClass()
    var cred = java.lang.reflect.Array.newInstance(cls, 2)
    cred[0] = username
    cred[1] = password
    var env = {"jmx.remote.credentials" : cred}
//    return JMXConnectorFactory.connect(url, env)
    return JMXConnectorFactory.connect(url)
}

function sleep(sec) {
    try { java.lang.Thread.sleep(sec*1000) } catch (e) {}
}

