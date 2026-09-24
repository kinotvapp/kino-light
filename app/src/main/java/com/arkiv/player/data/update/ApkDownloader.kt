package com.arkiv.player.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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
     * Downloads the APK at [url] to a private cache file and hands it to the installer. There's no
     * app-side integrity gate on purpose: Android's own package installer verifies the APK's
     * signature and integrity at install time (a truncated or altered file is rejected there), so a
     * second sha256 check here only ever added a fragile failure mode -- a stale/propagating
     * archive.org manifest whose sha didn't match the served bytes surfaced as a false "app corrupta"
     * even though the file was fine. Dropping it means the OTA never rejects a download; a bad one
     * simply fails to install and the user retries.
     */
    fun download(url: String): Flow<DownloadState> = flow {
        try {
            val dest = File(context.cacheDir, com.arkiv.player.data.local.AppStorage.UPDATE_APK)
            if (dest.exists()) dest.delete()
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                com.arkiv.player.crash.Crash.report(
                    com.arkiv.player.crash.OtaDownloadFailed("HTTP $code for $url"), "ota-download",
                )
                emit(DownloadState.Failed("HTTP $code"))
                return@flow
            }
            val body = response.body ?: run {
                response.close()
                emit(DownloadState.Failed("Empty response"))
                return@flow
            }
            val total = body.contentLength()
            var downloaded = 0L
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        downloaded += read
                        val progress = if (total > 0) downloaded.toFloat() / total else -1f
                        emit(DownloadState.Downloading(progress))
                    }
                }
            }
            emit(DownloadState.Ready(dest))
        } catch (e: Exception) {
            emit(DownloadState.Failed(e.message ?: "Error de descarga"))
        }
    }.flowOn(Dispatchers.IO)
}
