package com.arkiv.player.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arkiv.player.ui.theme.ArkivBlack
import com.arkiv.player.ui.theme.ArkivTheme
import com.arkiv.player.ui.tv.TV_RAIL_CONTENT_START
import com.arkiv.player.ui.tv.TvRailItem
import com.arkiv.player.ui.tv.TvSideRail

/**
 * Debug-only lab for the TV side rail (branch experiment/tv-sidebar): the REAL [TvSideRail] over a fake Home (a hero and
 * two rows of cards), so its look and its D-pad behaviour can be tried on any device or emulator without activating the
 * app. Open it with
 *
 * ```
 * adb shell am start -n com.arkiv.player.light/com.arkiv.player.debug.TvSidebarLabActivity
 * ```
 *
 * and drive it with `adb shell input keyevent KEYCODE_DPAD_LEFT` and friends. Lives in `src/debug`: a test tool, not a
 * feature; a release APK does not contain it.
 */
class TvSidebarLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ArkivTheme { Lab() } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Lab() {
    var last by remember { mutableStateOf("(nada todavía)") }
    val items = listOf(
        TvRailItem(Icons.Default.Search, "Buscar", { last = "Buscar" }),
        TvRailItem(Icons.Default.Refresh, "Recargar", { last = "Recargar" }),
        TvRailItem(Icons.Default.GridView, "Categorías", { last = "Categorías" }),
        TvRailItem(Icons.Default.PlayCircle, "Xuper", { last = "Xuper" }),
        TvRailItem(Icons.Default.VideoLibrary, "Mi biblioteca", { last = "Mi biblioteca" }),
        TvRailItem(Icons.Default.LiveTv, "En vivo", { last = "En vivo" }),
        TvRailItem(Icons.Default.Tv, "Caracol", { last = "Caracol" }),
        TvRailItem(Icons.Default.Settings, "Ajustes", { last = "Ajustes" }),
    )
    Box(Modifier.fillMaxSize().background(ArkivBlack)) {
        // A stand-in for the hero's backdrop.
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(ArkivBlack, Color(0xFF6B2D2D)))))
        Column(Modifier.fillMaxSize().padding(start = TV_RAIL_CONTENT_START)) {
            Column(Modifier.weight(1f).padding(horizontal = 48.dp, vertical = 28.dp)) {
                Spacer(Modifier.weight(1f))
                Text("Título de ejemplo", style = MaterialTheme.typography.displaySmall, color = Color.White)
                Text("Último botón pulsado: $last", style = MaterialTheme.typography.titleSmall, color = Color.White)
            }
            LazyColumn(Modifier.fillMaxSize().height(300.dp)) {
                items(listOf("Continuar viendo", "Recién agregadas")) { rowTitle ->
                    Text(rowTitle, color = Color.White, modifier = Modifier.padding(start = 48.dp, top = 8.dp, bottom = 6.dp))
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items((1..12).toList()) { n ->
                            Surface(
                                onClick = { last = "$rowTitle #$n" },
                                modifier = Modifier.size(width = 200.dp, height = 112.dp),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("$n", color = Color.White) }
                            }
                        }
                    }
                }
            }
        }
        TvSideRail(items, Modifier.align(Alignment.CenterStart))
    }
}
