package com.arkiv.player.data.plugin

import com.arkiv.player.crash.PrivateText
import com.arkiv.player.crash.SentryScrubber
import kotlinx.coroutines.CancellationException

/** What went wrong, the last part of a plugin failure's fingerprint. [wire] is what GlitchTip shows. */
enum class PluginFailureKind(val wire: String) {
    /** A call ran out of its time. */
    TIMEOUT("timeout"),
    /** The script threw (an untyped error, a typed code Kino doesn't know, a Nuvio scraper's own error). */
    THROWN("thrown"),
    /** The script never loaded: a syntax error, a top-level throw, a load timeout, damaged files. */
    LOAD("load"),
    /** A `kino.fetch` went to a host the plugin never declared and nobody was asked (search, Home, live). */
    HOST("host"),
    /** A `kino.fetch` failed to reach its site (DNS, timeout, connection) and the call died of it. */
    NETWORK("network"),
    /** The answer was unusable: not a list, every item dropped, a Stream Kino can't play. */
    INVALID_OUTPUT("invalid_output"),
    /** The plugin said `kino.error("unavailable")`: its site is down, as the plugin itself sees it. */
    UNAVAILABLE("unavailable"),
    /** The plugin said `kino.error("rate_limited")`. */
    RATE_LIMITED("rate_limited"),
    /** A converted Nuvio scraper's resolve found no playable stream. */
    NO_STREAMS("no_streams"),
    /** Kino's own runtime gave up on the call (restarted, no result, too big an answer). */
    RUNTIME("runtime"),
    /** A Nuvio repo or scraper could not be turned into a Kino plugin. */
    CONVERSION("conversion"),
    /** An install or update could not complete (invalid manifest, download, probe, save). */
    INSTALL("install"),
    /** An offline download of the plugin's video failed for good. */
    DOWNLOAD("download"),
    /** The plugin's video can never be saved as-is (HLS it can't read, a manifest in disguise). */
    DOWNLOAD_REFUSED("download_refused"),
}

/**
 * One failure worth telling the owner about. [raw] is the failure's own text (a script's message,
 * Kino's sentence): it never leaves the device as is, only [PluginTelemetry.cleanReason]'s version of
 * it. [detail] is Kino's own fixed vocabulary (a typed code, an install stage), never plugin text.
 * [facts] overrides the lookup for a plugin that isn't installed (yet): an install, a conversion.
 */
data class PluginFailure(
    val pluginId: String,
    val function: String,
    val kind: PluginFailureKind,
    val raw: String? = null,
    val elapsedMs: Long? = null,
    /** The site involved, hostname only; dropped when local, an IP literal or one of the person's own servers. */
    val host: String? = null,
    val detail: Map<String, String> = emptyMap(),
    val facts: PluginFacts? = null,
    /** Text the person gave the call (a search query, a title, a ref): removed from [raw] like a setting value, never sent. */
    val privateText: List<String> = emptyList(),
)

/**
 * What the owner may know about a plugin: nothing the person typed. [origin] is `nuvio`, `catalog`
 * (a recommended one), `community_or_manual` (any other repo: the record doesn't say which) or
 * `unknown`. [privateHosts] are the person's own servers (settings they typed) and the hosts they
 * approved themselves: never sent. [nuvioRepo] is sent only when it is a well-known public repo
 * ([PluginTelemetry.PUBLIC_NUVIO_REPOS]).
 */
data class PluginFacts(
    val version: String?,
    val apiVersion: Int?,
    val origin: String,
    val nuvioRepo: String? = null,
    val nuvioScraperId: String? = null,
    val privateHosts: Set<String> = emptySet(),
) {
    companion object {
        val UNKNOWN = PluginFacts(version = null, apiVersion = null, origin = "unknown")
    }
}

/** Where a built event goes: `Crash.report` in the app (a no-op in debug builds), a list in tests. */
fun interface PluginFailureSink {
    fun send(event: PluginTelemetry.Event)
}

