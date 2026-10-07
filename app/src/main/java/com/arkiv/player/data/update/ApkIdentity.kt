package com.arkiv.player.data.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * One copy's verdict: [failure] null = installable; [facts] what was read, for the reports (never a URL); [notable] the
 * `identity` event's reason when this check refused the copy or was skipped (`refused_<failure>`, `skipped_remote`);
 * [observed] the non-blocking `identity_observed` event's reason when the signers read back empty or differing from the
 * installed app's (`empty`, `differ`). Both null for a clean pass.
 */
data class ApkCheck(
    val failure: String?,
    val facts: Map<String, String> = emptyMap(),
    val notable: String? = null,
    val observed: String? = null,
)

/**
 * The last gate before the installer: a downloaded APK whose sha256 matched its manifest is still only as
 * trustworthy as the account that wrote that manifest. So the APK itself must be THIS app ([Facts.packageName]), of
 * the release the manifest announced (same [OtaVersion.baseOf]) and newer than what is installed. Otherwise
 * [ApkDownloader] drops the copy and tries the next one, reporting only the reason (`apk_package`, `apk_version`).
 * Only those two refuse: a read that failed (the archive's `apk_unreadable`, our own `apk_installed_unreadable`) proves
 * nothing about the bytes, so the installer decides and the failure goes to telemetry (`identity/unverified_<why>`). These are the cases Android's installer would NOT catch: another package (it would offer to install
 * a second app under "Actualizar Kino") and a mislabelled release (an endless "update" loop on the same code).
 *
 * The signers are NOT compared. 0.9.51 compared them and refused every copy of a correctly signed 0.9.51 APK as
 * `apk_signer` (ERRORES-EFS: 8 people on Android 9/10/11/13, downloading ~14 MB again and again). Measured on a Fire TV
 * (Android 11, 0.9.50 installed): the app's own code, run from outside the app, read IDENTICAL signers for the
 * installed app and that APK (cert 35c2ff7d...), so the mismatch only happens inside the app's process at download
 * time and nobody could reproduce it. Android's installer refuses an update signed by another key by itself
 * (INSTALL_FAILED_UPDATE_INCOMPATIBLE), so the comparison added only risk. What the app sees of the signers is still
 * read three ways ([readArchive]) and sent as `identity_observed` when they read back empty or differ, to find the
 * real cause.
 *
 * The owner's remote switch (`"otaIdentityCheck": false` in `access.json`, see [verdict]) skips this check only; the
 * sha256 check in [ApkDownloader] always runs.
 */
object ApkIdentity {
    /**
     * What the identity check needs from a package: [signers] are SHA-256 hex digests of the signing certificates,
     * empty when none could be read (unknown, not "unsigned").
     */
    data class Facts(val packageName: String, val versionCode: Long, val signers: Set<String>)

    /**
     * Everything read from the downloaded file, for the verdict and the report: [facts] null when it could not be
     * parsed; [via] which read gave the signers (`signing_certs`, `history`, `signatures`, `none`, `error`);
     * [signingInfo] `null`/`present`/`n/a` (API < 28); [contentsSigners] `null`/`empty`/a count/`n/a`; [error] the class
     * of what a read threw.
     */
    data class ArchiveRead(
        val facts: Facts?,
        val via: String,
        val signingInfo: String = "n/a",
        val contentsSigners: String = "n/a",
        val multipleSigners: Boolean? = null,
        val pastCertificates: Boolean? = null,
        val error: String? = null,
        val fileSize: Long = -1,
        val readable: Boolean = false,
        /** The PackageManager flags the reads used, e.g. `signing_certs+signatures`. */
        val flags: String = "",
        /** The file's name only (never a path with the app's directories). */
        val fileName: String = "",
    )

    /** Null when [apk] may be installed over [installed] as release [expectedBase]; else the failure reason. */
    fun decide(apk: Facts?, installed: Facts, expectedBase: Int): String? {
        if (apk == null) return "apk_unreadable"
        if (apk.packageName != installed.packageName) return "apk_package"
        // No signer comparison: the installer refuses another key itself (see the class KDoc).
        if (apk.versionCode !in 1..Int.MAX_VALUE.toLong()) return "apk_version"
        val code = apk.versionCode.toInt()
        val installedCode = installed.versionCode.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        if (OtaVersion.baseOf(code) != expectedBase || !OtaVersion.isNewer(code, installedCode)) return "apk_version"
        return null
    }

