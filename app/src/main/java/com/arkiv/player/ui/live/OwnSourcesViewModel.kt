package com.arkiv.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.live.OwnField
import com.arkiv.player.data.live.OwnKind
import com.arkiv.player.data.live.OwnLiveStore
import com.arkiv.player.data.live.OwnPastedCheck
import com.arkiv.player.data.live.OwnPastedList
import com.arkiv.player.data.live.OwnProbe
import com.arkiv.player.data.live.OwnSaveResult
import com.arkiv.player.data.live.OwnSourceForm
import com.arkiv.player.data.live.OwnSourceValidator
import com.arkiv.player.data.live.OwnUrlCheck
import com.arkiv.player.data.live.toForm
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OwnFormUi(
    val editingId: String? = null,
    val form: OwnSourceForm = OwnSourceForm(OwnKind.CHANNEL, "", ""),
    val errors: Map<OwnField, String> = emptyMap(),
    val cleartext: Boolean = false,
    val busy: Boolean = false,
    val probe: OwnProbe? = null,
    val notice: String? = null,
    val open: Boolean = false,
    /** A list with no address: what is shown in place of the address field ("Lista pegada", the file's name...). */
    val pasted: String? = null,
)

/** State of the add/edit dialog and the manager, shared by the phone and the TV. [onSaved] = reload the En vivo listing. */
class OwnSourcesViewModel(
    private val store: OwnLiveStore,
    private val probe: suspend (OwnSourceForm) -> OwnProbe,
    /** Where a pasted list or an opened file (up to 2 MB) is checked: off the main thread. */
    private val work: CoroutineContext = Dispatchers.Default,
    private val onSaved: () -> Unit = {},
) : ViewModel() {
    val sources: StateFlow<List<OwnLiveSourceEntity>> =
        store.sources.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(OwnFormUi())
    val ui: StateFlow<OwnFormUi> = _ui.asStateFlow()

    fun startNew(kind: OwnKind) {
        _ui.value = OwnFormUi(form = OwnSourceForm(kind, "", ""), open = true)
    }

    fun startEdit(source: OwnLiveSourceEntity) {
        val form = source.toForm()
        _ui.value = OwnFormUi(
            editingId = source.id, form = form, cleartext = cleartextOf(form.url), open = true,
            pasted = if (OwnPastedList.isPasted(form.url)) OwnSourcesCopy.PASTED_SAVED else null,
        )
    }

    /** "Pegar lista": [raw] is the clipboard's text (null = nothing there). */
    fun usePasted(raw: String?) = useList(OwnSourcesCopy.PASTED_LABEL, nameHint = "") {
        if (raw.isNullOrBlank()) OwnPastedCheck.Refused(OwnPastedList.EMPTY) else OwnPastedList.check(raw)
    }

    /** "Abrir archivo": [bytes] null = it could not be read; [truncated] = it had more than the cap. */
    fun useFile(name: String?, bytes: ByteArray?, truncated: Boolean) =
        useList(name?.let(OwnSourcesCopy::fileLabel) ?: OwnSourcesCopy.PASTED_LABEL, nameHint = OwnPastedList.nameFromFile(name)) {
            if (bytes == null) OwnPastedCheck.Refused(OwnPastedList.UNREADABLE_FILE) else OwnPastedList.checkFile(name, bytes, truncated)
        }

    /** Back to typing an address. */
    fun clearPasted() {
        val s = _ui.value
        val url = if (OwnPastedList.isPasted(s.form.url)) "" else s.form.url
        _ui.value = s.copy(form = s.form.copy(pastedText = null, url = url), pasted = null, probe = null, errors = s.errors - OwnField.URL)
    }

    private fun useList(label: String, nameHint: String, check: () -> OwnPastedCheck) {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, notice = null)
        viewModelScope.launch {
            val r = withContext(work) { check() }
            val s = _ui.value
            _ui.value = when (r) {
                is OwnPastedCheck.Refused -> s.copy(busy = false, errors = s.errors + (OwnField.URL to r.message))
                is OwnPastedCheck.Ok -> s.copy(
                    busy = false,
                    form = s.form.copy(kind = OwnKind.PLAYLIST, pastedText = r.text, url = "", name = s.form.name.ifBlank { nameHint }),
                    pasted = label,
                    probe = OwnProbe.Ok(r.summary),
                    cleartext = false,
                    errors = s.errors - OwnField.URL - (if (s.form.name.isBlank() && nameHint.isNotEmpty()) setOf(OwnField.NAME) else emptySet()),
                )
            }
        }
    }

    private fun isPastedForm(f: OwnSourceForm) = f.pastedText != null || OwnPastedList.isPasted(f.url)

    fun change(next: OwnSourceForm) {
        val old = _ui.value
        // A channel never holds a pasted list: switching the kind goes back to an address.
        val form = if (next.kind == OwnKind.CHANNEL && isPastedForm(next)) {
            next.copy(pastedText = null, url = if (OwnPastedList.isPasted(next.url)) "" else next.url)
        } else next
        val leftPasted = form !== next
        _ui.value = old.copy(
            form = form,
            pasted = if (leftPasted) null else old.pasted,
            cleartext = cleartextOf(form.url),
            notice = null,
            // What was checked no longer describes what is typed; and a field's error goes away once it is edited.
            probe = if (!leftPasted && form.url == old.form.url && form.kind == old.form.kind) old.probe else null,
            errors = old.errors.filterKeys { field -> !changed(old.form, form, field) },
        )
    }

    fun probeNow() {
        val form = _ui.value.form
        // A pasted list was already checked when it was pasted; it has no address to ask.
        if (isPastedForm(form)) return
        val check = OwnSourceValidator.checkUrl(form.url)
        if (check is OwnUrlCheck.Refused) {
            _ui.value = _ui.value.copy(errors = _ui.value.errors + (OwnField.URL to check.message))
            return
        }
        _ui.value = _ui.value.copy(busy = true, probe = null)
        viewModelScope.launch {
            val r = probe(form)
            // The address may have been edited while it was being checked: that answer is about another one.
            val stale = _ui.value.form.url != form.url
            _ui.value = _ui.value.copy(busy = false, probe = if (stale) null else r)
        }
    }

    fun save() {
        val state = _ui.value
        if (state.busy) return
        _ui.value = state.copy(busy = true)
        viewModelScope.launch {
            val result = try {
                store.save(state.editingId, state.form)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (result) {
                OwnSaveResult.Saved -> {
                    _ui.value = OwnFormUi()
                    onSaved()
                }
                is OwnSaveResult.Invalid -> _ui.value = _ui.value.copy(busy = false, errors = result.errors)
                OwnSaveResult.TooMany -> _ui.value = _ui.value.copy(busy = false, notice = OwnSourcesCopy.TOO_MANY)
                null -> _ui.value = _ui.value.copy(busy = false, notice = OwnSourcesCopy.SAVE_FAILED)
            }
        }
    }

    fun dismiss() {
        _ui.value = OwnFormUi()
    }

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            onSaved()
        }
    }

    private fun cleartextOf(url: String) = (OwnSourceValidator.checkUrl(url) as? OwnUrlCheck.Ok)?.cleartext == true

    private fun changed(a: OwnSourceForm, b: OwnSourceForm, field: OwnField): Boolean = when (field) {
        OwnField.NAME -> a.name != b.name
        OwnField.URL -> a.url != b.url
        OwnField.LOGO -> a.logo != b.logo
        OwnField.EPG -> a.epgUrl != b.epgUrl
        OwnField.USER_AGENT -> a.userAgent != b.userAgent
        OwnField.REFERER -> a.referer != b.referer
    }
}
