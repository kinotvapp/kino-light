package app.kino.demo.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val UP_TO_DATE = "Ya tienes la última versión"

/** "Buscar actualizaciones": a short "Buscando…" ([checking]), then the answer as a toast. */
@Stable
class UpdateCheck internal constructor(private val context: Context, private val scope: CoroutineScope) {
    var checking by mutableStateOf(false)
        private set

    fun run() {
        if (checking) return
        checking = true
        scope.launch {
            delay(1_200)
            checking = false
            Toast.makeText(context, UP_TO_DATE, Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun rememberUpdateCheck(): UpdateCheck {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember { UpdateCheck(context, scope) }
}
