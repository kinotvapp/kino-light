package com.arkiv.player.data.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class UpdateChecker(
    private val client: OkHttpClient,
    private val url: String = DEFAULT_URL,
) {
    companion object {
        // The OTA manifest lives on an archive.org item (see kino-light-release-credentials-blob /
        // the OTA runbook): a stable `download/<item>/latest.json` URL with raw bytes and no
        // Cloudflare in front (so no anti-XSSI prefix / notes-truncation gotchas). The GitHub
        // releases URL 404'd once the repo went private. The `<item>` (kino-app) also hosts the
        // versioned APKs the manifest's `url` points at.
        const val DEFAULT_URL = "https://archive.org/download/kino-app/latest.json"

        /**
         * A unique query string per fetch so archive.org's CDN can't serve a STALE cached
         * `latest.json`. FORCE_NETWORK only bypasses OkHttp's own cache, not the CDN's, so without
         * this a CDN edge holding an old manifest could keep pointing the app at an outdated
         * `versionCode`/`url`. Different URL = cache miss = fresh bytes.
         */
        private fun busted(u: String): String = u + (if ('?' in u) "&" else "?") + "cb=" + System.currentTimeMillis()
    }

    suspend fun check(currentVersionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.newCall(Request.Builder().url(busted(url)).cacheControl(CacheControl.FORCE_NETWORK).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
            val json = JSONObject(raw)
            val remote = UpdateInfo(
                versionCode = json.getInt("versionCode"),
                versionName = json.getString("versionName"),
                url = json.getString("url"),
                notes = json.optString("notes", ""),
            )
            if (remote.versionCode > currentVersionCode) remote else null
        }.getOrNull()
    }

    /**
     * The APK's URL, whether or not there's a newer version than the installed one.
     *
     * [check] returns `null` when you're already up to date, which is right for the update notice
     * but useless for the TV entry screen's download QR: there the URL is needed always, and it
     * comes from the same `latest.json` so it doesn't go stale once a new version is published
     * (the URL carries the number inside).
     */
    suspend fun downloadUrl(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.newCall(Request.Builder().url(busted(url)).cacheControl(CacheControl.FORCE_NETWORK).build()).execute()
                .use { if (it.isSuccessful) it.body?.string() else null } ?: return@withContext null
            JSONObject(raw).getString("url")
        }.getOrNull()
    }
}
