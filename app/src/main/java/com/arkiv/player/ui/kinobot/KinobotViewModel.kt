package com.arkiv.player.ui.kinobot

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.ai.ChatMessage
import com.arkiv.player.data.ai.KinobotChunk
import com.arkiv.player.data.ai.KinobotClient
import com.arkiv.player.data.db.KinobotDao
import com.arkiv.player.data.db.KinobotMessageEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray

/** A chat turn as the screen renders it. */
internal data class KinobotMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val suggestions: List<String>,
)

/**
 * Drives the Kinobot chat: reads the persisted history, streams a reply and stores the finished
 * turn. Only completed turns are persisted; a reply abandoned mid-stream (the user leaves) is not
 * stored. A [Mutex] drops a second send while one is in flight.
 */
internal class KinobotViewModel(
    private val dao: KinobotDao,
    private val client: KinobotClient,
) : ViewModel() {

    val messages: StateFlow<List<KinobotMessage>> = dao.flowAll()
        .map { rows -> rows.map { it.toMessage() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The assistant reply being generated right now (grows token by token), or null. */
    private val _streaming = MutableStateFlow<String?>(null)
    val streaming: StateFlow<String?> = _streaming

    /** Sent, waiting for the first token. */
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking

    private val turnLock = Mutex()

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        if (!turnLock.tryLock()) return
        viewModelScope.launch {
            try {
                dao.insert(KinobotMessageEntity(role = "user", content = prompt, createdAt = System.currentTimeMillis()))
                _thinking.value = true
                val history = dao.flowAll().first().takeLast(MAX_HISTORY).map {
                    ChatMessage(if (it.role == "user") "user" else "assistant", it.content)
                }
                val sb = StringBuilder()
                var handled = false
                client.reply(history).collect { chunk ->
                    when (chunk) {
                        is KinobotChunk.Delta -> {
                            _thinking.value = false
                            sb.append(chunk.text)
                            _streaming.value = sb.toString()
                        }
                        is KinobotChunk.Done -> { persistAssistant(sb.toString(), chunk.suggestions); handled = true }
                        is KinobotChunk.Refusal -> { persistAssistant(chunk.text, emptyList()); handled = true }
                        KinobotChunk.Failed -> { persistAssistant(FALLBACK, emptyList()); handled = true }
                    }
                }
                if (!handled) persistAssistant(FALLBACK, emptyList()) // stream ended without a terminal chunk
            } finally {
                _thinking.value = false
                _streaming.value = null
                turnLock.unlock()
            }
        }
    }

    fun clear() {
        viewModelScope.launch { dao.clear() }
    }

    private suspend fun persistAssistant(text: String, suggestions: List<String>) {
        val body = text.trim().ifEmpty { FALLBACK }
        dao.insert(
            KinobotMessageEntity(
                role = "assistant",
                content = body,
                suggestionsJson = JSONArray(suggestions).toString(),
                createdAt = System.currentTimeMillis(),
            ),
        )
        _streaming.value = null
    }

    private fun KinobotMessageEntity.toMessage() = KinobotMessage(
        id = id,
        fromUser = role == "user",
        text = content,
        suggestions = decodeSuggestions(suggestionsJson),
    )

    companion object {
        private const val MAX_HISTORY = 12
        const val FALLBACK = "Uy, no pude procesar eso. ¿Lo intentas de nuevo?"

        private fun decodeSuggestions(json: String): List<String> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }
}
