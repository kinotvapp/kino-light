package com.arkiv.player.data.plugin

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Where [PluginRuntime.open] keeps the QuickJS bytecode it compiled, so the next runtime of the
 * same code skips the parse. Compiling is most of what opening a runtime costs on old devices
 * (measured, Fire TV armv7: 364 / 365 / 1028 ms for a 0.5 / 1 / 2 MB script), every time.
 *
 * Contract for implementations: [read] answers only bytecode that THIS app compiled from exactly
 * [source] (QuickJS bytecode is not validated by the engine: loading bytes from anywhere else can
 * corrupt memory), and answers null on any doubt. [write] and [discard] never throw.
 */
interface CompiledCodeStore {
    /** Bytecode compiled earlier from exactly [source] by the same engine and app build, or null. */
    fun read(owner: String, kind: String, source: String): ByteArray?

    /** Keeps [bytecode], compiled from [source], for [owner]'s next runtime. May happen later, off the caller's thread. */
    fun write(owner: String, kind: String, source: String, bytecode: ByteArray)

    /** Forgets everything kept for [owner] (uninstall, update, or bytecode that failed to load). */
    fun discard(owner: String)
}

/**
 * [CompiledCodeStore] on disk, in app-private storage (`noBackupFilesDir`: never in a backup, never
 * reachable by plugins, whose only file access is their own `kino.storage` JSON).
 *
 * One file per (owner, kind): `<owner>.<kind>.qjsc`. A plugin update rewrites its file (the new
 * script's hash doesn't match, so the old bytes are never read), an uninstall deletes it ([discard]).
 *
 * Every file is `MAGIC | key (32) | length (4) | sha256(payload) (32) | payload`, where key =
 * sha256 of [engineTag] (QuickJS + binding + app versionCode + ABI), the kind and the sha256 of the
 * source code itself. A read checks all of it before handing anything to the engine; a file that
 * fails any check (truncated, flipped bit, older app, other ABI, other script) is deleted and the
 * caller compiles from source, silently. The key is the hash of the code actually being run (for a
 * plugin, the script `PluginStore.readVerifiedScript` already checked against its install record),
 * so bytes compiled from any other code can never be returned for it.
 *
 * Bounded: after each write the directory is trimmed to [maxBytes], least recently used first (a
 * hit touches its file). Writes, trims and deletes run in order on [writer], one background
 * thread, never on the thread that opens the runtime.
 */
