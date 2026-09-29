package com.arkiv.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.live.OwnField
import com.arkiv.player.data.live.OwnKind
import com.arkiv.player.data.live.OwnLiveStore
import com.arkiv.player.data.live.OwnProbe
import com.arkiv.player.data.live.OwnSaveResult
import com.arkiv.player.data.live.OwnSourceForm
import com.arkiv.player.data.live.OwnSourceValidator
import com.arkiv.player.data.live.OwnUrlCheck
import com.arkiv.player.data.live.toForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OwnFormUi(
    val editingId: String? = null,
    val form: OwnSourceForm = OwnSourceForm(OwnKind.CHANNEL, "", ""),
    val errors: Map<OwnField, String> = emptyMap(),
    val cleartext: Boolean = false,
    val busy: Boolean = false,
    val probe: OwnProbe? = null,
    val notice: String? = null,
    val open: Boolean = false,
)

/** State of the add/edit dialog and the manager, shared by the phone and the TV. [onSaved] = reload the En vivo listing. */
class OwnSourcesViewModel(
    private val store: OwnLiveStore,
    private val probe: suspend (OwnSourceForm) -> OwnProbe,
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
        _ui.value = OwnFormUi(editingId = source.id, form = form, cleartext = cleartextOf(form.url), open = true)
    }

    fun change(form: OwnSourceForm) {
        val old = _ui.value
        _ui.value = old.copy(
            form = form,
            cleartext = cleartextOf(form.url),
            notice = null,
            // What was checked no longer describes what is typed; and a field's error goes away once it is edited.
            probe = if (form.url == old.form.url && form.kind == old.form.kind) old.probe else null,
            errors = old.errors.filterKeys { field -> !changed(old.form, form, field) },
        )
    }

    fun probeNow() {
        val form = _ui.value.form
        val check = OwnSourceValidator.checkUrl(form.url)
        if (check is OwnUrlCheck.Refused) {
            _ui.value = _ui.value.copy(errors = _ui.value.errors + (OwnField.URL to check.message))
            return
        }
        _ui.value = _ui.value.copy(busy = true, probe = null)
        viewModelScope.launch {
            val r = probe(form)
            _ui.value = _ui.value.copy(busy = false, probe = r)
        }
    }

    fun save() {
        val state = _ui.value
        if (state.busy) return
        _ui.value = state.copy(busy = true)
        viewModelScope.launch {
            when (val r = store.save(state.editingId, state.form)) {
                OwnSaveResult.Saved -> {
                    _ui.value = OwnFormUi()
                    onSaved()
                }
                is OwnSaveResult.Invalid -> _ui.value = _ui.value.copy(busy = false, errors = r.errors)
                OwnSaveResult.TooMany -> _ui.value = _ui.value.copy(busy = false, notice = OwnSourcesCopy.TOO_MANY)
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