    /**
     * The verdict on [read] against [installed] (null = this app's own package could not be read) and its report:
     * `sdk`, `flags`, `via`, `archive_signers`/`installed_signers` (0/1/n), `signers_match` (yes/no/unknown),
     * `archive_sig` / `installed_sig` (first 4 bytes of each digest, hex), `signing_info`, `contents_signers`,
     * `multi_signers`, `past_certs`, `read_error`, `file` (name only), `size`, `readable`, `installed_code`, `apk_code`.
     * [enabled] false (the owner's switch) passes without deciding.
     */
    fun verdict(read: ArchiveRead, installed: Facts?, expectedBase: Int, sdk: Int, enabled: Boolean): ApkCheck {
        val archive = read.facts?.signers.orEmpty()
        val mine = installed?.signers.orEmpty()
        val facts = linkedMapOf(
            "sdk" to sdk.toString(),
            "via" to read.via,
            "archive_signers" to count(archive),
            "installed_signers" to if (installed == null) "unreadable" else count(mine),
            "signers_match" to when {
                archive.isEmpty() || installed == null -> "unknown"
                archive == mine -> "yes"
                else -> "no"
            },
            "archive_sig" to prefixes(archive),
            "installed_sig" to prefixes(mine),
            "signing_info" to read.signingInfo,
            "contents_signers" to read.contentsSigners,
            "multi_signers" to (read.multipleSigners?.toString() ?: "n/a"),
            "past_certs" to (read.pastCertificates?.toString() ?: "n/a"),
            "read_error" to (read.error ?: "none"),
            "flags" to read.flags,
            "file" to read.fileName,
            "size" to read.fileSize.toString(),
            "readable" to read.readable.toString(),
            "installed_code" to (installed?.versionCode?.toString() ?: "unreadable"),
            "apk_code" to (read.facts?.versionCode?.toString() ?: "unreadable"),
        )
        val observed = when {
            archive.isEmpty() || mine.isEmpty() -> "empty"
            archive != mine -> "differ"
            else -> null
        }
        if (!enabled) return ApkCheck(null, facts + ("identity" to "skipped_remote"), notable = "skipped_remote", observed = observed)
        val found = if (installed == null) "apk_installed_unreadable" else decide(read.facts, installed, expectedBase)
        // Only what the bytes PROVE blocks: another package or a wrong release. A read that failed (ours or the
        // archive's) proves nothing: the installer decides, and it goes to telemetry as `unverified_<why>`.
        if (found != null && found !in BLOCKING) {
            return ApkCheck(null, facts + ("identity" to found), notable = "unverified_$found", observed = observed)
        }
        return ApkCheck(found, facts + ("identity" to (found ?: "ok")), found?.let { "refused_$it" }, observed)
    }

    /** The only verdicts that refuse a copy (see [verdict]). */
    val BLOCKING = setOf("apk_package", "apk_version")

    private fun count(signers: Set<String>) = if (signers.size > 1) "n" else signers.size.toString()

    private fun prefixes(signers: Set<String>) = signers.map { it.take(8) }.sorted().joinToString(",").ifEmpty { "none" }

    /**
     * The check [ApkDownloader] runs on each downloaded copy: (file, expected release base) -> [verdict]. [enabled] is
     * read on every check (the owner's remote switch, cached).
     */
    fun checker(context: Context, enabled: () -> Boolean): (File, Int) -> ApkCheck {
        val app = context.applicationContext
        return { file, expectedBase ->
            val pm = app.packageManager
            // Our own package: with its signers, else (DeadSystemException, a vendor's PackageManager) just its version.
            val installed = runCatching { factsOf(pm.getPackageInfo(app.packageName, signerFlags())) }.getOrNull()
                ?: runCatching { factsOf(pm.getPackageInfo(app.packageName, 0)) }.getOrNull()
            val read = readArchive(Build.VERSION.SDK_INT, file) { flags -> pm.getPackageArchiveInfo(file.absolutePath, flags) }
            verdict(read, installed, expectedBase, Build.VERSION.SDK_INT, runCatching(enabled).getOrDefault(true))
        }
    }

