package com.arkiv.player.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

sealed interface DownloadState {
    data class Downloading(val progress: Float) : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Failed(val error: String) : DownloadState
}

/**
 * Downloads the APK of one release into [dir] (the app's cache) and hands it to the installer.
 *
 * Sources, in order: the manifest's choice for this device (`info.url`: its ABI APK, or the universal one),
 * then `info.alternates` (the same bytes on the other mirrors -- GitHub Releases, jsDelivr, unpkg -- then,
 * for an ABI APK, the universal APK's copies down to archive.org's), and only when all of those failed the
 * copies the sources' manifests name for the same versionCode (`fallbackUrls`, asked at most once; see
 * [UpdateChecker.mirrorApkUrls]). Only `https` urls on the four known hosts are ever requested
 * ([OtaSources.isAllowedApkUrl]); github.com's 302 to its asset CDN is followed.
 *
 * Integrity: every copy carries the sha256 of ITS file (an ABI APK and the universal are different files),
 * and the bytes must match it whatever source served them; a mismatch (a stale CDN copy, archive.org's
 * fixed-name `kino.apk` mid-replacement) just moves on to the next copy. This gate was dropped once
 * (5c42408e) because a stale archive.org copy turned into a dead-end "app corrupta"; with the mirror copies
 * behind it a mismatch is never a dead end. A legacy manifest without sha256 downloads ungated (Android
 * still verifies the signature at install, and refuses a lower versionCode).
 *
 * Identity: bytes that passed the sha256 gate are then opened as a package ([verifyApk], [ApkIdentity]) and must
 * be this app, signed like the installed one, of the announced release and newer than the installed one; a
 * copy that is not is deleted and the next copy of OTHER bytes tried (the same sha256 elsewhere would get the same
 * verdict, so it is skipped). A release refused that way is recorded in [rejections]: it is not downloaded again
 * while its backoff lasts, the person is told to install it by hand ([MANUAL_INSTALL]), and it is reported once.
 *
 * Failure modes end in ONE [DownloadState.Failed] with one sentence, never a throw (only cancellation, which deletes the
 * partial file): every copy failing ([USER_ERROR]), a refused release ([MANUAL_INSTALL]), or no room on the device
 * ([NO_SPACE]: checked against the announced size before writing, and a write that fails with ENOSPC; no other copy is
 * tried, they need the same room). One download at a time: a second caller waits for the first ([lock]).
 * Telemetry ([OtaTelemetry]): `download_failed`, `identity`, `given_up`.
 */
