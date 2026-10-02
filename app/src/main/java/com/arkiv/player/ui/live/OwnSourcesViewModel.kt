package com.arkiv.player.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arkiv.player.data.db.OwnLiveSourceEntity
import com.arkiv.player.data.live.IptvOrgCatalog
import com.arkiv.player.data.live.IptvOrgList
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
import com.arkiv.player.data.live.XtreamUrl
import com.arkiv.player.data.live.XtreamUrl.XtreamField
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

/** The three fields of a Xtream server. [password] is a secret: it is shown masked and never logged. */
data class XtreamInput(val server: String = "", val username: String = "", val password: String = "") {
    override fun toString() = "XtreamInput(server=$server, username=***, password=***)"
}

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
    /** Non-null = the form is a Xtream server (server + user + password) instead of an address. */
    val xtream: XtreamInput? = null,
    val xtreamErrors: Map<XtreamField, String> = emptyMap(),
    /** The iptv-org picker is open. */
    val picker: Boolean = false,
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

    /** "Agregar servidor Xtream": server, user and password instead of an address. */
    fun startNewXtream() {
        _ui.value = OwnFormUi(form = OwnSourceForm(OwnKind.PLAYLIST, "", ""), xtream = XtreamInput(), open = true)
    }

    /** The type chips of the form: the format of a list is told by its content, so only a Xtream server needs its own fields. */
    fun setXtreamMode(on: Boolean) {
        val s = _ui.value
        if (s.editingId != null || (s.xtream != null) == on) return
        _ui.value = if (on) s.copy(form = s.form.copy(kind = OwnKind.PLAYLIST, pastedText = null, url = ""), xtream = XtreamInput(), pasted = null, probe = null, errors = emptyMap(), cleartext = false)
        else s.copy(xtream = null, xtreamErrors = emptyMap(), probe = null)
    }

    fun changeXtream(next: XtreamInput) {
        val s = _ui.value
        // A whole address pasted in the server field (get.php or player_api.php) fills the three fields.
        val split = XtreamUrl.split(next.server.trim())
        val input = if (split != null) XtreamInput(split.first, split.second, split.third) else next
        val old = s.xtream ?: XtreamInput()
        _ui.value = s.copy(
            xtream = input, probe = null, notice = null,
            // No scheme means http (the usual way a provider gives it): the warning shows unless it says https.
            cleartext = input.server.isNotBlank() && !input.server.trim().startsWith("https://", ignoreCase = true),
            xtreamErrors = s.xtreamErrors.filterKeys { f ->
                when (f) {
                    XtreamField.SERVER -> old.server == input.server
                    XtreamField.USERNAME -> old.username == input.username
                    XtreamField.PASSWORD -> old.password == input.password
                }
            },
        )
    }

    fun openPicker() {
        _ui.value = OwnFormUi(picker = true)
    }

    /** Saves one ready-made iptv-org list as an ordinary list. */
    fun addIptvOrg(item: IptvOrgList) {
        val s = _ui.value
        if (s.busy) return
        _ui.value = s.copy(busy = true, notice = null)
        viewModelScope.launch {
            val result = try {
                store.save(null, IptvOrgCatalog.form(item))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            when (result) {
                OwnSaveResult.Saved -> { _ui.value = OwnFormUi(); onSaved() }
                is OwnSaveResult.Invalid -> _ui.value = _ui.value.copy(busy = false, notice = "Ya agregaste «${item.title}» (iptv-org)")
                OwnSaveResult.TooMany -> _ui.value = _ui.value.copy(busy = false, notice = OwnSourcesCopy.TOO_MANY)
                null -> _ui.value = _ui.value.copy(busy = false, notice = OwnSourcesCopy.SAVE_FAILED)
            }
        }
    }

    fun startEdit(source: OwnLiveSourceEntity) {
        val form = source.toForm()
        val xtream = if (source.kind == "PLAYLIST") XtreamUrl.split(source.url) else null
        if (xtream != null && XtreamUrl.isApi(source.url)) {
            _ui.value = OwnFormUi(
                editingId = source.id, form = form.copy(url = ""), xtream = XtreamInput(xtream.first, xtream.second, xtream.third),
                cleartext = cleartextOf(source.url), open = true,
            )
            return
        }
        _ui.value = OwnFormUi(
            editingId = source.id, form = form, cleartext = cleartextOf(form.url), open = true,
            pasted = if (OwnPastedList.isPasted(form.url)) OwnSourcesCopy.PASTED_SAVED else null,
        )
    }

    /** "Pegar lista": [raw] is the clipboard's text (null = nothing there). */
    fun usePasted(raw: String?) {
        // One address (a get.php, a player_api.php, an .m3u8...) is an address, not a list: it goes to the address
        // (or Xtream) fields, where it is checked and refreshed like a typed one.
        val address = raw?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it && '\r' !in it && it.startsWith("http", ignoreCase = true) && it.none(Char::isWhitespace) }
        if (address != null && !_ui.value.busy) {
            useAddress(address)
            return
        }
        useList(OwnSourcesCopy.PASTED_LABEL, nameHint = "") {
            if (raw.isNullOrBlank()) OwnPastedCheck.Refused(OwnPastedList.EMPTY) else OwnPastedList.check(raw)
        }
    }

    private fun useAddress(address: String) {
        val s = _ui.value
        val xt = XtreamUrl.accountOf(address)
        _ui.value = if (xt != null) {
            s.copy(
                form = s.form.copy(kind = OwnKind.PLAYLIST, pastedText = null, url = ""), pasted = null,
                xtream = XtreamInput(xt.base.toString().trimEnd('/'), xt.username, xt.password), xtreamErrors = emptyMap(),
                probe = null, errors = s.errors - OwnField.URL, cleartext = xt.base.scheme == "http",
            )
        } else {
            val listLike = Regex("(?i)\\.(m3u8?|w3u|xspf|txt|json)(\\?|$)|/get\\.php").containsMatchIn(address)
            s.copy(
                form = s.form.copy(kind = if (listLike) OwnKind.PLAYLIST else s.form.kind, pastedText = null, url = address),
                pasted = null, xtream = null, probe = null, errors = s.errors - OwnField.URL, cleartext = cleartextOf(address),
            )
        }
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
        if (next.kind == OwnKind.PLAYLIST && next.url != _ui.value.form.url && XtreamUrl.isApi(next.url.trim())) {
            useAddress(next.url.trim())
            return
        }
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

    /** The stored address of the Xtream fields, or null after showing each field's error. */
    private fun xtreamForm(): OwnSourceForm? {
        val s = _ui.value
        val x = s.xtream ?: return s.form
        return when (val b = XtreamUrl.build(x.server, x.username, x.password)) {
            is XtreamUrl.Built.Invalid -> { _ui.value = s.copy(xtreamErrors = b.errors); null }
            is XtreamUrl.Built.Ok -> s.form.copy(kind = OwnKind.PLAYLIST, url = b.url)
        }
    }

    fun probeNow() {
        if (_ui.value.xtream != null) {
            val form = xtreamForm() ?: return
            _ui.value = _ui.value.copy(busy = true, probe = null)
            val sent = _ui.value.xtream
            viewModelScope.launch {
                val r = probe(form)
                val stale = _ui.value.xtream != sent
                _ui.value = _ui.value.copy(busy = false, probe = if (stale) null else r)
            }
            return
        }
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
        val toSave = if (state.xtream != null) {
            // The name is checked by the store with the rest; the three fields here, so each shows its own error.
            xtreamForm() ?: return
        } else state.form
        _ui.value = _ui.value.copy(busy = true)
        viewModelScope.launch {
            val result = try {
                store.save(state.editingId, toSave)
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
