package com.arkiv.player.data.update

import com.arkiv.player.data.net.DohDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
 * Sources: the manifest's `url` first; if that download fails (network error, non-2xx, or bytes whose
 * sha256 is not the manifest's) the SAME release is fetched from the mirrors' exact-version copies
 * (`fallbackUrls`, asked at most once and only when needed; see [UpdateChecker.mirrorApkUrls]). Only
 * `https` urls on the three known hosts are ever requested ([OtaSources.isAllowedApkUrl]).
 *
 * Integrity: when the manifest carries a sha256 (every manifest the publish script writes does), the
 * bytes must match it whatever source served them -- the same bytes are published everywhere. This gate
 * was dropped once (5c42408e) because a stale archive.org copy of the fixed-name `kino.apk` turned into a
 * dead-end "app corrupta"; with the exact-version mirror copies behind it a mismatch now just moves on to
 * the next source. A legacy manifest without sha256 downloads ungated (Android still verifies the
 * signature at install).
 */
class ApkDownloader(
    private val dir: File,
    client: OkHttpClient = defaultClient(),
    private val report: (extras: Map<String, String>) -> Unit = { extras ->
        com.arkiv.player.crash.Crash.report(com.arkiv.player.crash.OtaDownloadFailed(FAILURE_MESSAGE), "ota-download", extras)
    },
) {
    private val http: OkHttpClient = client.newBuilder().followRedirects(true).followSslRedirects(true).build()

    companion object {
        const val FAILURE_MESSAGE = "ota: apk download failed"
        const val USER_ERROR = "No pudimos descargar la actualización. Revisa tu conexión e intenta de nuevo."

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .dns(DohDns)
            .build()
    }

    private class ShaMismatch : IOException("sha256 mismatch")
    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    fun download(info: UpdateInfo, fallbackUrls: suspend () -> List<String> = { emptyList() }): Flow<DownloadState> = flow {
        val dest = File(dir, com.arkiv.player.data.local.AppStorage.UPDATE_APK)
        val part = File(dir, com.arkiv.player.data.local.AppStorage.UPDATE_APK + ".part")
        dest.delete()
        val failures = LinkedHashMap<String, String>()
        val tried = HashSet<String>()

        suspend fun attempt(url: String): Boolean {
            if (!tried.add(url)) return false
            if (!OtaSources.isAllowedApkUrl(url)) { failures[OtaSources.nameOf(url)] = "host"; return false }
            val ok = try {
                fetchTo(url, part, info.sha256)
                true
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                failures[OtaSources.nameOf(url)] = when (e) {
                    is ShaMismatch -> "sha_mismatch"
                    is HttpStatus -> "http_${e.code}"
                    else -> OtaSources.reasonOf(e)
                }
                false
            }
            if (!ok) part.delete()
            return ok && part.renameTo(dest)
        }

        if (attempt(info.url)) { emit(DownloadState.Ready(dest)); return@flow }
        val mirrors = try { fallbackUrls() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        for (url in mirrors) {
            if (attempt(url)) { emit(DownloadState.Ready(dest)); return@flow }
        }
        runCatching { report(mapOf("versionCode" to info.versionCode.toString()) + failures) }
        emit(DownloadState.Failed(USER_ERROR))
    }.flowOn(Dispatchers.IO)

    /** Streams [url] into [target], emitting progress; throws [HttpStatus], [ShaMismatch] or the I/O error. */
    private suspend fun FlowCollector<DownloadState>.fetchTo(url: String, target: File, sha256: String) {
        emit(DownloadState.Downloading(-1f))
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw HttpStatus(response.code)
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            target.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        currentCoroutineContext().ensureActive()
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        emit(DownloadState.Downloading(if (total > 0) downloaded.toFloat() / total else -1f))
                    }
                }
            }
            val got = digest.digest().joinToString("") { "%02x".format(it) }
            if (sha256.isNotEmpty() && got != sha256) throw ShaMismatch()
        }
    }
}