class PluginBytecodeCache(
    private val dir: File,
    engineTag: String,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val writer: Executor = defaultWriter,
    private val clock: () -> Long = System::currentTimeMillis,
) : CompiledCodeStore {
    private val engineTag = "kino-qjsc-v$FORMAT_VERSION|$engineTag"

    override fun read(owner: String, kind: String, source: String): ByteArray? {
        val file = fileFor(owner, kind) ?: return null
        return try {
            val length = file.length()
            if (length <= 0) return null
            if (length > MAX_FILE_BYTES) { file.delete(); return null }
            val bytes = file.readBytes()
            val payload = unwrap(bytes, keyOf(kind, source))
            if (payload == null) {
                file.delete()
            } else {
                runCatching { file.setLastModified(clock()) }
            }
            payload
        } catch (_: Exception) {
            runCatching { file.delete() }
            null
        }
    }

    override fun write(owner: String, kind: String, source: String, bytecode: ByteArray) {
        val file = fileFor(owner, kind) ?: return
        if (bytecode.isEmpty() || bytecode.size + HEADER_BYTES > MAX_FILE_BYTES) return
        val key = keyOf(kind, source)
        runCatching {
            writer.execute {
                runCatching {
                    dir.mkdirs()
                    val tmp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
                    try {
                        tmp.writeBytes(wrap(key, bytecode))
                        if (!tmp.renameTo(file)) {
                            file.delete()
                            if (!tmp.renameTo(file)) return@runCatching
                        }
                        file.setLastModified(clock())
                    } finally {
                        tmp.delete()
                    }
                    trim()
                }
            }
        }
    }

    override fun discard(owner: String) {
        if (!OWNER.matches(owner)) return
        runCatching {
            writer.execute {
                runCatching { dir.listFiles()?.filter { it.name.startsWith("$owner.") }?.forEach { it.delete() } }
            }
        }
    }

    /** Deletes the least recently used files until the directory fits [maxBytes]; also drops stray temp files. */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        val entries = files.filter { it.name.endsWith(SUFFIX) }.sortedBy { it.lastModified() }
        var total = entries.sumOf { it.length() }
        for (f in entries) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun fileFor(owner: String, kind: String): File? =
        if (OWNER.matches(owner) && KIND.matches(kind)) File(dir, "$owner.$kind$SUFFIX") else null

    private fun keyOf(kind: String, source: String): ByteArray {
        val sourceHash = sha256(source.toByteArray(Charsets.UTF_8))
        val d = MessageDigest.getInstance("SHA-256")
        d.update(engineTag.toByteArray(Charsets.UTF_8))
        d.update(0)
        d.update(kind.toByteArray(Charsets.UTF_8))
        d.update(0)
        d.update(sourceHash)
        return d.digest()
    }

    companion object {
        /** Bump when the file layout or what goes into the key changes. */
        const val FORMAT_VERSION = 1

        const val DEFAULT_MAX_BYTES = 48L * 1024 * 1024

        /** Larger than any bytecode a plugin within the 64 MB heap can produce; anything bigger is not ours. */
        const val MAX_FILE_BYTES = 32L * 1024 * 1024

        /** The prelude every runtime evaluates first: one entry shared by all plugins. */
        const val SHARED_OWNER = "_shared"

        private const val SUFFIX = ".qjsc"
        private val MAGIC = "KINOQJBC".toByteArray(Charsets.US_ASCII)
        private const val HEADER_BYTES = 8 + 32 + 4 + 32
        private val OWNER = Regex("^[a-z0-9_][a-z0-9_-]{0,63}$")
        private val KIND = Regex("^[a-z]{1,16}$")

        private val defaultWriter: Executor by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "plugin-bytecode-cache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            }
        }

        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

        internal fun wrap(key: ByteArray, payload: ByteArray): ByteArray =
            ByteBuffer.allocate(HEADER_BYTES + payload.size)
                .put(MAGIC).put(key).putInt(payload.size).put(sha256(payload)).put(payload)
                .array()

        /** The payload if [bytes] is a whole, intact file for [key]; null otherwise. */
        internal fun unwrap(bytes: ByteArray, key: ByteArray): ByteArray? {
            if (bytes.size <= HEADER_BYTES) return null
            val buf = ByteBuffer.wrap(bytes)
            val magic = ByteArray(MAGIC.size).also { buf.get(it) }
            if (!magic.contentEquals(MAGIC)) return null
            val storedKey = ByteArray(32).also { buf.get(it) }
            if (!MessageDigest.isEqual(storedKey, key)) return null
            val length = buf.getInt()
            if (length <= 0 || length != bytes.size - HEADER_BYTES) return null
            val digest = ByteArray(32).also { buf.get(it) }
            val payload = ByteArray(length).also { buf.get(it) }
            if (!MessageDigest.isEqual(digest, sha256(payload))) return null
            return payload
        }

        /**
         * What a cached file depends on besides the code: the engine ([ENGINE]: QuickJS release +
         * the quickjs-kt binding, whose native lib this app ships patched), the app build (a new
         * versionCode may ship a rebuilt lib), and the architecture of the running process
         * (`os.arch`: a 32-bit process on a 64-bit device is armv7/armv8l, not aarch64).
         */
        fun engineTag(versionCode: Int, arch: String = System.getProperty("os.arch").orEmpty()): String =
            "engine=$ENGINE|app=$versionCode|arch=$arch"

        /** Change with the quickjs-kt dependency or the patched libquickjs (app/src/main/jniLibs/README.md). */
        const val ENGINE = "quickjs-2024-02-14+quickjs-kt-1.0.0-alpha13+kino-patch-0001"
    }
}