/**
 * Plugin failures to the owner's error tracker (GlitchTip), grouped so the board stays readable and
 * nothing private leaves the device.
 *
 * Grouping: one issue per (plugin id, function, kind) -- the [Event.fingerprint], and the exception's
 * constant message `plugin <id> <function> <kind>` -- never per message text. The cleaned first line
 * of the failure's text is an extra ([cleanReason]).
 *
 * Volume: each (plugin id, function, kind) at most once per [windowMs] (an hour) per app process, and
 * at most [sessionCap] events per process whatever the key; checked before anything is built, so a
 * failing loop costs a map lookup. An hour, not a day: a plugin broken all evening still shows up as
 * a steady count on its issue, while a search typed letter by letter against a dead plugin is one event.
 *
 * Privacy, enforced here for every event: no URL (only a hostname, and never a local/IP-literal one
 * or the person's own server), no setting value (texts, urls, passwords: [privateValues], read only
 * in the background right before sending and never kept), no query text or title (the call's argument
 * is never read), no headers or cookies (never handed in). Sealed secrets never reach a script's text
 * in plain ([PluginSecrets.redact]), and their markers are code-shaped, which [PluginErrorText.reason]
 * drops. [SentryScrubber] then runs on the text, and again on the whole event in `beforeSend`.
 *
 * The app's instance is [current] (installed by `AppGraph`); until then, and in unit tests, [NONE]
 * reports nothing.
 */
