package com.arkiv.player.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the OTA manifest from [sources] in order (GitHub Releases, the jsDelivr and unpkg npm mirror, then
 * archive.org, see [OtaSources]), moving on after a network error, a non-2xx answer or a body that is not a
 * valid manifest ([OtaManifest.parse]).
 *
 * The FIRST source that answers a valid manifest decides (the publish script writes GitHub last, after
 * every mirror already serves the files it names), so a later source never overrides a reachable earlier
 * one, and a stale source (an older release) only ever means [UpdateCheckResult.UpToDate] -- never a
 * downgrade. "Newer" is decided on release BASES ([OtaVersion.isNewer]), never on raw per-ABI codes.
 * The APK offered is the one for this device's ABI ([deviceAbis], see [OtaManifest.forDevice]).
 *
 * When no source can be read the answer is [UpdateCheckResult.Failed], never "up to date" (that
 * confusion told a person on an unreachable archive.org that there was nothing new). The first such
 * failure per process while the device has a network ([online]) is reported through [report] with only the
 * failure class per source: constant message `ota: update check failed`, extras `reason` +
 * `github`/`jsdelivr`/`unpkg`/`archive` + `trigger` (what started the check), never a URL. A failure with no
 * network at all is not reported: on 0.9.50, 53 of 78 reports said the device was offline, and 49 had
 * all four sources failing on `dns` -- that is a device with no usable network, not four mirrors down.
 */
class UpdateChecker(
    client: OkHttpClient,
    private val sources: List<OtaSource> = OtaSources.DEFAULT,
    private val deviceAbis: () -> List<String> = OtaAbi::device,
    private val online: () -> Boolean = { true },
    /** This device's rollout bucket for a release base ([OtaRollout.bucket]). */
    private val rolloutBucket: (base: Int) -> Int = { 0 },
    private val report: (extras: Map<String, String>) -> Unit = { extras ->
        OtaTelemetry.LOGCAT.send("check_failed", extras["reason"] ?: "error", extras)
    },
) {
    /** Redirects followed (archive.org and unpkg answer 302), and each whole call bounded so one slow source can't stall the chain. */
    private val http: OkHttpClient = client.newBuilder()
        .followRedirects(true)
        // Never across schemes: an https->http redirect would hand the manifest to anyone on the path.
        .followSslRedirects(false)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val reported = AtomicBoolean(false)

    companion object {
        private const val CALL_TIMEOUT_SECONDS = 20L
        private const val MAX_MANIFEST_BYTES = 64 * 1024L

        /**
         * A unique query string per fetch so a CDN can't serve a STALE cached `latest.json`.
         * FORCE_NETWORK only bypasses OkHttp's own cache, not the CDN's.
         */
        private fun busted(u: String): String = u + (if ('?' in u) "&" else "?") + "cb=" + System.currentTimeMillis()
    }

    private sealed interface Fetch {
        data class Ok(val manifest: OtaManifest, val source: OtaSource) : Fetch {
            fun forDevice(abis: List<String>) = manifest.forDevice(abis, preferHost = source.name)
        }
        data class Err(val reason: String) : Fetch
    }

    /**
     * [trigger] says what asked (`startup`, `worker`, `foreground`, `network`, `manual`): telemetry only. A newer release
     * outside this device's staged rollout is [UpdateCheckResult.HeldBack], except for the person's own manual check.
     */
    suspend fun check(currentVersionCode: Int, trigger: String = "manual"): UpdateCheckResult = withContext(Dispatchers.IO) {
        val failures = LinkedHashMap<String, String>()
        for (source in sources) {
            when (val got = fetch(source)) {
                is Fetch.Ok -> return@withContext offer(got, currentVersionCode, honorRollout = trigger != "manual")
                is Fetch.Err -> failures[source.name] = got.reason
            }
        }
        val reason = failures.values.firstOrNull() ?: "error"
        if (online() && reported.compareAndSet(false, true)) runCatching { report(mapOf("reason" to reason) + failures + ("trigger" to trigger)) }
        UpdateCheckResult.Failed(reason)
    }

    private fun offer(got: Fetch.Ok, currentVersionCode: Int, honorRollout: Boolean): UpdateCheckResult {
        val m = got.manifest
        if (!OtaVersion.isNewer(m.versionCode, currentVersionCode)) return UpdateCheckResult.UpToDate
        val bucket = rolloutBucket(OtaVersion.baseOf(m.versionCode))
        if (honorRollout && !OtaRollout.includes(bucket, m.rollout)) {
            return UpdateCheckResult.HeldBack(m.versionCode, got.source.name, m.rollout, bucket)
        }
        return UpdateCheckResult.Available(got.forDevice(deviceAbis()), got.source.name, m.rollout)
    }

    /**
     * Last-resort copies of exactly [info]'s APK, asked by [ApkDownloader] only once every url the manifest
     * named has failed: each source's manifest is read again, and any of its variants with [info]'s
     * versionCode and the same sha256 (when both carry one) contributes its urls (plus the npm twin on the
     * other CDN, [OtaSources.npmTwins]). Sources that can't be read are simply left out.
     */
    suspend fun mirrorApkUrls(info: UpdateInfo): List<ApkCopy> = withContext(Dispatchers.IO) {
        sources.flatMap { source ->
            val manifest = (fetch(source) as? Fetch.Ok)?.manifest ?: return@flatMap emptyList()
            (manifest.variants + manifest.universal)
                .filter { v ->
                    v.versionCode == info.versionCode &&
                        (info.sha256.isEmpty() || v.sha256.isEmpty() || info.sha256 == v.sha256)
                }
                .flatMap { v -> v.urls.flatMap(OtaSources::npmTwins).map { ApkCopy(it, info.sha256.ifEmpty { v.sha256 }) } }
        }.distinctBy { it.url }
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
        return OtaManifest.parse(raw, requireIdentity = source.requiresIdentity)?.let { Fetch.Ok(it, source) } ?: Fetch.Err("parse")
    }
}