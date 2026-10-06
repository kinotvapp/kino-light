package app.kino.demo.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.kino.demo.ui.settings.OSS_INTRO
import app.kino.demo.ui.settings.OSS_NOTICES
import app.kino.demo.ui.settings.OssNotice
import app.kino.demo.ui.settings.ossNoticeText
import app.kino.demo.ui.theme.KinoBlack
import app.kino.demo.ui.theme.KinoTextSecondary
import kotlinx.coroutines.delay

/** "Licencias de software libre" on the TV, full screen: the list, and one component's notice; Back goes up a level. */
@Composable
internal fun TvOssNoticesDialog(onDismiss: () -> Unit) {
    var open by remember { mutableStateOf<OssNotice?>(null) }
    val notice = open
    val first = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(notice) {
        // The dialog's window takes a few frames to get focus, and a request before that is silently dropped.
        focused = false
        repeat(40) {
            if (focused) return@LaunchedEffect
            runCatching { first.requestFocus() }
            delay(50)
        }
    }
    val landing = Modifier.focusRequester(first).onFocusChanged { if (it.isFocused) focused = true }
    Dialog(
        onDismissRequest = { if (notice != null) open = null else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.fillMaxSize().background(KinoBlack).padding(horizontal = 48.dp, vertical = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(notice?.name ?: "Licencias de software libre", style = MaterialTheme.typography.titleLarge, color = Color.White)
            if (notice == null) {
                Text(OSS_INTRO, style = MaterialTheme.typography.bodySmall, color = KinoTextSecondary)
                OSS_NOTICES.forEachIndexed { i, n ->
                    TvActionOption("${n.name} · ${n.license}", modifier = if (i == 0) landing else Modifier) { open = n }
                }
            } else {
                Text(notice.license, style = MaterialTheme.typography.bodyMedium, color = KinoTextSecondary)
                Text(ossNoticeText(notice), style = MaterialTheme.typography.bodySmall, color = Color.White, modifier = Modifier.fillMaxWidth(0.8f))
                TvCompactAction("Atrás", modifier = landing) { open = null }
            }
        }
    }
}