class PluginTelemetry(
    private val facts: (pluginId: String) -> PluginFacts?,
    private val sink: PluginFailureSink,
    /** The person's setting values for a plugin (IO: the Keystore); only ever called inside [dispatch]. */
    private val privateValues: (pluginId: String) -> List<String> = { emptyList() },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val windowMs: Long = WINDOW_MS,
    private val sessionCap: Int = SESSION_CAP,
    /** Where the event is built and sent: the app posts it to a background thread. */
    private val dispatch: (() -> Unit) -> Unit = { it() },
) {
    /** What reaches the sink: already clean, ready for `Crash.report`. */
    data class Event(
        val message: String,
        val fingerprint: List<String>,
        val tags: Map<String, String>,
        val extras: Map<String, String>,
    )

    private val lastSent = HashMap<String, Long>()
    private var sent = 0

    /** Reports [failure] unless its key was reported within the window or the session cap is reached. Never throws. */
    fun report(failure: PluginFailure) = submit(failure) { emptyList() }

    /**
     * A failed plugin call: reported when [failureOf] finds it worth it. Never throws, so the caller's
     * own exception is always the one that propagates; the call's argument ([argJson], up to 64 KB) is
     * parsed only for an admitted event, in the background.
     */
    fun reportCall(pluginId: String, function: String, error: Throwable, elapsedMs: Long? = null, argJson: String? = null) {
        runCatching {
            val failure = failureOf(pluginId, function, error, elapsedMs) ?: return
            submit(failure) { argTexts(argJson) }
        }
    }

    private fun submit(failure: PluginFailure, privateText: () -> List<String>) {
        runCatching {
            if (!admit(failure)) return
            dispatch { runCatching { sink.send(eventOf(failure.copy(privateText = failure.privateText + privateText()))) } }
        }
    }

    /** The plugin's facts, for another report that wants plugin tags (the player's): ids, versions, origin only. */
    fun describe(pluginId: String): Map<String, String> {
        val f = runCatching { facts(pluginId) }.getOrNull() ?: PluginFacts.UNKNOWN
        return factExtras(safeId(pluginId), f)
    }

    @Synchronized
    private fun admit(failure: PluginFailure): Boolean {
        val key = fingerprintOf(failure).joinToString("|")
        val now = clock()
        val last = lastSent[key]
        if (last != null && now - last < windowMs) return false
        if (sent >= sessionCap) return false
        lastSent[key] = now
        sent++
        return true
    }

    internal fun eventOf(failure: PluginFailure): Event {
        val id = safeId(failure.pluginId)
        val f = failure.facts ?: runCatching { facts(failure.pluginId) }.getOrNull() ?: PluginFacts.UNKNOWN
        val values = runCatching { privateValues(failure.pluginId) }.getOrDefault(emptyList())
        val function = safeWord(failure.function)
        val kind = failure.kind.wire
        val extras = LinkedHashMap<String, String>()
        extras += factExtras(id, f)
        extras["function"] = function
        extras["kind"] = kind
        failure.elapsedMs?.takeIf { it >= 0 }?.let { extras["elapsed_ms"] = it.toString() }
        publicHost(failure.host, f, values)?.let { extras["host"] = it }
        cleanReason(failure.raw, values + failure.privateText, f.privateHosts)?.let { extras["reason"] = it }
        failure.detail.forEach { (k, v) -> extras[safeWord(k)] = safeWord(v) }
        return Event(
            message = "plugin $id $function $kind",
            fingerprint = fingerprintOf(failure),
            tags = mapOf("plugin_id" to id, "plugin_function" to function, "plugin_kind" to kind, "plugin_origin" to f.origin),
            extras = extras,
        )
    }

    companion object {
        const val WINDOW_MS = 60 * 60 * 1000L
        const val SESSION_CAP = 30
        const val MAX_REASON_CHARS = 160

        /** Reports nothing: the default until `AppGraph` installs the app's instance. */
        val NONE = PluginTelemetry(facts = { null }, sink = { }, sessionCap = 0)

        @Volatile
        var current: PluginTelemetry = NONE

        fun fingerprintOf(f: PluginFailure): List<String> = listOf("plugin", safeId(f.pluginId), safeWord(f.function), f.kind.wire)

        /**
         * What a failed call is, or null when it is nothing to report: a cancellation, a host the
         * person said no to, a typed error the person already understands (`not_found`,
         * `auth_required`, `geo_blocked`). `unavailable` and `rate_limited` are reported under their
         * own kind: throttled like the rest, they cost one event an hour per plugin and show a site
         * going down. A converted Nuvio scraper's "nothing playable" (its adapter's `not_found`
         * [NuvioPluginConverter.NO_STREAMS] or `unavailable` [NuvioPluginConverter.ONLY_TORRENTS]) is
         * [PluginFailureKind.NO_STREAMS]. A search with no results is not a failure at all.
         */
        fun failureOf(pluginId: String, function: String, error: Throwable, elapsedMs: Long? = null): PluginFailure? {
            if (error is CancellationException) return null
            val pe = error as? PluginException
            val trace = pe?.trace
            fun of(kind: PluginFailureKind, raw: String? = null, host: String? = null, detail: Map<String, String> = emptyMap()) =
                PluginFailure(pluginId, function, kind, raw, elapsedMs, host, detail)
            val refused = trace?.events?.filterIsInstance<PluginCallTrace.Refused>()?.firstOrNull()
            if (refused != null) {
                // The person's "no" is their decision, never a plugin bug.
                if (refused.why != PluginCallTrace.Refusal.NOT_ASKED) return null
                return of(PluginFailureKind.HOST, host = refused.host)
            }
            val failedHost = trace?.events?.filterIsInstance<PluginCallTrace.Failed>()?.firstOrNull()?.host
            return when (error) {
                is PluginTimeoutException ->
                    if (error.atLoad) of(PluginFailureKind.LOAD, detail = mapOf("load" to "timeout"))
                    else of(PluginFailureKind.TIMEOUT, host = trace?.waitingFor)
                is PluginErrorException -> typed(error, function, failedHost, ::of)
                is PluginThrownException -> of(if (error.atLoad) PluginFailureKind.LOAD else PluginFailureKind.THROWN, raw = error.message, host = failedHost)
                is PluginDamagedException -> of(PluginFailureKind.LOAD, detail = mapOf("load" to "damaged"))
                is PluginException -> of(if (error.atLoad) PluginFailureKind.LOAD else PluginFailureKind.RUNTIME, raw = error.message)
                else -> of(PluginFailureKind.RUNTIME, detail = mapOf("error" to error.javaClass.simpleName))
            }
        }

        private fun typed(
            e: PluginErrorException,
            function: String,
            failedHost: String?,
            of: (PluginFailureKind, String?, String?, Map<String, String>) -> PluginFailure,
        ): PluginFailure? {
            val message = e.message.orEmpty()
            val code = mapOf("code" to e.code)
            return when (e.code) {
                PluginErrors.NOT_FOUND ->
                    if (function == "resolve" && message == NuvioPluginConverter.NO_STREAMS) of(PluginFailureKind.NO_STREAMS, null, null, mapOf("streams" to "none")) else null
                PluginErrors.AUTH_REQUIRED, PluginErrors.GEO_BLOCKED -> null
                PluginErrors.UNAVAILABLE -> when {
                    message == NuvioPluginConverter.ONLY_TORRENTS -> of(PluginFailureKind.NO_STREAMS, null, null, mapOf("streams" to "only_torrents"))
                    message.startsWith(NuvioPluginConverter.SCRAPER_ERROR_PREFIX) ->
                        of(PluginFailureKind.THROWN, message.removePrefix(NuvioPluginConverter.SCRAPER_ERROR_PREFIX), failedHost, code)
                    else -> of(PluginFailureKind.UNAVAILABLE, message, null, emptyMap())
                }
                PluginErrors.RATE_LIMITED -> of(PluginFailureKind.RATE_LIMITED, message, null, emptyMap())
                // A kino.fetch failure the script let through.
                "network", "timeout" -> of(PluginFailureKind.NETWORK, null, failedHost, code)
                "host_not_allowed" -> of(PluginFailureKind.HOST, null, null, code)
                else -> of(PluginFailureKind.THROWN, message, failedHost, if (e.code in FETCH_CODES) code else mapOf("code" to "other"))
            }
        }

        /**
         * An unusable `resolve`/`episodes` answer ([PluginContractException]) as the plugin's bug it is,
         * with a fixed-vocabulary `output` detail (never the message: it names hosts). Null for a host
         * the plugin didn't declare or a local address: the person was asked (or will be) and decides.
         */
        fun invalidOutput(pluginId: String, function: String, e: PluginContractException): PluginFailure? {
            val m = e.message.orEmpty()
            val what = when {
                "no declaró" in m || "dirección local" in m -> return null
                "DRM" in m -> "drm"
                "tipo de video" in m -> "bad_mime"
                "debe usar https" in m -> "not_https"
                "dirección inválida" in m -> "bad_url"
                "no devolvió un video" in m -> "no_video"
                "capítulos" in m -> "bad_episodes"
                else -> "other"
            }
            return PluginFailure(pluginId, function, PluginFailureKind.INVALID_OUTPUT, detail = mapOf("output" to what))
        }

        /** A list answer ([PluginOutput.page]/[PluginOutput.rows]) that kept nothing of what it carried ([PluginOutput.droppedEntirely]). */
        fun droppedList(pluginId: String, function: String, json: String, kept: Int): PluginFailure? =
            PluginOutput.droppedEntirely(json, kept)?.let { PluginFailure(pluginId, function, PluginFailureKind.INVALID_OUTPUT, detail = mapOf("output" to it)) }

        /** `kino.fetch`'s own codes: Kino's vocabulary, safe to send. A plugin's own code could be anything. */
        private val FETCH_CODES = setOf("too_large", "invalid_request")

        /**
         * [raw] as it may leave the device: [PluginErrorText.reason]'s first line (no stack, no URL,
         * nothing code-shaped, or null), then the person's own values and servers removed, credential
         * shapes redacted ([SentryScrubber.redactSensitiveText]), and every e-mail, hostname, IP and
         * long token replaced. Null when nothing readable is left.
         */
        fun cleanReason(raw: String?, privateValues: List<String> = emptyList(), privateHosts: Set<String> = emptySet()): String? {
            var text = PluginErrorText.reason(raw) ?: return null
            val whole = (privateValues + privateHosts).map { it.trim() }.filter { it.length >= MIN_PRIVATE_CHARS }
            whole.sortedByDescending { it.length }.forEach { text = text.replace(it, "[private]", ignoreCase = true) }
            // A query's words one by one too: "no hay resultados para casa" must not carry "casa de papel" in pieces.
            whole.flatMap { WORDS.findAll(it).map { m -> m.value }.toList() }.filter { it.length >= MIN_PRIVATE_WORD_CHARS }.distinct()
                .forEach { w -> text = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(w) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).replace(text, "[private]") }
            text = SentryScrubber.redactSensitiveText(text) ?: return null
            text = PrivateText.scrubAddresses(text)
            return text.trim().take(MAX_REASON_CHARS).ifEmpty { null }
        }

        /**
         * [host] when it may be named: a public DNS name that isn't one of the person's own servers
         * ([PluginFacts.privateHosts]: typed in a URL setting, or approved by the person at playback)
         * and that no setting value mentions ([privateValues]: a server typed into a TEXT setting).
         */
        fun publicHost(host: String?, facts: PluginFacts, privateValues: List<String> = emptyList()): String? {
            val h = host?.trim()?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() && it.length <= 253 } ?: return null
            if (HostRules.isLocalAddress(h)) return null
            if (facts.privateHosts.any { it.equals(h, ignoreCase = true) }) return null
            if (privateValues.any { it.contains(h, ignoreCase = true) }) return null
            if (!HOSTNAME.matches(h)) return null
            return h
        }

        /**
         * The well-known public Nuvio provider repos: the only `owner/repo` a report may name for a
         * Nuvio plugin. Any other repo -- typed by the person, often their own account -- is left out
         * (the origin still says `nuvio`, the scraper id stays). Lowercase.
         */
        val PUBLIC_NUVIO_REPOS = setOf("yoruix/nuvio-providers", "tapframe/nuvio-providers")

        /** [repo] (`owner/repo`, maybe with `@ref`) when it is one of [PUBLIC_NUVIO_REPOS], else null. */
        fun publicNuvioRepo(repo: String?): String? =
            repo?.substringBefore('@')?.trim()?.lowercase()?.takeIf { it in PUBLIC_NUVIO_REPOS }

        private fun factExtras(id: String, f: PluginFacts): Map<String, String> = buildMap {
            put("plugin_id", id)
            f.version?.let { put("plugin_version", safeWord(it)) }
            f.apiVersion?.let { put("plugin_api", it.toString()) }
            put("plugin_origin", f.origin)
            publicNuvioRepo(f.nuvioRepo)?.let { put("nuvio_repo", safeWord(it)) }
            f.nuvioScraperId?.let { put("nuvio_scraper", safeWord(it)) }
        }

        /** A plugin id (or `owner/repo` for one that has none yet), cut to a safe alphabet. */
        internal fun safeId(id: String): String = id.take(80).replace(UNSAFE_ID, "_").ifEmpty { "unknown" }

        private fun safeWord(s: String): String = s.take(80).replace(UNSAFE_ID, "_")

        /** Every string inside a call's JSON argument (the query, its titles, a ref): what the person gave it. */
        internal fun argTexts(argJson: String?): List<String> {
            val value = argJson?.takeIf { it.length <= MAX_ARG_CHARS }?.let { runCatching { org.json.JSONTokener(it).nextValue() }.getOrNull() } ?: return emptyList()
            val out = ArrayList<String>()
            fun walk(v: Any?, depth: Int) {
                if (depth > 4 || out.size >= 64) return
                when (v) {
                    is String -> out += v
                    is org.json.JSONObject -> v.keys().forEach { walk(v.opt(it), depth + 1) }
                    is org.json.JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), depth + 1)
                }
            }
            walk(value, 0)
            return out
        }

        private const val MAX_ARG_CHARS = 64 * 1024
        private const val MIN_PRIVATE_CHARS = 3
        private const val MIN_PRIVATE_WORD_CHARS = 4
        private val WORDS = Regex("[\\p{L}\\p{N}]+")
        private val UNSAFE_ID = Regex("[^A-Za-z0-9._/@:-]")
        private val HOSTNAME = Regex("^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,62}$")
    }
}

/**
 * Every plugin call through [delegate], with its failures handed to [PluginTelemetry.current]
 * (which decides what is worth reporting): search, Home, browse, resolve, episodes and the live
 * lists all go through the app's one caller, so this is the one place they are seen.
 */
class ReportingPluginCaller(private val delegate: PluginCaller) : PluginCaller {
    override suspend fun call(pluginId: String, function: String, argJson: String, timeoutMs: Long): String {
        val t0 = System.nanoTime()
        try {
            return delegate.call(pluginId, function, argJson, timeoutMs)
        } catch (e: Exception) {
            PluginTelemetry.current.reportCall(pluginId, function, e, (System.nanoTime() - t0) / 1_000_000, argJson)
            throw e
        }
    }
}
