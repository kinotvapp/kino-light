package com.arkiv.player.data.update

import org.json.JSONObject

/**
 * The versionCode scheme from 0.9.50 on (written down in `app/build.gradle.kts`, "Release versionCodes").
 *
 * `.env` VERSION_CODE is a RELEASE BASE that grows by one per published version (0.9.49 = 78, 0.9.50 = 79).
 * Each APK of a release is `base * 10 + digit`: armeabi-v7a 1, arm64-v8a 2, x86_64 4, universal 9. So every
 * APK of release N+1 is above every APK of release N, and the legacy codes (one APK per release, <= 78)
 * are all below the first split code 790.
 *
 * Two raw codes are NEVER compared to decide "newer": an arm64 device on 792 reading a manifest whose
 * universal is 799 would see a bogus update to its own release. [isNewer] compares bases instead.
 */
object OtaVersion {
    /** The first release built with per-ABI codes (0.9.50). */
    const val FIRST_SPLIT_BASE = 79
    const val UNIVERSAL_DIGIT = 9

    /** The release a versionCode belongs to: `code / 10` for a split-scheme code (>= 790), else the legacy code itself. */
    fun baseOf(code: Int): Int = if (code >= FIRST_SPLIT_BASE * 10) code / 10 else code

    /** True when [candidate] belongs to a LATER release than [installed] (any ABI of either). */
    fun isNewer(candidate: Int, installed: Int): Boolean = baseOf(candidate) > baseOf(installed)
}

/** The ABI names a manifest may list (`Build.SUPPORTED_ABIS` spelling) plus [UNIVERSAL]. */
object OtaAbi {
    const val UNIVERSAL = "universal"
    val SPLITS: Set<String> = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    /**
     * The device's ABIs, preferred first (`Build.SUPPORTED_ABIS`). Its FIRST entry is the userland the OS
     * runs: a Fire TV with a 64-bit kernel but a 32-bit userland answers `armeabi-v7a` first.
     */
    fun device(): List<String> = runCatching { android.os.Build.SUPPORTED_ABIS?.toList() }.getOrNull().orEmpty()
}

/** One place the exact bytes of one APK can be downloaded from, with the sha256 they must hash to. */
data class ApkCopy(val url: String, val sha256: String)

/** One APK of a release: [abi] is a split ABI or [OtaAbi.UNIVERSAL]; [urls] are its mirrors, all the same bytes. */
data class ApkVariant(val abi: String, val versionCode: Int, val sha256: String, val urls: List<String>)

/**
 * A parsed `latest.json`. The same document is served by every mirror; only the legacy top-level `url`
 * differs per host (each host's own universal copy, which is what clients <= 0.9.49 download).
 *
 * Schema 1 (every client reads it): `versionCode`, `versionName`, `url`, `sha256`, `notes` -- the
 * UNIVERSAL APK. Schema 2 adds, for clients >= 0.9.50 only:
 * ```
 * "schema": 2, "app": "com.arkiv.player.light",
 * "apks": [ { "abi": "arm64-v8a", "versionCode": 792, "sha256": "<hex>", "size": 13500000,
 *             "urls": ["https://github.com/kinotvapp/kino-light/releases/download/v0.9.50/kino-arm64-v8a.apk",
 *                      "https://cdn.jsdelivr.net/npm/static-asset-pack@1.0.9/ota/kino-arm64-v8a.apk", ...] },
 *           ...,
 *           { "abi": "universal", "versionCode": 799, "sha256": "<same as top-level>", "urls": [..., archive.org] } ]
 * ```
 * Identity: a schema-2 manifest (`schema` >= 2 or an `apks` list) MUST carry `"app": "`[APP_ID]`"`, and any
 * manifest that names another app is refused -- so a foreign manifest (the demo app sharing the GitHub repo)
 * is a failed source, never an answer. Pass `requireIdentity` for a source that never served schema 1.
 *
 * Staged rollout (clients >= 0.9.52): an optional top-level `"rollout": <0..100>` offers the release only to that
 * percent of devices ([OtaRollout]). Absent = 100 (everyone). Present but not a JSON number in 0..100 (text, null,
 * out of range) = [OtaRollout.INVALID]: offered to nobody automatically -- a typo must not ship a release to everyone --
 * and reported (`offered/invalid_rollout`). A whole-number percent; a fraction is rounded down. Clients <= 0.9.51
 * ignore the field and take the release at once.
 *
 * Validation: a variant with an unknown ABI, a malformed sha256, a code of another release than the
 * top-level one, or no allowed url is dropped (the rest of the manifest still counts). A `universal`
 * variant whose sha256 contradicts the top-level one makes the whole manifest invalid.
 *
 * kino-light ignores the CloudStream complement (`bridge` key) for two reasons: the bridge-contract AIDL module
 * is not part of this fork's build, and the manifest is dropped as an unknown JSON key (clients <= 0.9.53 do
 * the same).
 */
