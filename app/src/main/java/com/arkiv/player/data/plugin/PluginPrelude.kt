package com.arkiv.player.data.plugin

/**
 * The JS every plugin runtime starts with, read once from the Java resources
 * `plugin/web.js` and `plugin/prelude.js` (`app/src/main/resources/`). Java resources are on the
 * unit-test classpath and inside the APK, readable through the class loader in both (measured:
 * JVM unit test, and the app's PathClassLoader on emulator-5554, Android 14). A missing file is a
 * broken build: [IllegalStateException], which `PluginRuntime.open` turns into "the plugin
 * doesn't load" instead of a crash.
 */
object PluginPrelude {
    val web: String by lazy { read("plugin/web.js") }
    val prelude: String by lazy { read("plugin/prelude.js") }

    /**
     * The Nuvio compatibility shim (spec §5.2). Unlike [web]/[prelude], `PluginRuntime` never loads
     * this on its own -- it isn't part of every plugin's runtime, only of a Nuvio-converted one, so
     * [NuvioPluginConverter] reads it here and concatenates it into that ONE plugin's own script.
     */
    val nuvioShim: String by lazy { read("plugin/nuvio-shim.js") }

    /** The real libraries a Nuvio scraper may `require` (see [NuvioLibrary]), each read once. */
    private val nuvioVendor = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun nuvioVendor(file: String): String = nuvioVendor.getOrPut(file) { read("plugin/nuvio-vendor/$file") }

    private fun read(path: String): String =
        (PluginPrelude::class.java.classLoader ?: throw IllegalStateException("no class loader"))
            .getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("falta el recurso $path")
}
