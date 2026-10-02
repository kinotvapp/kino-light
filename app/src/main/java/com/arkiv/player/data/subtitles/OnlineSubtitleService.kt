package com.arkiv.player.data.subtitles

import android.util.Log
import com.arkiv.player.cast.CastSubtitleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One provider's part of a search: its results, or why it failed. */
data class ProviderResults(val provider: SubtitleProviderId, val results: List<OnlineSubtitle>, val failure: SubtitleFailure? = null)

/** What "Buscar subtítulos en línea" shows. */
sealed interface OnlineSearchOutcome {
    /** No enabled provider has a key (neither Kino's nor the person's). */
    data object NoKey : OnlineSearchOutcome

    /** The title could not be named: nothing to search by. */
    data object Unidentified : OnlineSearchOutcome

    /** Each usable provider's answer, in the person's order. */
    data class Found(val groups: List<ProviderResults>) : OnlineSearchOutcome
}

/**
 * The stream playing now, as far as a subtitle search cares: its [fileName] (from the URL, never
 * the URL) and, only when it is a stable plain file worth a hash, a [reader] for it.
 */
class PlayingFile(val fileName: String?, val reader: MovieHash.RangeReader?)

/** A downloaded subtitle, ready to be added as a track: a UTF-8 SRT on disk. */
data class DownloadedSubtitle(val lang: String, val label: String, val path: String)

/**
 * Online subtitle search for the title on screen, end to end and in the app (no server): finds the
 * title's ids ([SubtitleIdResolver]), asks every enabled provider with a key ([SubtitleKeys]) at once,
 * and downloads the one picked -- normalized to UTF-8 SRT ([CastSubtitleText], so a Windows-1252
 * Spanish file reads right on the phone and on a TV), kept in [cache] and remembered for the title.
 */
