package com.arkiv.player.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the OTA manifest from [sources] in order (archive.org, then the jsDelivr and unpkg mirrors, see
 * [OtaSources]), moving on after a network error, a non-2xx answer or a body that is not a valid manifest.
 *
 * The FIRST source that answers a valid manifest decides: archive.org is the source of truth and the
 * mirrors only stand in when it cannot be read, so a mirror never overrides a reachable archive, and a
 * stale mirror (lower versionCode) only ever means [UpdateCheckResult.UpToDate] -- never a downgrade.
 *
 * When no source can be read the answer is [UpdateCheckResult.Failed], never "up to date" (that
 * confusion told a person on an unreachable archive.org that there was nothing new). The first such
 * failure per process is reported through [report] with only the failure class per source: constant
 * message `ota: update check failed`, extras `reason` + `archive`/`jsdelivr`/`unpkg`, never a URL.
 */
class UpdateChecker(
    client: OkHttpClient,
    private val sources: List<OtaSource> = OtaSources.DEFAULT,
    private val report: (extras: Map<String, String>) -> Unit = { extras ->
        com.arkiv.player.crash.Crash.report(com.arkiv.player.crash.OtaCheckFailed(FAILURE_MESSAGE), "ota-check", extras)
    },
) {
    /** Redirects followed (archive.org and unpkg answer 302), and each whole call bounded so one slow source can't stall the chain. */
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val reported = AtomicBoolean(false)

    companion object {
        const val FAILURE_MESSAGE = "ota: update check failed"
        private const val CALL_TIMEOUT_SECONDS = 20L
        private const val MAX_MANIFEST_BYTES = 64 * 1024L
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /**
         * A unique query string per fetch so a CDN can't serve a STALE cached `latest.json`.
         * FORCE_NETWORK only bypasses OkHttp's own cache, not the CDN's.
         */
        private fun busted(u: String): String = u + (if ('?' in u) "&" else "?") + "cb=" + System.currentTimeMillis()
    }

    private sealed interface Fetch {
        data class Ok(val info: UpdateInfo) : Fetch
        data class Err(val reason: String) : Fetch
    }

    suspend fun check(currentVersionCode: Int): UpdateCheckResult = withContext(Dispatchers.IO) {
        val failures = LinkedHashMap<String, String>()
        for (source in sources) {
            when (val got = fetch(source)) {
                is Fetch.Ok -> return@withContext if (got.info.versionCode > currentVersionCode) {
                    UpdateCheckResult.Available(got.info)
                } else {
                    UpdateCheckResult.UpToDate
                }
                is Fetch.Err -> failures[source.name] = got.reason
            }
        }
        val reason = failures.values.firstOrNull() ?: "error"
        if (reported.compareAndSet(false, true)) runCatching { report(mapOf("reason" to reason) + failures) }
        UpdateCheckResult.Failed(reason)
    }

    /**
     * The mirrors' APK urls for exactly [info]'s release: a mirror counts only when its manifest names the
     * same versionCode and, when both carry one, the same sha256. For [ApkDownloader] to fall back on when
     * the primary APK can't be downloaded. Each url comes with its twin on the other CDN
     * ([OtaSources.npmTwins]). Mirrors that can't be read are simply left out.
     */
    suspend fun mirrorApkUrls(info: UpdateInfo): List<String> = withContext(Dispatchers.IO) {
        OtaSources.MIRRORS.mapNotNull { source ->
            val mirror = (fetch(source) as? Fetch.Ok)?.info ?: return@mapNotNull null
            val sameBytes = info.sha256.isEmpty() || mirror.sha256.isEmpty() || info.sha256 == mirror.sha256
            OtaSources.npmTwins(mirror.url).takeIf { mirror.versionCode == info.versionCode && sameBytes }
        }.flatten().distinct()
    }

    private suspend fun fetch(source: OtaSource): Fetch {
        val raw = try {
            http.newCall(Request.Builder().url(busted(source.manifestUrl)).cacheControl(CacheControl.FORCE_NETWORK).build()).execute().use { r ->
                if (!r.isSuccessful) return Fetch.Err("http_${r.code}")
                val body = r.body ?: return Fetch.Err("parse")
                val bytes = body.source().let { s ->
                    if (s.request(MAX_MANIFEST_BYTES + 1)) return Fetch.Err("parse")
                    s.readByteArray()
                }
                bytes.toString(Charsets.UTF_8)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            kotlin.coroutines.coroutineContext.ensureActive()
            return Fetch.Err(OtaSources.reasonOf(e))
        }
        return parse(raw)?.let { Fetch.Ok(it.copy(url = OtaSources.onHostOf(source, it.url))) } ?: Fetch.Err("parse")
    }

    private fun parse(raw: String): UpdateInfo? = runCatching {
        val json = JSONObject(raw)
        val sha = json.optString("sha256", "").lowercase()
        if (sha.isNotEmpty() && !SHA256.matches(sha)) return null
        val info = UpdateInfo(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            url = json.getString("url"),
            notes = json.optString("notes", ""),
            sha256 = sha,
        )
        info.takeIf { OtaSources.isAllowedApkUrl(it.url) }
    }.getOrNull()
}
