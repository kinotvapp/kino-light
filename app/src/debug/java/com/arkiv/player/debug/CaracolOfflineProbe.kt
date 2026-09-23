package com.arkiv.player.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.MediaDrmCallback
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.arkiv.player.data.ditu.DituClient
import com.arkiv.player.data.ditu.DituRef
import com.arkiv.player.data.ditu.DituResolve
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug-only probe: does Caracol's license server hand out an OFFLINE Widevine licence?
 *
 * That single yes/no decides whether a Caracol episode can be downloaded at all. Its video is
 * DASH + Widevine CENC (measured 2026-09-13 -- the manifest carries `cenc:pssh` and the media path
 * is literally `/video/drm/`), and Mediastream's progressive `.mp4` rendition, the one route that
 * would have needed no keys, answers 401 from every referer. So the only lawful door left is the
 * one the platform itself provides: a PERSISTENT licence, the same mechanism every "download to
 * watch offline" button in a streaming app uses. The bytes stay encrypted on disk; what is stored
 * next to them is a key-set id that only this device's CDM can use, and only until the licence
 * expires.
 *
 * If the server refuses (its licence policy may simply not allow persistence for a guest), the
 * feature is impossible without breaking the DRM, and that is where this stops.
 *
 * Run it with the app already launched at least once:
 * ```
 * adb shell am broadcast -a com.arkiv.player.light.SONDA_CARACOL \
 *     -p com.arkiv.player.light --es id 1000010026
 * adb logcat -s ArkivCaracolProbe
 * ```
 * `id` is a Caracol `contentId` of an episode (a VOD); it defaults to [DEFAULT].
 *
 * Lives in `src/debug` on purpose: it is a measurement, not a feature, and it must not exist in a
 * release APK.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CaracolOfflineProbe : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val contentId = intent.getStringExtra("id")?.takeIf { it.isNotBlank() } ?: DEFAULT
        // Its own scope: a receiver's onReceive has ~10 s and the licence round trip is slower.
        CoroutineScope(Dispatchers.IO).launch { runProbe(contentId) }
    }

    private suspend fun runProbe(contentId: String) {
        Log.w(TAG, "probe starts · contentId=$contentId")

        val play = runCatching { DituResolve(DituClient()).vod(DituRef(contentId, "VOD")) }
            .getOrElse { Log.e(TAG, "step 1/4 resolve FAILED", it); return }
        Log.w(
            TAG,
            "step 1/4 resolved · mpd=${play.url.take(90)} license=${play.drmLicenseUrl.take(70)} " +
                "headers=${play.drmLicenseHeaders.keys}",
        )
        if (play.drmLicenseHeaders.isEmpty()) {
            Log.w(TAG, "no playback_token came back -- the licence request will very likely be rejected")
        }

        // The same data source the player uses: without `restful: yes` and this User-Agent the CDN
        // answers 403 to the manifest, and without the token cookie the licence server says no.
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("okhttp/4.12.0")
            .setDefaultRequestProperties(mapOf("restful" to "yes") + play.drmLicenseHeaders)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)

        val manifest = runCatching { DashUtil.loadManifest(http.createDataSource(), Uri.parse(play.url)) }
            .getOrElse { Log.e(TAG, "step 2/4 manifest FAILED", it); return }
        Log.w(TAG, "step 2/4 manifest · periods=${manifest.periodCount} durationMs=${manifest.durationMs}")

        val format = runCatching { DashUtil.loadFormatWithDrmInitData(http.createDataSource(), manifest.getPeriod(0)) }
            .getOrElse { Log.e(TAG, "step 3/4 format FAILED", it); return }
        if (format == null) {
            Log.e(TAG, "step 3/4 no format carries drmInitData -- nothing to ask a licence for")
            return
        }
        Log.w(TAG, "step 3/4 format · ${format.codecs} ${format.width}x${format.height} drmInit=${format.drmInitData != null}")

        // THE CONTROL, and it runs first on purpose. A failed offline request proves nothing on its
        // own: a missing header or a stale token fails in exactly the same way. So ask the same
        // server, with the same token and the same data source, for the STREAMING licence the
        // player gets every day. If that one is granted and the offline one is not, the only thing
        // that differs between the two requests is persistence.
        val streaming = streamingControl(callbackFor(http, play.drmLicenseUrl, play.drmLicenseHeaders), format)
        Log.w(TAG, "step 4/5 STREAMING licence (control) · $streaming")

        val helper = OfflineLicenseHelper.newWidevineInstance(
            play.drmLicenseUrl,
            http,
            DrmSessionEventListener.EventDispatcher(),
        )
        val keySetId = runCatching { helper.downloadLicense(format) }
            .getOrElse {
                Log.e(TAG, "step 5/5 OFFLINE LICENCE REFUSED · ${failureBody(it)}", it)
                helper.release()
                return
            }
        val remaining = runCatching { helper.getLicenseDurationRemainingSec(keySetId) }.getOrNull()
        Log.w(
            TAG,
            "step 5/5 OFFLINE LICENCE GRANTED · keySetId=${keySetId.size}B " +
                "playbackRemaining=${remaining?.first}s purchaseRemaining=${remaining?.second}s",
        )
        // Hand it straight back: this probe only asks whether the door opens, and a licence left
        // behind counts against whatever per-device limit the server keeps.
        runCatching { helper.releaseLicense(keySetId) }
            .onFailure { Log.w(TAG, "releasing the licence failed: $it") }
        helper.release()
    }

    private fun callbackFor(
        http: HttpDataSource.Factory,
        licenseUrl: String,
        headers: Map<String, String>,
    ): MediaDrmCallback = HttpMediaDrmCallback(licenseUrl, http).also { cb ->
        headers.forEach { (k, v) -> cb.setKeyRequestProperty(k, v) }
    }

    /**
     * Asks for a normal PLAYBACK licence and reports what happened.
     *
     * It needs a Looper of its own because [DefaultDrmSessionManager] insists on being driven from
     * the playback thread -- `acquireSession` asserts it. [OfflineLicenseHelper] hides that same
     * machinery for the download case; there is no equivalent helper for playback, so this is it.
     */
    private fun streamingControl(license: MediaDrmCallback, format: Format): String {
        val thread = HandlerThread("caracol-probe-drm").also { it.start() }
        return try {
            val manager = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .build(license)
            manager.setPlayer(thread.looper, PlayerId.UNSET)
            manager.prepare()

            var session: DrmSession? = null
            val sessionLatch = CountDownLatch(1)
            Handler(thread.looper).post {
                session = runCatching { manager.acquireSession(DrmSessionEventListener.EventDispatcher(), format) }
                    .getOrElse { Log.e(TAG, "acquireSession threw", it); null }
                sessionLatch.countDown()
            }
            sessionLatch.await(20, TimeUnit.SECONDS)
            val s = session ?: return "acquireSession gave nothing back"

            val deadline = SystemClock.elapsedRealtime() + WAIT_MS
            while (SystemClock.elapsedRealtime() < deadline &&
                s.state != DrmSession.STATE_OPENED_WITH_KEYS &&
                s.state != DrmSession.STATE_ERROR
            ) {
                Thread.sleep(100)
            }
            val verdict = when (s.state) {
                DrmSession.STATE_OPENED_WITH_KEYS -> "GRANTED"
                DrmSession.STATE_ERROR -> "REFUSED · ${failureBody(s.error)} · ${s.error}"
                else -> "timed out in state ${s.state}"
            }
            Handler(thread.looper).post {
                runCatching { s.release(null) }
                runCatching { manager.release() }
            }
            verdict
        } catch (e: Throwable) {
            "the control itself blew up: $e"
        } finally {
            thread.quitSafely()
        }
    }

    /**
     * What the licence server actually WROTE in its error, walking down the chain of causes.
     *
     * media3 reports "Response code: 500" and drops the body on the floor, and the body is where a
     * server says whether it refused the request or merely fell over.
     */
    private fun failureBody(e: Throwable?): String {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                val body = runCatching { String(cause.responseBody).take(400) }.getOrDefault("")
                return "HTTP ${cause.responseCode} body=${body.ifBlank { "(empty)" }}"
            }
            cause = cause.cause
        }
        return "no HTTP response behind it"
    }

    private companion object {
        const val TAG = "ArkivCaracolProbe"

        /** "Dulce Amor" episode 1 -- a free, non-geoblocked VOD that resolves today. */
        const val DEFAULT = "1000010026"

        /** How long to wait for a licence verdict before calling it a timeout. */
        const val WAIT_MS = 20_000L
    }
}