class OnlineSubtitleService(
    private val keys: SubtitleKeys,
    private val providers: List<SubtitleProvider>,
    private val resolver: SubtitleIdResolver,
    /** What the library knows of an episode id, or null when it is not a saved title. */
    private val subjectFor: suspend (String) -> SubtitleSubject?,
    /** The ISO codes to ask for, from the person's subtitle languages. */
    private val languages: () -> List<String>,
    private val cache: OnlineSubtitleCache,
    private val prefs: SubtitlePrefs?,
) {
    /** Is there any provider to search with? Off the main thread. */
    fun available(): Boolean = runCatching { keys.usable().isNotEmpty() }.getOrDefault(false)

    private val hashes = object : LinkedHashMap<String, String?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 32
    }
    private val hashLock = Mutex()

    /**
     * The playing file's hash, at most once per title and file (failures included, so reopening the
     * menu never repeats the reads); null when [file] has no reader, or the hash fails or times out.
     */
    suspend fun hashFor(episodeId: String, file: PlayingFile?): String? {
        val reader = file?.reader ?: return null
        val key = "$episodeId|${file.fileName.orEmpty()}"
        return hashLock.withLock {
            synchronized(hashes) { if (hashes.containsKey(key)) return@withLock hashes[key] }
            val hash = withContext(Dispatchers.IO) { MovieHash.compute(reader) }
            synchronized(hashes) { hashes[key] = hash }
            hash
        }
    }

    suspend fun search(episodeId: String, file: PlayingFile? = null): OnlineSearchOutcome = withContext(Dispatchers.IO) {
        val usable = keys.usable()
        if (usable.isEmpty()) return@withContext OnlineSearchOutcome.NoKey
        val base = queryFor(episodeId) ?: return@withContext OnlineSearchOutcome.Unidentified
        val hash = hashFor(episodeId, file)
        val query = base.copy(movieHash = hash)
        val hint = FileHint(hash, file?.fileName, query.title, query.year)
        val langs = query.languages
        val groups = coroutineScope {
            usable.mapNotNull { (id, key) -> providerOf(id)?.let { it to key } }.map { (provider, key) ->
                async {
                    when (val r = provider.search(query, key.auth)) {
                        is SubtitleResult.Ok -> ProviderResults(provider.id, OnlineSubtitleRules.sort(r.value, langs, file = hint))
                        is SubtitleResult.Failed -> {
                            Log.w(TAG, "${provider.id} search failed: ${r.failure} (key ${key.source} ${OnlineSubtitleRules.mask(key.auth.apiKey)})")
                            ProviderResults(provider.id, emptyList(), r.failure)
                        }
                    }
                }
            }.awaitAll()
        }
        Log.i(TAG, "search: ${groups.joinToString { "${it.provider}=${it.results.size}${it.failure?.let { f -> "/$f" } ?: ""}" }} " +
            "(imdb=${query.imdbId != null} tmdb=${query.tmdbId != null} episode=${query.isEpisode} hash=${hash != null} " +
            "hashMatches=${groups.sumOf { g -> g.results.count { it.hashMatch } }})")
        OnlineSearchOutcome.Found(OnlineSubtitleRules.orderGroups(groups))
    }

    /** Downloads [subtitle] for [episodeId]: the subtitle to add, or why not. */
    suspend fun download(episodeId: String, subtitle: OnlineSubtitle): SubtitleResult<DownloadedSubtitle> = withContext(Dispatchers.IO) {
        val provider = providerOf(subtitle.provider) ?: return@withContext SubtitleResult.Failed(SubtitleFailure.UNAVAILABLE)
        val key = keys.effective(subtitle.provider) ?: return@withContext SubtitleResult.Failed(SubtitleFailure.BAD_KEY)
        val query = queryFor(episodeId) ?: SubtitleQuery(isEpisode = false)
        val raw = when (val r = provider.download(subtitle, key.auth, query)) {
            is SubtitleResult.Failed -> {
                Log.w(TAG, "${subtitle.provider} download failed: ${r.failure}")
                return@withContext r
            }
            is SubtitleResult.Ok -> r.value
        }
        val srt = normalize(raw) ?: return@withContext SubtitleResult.Failed(SubtitleFailure.NO_FILE)
        val file = cache.store("$episodeId|${subtitle.provider}|${subtitle.ref}", srt)
            ?: return@withContext SubtitleResult.Failed(SubtitleFailure.NO_FILE)
        val saved = SavedOnlineSubtitle(subtitle.language, OnlineSubtitleRules.trackLabel(subtitle), file.absolutePath)
        runCatching { prefs?.rememberOnlineSubtitle(episodeId, saved) }
        SubtitleResult.Ok(DownloadedSubtitle(saved.lang, saved.label, saved.path))
    }

    /** The online subtitles [episodeId] got before whose files are still here. */
    fun rememberedFor(episodeId: String): List<DownloadedSubtitle> = runCatching {
        prefs?.onlineSubtitles(episodeId).orEmpty().mapNotNull { s ->
            cache.existing(s.path)?.let { DownloadedSubtitle(s.lang, s.label, it.absolutePath) }
        }
    }.getOrDefault(emptyList())

    private suspend fun queryFor(episodeId: String): SubtitleQuery? {
        val subject = try {
            subjectFor(episodeId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        return resolver.resolve(subject, languages()).takeIf { !it.isEmpty }
    }

    private fun providerOf(id: SubtitleProviderId) = providers.firstOrNull { it.id == id }

    companion object {
        private const val TAG = "OnlineSubs"

        /** Any SRT/WebVTT in any common encoding → UTF-8 SRT bytes, or null when it holds no cue. */
        fun normalize(raw: ByteArray): ByteArray? {
            if (raw.isEmpty() || raw.size > CastSubtitleText.MAX_BYTES) return null
            val cues = runCatching { CastSubtitleText.parse(CastSubtitleText.decode(raw)) }.getOrNull()
            if (cues.isNullOrEmpty()) return null
            return CastSubtitleText.toSrt(cues).toByteArray(Charsets.UTF_8)
        }
    }
}
