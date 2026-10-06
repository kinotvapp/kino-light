package app.kino.tv.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Where the full Kino app is downloaded. */
const val FULL_APP_URL = "https://archive.org/details/kino-app"

/** The community channel. */
const val TELEGRAM_URL = "https://t.me/Kinoapptv"

/** What a placeholder button says instead of doing the real work. */
const val FULL_APP_ONLY = "Disponible en la app completa de Kino: archive.org/details/kino-app"

/** Shows [FULL_APP_ONLY]: the button exists so the screen looks like Kino, the feature lives in the full app. */
fun fullAppOnly(context: Context) {
    Toast.makeText(context, FULL_APP_ONLY, Toast.LENGTH_LONG).show()
}

/** Opens [url] in whatever app handles it; on a device with no browser it says where to go instead. */
fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, url, Toast.LENGTH_LONG).show()
    }
}
