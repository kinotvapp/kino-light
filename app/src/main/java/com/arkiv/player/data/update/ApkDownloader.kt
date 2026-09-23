package com.arkiv.player.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface DownloadState {
    data class Downloading(val progress: Float) : DownloadState
    data class Ready(val file: File) : DownloadState
    data class Failed(val error: String) : DownloadState
}

class ApkDownloader(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads the APK at [url] to a private cache file. When [expectedSha256] is non-blank, the
     * bytes are hashed as they stream and the file is REJECTED (deleted, [DownloadState.Failed]) if
     * the digest doesn't match -- so a truncated/corrupt download, or an error page served instead
     * of the APK (an archive.org item still propagating, a stale cache), never reaches the installer.
     * Integrity only; the APK signature is what gates the actual install.
     */
    fun download(url: String, expectedSha256: String = ""): Flow<DownloadState> = flow {
        try {
            val dest = File(context.cacheDir, "update.apk")
            if (dest.exists()) dest.delete()
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                response.close()
                emit(DownloadState.Failed("HTTP ${response.code}"))
                return@flow
            }
            val body = response.body ?: run {
                response.close()
                emit(DownloadState.Failed("Empty response"))
                return@flow
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val total = body.contentLength()
            var downloaded = 0L
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        val progress = if (total > 0) downloaded.toFloat() / total else -1f
                        emit(DownloadState.Downloading(progress))
                    }
                }
            }
            if (expectedSha256.isNotBlank()) {
                val actual = toHex(digest.digest())
                if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                    dest.delete()
                    emit(DownloadState.Failed("La descarga no coincide con la esperada (archivo corrupto o incompleto). Se reintentará."))
                    return@flow
                }
            }
            emit(DownloadState.Ready(dest))
        } catch (e: Exception) {
            emit(DownloadState.Failed(e.message ?: "Error de descarga"))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Lowercase-hex encoding of [bytes]. Shared by the download's integrity check and its test. */
        fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        /** Lowercase-hex SHA-256 of [bytes]. */
        fun sha256Hex(bytes: ByteArray): String =
            toHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
