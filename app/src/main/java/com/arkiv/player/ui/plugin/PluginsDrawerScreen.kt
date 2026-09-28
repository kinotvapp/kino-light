package com.arkiv.player.ui.plugin

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkiv.player.ui.readingWidth

/** The phone's Plugins drawer destination (route and label shared by the drawer, the rail and the NavHost). */
const val PLUGINS_ROUTE = "plugins"

/**
 * The phone's Plugins screen, one more item of the drawer (before Ajustes) under the same top bar as the other
 * drawer destinations. Its heading matches Ajustes'; the body is [PluginsContent], whose tabs are lazy lists
 * that scroll on their own, so it takes the height the heading leaves. The keyboard lifts it by what it covers
 * beyond the system bar. The TV keeps Plugins as a tab of its Ajustes (`TvSettingsScreen`).
 */
@Composable
fun PluginsDrawerScreen(contentPadding: PaddingValues) {
    val bottomInset = contentPadding.calculateBottomPadding()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .readingWidth()
                .fillMaxSize()
                .padding(top = contentPadding.calculateTopPadding()),
        ) {
            Text(
                "Plugins",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            )
            // PluginsContent keeps its own 16 dp gutter; 4 dp more lines it up with the 20 dp heading.
            PluginsContent(
                bottomInset = bottomInset,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .consumeWindowInsets(PaddingValues(bottom = bottomInset))
                    .imePadding(),
            )
        }
    }
}