class ApkDownloader(
    private val dir: File,
    client: OkHttpClient = defaultClient(),
    private val telemetry: OtaTelemetry = OtaTelemetry.LOGCAT,
    /** The installable-APK gate run on each copy whose sha256 matched ([ApkIdentity.checker]): (file, release base) -> verdict. */
    private val verifyApk: (File, Int) -> ApkCheck = { _, _ -> ApkCheck(null) },
    /** Releases refused for a reason that would repeat; null = none kept (tests of the plain download). */
    private val rejections: OtaRejections? = null,
    /** Attempts and the winning source per release, for the `updated` event; null = not kept. */
    private val ledger: OtaLedger? = null,
    /** This device's rollout bucket for a release base, added to every report. */
    private val bucketOf: (base: Int) -> Int = { -1 },
    /** Free bytes where the APK is written (a seam for the full-disk test). */
    private val freeBytes: () -> Long = { dir.usableSpace },
    /** The name the APK is written under in [dir]: Kino's own update, or another APK's (the CloudStream complement). */
    private val fileName: String = "update.apk",
    /** What the person reads when no copy could be downloaded and verified (the [USER_ERROR] case). */
    private val userError: String = USER_ERROR,
    /** What the person reads when there is no room for the APK (the [NO_SPACE] case). */
    private val noSpaceError: String = NO_SPACE,
    /**
     * What the person reads when every copy that downloaded was refused by [verifyApk] and no [rejections] store is kept;
     * null = told as [userError] (a store, as for Kino's own update, sends the person to GitHub instead).
     */
    private val refusedError: String? = null,
) {
    /** https->https redirects only (github.com's 302 to its asset CDN): a hop down to http is not followed. */
    private val http: OkHttpClient = client.newBuilder().followRedirects(true).followSslRedirects(false).build()

    private val lock = Mutex()

    companion object {
        const val USER_ERROR = "No pudimos descargar la actualización. Revisa tu conexión e intenta de nuevo."
        const val MANUAL_INSTALL = "No pudimos instalar la actualización automáticamente: instálala desde github.com/kinotvapp/kino-light/releases"
        const val NO_SPACE = "No hay espacio suficiente para descargar la actualización. Libera espacio en el aparato e intenta de nuevo."

        /** Room kept free beyond the APK itself, so the download never fills the device to the last byte. */
        const val SPACE_MARGIN_BYTES = 20L * 1024 * 1024

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /** An I/O failure that is the device running out of room (ENOSPC), whatever layer wrapped it. */
        internal fun isDiskFull(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(10).any {
            it.message.orEmpty().let { m -> "ENOSPC" in m || m.contains("No space left", ignoreCase = true) }
        }
    }

    private class ShaMismatch : IOException("sha256 mismatch")
    private class HttpStatus(val code: Int) : IOException("HTTP $code")
    private class DiskFull : IOException("no space left")
    private class Truncated : IOException("truncated")

    /** One copy tried, for the report: `host:class:gotKB/expectedKB:ms`. */
    private class CopyLog(val host: String) {
        var outcome = "ok"
        var got = 0L
        var expected = -1L
        var ms = 0L
        override fun toString() = "$host:$outcome:${got / 1024}/${if (expected < 0) "?" else (expected / 1024).toString()}:$ms"
    }

    fun download(info: UpdateInfo, fallbackUrls: suspend () -> List<ApkCopy> = { emptyList() }): Flow<DownloadState> = flow {
        lock.withLock { run(info, fallbackUrls) }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<DownloadState>.run(info: UpdateInfo, fallbackUrls: suspend () -> List<ApkCopy>) {
        val dest = File(dir, fileName)
        val part = File(dir, "$fileName.part")
        runCatching { dest.delete() }
        val base = OtaVersion.baseOf(info.versionCode)
        if (rejections?.blocked(base) == true) {
            emit(DownloadState.Failed(MANUAL_INSTALL))
            return
        }
        val attempts = runCatching { ledger?.downloadStarted(base) }.getOrNull()
        val failures = LinkedHashMap<String, String>()
        val facts = LinkedHashMap<String, String>()
        val copies = ArrayList<CopyLog>()
        val tried = HashSet<String>()
        val refusedSha = HashSet<String>()
        val refusals = ArrayList<String>()
        var diskFull = false
        val common = mapOf("versionCode" to info.versionCode.toString(), "abi" to info.abi, "bucket" to bucketOf(base).toString())

        fun fail(url: String, reason: String) {
            val key = OtaSources.nameOf(url)
            failures[key] = failures[key]?.let { "$it,$reason" } ?: reason
        }

        suspend fun attempt(copy: ApkCopy): Boolean {
            val url = copy.url
            if (diskFull || !tried.add(url)) return false
            if (!OtaSources.isAllowedApkUrl(url)) { fail(url, "host"); return false }
            if (copy.sha256.isNotEmpty() && copy.sha256 in refusedSha) { fail(url, "same_bytes"); return false }
            val log = CopyLog(OtaSources.nameOf(url)).also { copies += it }
            val started = System.currentTimeMillis()
            val ok = try {
                fetchTo(url, part, copy.sha256, log)
                true
            } catch (e: CancellationException) {
                runCatching { part.delete() }
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val reason = when {
                    e is ShaMismatch -> "sha_mismatch"
                    e is HttpStatus -> "http_${e.code}"
                    e is Truncated -> "truncated"
                    e is DiskFull || isDiskFull(e) -> "disk_full"
                    else -> OtaSources.reasonOf(e)
                }
                if (reason == "disk_full") diskFull = true
                log.outcome = reason
                fail(url, reason)
                false
            } finally {
                log.ms = System.currentTimeMillis() - started
            }
            if (!ok) { runCatching { part.delete() }; return false }
            if (!runCatching { part.renameTo(dest) }.getOrDefault(false)) {
                log.outcome = "rename"
                fail(url, "rename")
                runCatching { part.delete() }
                return false
            }
            // Opened under its final `.apk` name: the package parser is not asked to read a `.part` file.
            // A verifier that throws proved nothing: the installer decides (see ApkIdentity.BLOCKING).
            val check = runCatching { verifyApk(dest, base) }.getOrElse {
                ApkCheck(null, mapOf("read_error" to it.javaClass.simpleName), notable = "unverified_apk_unreadable")
            }
            facts.putAll(check.facts)
            check.notable?.let { telemetry.send("identity", it, common + check.facts + ("host" to log.host)) }
            check.observed?.let { telemetry.send("identity_observed", it, common + check.facts + ("host" to log.host)) }
            val wrong = check.failure
            if (wrong == null) {
                runCatching { ledger?.downloaded(base, log.host) }
                return true
            }
            log.outcome = wrong
            fail(url, wrong)
            refusals += wrong
            runCatching { dest.delete() }
            if (copy.sha256.isNotEmpty()) refusedSha += copy.sha256
            return false
        }

        for (copy in listOf(ApkCopy(info.url, info.sha256)) + info.alternates) {
            if (attempt(copy)) { emit(DownloadState.Ready(dest)); return }
        }
        if (!diskFull) {
            val mirrors = try { fallbackUrls() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
            for (copy in mirrors) {
                if (attempt(copy)) { emit(DownloadState.Ready(dest)); return }
            }
        }
        val extras = common + failures + facts + mapOf(
            "copies" to copies.joinToString(";"),
            "tries" to copies.size.toString(),
            "attempt" to (attempts?.toString() ?: "?"),
        )
        when {
            diskFull -> {
                telemetry.send("download_failed", "disk_full", extras)
                emit(DownloadState.Failed(noSpaceError))
            }
            // Refused bytes are refused again next time: record the release, report it once, send the person to GitHub.
            refusals.isNotEmpty() && rejections != null -> {
                val refusal = rejections.reject(base, OtaRejections.CAUSE_IDENTITY)
                if (refusal.first) telemetry.send("download_failed", "refused", extras)
                telemetry.send(
                    "given_up", refusals.first(),
                    common + mapOf("base" to base.toString(), "count" to refusal.count.toString(), "backoff_h" to (refusal.backoffMs / 3_600_000).toString()),
                )
                emit(DownloadState.Failed(MANUAL_INSTALL))
            }
            // No store to remember it (the CloudStream complement): told as a refusal, never as a connection problem.
            refusals.isNotEmpty() && refusedError != null -> {
                telemetry.send("download_failed", "refused", extras)
                emit(DownloadState.Failed(refusedError))
            }
            else -> {
                telemetry.send("download_failed", failures.values.firstOrNull()?.substringBefore(',') ?: "none", extras)
                emit(DownloadState.Failed(userError))
            }
        }
    }

    /** Streams [url] into [target], emitting progress; throws [HttpStatus], [ShaMismatch], [DiskFull] or the I/O error. */
    private suspend fun FlowCollector<DownloadState>.fetchTo(url: String, target: File, sha256: String, log: CopyLog) {
        emit(DownloadState.Downloading(-1f))
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatus(response.code)
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength()
            log.expected = total
            if (total > 0 && runCatching(freeBytes).getOrDefault(Long.MAX_VALUE) < total + SPACE_MARGIN_BYTES) throw DiskFull()
            val digest = MessageDigest.getInstance("SHA-256")
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        currentCoroutineContext().ensureActive()
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        log.got += read
                        emit(DownloadState.Downloading(if (total > 0) (log.got.toFloat() / total).coerceAtMost(1f) else -1f))
                    }
                }
            }
            if (total > 0 && log.got != total) throw Truncated()
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha256.isNotEmpty() && got != sha256) throw ShaMismatch()
        }
    }
}