    /** Installed package [pkg]'s facts: throws NameNotFoundException when absent, IllegalStateException when unreadable. */
    fun installedFacts(context: Context, pkg: String): Facts =
        factsOf(context.packageManager.getPackageInfo(pkg, signerFlags())) ?: error("unreadable package $pkg")

    @Suppress("DEPRECATION")
    internal fun signerFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    internal fun factsOf(info: PackageInfo): Facts? {
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return Facts(info.packageName ?: return null, PackageInfoCompat.getLongVersionCode(info), digests(sigs))
    }

    /**
     * Reads the archive's package facts and signers through [archiveInfo] (`getPackageArchiveInfo` with those flags): on
     * API 28+ `signingInfo.apkContentsSigners`, then `signingInfo.signingCertificateHistory`; then the legacy
     * `signatures`; and last with no flags at all, for the package and version only. Each read is tried on its own: one
     * that throws is noted ([ArchiveRead.error], the first class seen) and the next one still runs. Never throws.
     */
    // The API 28 reads only run under `sdk >= P`, and the one caller passes Build.VERSION.SDK_INT; lint can't see
    // through the parameter, and lintVitalRelease fails the release build on it.
    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    internal fun readArchive(sdk: Int, file: File, archiveInfo: (flags: Int) -> PackageInfo?): ArchiveRead {
        val size = runCatching { file.length() }.getOrDefault(-1L)
        val readable = runCatching { file.isFile && file.canRead() }.getOrDefault(false)
        var signingInfo = "n/a"
        var contents = "n/a"
        var multiple: Boolean? = null
        var past: Boolean? = null
        var error: String? = null
        var base: PackageInfo? = null
        val flags = if (sdk >= Build.VERSION_CODES.P) "signing_certs+signatures+plain" else "signatures+plain"
        fun result(facts: Facts?, via: String) =
            ArchiveRead(facts, via, signingInfo, contents, multiple, past, error, size, readable, flags, file.name)
        fun read(flag: Int): PackageInfo? = try {
            archiveInfo(flag)
        } catch (e: Exception) {
            if (error == null) error = e.javaClass.simpleName
            null
        }
        if (sdk >= Build.VERSION_CODES.P) {
            val info = read(PackageManager.GET_SIGNING_CERTIFICATES)
            base = info
            val found = runCatching {
                val signing = info?.signingInfo
                signingInfo = if (signing == null) "null" else "present"
                val listed = signing?.apkContentsSigners
                contents = when {
                    signing == null -> "null"
                    listed == null -> "null"
                    listed.isEmpty() -> "empty"
                    else -> listed.size.toString()
                }
                multiple = signing?.hasMultipleSigners()
                past = signing?.hasPastSigningCertificates()
                factsWith(info, digests(listed))?.let { it to "signing_certs" }
                    ?: factsWith(info, digests(signing?.signingCertificateHistory))?.let { it to "history" }
            }.onFailure { if (error == null) error = it.javaClass.simpleName }.getOrNull()
            found?.let { return result(it.first, it.second) }
        }
        val legacy = read(PackageManager.GET_SIGNATURES)
        runCatching { factsWith(legacy, digests(legacy?.signatures)) }.getOrNull()?.let { return result(it, "signatures") }
        val info = base ?: legacy ?: read(0) ?: return result(null, if (error != null) "error" else "none")
        val plain = runCatching { Facts(info.packageName ?: return result(null, "none"), PackageInfoCompat.getLongVersionCode(info), emptySet()) }
            .getOrNull() ?: return result(null, "error")
        return result(plain, "none")
    }

    private fun factsWith(info: PackageInfo?, signers: Set<String>): Facts? {
        if (info == null || signers.isEmpty()) return null
        return Facts(info.packageName ?: return null, PackageInfoCompat.getLongVersionCode(info), signers)
    }

    private fun digests(sigs: Array<Signature>?): Set<String> = sigs.orEmpty().map { sha256(it.toByteArray()) }.toSet()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}