package com.arkiv.player.ui.offline

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.arkiv.player.data.local.DuplicateDownloadPolicy
import com.arkiv.player.data.local.EnqueueOutcome

/**
 * "You already have that downloaded" notice for the save-to-device buttons.
 *
 * The queue skips downloads on its own whose content is already on disk under another item (see
 * [com.arkiv.player.data.local.LocalDownloadManager.enqueue]); without this notice the button
 * would look like it does nothing. Same shape as [rememberPostNotificationsRequest]: returns a
 * lambda to call after queuing.
 *
 * ALL the action's results are passed together (a pack sends its N chapters at once) so a single
 * Toast comes out instead of one per chapter.
 */
@Composable
fun rememberDuplicateDownloadNotice(): (List<EnqueueOutcome>) -> Unit {
    val context = LocalContext.current
    return { outcomes ->
        val skipped = outcomes.count { it == EnqueueOutcome.ALREADY_DOWNLOADED }
        DuplicateDownloadPolicy.skippedNotice(skipped)?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }
    }
}