data class OtaManifest(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val sha256: String,
    val notes: String,
    val variants: List<ApkVariant>,
    /** Percent of devices offered this release ([OtaRollout]); [OtaRollout.ALL] when the manifest says nothing usable. */
    val rollout: Int = OtaRollout.ALL,
) {
    /**
     * The universal APK: the schema-2 entry merged with the legacy top-level fields. An npm url also
     * yields its twin on the other CDN ([OtaSources.npmTwins]): the mirror publishes one document for both.
     */
    val universal: ApkVariant
        get() {
            val listed = variants.firstOrNull { it.abi == OtaAbi.UNIVERSAL }
            return ApkVariant(
                abi = OtaAbi.UNIVERSAL,
                versionCode = listed?.versionCode ?: versionCode,
                sha256 = sha256.ifEmpty { listed?.sha256.orEmpty() },
                urls = (OtaSources.npmTwins(url) + listed?.urls.orEmpty().flatMap(OtaSources::npmTwins)).distinct(),
            )
        }

    /**
     * What this device downloads: the variant of its FIRST ABI ([OtaAbi.device]) when the manifest lists
     * one, else the universal. Its urls are ordered with [preferHost]'s copies first (the host the manifest
     * was just read from has proved reachable); when the choice is a split, the universal's copies come
     * after as the last fallback (another file, so each copy carries its own sha256).
     */
    fun forDevice(abis: List<String>, preferHost: String? = null): UpdateInfo {
        val primary = abis.firstOrNull()
        val chosen = variants.firstOrNull { it.abi == primary && it.abi != OtaAbi.UNIVERSAL } ?: universal
        fun order(urls: List<String>) =
            if (preferHost == null) urls else urls.sortedBy { if (OtaSources.nameOf(it) == preferHost) 0 else 1 }
        val urls = order(chosen.urls.flatMap(OtaSources::npmTwins).distinct())
        val alternates = urls.drop(1).map { ApkCopy(it, chosen.sha256) } +
            (if (chosen.abi == OtaAbi.UNIVERSAL) emptyList() else universal.let { u -> order(u.urls).map { ApkCopy(it, u.sha256) } })
        return UpdateInfo(
            versionCode = chosen.versionCode,
            versionName = versionName,
            url = urls.first(),
            notes = notes,
            sha256 = chosen.sha256,
            abi = chosen.abi,
            alternates = alternates,
        )
    }

    companion object {
        /** The app every OTA manifest must name (the release applicationId; a debug build's id is the same). */
        const val APP_ID = "com.arkiv.player.light"
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** A PRESENT `rollout`'s value: a JSON number in 0..100 (rounded down), anything else [OtaRollout.INVALID]. */
        internal fun rolloutOf(value: Any?): Int {
            val n = (value as? Number)?.toDouble() ?: return OtaRollout.INVALID
            return if (n.isNaN() || n < 0 || n > 100) OtaRollout.INVALID else n.toInt()
        }

        /** Parses [raw] or returns null when it is not a usable manifest (see the class KDoc for the rules). */
        fun parse(raw: String, requireIdentity: Boolean = false): OtaManifest? = runCatching {
            val json = JSONObject(raw)
            val schema2 = json.optInt("schema", 1) >= 2 || json.has("apks")
            val app = json.optString("app", "")
            if (app.isNotEmpty() && app != APP_ID) return null
            if ((schema2 || requireIdentity) && app != APP_ID) return null
            val sha = json.optString("sha256", "").lowercase()
            if (sha.isNotEmpty() && !SHA256.matches(sha)) return null
            val versionCode = json.getInt("versionCode")
            val url = json.getString("url")
            if (!OtaSources.isAllowedApkUrl(url)) return null
            val base = OtaVersion.baseOf(versionCode)
            val variants = ArrayList<ApkVariant>()
            val apks = json.optJSONArray("apks")
            if (apks != null) for (i in 0 until apks.length()) {
                val o = apks.optJSONObject(i) ?: continue
                val abi = o.optString("abi", "")
                if (abi != OtaAbi.UNIVERSAL && abi !in OtaAbi.SPLITS) continue
                if (variants.any { it.abi == abi }) continue
                val code = o.optInt("versionCode", -1)
                if (code < 0 || OtaVersion.baseOf(code) != base) continue
                val vSha = o.optString("sha256", "").lowercase()
                if (!SHA256.matches(vSha)) continue
                val urlArray = o.optJSONArray("urls") ?: continue
                val urls = (0 until urlArray.length()).mapNotNull { urlArray.optString(it, null) }
                    .filter { OtaSources.isAllowedApkUrl(it) }.distinct()
                if (urls.isEmpty()) continue
                if (abi == OtaAbi.UNIVERSAL && (code != versionCode || (sha.isNotEmpty() && sha != vSha))) return null
                variants += ApkVariant(abi, code, vSha, urls)
            }
            val rollout = if (json.has("rollout")) rolloutOf(json.opt("rollout")) else OtaRollout.ALL
            OtaManifest(versionCode, json.getString("versionName"), url, sha, json.optString("notes", ""), variants, rollout)
        }.getOrNull()
    }
}